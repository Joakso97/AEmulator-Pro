
package app.aemu.importer

import app.aemu.core.LegacyFirmware
import app.aemu.core.VmSettings
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater


object BootImage {
    
    private const val MAX_DECODED = 128 * 1024 * 1024

    
    private const val CODEC_LIMIT_KIB = 160 * 1024
    private fun lzmaStream(d: ByteArray): InputStream = LZMAInputStream(ByteArrayInputStream(d), CODEC_LIMIT_KIB)
    private fun xzStream(d: ByteArray): InputStream = XZInputStream(ByteArrayInputStream(d), CODEC_LIMIT_KIB)
    private fun InputStream.bounded(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size().toLong() + n <= MAX_DECODED) { "Boot ramdisk is too large" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
    data class CpioEntry(val name: String, val mode: Int, val data: ByteArray)

    
    fun stripHtcSignature(img: ByteArray): ByteArray {
        if (img.size < 264) return img
        fun at(o: Int) = String(img, o, 8, Charsets.ISO_8859_1) == "ANDROID!"
        return if (!at(0) && at(256)) img.copyOfRange(256, img.size) else img
    }

    
    private fun gkiVersion(img: ByteArray, bb: ByteBuffer): Int {
        if (img.size < 4096 || String(img, 0, 8, Charsets.ISO_8859_1) != "ANDROID!") return 0
        val version = bb.getInt(40)
        val headerSize = bb.getInt(20)
        return if ((version == 3 || version == 4) && headerSize in 1024..4096) version else 0
    }

    
    fun isKernelOnly(raw: ByteArray): Boolean {
        val img = stripHtcSignature(raw)
        if (img.size < 64 || String(img, 0, 8, Charsets.ISO_8859_1) != "ANDROID!") return false
        val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        val kernelSize = bb.getInt(8).toLong() and 0xffffffffL
        val gki = gkiVersion(img, bb)
        val ramdiskSize = (if (gki != 0) bb.getInt(12) else bb.getInt(16)).toLong() and 0xffffffffL
        return kernelSize > 0 && ramdiskSize == 0L
    }

    
    fun trimmed(raw: ByteArray): ByteArray {
        val img = stripHtcSignature(raw)
        val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        val gki = gkiVersion(img, bb)
        if (gki == 0) return raw
        fun align(n: Long) = (n + 4095L) / 4096L * 4096L
        val kernel = bb.getInt(8).toLong() and 0xffffffffL
        val ramdisk = bb.getInt(12).toLong() and 0xffffffffL
        val signature = if (gki >= 4 && img.size >= 1584) bb.getInt(1580).toLong() and 0xffffffffL else 0L
        val end = 4096L + align(kernel) + align(ramdisk) + align(signature)
        return if (end in 4097L until img.size.toLong()) img.copyOf(end.toInt()) else img
    }

    fun ramdisk(raw: ByteArray): List<CpioEntry>? {
        val img = stripHtcSignature(raw)
        val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        if (gkiVersion(img, bb) != 0) {
            val kernelSize = bb.getInt(8).toLong() and 0xffffffffL
            val ramdiskSize = bb.getInt(12).toLong() and 0xffffffffL
            val off = 4096L + (kernelSize + 4095L) / 4096L * 4096L
            if (ramdiskSize < 6 || off + ramdiskSize > img.size) return null
            return cpio(decompress(img.copyOfRange(off.toInt(), (off + ramdiskSize).toInt())) ?: return null)
        }
        if (img.size > 64 && String(img, 0, 8, Charsets.ISO_8859_1) == "ANDROID!") {
            val kernelSize = bb.getInt(8).toLong() and 0xffffffffL
            val ramdiskSize = bb.getInt(16).toLong() and 0xffffffffL
            val pageSize = bb.getInt(36).let { if (it in 2048..131072) it else 2048 }
            val kPages = (kernelSize + pageSize - 1) / pageSize
            var off = (1 + kPages) * pageSize
            var len = ramdiskSize
            if (len < 4 || off + len > img.size) return null
            
            if (bb.getInt(off.toInt()) == 0x58881688) { off += 512; len -= 512 }
            if (len < 6) return null
            val rd = img.copyOfRange(off.toInt(), (off + len).toInt())
            return cpio(decompress(rd) ?: return null)
        }
        
        if (img.size > 64 && img[0] == 0x7f.toByte() && String(img, 1, 3, Charsets.ISO_8859_1) == "ELF") fromElf(img)?.let { return it }
        
        return fromZImage(img) ?: fromAppendedRamdisk(img)
    }

    
    private fun fromAppendedRamdisk(img: ByteArray): List<CpioEntry>? {
        var i = 0
        while (i < img.size - 16) {
            if (img[i] == 0x1f.toByte() && img[i + 1] == 0x8b.toByte() && img[i + 2] == 0x08.toByte()) {
                val entries = runCatching {
                    val d = gunzipLenient(img.copyOfRange(i, img.size))
                    if (d.size > 6 && String(d, 0, 6, Charsets.ISO_8859_1) == "070701") cpio(d) else null
                }.getOrNull()
                if (entries != null && holdsInitScript(entries)) return entries
            }
            i++
        }
        return null
    }

    
    fun holdsInitScript(entries: List<CpioEntry>): Boolean = entries.any { e ->
        when (e.name.removePrefix("./").trimStart('/')) {
            "init.rc" -> true
            "etc/init.rc" -> LegacyFirmware.isLegacyInitRc(String(e.data, Charsets.ISO_8859_1))
            else -> false
        }
    }

    
    fun legacyRamdisk(raw: ByteArray): List<CpioEntry>? {
        for (off in intArrayOf(4, 0)) {
            if (raw.size < off + 32) continue
            if (raw[off] != 0x1f.toByte() || raw[off + 1] != 0x8b.toByte() || raw[off + 2] != 0x08.toByte()) continue
            var end = raw.size
            if (off == 4) {
                val n = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xffffffffL
                if (n < 32 || n > raw.size - 4L) continue
                end = (4 + n).toInt()
            }
            val d = runCatching { gunzipLenient(raw.copyOfRange(off, end)) }.getOrNull() ?: continue
            if (d.size < 6 || String(d, 0, 6, Charsets.ISO_8859_1) != "070701") continue
            val entries = runCatching { cpio(d) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            if (holdsInitScript(entries)) return entries
        }
        return null
    }

    
    private fun fromElf(img: ByteArray): List<CpioEntry>? {
        val bb = ByteBuffer.wrap(img).order(if (img[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val is64 = img[4].toInt() == 2
        val phOff = if (is64) bb.getLong(0x20) else bb.getInt(0x1C).toLong() and 0xffffffffL
        val phSize = bb.getShort(if (is64) 0x36 else 0x2A).toInt() and 0xffff
        val phNum = bb.getShort(if (is64) 0x38 else 0x2C).toInt() and 0xffff
        if (phSize < (if (is64) 56 else 32) || phNum == 0 || phOff < 0 || phOff + phSize.toLong() * phNum > img.size) return null
        var fallback: List<CpioEntry>? = null
        for (i in 0 until phNum) {
            val ph = (phOff + i.toLong() * phSize).toInt()
            val off = if (is64) bb.getLong(ph + 8) else bb.getInt(ph + 4).toLong() and 0xffffffffL
            val len = if (is64) bb.getLong(ph + 32) else bb.getInt(ph + 16).toLong() and 0xffffffffL
            if (len < 6 || off < 0 || off + len > img.size) continue
            val seg = img.copyOfRange(off.toInt(), (off + len).toInt())
            val entries = runCatching { decompress(seg)?.let { cpio(it) } }.getOrNull() ?: continue
            if (entries.any { it.name == "init.rc" }) return entries
            if (fallback == null && entries.isNotEmpty()) fallback = nestedBoot(entries)
        }
        return fallback
    }

    private fun fromZImage(img: ByteArray): List<CpioEntry>? {
        
        for (i in 0 until img.size - 6) {
            val gz = img[i] == 0x1f.toByte() && img[i + 1] == 0x8b.toByte() && img[i + 2] == 0x08.toByte()
            val lz = img[i] == 0x5d.toByte() && img[i + 1] == 0x00.toByte() && img[i + 2] == 0x00.toByte() &&
                (img[i + 3] == 0x80.toByte() || img[i + 3] == 0x40.toByte() || img[i + 3] == 0x00.toByte() || img[i + 3] == 0x01.toByte())
            val xz = img[i] == 0xfd.toByte() && img[i + 1] == '7'.code.toByte() && img[i + 2] == 'z'.code.toByte() &&
                img[i + 3] == 'X'.code.toByte() && img[i + 4] == 'Z'.code.toByte()
            if (!gz && !lz && !xz) continue
            val kernel = runCatching {
                val tail = img.copyOfRange(i, img.size)
                when {
                    gz -> gunzipLenient(tail)
                    lz -> lzmaLenient(tail)
                    else -> xzStream(tail).use { it.bounded() }
                }
            }.getOrNull() ?: continue
            if (kernel.size < 1_000_000) continue
            findCpio(kernel)?.let { return it }
        }
        return null
    }

    
    private fun lzmaLenient(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        runCatching {
            lzmaStream(d).use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; require(out.size().toLong() + n <= MAX_DECODED); out.write(buf, 0, n) }
            }
        }
        return out.toByteArray()
    }

    
    private fun nestedBoot(entries: List<CpioEntry>): List<CpioEntry>? {
        val cand = entries.filter { e ->
            val n = e.name.substringAfterLast('/')
            (n.startsWith("boot") || n.startsWith("ramdisk")) && (n.endsWith(".cpio") || n.contains(".cpio."))
        }
        for (e in cand) {
            val inner = decompress(e.data) ?: continue
            val list = runCatching { cpio(inner) }.getOrNull() ?: continue
            if (list.any { it.name == "init.rc" }) return list
        }
        return null
    }

    
    private fun findCpio(k: ByteArray): List<CpioEntry>? {
        val magic = "070701".toByteArray()
        var i = 0
        while (i < k.size - 6) {
            if (k[i] == magic[0] && k[i + 1] == magic[1] && k[i + 2] == magic[2] && k[i + 3] == magic[3] && k[i + 4] == magic[4] && k[i + 5] == magic[5]) {
                val entries = runCatching { cpio(k.copyOfRange(i, k.size)) }.getOrNull()
                if (entries != null && entries.any { it.name == "init.rc" }) return entries
                if (entries != null) nestedBoot(entries)?.let { return it }
            }
            if (k[i] == 0x1f.toByte() && k[i + 1] == 0x8b.toByte() && k[i + 2] == 0x08.toByte()) {
                val inner = runCatching { gunzipLenient(k.copyOfRange(i, minOf(k.size, i + 64 * 1024 * 1024))) }.getOrNull()
                if (inner != null && inner.size > 6 && String(inner, 0, 6) == "070701") {
                    val entries = runCatching { cpio(inner) }.getOrNull()
                    if (entries != null && entries.any { it.name == "init.rc" }) return entries
                    if (entries != null) nestedBoot(entries)?.let { return it }
                }
            }
            i++
        }
        return null
    }

    fun decompress(d: ByteArray): ByteArray? {
        if (d.size < 6) return null
        return runCatching {
            when {
                d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte() -> gunzipLenient(d)
                d[0] == 0x5d.toByte() && d[1] == 0x00.toByte() -> lzmaStream(d).use { it.bounded() }
                d[0] == 0xfd.toByte() && String(d, 1, 4) == "7zXZ" -> xzStream(d).use { it.bounded() }
                d[0] == 0x02.toByte() && d[1] == 0x21.toByte() && d[2] == 0x4c.toByte() -> Lz4Legacy.decode(d)
                String(d, 0, 6) == "070701" -> d
                else -> null
            }
        }.getOrNull()
    }

    
    fun gunzipLenient(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(d), 65536).use { g ->
                val b = ByteArray(65536)
                while (true) { val n = g.read(b); if (n < 0) break; require(out.size().toLong() + n <= MAX_DECODED); out.write(b, 0, n) }
            }
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            if (out.size() == 0) {
                
                val inf = Inflater(true)
                inf.setInput(d, 10, d.size - 10)
                val b = ByteArray(65536)
                try {
                    while (!inf.finished()) { val n = inf.inflate(b); if (n == 0) break; require(out.size().toLong() + n <= MAX_DECODED); out.write(b, 0, n) }
                } finally { inf.end() }
            }
        }
        return out.toByteArray()
    }

    fun cpio(d: ByteArray): List<CpioEntry> {
        val out = ArrayList<CpioEntry>()
        var off = 0
        while (off + 110 <= d.size) {
            val m = String(d, off, 6, Charsets.ISO_8859_1)
            if (m != "070701" && m != "070702") break
            fun hex(i: Int) = String(d, off + 6 + i * 8, 8, Charsets.ISO_8859_1).toLong(16)
            val mode = hex(1).toInt()
            val fileSizeLong = hex(6)
            val nameSizeLong = hex(11)
            require(fileSizeLong in 0..MAX_DECODED.toLong() && nameSizeLong in 1..4096)
            val fileSize = fileSizeLong.toInt()
            val nameSize = nameSizeLong.toInt()
            val nameStart = off + 110
            require(nameStart.toLong() + nameSize <= d.size && d[nameStart + nameSize - 1] == 0.toByte())
            val name = String(d, nameStart, maxOf(0, nameSize - 1), Charsets.UTF_8)
            var dataStart = nameStart + nameSize
            dataStart = (dataStart + 3) and 3.inv()
            if (name == "TRAILER!!!") break
            require(dataStart.toLong() + fileSize <= d.size) { "Truncated ramdisk" }
            var data = d.copyOfRange(dataStart, dataStart + fileSize)
            
            
            if ((mode and 0xF000) == 0xA000) {
                var end = data.size
                while (end > 0 && data[end - 1] == 0.toByte()) end--
                if (end != data.size) data = data.copyOfRange(0, end)
            }
            out.add(CpioEntry(name.removePrefix("./").trimStart('/'), mode, data))
            off = (dataStart + fileSize + 3) and 3.inv()
        }
        return out
    }

    fun readAll(i: InputStream): ByteArray = i.readBytes()

    

    
    data class KernelBanner(val release: String, val user: String, val host: String, val compiler: String,
                            val build: String, val flags: String, val date: String)

    private val BANNER = Regex("""^Linux version (\S+)(?: \(([^()@\s]+)@([^()\s]+)\))?(?: \((.*)\))? #(\d+)\s*(.*)$""")
    private val BANNER_DATE = Regex("""\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) \w{3} +\d+ [\d:]+ .*$""")

    
    private fun findBannerLine(d: ByteArray): String? {
        val key = "Linux version ".toByteArray(Charsets.ISO_8859_1)
        var i = 0
        val last = d.size - key.size - 3
        while (i <= last) {
            if (d[i] == key[0]) {
                var k = 1
                while (k < key.size && d[i + k] == key[k]) k++
                if (k == key.size && d[i + key.size] in '0'.code.toByte()..'9'.code.toByte()) {
                    var e = i
                    while (e < d.size && e - i < 512 && d[e] != 0.toByte() && d[e] != '\n'.code.toByte()) e++
                    return String(d, i, e - i, Charsets.ISO_8859_1).trim().takeIf { it.length > 20 }
                }
            }
            i++
        }
        return null
    }

    
    fun kernelBanner(raw: ByteArray): KernelBanner? {
        val img = stripHtcSignature(raw)
        var from = 0; var to = img.size
        if (img.size > 64 && String(img, 0, 8, Charsets.ISO_8859_1) == "ANDROID!") {
            val bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
            val kernelSize = bb.getInt(8).toLong() and 0xffffffffL
            val pageSize = if (gkiVersion(img, bb) != 0) 4096 else bb.getInt(36).let { if (it in 2048..131072) it else 2048 }
            if (kernelSize > 0 && pageSize + kernelSize <= img.size) { from = pageSize; to = (pageSize + kernelSize).toInt() }
        }
        val kernel = img.copyOfRange(from, to)
        val line = findBannerLine(kernel) ?: findBannerInCompressed(kernel) ?: return null
        return parseBanner(line)
    }

    private fun findBannerInCompressed(img: ByteArray): String? {
        var tries = 0
        for (i in 0 until img.size - 6) {
            val gz = img[i] == 0x1f.toByte() && img[i + 1] == 0x8b.toByte() && img[i + 2] == 0x08.toByte()
            val lz = img[i] == 0x5d.toByte() && img[i + 1] == 0x00.toByte() && img[i + 2] == 0x00.toByte() &&
                (img[i + 3] == 0x80.toByte() || img[i + 3] == 0x40.toByte() || img[i + 3] == 0x00.toByte() || img[i + 3] == 0x01.toByte())
            val xz = img[i] == 0xfd.toByte() && img[i + 1] == '7'.code.toByte() && img[i + 2] == 'z'.code.toByte() &&
                img[i + 3] == 'X'.code.toByte() && img[i + 4] == 'Z'.code.toByte()
            if (!gz && !lz && !xz) continue
            if (++tries > 64) return null
            val k = runCatching {
                val tail = img.copyOfRange(i, img.size)
                when {
                    gz -> gunzipLenient(tail)
                    lz -> lzmaLenient(tail)
                    else -> xzStream(tail).use { it.bounded() }
                }
            }.getOrNull() ?: continue
            if (k.size < 500_000) continue
            findBannerLine(k)?.let { return it }
        }
        return null
    }

    fun parseBanner(line: String): KernelBanner? {
        
        val l = VmSettings.cleanBanner(line).trim()
        val m = BANNER.find(l)
        if (m == null) {
            val rel = Regex("""^Linux version (\S+)""").find(l)?.groupValues?.get(1) ?: return null
            return KernelBanner(VmSettings.cleanToken(rel, VmSettings.KERNEL_MAX), "", "", "", "", "", "")
        }
        val (rel, user, host, cc, build, rest) = m.destructured
        val date = BANNER_DATE.find(rest)?.value ?: ""
        val flags = if (date.isEmpty()) rest else rest.removeSuffix(date)
        return KernelBanner(
            VmSettings.cleanToken(rel, VmSettings.KERNEL_MAX), VmSettings.cleanToken(user, VmSettings.TOKEN_MAX),
            VmSettings.cleanToken(host, 64), VmSettings.cleanCompiler(cc), build.take(6),
            VmSettings.cleanText(flags, 40), VmSettings.cleanText(date, 40))
    }

    
    fun withKernelBanner(s: VmSettings, boot: ByteArray): VmSettings {
        if (VmSettings.kernelCustom(s)) return s
        val b = runCatching { kernelBanner(boot) }.getOrNull() ?: return s
        return s.copy(kernel = b.release, kernelUser = b.user, kernelHost = b.host, kernelCompiler = b.compiler,
            kernelBuild = b.build, kernelFlags = b.flags, kernelDate = b.date)
    }
}


object Lz4Legacy {
    fun decode(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 4
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        while (p + 4 <= d.size) {
            val clen = bb.getInt(p); p += 4
            if (clen == 0x184C2102 || clen <= 0 || p + clen > d.size) break
            val decoded = block(d, p, clen)
            require(out.size().toLong() + decoded.size <= 128 * 1024 * 1024)
            out.write(decoded)
            p += clen
        }
        return out.toByteArray()
    }

    private fun block(src: ByteArray, start: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len * 3)
        var buf = ByteArray(minOf(len.toLong() * 4, 1024 * 1024L).toInt())
        var o = 0
        var i = start
        val end = start + len
        fun ensure(n: Int) { require(n >= 0 && o.toLong() + n <= 128 * 1024 * 1024); if (o + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, o + n).coerceAtMost(128 * 1024 * 1024)) }
        while (i < end) {
            val token = src[i++].toInt() and 0xff
            var lit = token ushr 4
            if (lit == 15) { while (true) { val b = src[i++].toInt() and 0xff; lit += b; if (b != 255) break } }
            ensure(lit); System.arraycopy(src, i, buf, o, lit); i += lit; o += lit
            if (i >= end) break
            val offset = (src[i].toInt() and 0xff) or ((src[i + 1].toInt() and 0xff) shl 8); i += 2
            var ml = token and 15
            if (ml == 15) { while (true) { val b = src[i++].toInt() and 0xff; ml += b; if (b != 255) break } }
            ml += 4
            ensure(ml)
            for (k in 0 until ml) { buf[o] = buf[o - offset]; o++ }
        }
        out.write(buf, 0, o)
        return out.toByteArray()
    }
}
