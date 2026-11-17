
package app.aemu.core

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


object WatchdogPatch {
    const val CLASS_DESC = "Lcom/android/server/Watchdog;"
    const val METHOD = "run"
    
    const val KILL_MARK = "*** WATCHDOG KILLING SYSTEM PROCESS"

    
    const val PERM_CLASS = "Lcom/android/server/policy/PermissionPolicyService;"
    const val PERM_METHOD = "grantOrUpgradeDefaultRuntimePermissionsIfNeeded"
    const val PERM_MARK = "Error granting/upgrading runtime permissions"

    enum class Outcome {
        
        PATCHED,
        
        ALREADY,
        
        ABSENT,
        
        UNEXPECTED,
    }

    class Result(val outcome: Outcome, val detail: String = "")

    private fun isDex(d: ByteArray) = d.size >= 112 && d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() &&
        d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()

    
    private fun patchOne(d: ByteArray, cls: String, method: String, mark: String): Result {
        if (!isDex(d)) return Result(Outcome.ABSENT, "not a dex")
        val x = try { DexTools.Dex(d) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable header: ${e.message}") }
        val found = try {
            DexTools.methodCode(x, method, cls, voidOnly = true)
        } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable class data: ${e.message}") }
        val codes: List<Int> = found ?: return Result(Outcome.ABSENT)
        if (codes.isEmpty()) return Result(Outcome.UNEXPECTED, "no $method() with code in the class")
        if (codes.size != 1) return Result(Outcome.UNEXPECTED, "${codes.size} methods called $method")
        val marked = try { DexTools.stringsStartingWith(x, mark).isNotEmpty() }
            catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable string table: ${e.message}") }
        if (!marked) return Result(Outcome.UNEXPECTED, "the dex has no \"$mark\" message")
        val code = codes[0]
        val insnsSize = DexTools.u4(d, code + 12)
        val start = code + 16
        if (insnsSize <= 0 || start + 2L * insnsSize > d.size) return Result(Outcome.UNEXPECTED, "code item out of range")
        if (d[start] == RETURN_VOID && d[start + 1] == 0.toByte()) return Result(Outcome.ALREADY)
        
        
        
        val len = DexTools.insnLength(d[start].toInt() and 0xff)
        if (len > insnsSize) return Result(Outcome.UNEXPECTED, "first instruction runs past the code item")
        d[start] = RETURN_VOID; d[start + 1] = 0
        for (k in 2 until 2 * len) d[start + k] = 0
        DexTools.fixHeader(d)
        return Result(Outcome.PATCHED)
    }

    
    fun patchDex(d: ByteArray): Result = patchDex(d, watchdog = true, perm = true)

    
    fun patchDex(d: ByteArray, watchdog: Boolean, perm: Boolean): Result {
        val rs = listOfNotNull(
            if (watchdog) patchOne(d, CLASS_DESC, METHOD, KILL_MARK) else null,
            if (perm) patchOne(d, PERM_CLASS, PERM_METHOD, PERM_MARK) else null,
        )
        if (rs.isEmpty()) return Result(Outcome.ABSENT)
        for (o in listOf(Outcome.PATCHED, Outcome.ALREADY, Outcome.UNEXPECTED)) rs.firstOrNull { it.outcome == o }?.let { return it }
        return rs[0]
    }

    private const val RETURN_VOID: Byte = 0x0e

    private val dexName = Regex("classes\\d*\\.dex")

    
    fun isPatched(jar: File): Boolean = runCatching {
        var already = false
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !dexName.matches(e.name)) continue
                when (patchDex(z.getInputStream(e).use { it.readBytes() }).outcome) {
                    Outcome.ALREADY -> already = true
                    Outcome.PATCHED -> return@runCatching false      
                    else -> {}
                }
            }
        }
        already
    }.getOrDefault(false)

    
    fun isStockPlusPatch(jar: File, stock: File, watchdog: Boolean = true, perm: Boolean = true): Boolean {
        val tmp = File(jar.parentFile, jar.name + ".aemu-chk")
        return try {
            stock.copyTo(tmp, overwrite = true)
            patchJar(tmp, null) { patchDex(it, watchdog, perm) }
            tmp.readBytes().contentEquals(jar.readBytes())
        } catch (e: Exception) {
            true
        } finally {
            tmp.delete()
        }
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    
    fun patchJar(jar: File, backup: File?, patcher: ((ByteArray) -> Result)? = null): Result {
        val patch: (ByteArray) -> Result = patcher ?: { b -> patchDex(b) }
        val dexNames = ArrayList<String>()
        val patched = HashMap<String, ByteArray>()
        var seen = Outcome.ABSENT
        var detail = ""
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !dexName.matches(e.name)) continue
                dexNames += e.name
                val bytes = z.getInputStream(e).use { it.readBytes() }
                val r = patch(bytes)
                when (r.outcome) {
                    Outcome.PATCHED -> { patched[e.name] = bytes; seen = Outcome.PATCHED }
                    Outcome.ALREADY -> if (seen != Outcome.PATCHED) seen = Outcome.ALREADY
                    Outcome.UNEXPECTED -> if (seen == Outcome.ABSENT) { seen = Outcome.UNEXPECTED; detail = "${e.name}: ${r.detail}" }
                    Outcome.ABSENT -> {}
                }
            }
        }
        if (patched.isEmpty()) return Result(seen, detail)
        if (backup != null && !backup.exists()) { backup.parentFile?.mkdirs(); jar.copyTo(backup) }
        val tmp = File(jar.parentFile, jar.name + ".aemu-tmp")
        try {
            ZipFile(jar).use { z ->
                Counting(FileOutputStream(tmp)).use { cnt ->
                    ZipOutputStream(cnt).use { zo ->
                        for (e in z.entries()) {
                            val data = patched[e.name] ?: if (e.isDirectory) ByteArray(0) else z.getInputStream(e).use { it.readBytes() }
                            val ne = ZipEntry(e.name)
                            if (e.time != -1L) ne.time = e.time
                            if (e.isDirectory) { zo.putNextEntry(ne); zo.closeEntry(); continue }
                            if (e.method == ZipEntry.STORED) {
                                ne.method = ZipEntry.STORED
                                ne.size = data.size.toLong(); ne.compressedSize = data.size.toLong()
                                ne.crc = CRC32().apply { update(data) }.value
                                
                                val base = cnt.count + 30 + e.name.toByteArray(Charsets.UTF_8).size
                                var pad = ((4 - base % 4) % 4).toInt()
                                if (pad in 1..3) pad += 4
                                if (pad > 0) ne.extra = ByteArray(pad).also { it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = (pad - 4).toByte() }
                            } else ne.method = ZipEntry.DEFLATED
                            zo.putNextEntry(ne); zo.write(data); zo.closeEntry()
                        }
                    }
                }
            }
            
            ZipFile(tmp).use { z -> for (n in dexNames) if (z.getEntry(n) == null) throw IllegalStateException("$n missing after rewrite") }
            if (!tmp.renameTo(jar)) { jar.delete(); if (!tmp.renameTo(jar)) throw IllegalStateException("cannot replace ${jar.name}") }
        } finally {
            tmp.delete()
        }
        return Result(Outcome.PATCHED)
    }
}
