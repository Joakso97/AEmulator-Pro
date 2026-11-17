package app.aemu.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


object RomDownloader {
    
    private const val PAGE_SCAN_CHARS = 2 * 1024 * 1024

    fun downloadsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads").apply { mkdirs() }

    private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz4", "lzma",
        "img", "bin", "tar.gz", "ota", "sin", "kdz", "dz", "cab", "iso", "aessvm", "nb0", "ftf", "pac", "mbn", "sparse")

    
    private fun isPageType(type: String): Boolean {
        val t = type.lowercase(Locale.ROOT).substringBefore(';').trim()
        return t.startsWith("text/") || t.startsWith("image/") || t.startsWith("audio/") || t.startsWith("video/") ||
            t == "application/xhtml+xml" || t == "application/xml" || t == "application/json" ||
            t == "application/javascript" || t == "application/ld+json" || t.endsWith("+xml") || t.endsWith("+json")
    }

    
    internal fun isFile(contentType: String?, disposition: String?, url: String): Boolean {
        if (disposition != null && disposition.trimStart().startsWith("attachment", true)) return true
        if (!contentType.isNullOrBlank()) return !isPageType(contentType)
        
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        return ARCHIVE_EXT.any { path.endsWith(".$it") }
    }

    internal fun fileName(disposition: String?, url: String): String {
        var name: String? = null
        if (disposition != null) {
            Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = runCatching { URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8") }.getOrNull()
            }
            if (name.isNullOrBlank()) Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = it.groupValues[1].trim()
            }
        }
        if (name.isNullOrBlank()) {
            name = runCatching {
                URLDecoder.decode(url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8")
            }.getOrNull()
        }
        val clean = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        if (clean.isEmpty()) return "rom-download.bin"
        if (clean.length <= 120) return clean
        val dot = clean.lastIndexOf('.')
        return if (dot in 1 until clean.length && clean.length - dot <= 12) clean.take(120 - (clean.length - dot)) + clean.substring(dot) else clean.take(120)
    }


    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    
    private const val PARALLEL_MIN_BYTES = 16L * 1024 * 1024
    private const val CONNECTIONS = 6
    private const val CHUNK_BYTES = 8L * 1024 * 1024

    private class RangeUnsupported : IOException("Server does not honour byte ranges")

    private fun prepare(conn: HttpURLConnection) {
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
    }

    
    private suspend fun fetchChunk(url: String, from: Long, to: Long, conns: MutableSet<HttpURLConnection>,
                                   file: RandomAccessFile, written: AtomicLong, report: () -> Unit) {
        var pos = from
        var failures = 0
        val buf = ByteArray(256 * 1024)
        while (pos <= to) {
            currentCoroutineContext().ensureActive()
            val conn = URL(url).openConnection() as HttpURLConnection
            conns.add(conn)
            try {
                prepare(conn)
                conn.setRequestProperty("Range", "bytes=$pos-$to")
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) throw RangeUnsupported()
                if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
                val cr = conn.getHeaderField("Content-Range") ?: ""
                if (!cr.trim().startsWith("bytes $pos-", true)) throw RangeUnsupported()
                conn.inputStream.use { input ->
                    while (pos <= to) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                        if (n < 0) throw IOException("Connection closed early")
                        file.channel.write(ByteBuffer.wrap(buf, 0, n), pos)
                        pos += n
                        written.addAndGet(n.toLong())
                        failures = 0
                        report()
                    }
                }
            } catch (e: RangeUnsupported) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > 3) throw e
            } finally { conns.remove(conn); conn.disconnect() }
        }
    }

    
    private suspend fun downloadParallel(url: String, part: File, total: Long, conns: MutableSet<HttpURLConnection>,
                                         onProgress: (Long, Long) -> Unit) {
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val chunks = ((total + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
            val next = AtomicInteger(0)
            val written = AtomicLong(0)
            val lastReport = AtomicLong(0)
            val report = {
                val now = System.nanoTime()
                val last = lastReport.get()
                if (now - last > 100_000_000L && lastReport.compareAndSet(last, now)) onProgress(written.get(), total)
            }
            coroutineScope {
                repeat(minOf(CONNECTIONS, chunks)) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= chunks) break
                            val from = i * CHUNK_BYTES
                            fetchChunk(url, from, minOf(from + CHUNK_BYTES, total) - 1, conns, file, written, report)
                        }
                    }
                }
            }
            if (written.get() != total) throw IOException("Download incomplete (${written.get()} of $total bytes)")
        }
    }

    
    private fun pageFileLink(html: String, base: String): String? {
        for (m in Regex("(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true) || href.startsWith("mailto:", true)) continue
            val abs = runCatching { URL(URL(base), href).toExternalForm() }.getOrNull() ?: continue
            val valid = CatalogUrls.valid(abs) ?: continue
            if (base.startsWith("https:", true) && !valid.startsWith("https:", true)) continue
            val path = valid.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
            if (ARCHIVE_EXT.any { path.endsWith(".$it") }) return valid
        }
        return null
    }

    
    suspend fun fetch(ctx: Context, startUrl: String, onFile: (String, Long) -> Unit, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            var url = CatalogUrls.valid(startUrl) ?: throw IOException("Invalid link")
            val conns: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
            coroutineScope {
                
                val watcher = launch { try { awaitCancellation() } finally { conns.forEach { runCatching { it.disconnect() } } } }
                var target: File? = null
                var part: File? = null
                try {
                    var hop = 0
                    var forceSingle = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conns.add(conn)
                        prepare(conn)
                        val code = conn.responseCode
                        if (code in listOf(301, 302, 303, 307, 308)) {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (location == null || ++hop > 6) throw IOException("Too many redirects")
                            val next = CatalogUrls.valid(URL(URL(url), location).toExternalForm()) ?: throw IOException("Invalid redirect")
                            if (url.startsWith("https:", true) && !next.startsWith("https:", true)) throw IOException("HTTPS downgrade rejected")
                            url = next
                            continue
                        }
                        if (code != HttpURLConnection.HTTP_OK) { conn.disconnect(); throw IOException("HTTP $code") }
                        val disposition = conn.getHeaderField("Content-Disposition")
                        if (!isFile(conn.contentType, disposition, url)) {
                            
                            val type = conn.contentType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: ""
                            val link = if (type == "text/html" || type == "application/xhtml+xml") {
                                val html = conn.inputStream.use { input ->
                                    val sb = StringBuilder()
                                    val buf = CharArray(8192)
                                    val reader = input.bufferedReader()
                                    while (sb.length < PAGE_SCAN_CHARS) {
                                        currentCoroutineContext().ensureActive()
                                        val n = reader.read(buf)
                                        if (n < 0) break
                                        sb.append(buf, 0, n)
                                    }
                                    sb.toString()
                                }
                                pageFileLink(html, url)
                            } else null
                            conn.disconnect()
                            if (link == null) throw IOException(if (type.startsWith("text/html")) "The page has no downloadable file link" else "Not a file: $type")
                            if (++hop > 6) throw IOException("Too many redirects")
                            url = link
                            continue
                        }

                        val name = fileName(disposition, url)
                        val total = conn.contentLengthLong
                        onFile(name, total)
                        val dir = downloadsDir(ctx)
                        target = File(dir, name)
                        part = File(dir, "$name.part")
                        var written = 0L
                        val ranges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true
                        if (!forceSingle && ranges && total >= PARALLEL_MIN_BYTES) {
                            
                            conns.remove(conn); conn.disconnect()
                            try {
                                downloadParallel(url, part, total, conns, onProgress)
                                target.delete()
                                if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
                                onProgress(total, total)
                                return@coroutineScope target
                            } catch (_: RangeUnsupported) {
                                part.delete()
                                forceSingle = true
                                continue
                            }
                        }
                        try {
                            conn.inputStream.use { input -> part.outputStream().buffered(256 * 1024).use { out ->
                                val buf = ByteArray(256 * 1024)
                                var lastReport = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    val now = System.nanoTime()
                                    if (now - lastReport > 100_000_000L) { lastReport = now; onProgress(written, total) }
                                }
                            } }
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw e
                        } finally { conns.remove(conn); conn.disconnect() }
                        if (total >= 0 && written != total) throw IOException("Download incomplete ($written of $total bytes)")
                        target.delete()
                        if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
                        onProgress(written, if (total >= 0) total else written)
                        return@coroutineScope target
                    }
                    @Suppress("UNREACHABLE_CODE")
                    throw IOException("Unreachable")
                } catch (e: IOException) {
                    part?.delete()
                    throw e
                } catch (e: Throwable) {
                    part?.delete()
                    throw e
                } finally { watcher.cancel() }
            }
        }

    
    fun purgeStale(ctx: Context, maxAgeMs: Long = 24L * 3600_000L) {
        val now = System.currentTimeMillis()
        downloadsDir(ctx).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }
}
