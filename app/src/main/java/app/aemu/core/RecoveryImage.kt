package app.aemu.core

import android.system.Os
import app.aemu.importer.AbLayout
import app.aemu.importer.BootImage
import java.io.File
import java.io.RandomAccessFile


object RecoveryImage {
    fun dir(paths: VmPaths) = File(paths.dir, "recovery")
    fun installed(paths: VmPaths) = File(dir(paths), "sbin/recovery").isFile
    fun fb(paths: VmPaths) = File(dir(paths), "dev/graphics/fb0")

    enum class Kind { TWRP, ORANGEFOX, CWM, STOCK }

    
    fun kindOf(dir: File): Kind {
        val prop = runCatching { File(dir, "default.prop").readText() }.getOrDefault("")
        return when {
            Regex("ro\\.twrp\\.version=").containsMatchIn(prop) || File(dir, "twres").isDirectory -> Kind.TWRP
            File(dir, "sbin/orangefox.sh").exists() || prop.contains("orangefox", true) -> Kind.ORANGEFOX
            prop.contains("cwm", true) || File(dir, "res/images/icon_clockwork.png").exists() ||
                File(dir, "sbin/recovery").takeIf { it.isFile }?.let { f ->
                    runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains("CWM-based Recovery") }.getOrDefault(false)
                } == true -> Kind.CWM
            else -> Kind.STOCK
        }
    }

    fun kind(paths: VmPaths): Kind = kindOf(dir(paths))

    fun isElf64(paths: VmPaths): Boolean = File(dir(paths), "sbin/recovery").let { f ->
        f.isFile && runCatching {
            f.inputStream().use {
                val b = ByteArray(5)
                it.read(b) == 5 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() && b[4] == 2.toByte()
            }
        }.getOrDefault(false)
    }

    
    fun install(paths: VmPaths, image: ByteArray, log: (String) -> Unit): Boolean {
        val rd = runCatching { BootImage.ramdisk(image) }.getOrNull()
        if (rd.isNullOrEmpty() || rd.none { it.name == "sbin/recovery" }) { log("recovery: image has no /sbin/recovery"); return false }
        return installEntries(paths, rd, log)
    }

    
    fun isEmbedded(rd: List<BootImage.CpioEntry>): Boolean =
        rd.any { it.name == "sbin/recovery" && (it.mode and 0xF000) == 0x8000 } && !AbLayout.isRecoveryRamdisk(rd)

    
    fun embeddedScript(rd: List<BootImage.CpioEntry>): String? = rd.firstOrNull { it.name == "recovery.rc" }?.name

    
    fun installEmbedded(paths: VmPaths, rd: List<BootImage.CpioEntry>, log: (String) -> Unit): Boolean {
        if (!isEmbedded(rd)) return false
        log("recovery: embedded in the kernel's initramfs (Samsung style${embeddedScript(rd)?.let { ", started by /$it" } ?: ""})")
        return installEntries(paths, rd, log)
    }

    
    fun installEntries(paths: VmPaths, rd: List<BootImage.CpioEntry>, log: (String) -> Unit): Boolean {
        val out = dir(paths)
        
        ImageStore.wipe(out)
        out.mkdirs()
        val base = out.canonicalPath
        for (e in rd) {
            val n = e.name.trimStart('/')
            if (n.isEmpty() || n == ".") continue
            val f = File(out, n)
            if (!f.canonicalPath.startsWith(base)) continue
            when (e.mode and 0xF000) {
                0x4000 -> f.mkdirs()
                0xA000 -> { f.parentFile?.mkdirs(); runCatching { Os.symlink(String(e.data), f.path) } }
                0x8000 -> {
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    if (e.mode and 0x49 != 0) f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
            }
        }
        val name = runCatching { File(out, "default.prop").readText() }.getOrDefault("")
            .let { Regex("twrp", RegexOption.IGNORE_CASE).containsMatchIn(it) }.let { if (it) "TWRP" else "" }
        log("recovery: installed ${rd.size} files ${name}".trim())
        return true
    }

    
    data class Prepared(
        
        val touchProtocolB: Boolean = false,
        
        val format: Int? = null,
        
        val notes: List<String> = emptyList(),
    )

    
    fun prepare(paths: VmPaths, sdcard: File?, log: (String) -> Unit = {}, api: Int = 0, settings: VmSettings? = null): Prepared {
        val r = dir(paths)
        for (d in listOf("dev/graphics", "dev/input", "tmp", "cache", "data", "system", "sdcard", "proc", "sys", "etc")) File(r, d).mkdirs()
        
        File(r, "dev/tty0").delete()

        
        val fb = fb(paths)
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: ((settings?.width ?: 720) to (settings?.height ?: 1280))
        val minSize = (w.toLong() * h * 4 * 2).coerceAtLeast(paths.fb.length())
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(fb.toPath())) fb.delete()
            fb.parentFile?.mkdirs()
            if (!fb.exists() || fb.length() < minSize) {
                RandomAccessFile(fb, "rw").use { it.setLength(minSize) }
            }
        }

        
        runCatching {
            for (lib in listOf("librecshim.so", "libaemushim.so")) {
                EngineAssets.open(paths.ctx, "engines/common/$lib").use { i -> File(r, "sbin/$lib").outputStream().use { i.copyTo(it) } }
                File(r, "sbin/$lib").setReadable(true, false)
            }
        }
        File(r, "dev/aemu_power").let { if (!it.exists()) it.createNewFile() }
        runCatching { File(r, "dhd.fbgeom").writeText("$w $h\n") }

        
        val mounts = """
rootfs / rootfs rw,relatime 0 0
tmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0
devpts /dev/pts devpts rw,relatime,mode=600 0 0
proc /proc proc rw,relatime 0 0
sysfs /sys sysfs rw,relatime 0 0
/dev/block/bootdevice/by-name/system /system ext4 rw,relatime 0 0
/dev/block/bootdevice/by-name/userdata /data ext4 rw,relatime 0 0
/dev/block/bootdevice/by-name/cache /cache ext4 rw,relatime 0 0
/dev/block/bootdevice/by-name/boot /boot emmc rw,relatime 0 0
/dev/block/bootdevice/by-name/recovery /recovery emmc rw,relatime 0 0
/data/media/0 /sdcard vfat rw,relatime 0 0
""".trimIndent()
        runCatching { File(r, "tmp/aemu_mounts").writeText(mounts + "\n") }

        
        runCatching {
            val defProp = File(r, "default.prop")
            val existing = if (defProp.isFile) defProp.readText() else ""
            val density = settings?.density?.takeIf { it > 0 } ?: 320
            val sb = java.lang.StringBuilder(existing)
            if (!existing.contains("ro.sf.lcd_density")) sb.append("\nro.sf.lcd_density=$density\n")
            if (!existing.contains("ro.twrp.boot")) sb.append("ro.twrp.boot=1\n")
            if (!existing.contains("ro.boot.recovery")) sb.append("ro.boot.recovery=1\n")
            if (!existing.contains("ro.product.model")) sb.append("ro.product.model=AEmulator Guest\n")
            defProp.writeText(sb.toString())
        }

        
        for ((name, target) in listOf("system" to File(paths.root, "system"), "data" to File(paths.root, "data"), "cache" to File(paths.root, "cache"))) {
            val link = File(r, name)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && (!link.exists() || (link.isDirectory && !hasFiles(link)))) {
                ImageStore.wipe(link); runCatching { Os.symlink(target.absolutePath, link.path) }
            }
        }
        if (sdcard != null) {
            val link = File(r, "sdcard")
            if (link.isDirectory && link.list().isNullOrEmpty()) { link.delete(); runCatching { Os.symlink(sdcard.absolutePath, link.path) } }
        }
        return prepareStatic(r, log, api)
    }

    
    
    

    private fun prepareStatic(r: File, log: (String) -> Unit, api: Int): Prepared {
        val bin = File(r, "sbin/recovery")
        val data = runCatching { bin.readBytes() }.getOrNull() ?: return Prepared()
        if (!isStaticArmElf(data)) return Prepared()
        val notes = ArrayList<String>()
        
        
        val flush = patchCacheflushExec(data)
        if (flush > 0) {
            runCatching { bin.writeBytes(data) }.onFailure { log("recovery: cacheflush patch failed: ${it.message}") }
                .onSuccess { notes += "cacheflush() makes generated code executable (static libc before 2.3)" }
        }
        
        
        val pers = patchPersonality(data)
        if (pers > 0) {
            runCatching { bin.writeBytes(data) }.onFailure { log("recovery: personality patch failed: ${it.message}") }
                .onSuccess { notes += "personality(PER_LINUX32) made a no-op ($pers)" }
        }
        
        
        
        val um = patchUmount(data)
        if (um > 0) {
            runCatching { bin.writeBytes(data) }.onFailure { log("recovery: umount patch failed: ${it.message}") }
                .onSuccess { notes += "umount()/umount2()/syslog() answered without a system call ($um)" }
        }
        stubBlockDevices(r)?.let { notes += "stubbed $it block device nodes" }
        stubProcMounts(r)
        
        
        val mask = runCatching { patchEvdevProbes(bin, data) }.onFailure { log("recovery: input patch failed: ${it.message}") }.getOrDefault(0)
        if (mask != 0) notes += "input probes accepted (${Integer.toBinaryString(mask)})"
        
        
        val fb = runCatching { patchFramebuffer(data)?.also { if (it.patched > 0) bin.writeBytes(data) } }
            .onFailure { log("recovery: framebuffer patch failed: ${it.message}") }.getOrNull()
        val format = chooseFormat(api, fb?.format) { fbWants32Bit(data) }
        if (format != null) notes += when (format) { 0 -> "framebuffer RGB565"; 2 -> "framebuffer BGRA_8888"; else -> "framebuffer 32 bpp RGBX" }
        if (fb != null && fb.patched > 0) notes += "framebuffer pitch matched to the host (${fb.patched} instructions)"
        notes.forEach { log("recovery: $it") }
        return Prepared(touchProtocolB = mask and 1 != 0, format = format, notes = notes)
    }

    
    internal fun chooseFormat(api: Int, patched: Int?, wants32: () -> Boolean): Int? =
        patched ?: if (api in 1..13) 0 else if (wants32()) 1 else null

    
    internal fun patchCacheflushExec(d: ByteArray): Int {
        val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var n = 0
        var i = 0
        while (i + 4 <= d.size) {
            if (b.getInt(i) == 0x000F0002) {
                for (k in 1..11) {
                    val off = i - 4 * k
                    if (off < 0) break
                    if ((b.getInt(off) and 0xffff0000.toInt()) == 0xE92D0000.toInt()) { 
                        val code = ElfPatch.cacheflushExecBytes(d, off)
                        if (code != null) { System.arraycopy(code, 0, d, off, code.size); n++ }
                        break
                    }
                }
            }
            i += 4
        }
        return n
    }

    
    internal fun patchPersonality(d: ByteArray): Int {
        val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var n = 0
        var i = 0
        while (i + 12 <= d.size) {
            if (b.getInt(i) == 0xe1a0c007.toInt() && b.getInt(i + 8) == 0xef000000.toInt()) {
                val w = b.getInt(i + 4)
                val nr = when {
                    w == 0xe3a07088.toInt() -> 136
                    w and 0xfffff000.toInt() == 0xe59f7000.toInt() ->
                        (i + 4 + 8 + (w and 0xfff)).takeIf { it + 4 <= d.size }?.let { b.getInt(it) } ?: -1
                    else -> -1
                }
                if (nr == 136) { b.putInt(i + 8, 0xe3a00000.toInt()); n++ }
            }
            i += 4
        }
        return n
    }

    
    internal fun patchUmount(d: ByteArray): Int {
        val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val answers = mapOf(
            0xe3a07016.toInt() to 0xe3e00015.toInt(), 
            0xe3a07034.toInt() to 0xe3e00015.toInt(), 
            0xe3a07067.toInt() to 0xe3a00000.toInt(), 
        )
        var n = 0
        var i = 0
        while (i + 8 <= d.size) {
            val answer = answers[b.getInt(i)]
            if (answer != null && b.getInt(i + 4) == 0xef000000.toInt()) {
                var prologue = false
                for (k in 1..3) {
                    val o = i - 4 * k
                    if (o < 0) break
                    val p = b.getInt(o)
                    if ((p and 0xffff0000.toInt()) == 0xe92d0000.toInt() || p == 0xe1a0c007.toInt() || p == 0xe1a0c00d.toInt()) { prologue = true; break }
                }
                if (prologue) { b.putInt(i + 4, answer); n++ }
            }
            i += 4
        }
        return n
    }

    private fun isStaticArmElf(d: ByteArray): Boolean {
        if (d.size < 64 || d[0] != 0x7F.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return false
        if (d[4].toInt() != 1 || d[5].toInt() != 1) return false 
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (u16(18) != 40) return false 
        val off = u32(28); val sz = u16(42); val n = u16(44)
        for (i in 0 until n) {
            val h = off + i * sz
            if (h + 4 > d.size) return false
            if (u32(h) == 3) return false 
        }
        return true
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int = 0): Int {
        var i = from
        outer@ while (i <= h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    
    private class ProbePatch(val sig: ByteArray, val nopAt: Int, val branchAt: Int)

    private val probePatches = listOf(
        
        ProbePatch(hex("002804dda87900f06001602904d0"), 2, 12),
        
        ProbePatch(hex("002803db019b13f0060f03d1"), 2, 10),
        
        
        ProbePatch(hex("002803db039b13f0060f03d1"), 2, 10),
        
        
        
        ProbePatch(hex("002803db039b13f00e0f03d1"), 2, 10),
    )

    
    private fun patchEvdevProbes(bin: File, data: ByteArray): Int {
        val before = data.copyOf()
        val mask = patchEvdevProbes(data)
        if (!data.contentEquals(before)) bin.writeBytes(data)
        return mask
    }

    
    internal fun patchEvdevProbes(data: ByteArray): Int {
        var mask = 0
        for ((i, p) in probePatches.withIndex()) {
            val at = indexOf(data, p.sig)
            if (at >= 0) {
                data[at + p.nopAt] = 0x00; data[at + p.nopAt + 1] = 0xBF.toByte()   
                data[at + p.branchAt + 1] = 0xE0.toByte()                           
                mask = mask or (1 shl i)
            } else {
                val done = p.sig.copyOf().also {
                    it[p.nopAt] = 0x00; it[p.nopAt + 1] = 0xBF.toByte(); it[p.branchAt + 1] = 0xE0.toByte()
                }
                if (indexOf(data, done) >= 0) mask = mask or (1 shl i)
            }
        }
        return mask
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun put16(d: ByteArray, o: Int, v: Int) { d[o] = v.toByte(); d[o + 1] = (v shr 8).toByte() }

    
    internal class FbFix(val format: Int?, val patched: Int)

    
    internal fun patchFramebuffer(d: ByteArray): FbFix? {
        var at = 0
        var anchor = -1
        var format: Int? = null
        while (at + 4 <= d.size) {
            
            if (u16(d, at) == 0xF244 && (u16(d, at + 2) and 0xF0FF) == 0x6002) {
                var found: Int? = null
                var j = at + 4
                val limit = minOf(d.size - 2, at + 0x200)
                while (j < limit && found == null) {
                    val h = u16(d, j)
                    if ((h and 0xFFC0) == 0x7500) { 
                        val rt = h and 7
                        var k = j - 2
                        while (k >= maxOf(at, j - 24)) {
                            val m = u16(d, k)
                            if ((m and 0xF800) == 0x2000 && ((m shr 8) and 7) == rt) { found = m and 0xFF; break }
                            k -= 2
                        }
                    }
                    j += 2
                }
                if (anchor < 0 || (format == null && found != null)) { anchor = at; format = found }
                if (found != null) break
            }
            at += 2
        }
        if (anchor < 0) return null
        val host = when (format) { 4 -> 0; 1, 2 -> 1; 5 -> 2; else -> null }
        if (format == 4) return FbFix(host, 0) 
        val end = grInitEnd(d, anchor)
        hookLineLength(d, anchor, end)?.let { return FbFix(host, it) }
        var patched = 0
        var j = anchor + 4
        while (j < end) {
            val h = u16(d, j)
            if ((h and 0xFFC0) == 0x0880) { put16(d, j, (h and 0x3F) or 0x0840); patched++ } 
            j += 2
        }
        if (patched == 0) return FbFix(host, 0)
        j = anchor + 4
        while (j < end) {
            
            if ((u16(d, j) and 0xFFF0) == 0xEBB0 && (u16(d, j + 2) and 0xFFF0) == 0x0F40) {
                val off = u16(d, j + 2) and 0xF
                var k = j + 4
                while (k < minOf(end, j + 0x40)) {
                    val h1 = u16(d, k); val h2 = u16(d, k + 2)
                    
                    if ((h1 and 0xFFF0) == 0xEB00 && (h2 and 0xF0F0) == 0) {
                        val rn = h1 and 0xF; val rm = h2 and 0xF
                        if (rm == off) { put16(d, k + 2, h2 or 0x40); patched++; break }
                        if (rn == off) { put16(d, k, (h1 and 0xFFF0) or rm); put16(d, k + 2, (h2 and 0xFFF0) or rn or 0x40); patched++; break }
                    }
                    k += 2
                }
                break
            }
            j += 2
        }
        return FbFix(host, patched)
    }

    
    private fun grInitEnd(d: ByteArray, anchor: Int): Int {
        var end = minOf(d.size - 4, anchor + 0x180)
        var j = anchor + 4
        while (j < end) { if (u16(d, j) == 0xE8BD && (u16(d, j + 2) and 0x8000) != 0) { end = j; break }; j += 2 }
        return end
    }

    

    private const val HOOK_BYTES = 20                 
    
    private fun caveBytes(moved: Int) = HOOK_BYTES + moved + 4

    private fun u32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)
    private fun put32(d: ByteArray, o: Int, v: Int) { put16(d, o, v and 0xFFFF); put16(d, o + 2, v ushr 16) }

    
    private fun encodeBw(from: Int, to: Int): Int? {
        val off = to - (from + 4)
        if (off < -(1 shl 24) || off >= (1 shl 24) || off and 1 != 0) return null
        val s = (off shr 24) and 1
        val j1 = (if (((off shr 23) and 1) xor s == 0) 1 else 0)
        val j2 = (if (((off shr 22) and 1) xor s == 0) 1 else 0)
        val h1 = 0xF000 or (s shl 10) or ((off shr 12) and 0x3FF)
        val h2 = 0x9000 or (j1 shl 13) or (j2 shl 11) or ((off shr 1) and 0x7FF)
        return h1 or (h2 shl 16)
    }

    
    private fun decodeBw(d: ByteArray, at: Int): Int? {
        val h1 = u16(d, at); val h2 = u16(d, at + 2)
        if ((h1 and 0xF800) != 0xF000 || (h2 and 0xD000) != 0x9000) return null
        val s = (h1 shr 10) and 1
        val i1 = ((h2 shr 13) and 1 xor s) xor 1
        val i2 = ((h2 shr 11) and 1 xor s) xor 1
        var off = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3FF) shl 12) or ((h2 and 0x7FF) shl 1)
        if (s == 1) off = off or (-1 shl 25)
        return at + 4 + off
    }

    
    private fun relocatable16(h: Int): Boolean {
        if ((h and 0xF800) == 0x4800) return false                         
        if ((h and 0xF800) == 0xA000) return false                         
        if ((h and 0xF000) == 0xD000) return false                         
        if ((h and 0xF800) == 0xE000) return false                         
        if ((h and 0xF500) == 0xB100) return false                         
        if ((h and 0xFF00) == 0xBD00) return false                         
        if ((h and 0xFF00) == 0xBF00 && h != 0xBF00) return false          
        if ((h and 0xFC00) == 0x4400) {                                    
            if ((h and 0xFF00) == 0x4700) return false
            val rm = (h shr 3) and 0xF
            val rd = (h and 7) or ((h shr 4) and 8)
            if (rm == 15 || rd == 15) return false
        }
        return true
    }

    
    private fun movedLength(d: ByteArray, at: Int): Int? {
        var n = 0
        while (n < 4) {
            if (at + n + 2 > d.size) return null
            val wide = (u16(d, at + n) and 0xF800) >= 0xE800
            if (wide) {
                if (at + n + 4 > d.size || !relocatable32(d, at + n)) return null
                n += 4
            } else {
                if (!relocatable16(u16(d, at + n))) return null
                n += 2
            }
        }
        return n
    }

    private fun signExtend(v: Int, bits: Int) = (v shl (32 - bits)) shr (32 - bits)

    
    private fun branchInto(d: ByteArray, from: Int, to: Int, lo: Int, hi: Int): Boolean {
        var j = from and 1.inv()
        while (j + 2 <= to) {
            val h = u16(d, j)
            var target: Int? = null
            when {
                (h and 0xF000) == 0xD000 && ((h shr 8) and 0xF) < 0xE -> target = j + 4 + 2 * signExtend(h and 0xFF, 8)
                (h and 0xF800) == 0xE000 -> target = j + 4 + 2 * signExtend(h and 0x7FF, 11)
                (h and 0xF500) == 0xB100 -> target = j + 4 + ((((h shr 9) and 1) shl 6) or (((h shr 3) and 0x1F) shl 1))
                (h and 0xF800) == 0xF000 && j + 4 <= d.size -> {
                    val h2 = u16(d, j + 2)
                    if ((h2 and 0xD000) == 0x9000) target = decodeBw(d, j)
                    else if ((h2 and 0xD000) == 0x8000) { 
                        val s = (h shr 10) and 1
                        val imm = (s shl 20) or (((h2 shr 11) and 1) shl 19) or (((h2 shr 13) and 1) shl 18) or
                            ((h and 0x3F) shl 12) or ((h2 and 0x7FF) shl 1)
                        target = j + 4 + signExtend(imm, 21)
                    }
                }
            }
            if (target != null && target >= lo && target < hi) return true
            j += 2
        }
        return false
    }

    
    private fun relocatable32(d: ByteArray, at: Int): Boolean {
        val h1 = u16(d, at); val h2 = u16(d, at + 2)
        if ((h1 and 0xF800) < 0xE800) return false                         
        if ((h1 and 0xF800) == 0xF000 && (h2 and 0x8000) != 0) return false 
        if ((h1 and 0xFE00) == 0xF800 && (h1 and 0xF) == 0xF) return false  
        if ((h1 and 0xFE50) == 0xE850 && (h1 and 0xF) == 0xF) return false  
        if ((h1 and 0xFFF0) == 0xE8D0) return false                         
        if ((h1 and 0xFBFF) == 0xF2AF || (h1 and 0xFBFF) == 0xF20F) return false 
        return true
    }

    
    private fun hookLineLength(d: ByteArray, anchor: Int, end: Int): Int? {
        
        var rFi = -1
        var bl = -1
        var j = anchor + 4
        while (j < minOf(d.size - 4, anchor + 0x20)) {
            val h = u16(d, j)
            if ((h and 0xFF87) == 0x4602) rFi = (h shr 3) and 0xF                  
            val h2 = u16(d, j + 2)
            if ((h and 0xF800) == 0xF000 && ((h2 and 0xD000) == 0xD000 || (h2 and 0xD000) == 0xC000)) { bl = j; break } 
            j += 2
        }
        if (bl < 0 || rFi !in 4..11) return null
        
        if (bl + 8 > d.size || u16(d, bl + 4) != 0x2800) return null
        val br = u16(d, bl + 6)
        if ((br and 0xF000) != 0xD000) return null
        val cond = (br shr 8) and 0xF
        val imm = (br and 0xFF).let { if (it >= 0x80) it - 0x100 else it }
        val hook = when {
            (cond == 0xA || cond == 0x5) && imm >= 0 -> bl + 6 + 4 + 2 * imm
            (cond == 0xB || cond == 0x4) && imm > 0 -> bl + 8
            else -> return null
        }
        if (hook + 4 > d.size) return null

        
        decodeBw(d, hook)?.let { cave ->
            if (cave in 0..d.size - HOOK_BYTES - 4 && u32(d, cave) == 0x0D04F84D && u32(d, cave + 4) == (0x002CF8D0 or rFi)) return 0
        }
        val moved = movedLength(d, hook) ?: return null
        
        if (branchInto(d, maxOf(0, anchor - 0x200), minOf(d.size - 4, end), hook + 2, hook + moved)) return null

        
        val size = caveBytes(moved)
        val cave = findCave(d, hook, size) ?: return null
        val back = encodeBw(cave.start + HOOK_BYTES + moved, hook + moved) ?: return null
        val there = encodeBw(hook, cave.start) ?: return null
        put32(d, cave.start, 0x0D04F84D)                            
        put32(d, cave.start + 4, 0x002CF8D0 or rFi)                 
        put32(d, cave.start + 8, 0x0040EA4F)                        
        put32(d, cave.start + 12, 0x002CF8C0 or rFi)                
        put32(d, cave.start + 16, 0x0B04F85D)                       
        System.arraycopy(d, hook, d, cave.start + HOOK_BYTES, moved) 
        put32(d, cave.start + HOOK_BYTES + moved, back)
        put32(d, hook, there)
        if (moved == 6) put16(d, hook + 4, 0xBF00)                  
        put32(d, cave.header + 16, cave.fileSize + cave.start + size - cave.end) 
        put32(d, cave.header + 20, cave.memSize + cave.start + size - cave.end)  
        var changed = 3
        
        
        changed += undoPitchPatch(d, anchor, end)
        return changed
    }

    private class Cave(val start: Int, val end: Int, val header: Int, val fileSize: Int, val memSize: Int)

    
    private fun findCave(d: ByteArray, at: Int, size: Int): Cave? {
        if (d.size < 64 || u32(d, 0) != 0x464C457F || d[4].toInt() != 1 || d[5].toInt() != 1) return null
        val off = u32(d, 28); val sz = u16(d, 42); val n = u16(d, 44)
        if (sz < 32 || off < 52 || off + n * sz > d.size) return null
        for (i in 0 until n) {
            val h = off + i * sz
            if (u32(d, h) != 1 || (u32(d, h + 24) and 1) == 0) continue            
            val po = u32(d, h + 4); val va = u32(d, h + 8); val fs = u32(d, h + 16); val ms = u32(d, h + 20)
            if (at < po || at + 4 > po + fs || fs != ms) continue
            val end = po + fs
            val start = (end + 3) and 3.inv()
            val pageEndVa = (va + fs + 0xFFF) and 0xFFF.inv()
            val pageEnd = po + (pageEndVa - va)                             
            if (start + size > pageEnd || start + size > d.size) return null
            for (k in 0 until n) {                                          
                val g = off + k * sz
                if (k == i || u32(d, g) != 1) continue
                val gv = u32(d, g + 8); val gm = u32(d, g + 20)
                if (gm != 0 && gv < pageEndVa && gv + gm > va + fs) return null
            }
            for (k in end until start + size) if (d[k].toInt() != 0) return null
            return Cave(start, end, h, fs, ms)
        }
        return null
    }

    
    private fun undoPitchPatch(d: ByteArray, anchor: Int, end: Int): Int {
        var j = anchor + 4
        var undone = 0
        while (j < end) {
            if ((u16(d, j) and 0xFFF0) == 0xEBB0 && (u16(d, j + 2) and 0xFFF0) == 0x0F40) {
                val off = u16(d, j + 2) and 0xF
                var k = j + 4
                while (k < minOf(end, j + 0x40)) {
                    val h1 = u16(d, k); val h2 = u16(d, k + 2)
                    
                    if ((h1 and 0xFFF0) == 0xEB00 && (h2 and 0xF0F0) == 0x0040 && (h2 and 0xF) == off) {
                        put16(d, k + 2, h2 and 0x40.inv()); undone++
                        var m = anchor + 4
                        while (m < end) {
                            val h = u16(d, m)
                            if ((h and 0xFFC0) == 0x0840) { put16(d, m, (h and 0x3F) or 0x0880); undone++ }
                            m += 2
                        }
                        return undone
                    }
                    k += 2
                }
                break
            }
            j += 2
        }
        return 0
    }

    
    private fun fbWants32Bit(d: ByteArray): Boolean {
        fun b(i: Int) = d[i].toInt() and 0xFF
        for (i in 0 until d.size - 4) {
            val thumbMovw = b(i) == 0x44 && b(i + 1) == 0xF2 && b(i + 2) == 0x01 && (b(i + 3) and 0xF0) == 0x60
            val armMovw = b(i) == 0x01 && (b(i + 1) and 0x0F) == 0x06 && b(i + 2) == 0x04 && b(i + 3) == 0xE3
            if (!thumbMovw && !armMovw) continue
            for (j in maxOf(0, i - 128) until i) {
                if (b(j) == 0x20 && b(j + 1) in 0x20..0x27) return true                                   
                if (j + 3 < d.size && b(j) == 0x4F && b(j + 1) == 0xF0 && b(j + 2) == 0x20 && (b(j + 3) and 0xF0) == 0) return true 
                if (j + 3 < d.size && b(j) == 0x20 && (b(j + 1) and 0x0F) == 0 && b(j + 2) == 0xA0 && b(j + 3) == 0xE3) return true 
            }
        }
        return false
    }

    private class Vol(val mount: String, val fs: String, val device: String)

    
    private fun volumes(r: File): List<Vol> {
        val fstab = listOf("etc/recovery.fstab", "etc/twrp.fstab", "res/recovery.fstab").map { File(r, it) }.firstOrNull { it.isFile } ?: return emptyList()
        return fstab.readLines().mapNotNull { line ->
            val p = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(Regex("\\s+")) ?: return@mapNotNull null
            if (p.size < 3) null
            else if (p[0].startsWith("/dev/") || p[0] == "ramdisk" || p[0] == "tmpfs") Vol(p[1], p[2], p[0])
            else Vol(p[0], p[1], p[2])
        }
    }

    
    private fun stubBlockDevices(r: File): Int? {
        var made = 0
        for (v in volumes(r)) {
            if (!v.device.startsWith("/dev/block/")) continue
            val f = File(r, v.device.removePrefix("/"))
            if (f.exists()) continue
            runCatching { f.parentFile?.mkdirs(); f.createNewFile(); f.setReadable(true, false); f.setWritable(true, false); made++ }
        }
        return made.takeIf { it > 0 }
    }

    
    private fun stubProcMounts(r: File) {
        val f = File(r, "proc/mounts")
        if (f.exists()) return
        val text = StringBuilder()
        for (v in volumes(r)) {
            if (v.fs in setOf("ext4", "ext3", "ext2", "f2fs", "vfat", "rfs", "ramdisk") && v.mount.length > 1 && v.mount.startsWith("/"))
                text.append("${v.device} ${v.mount} ${if (v.fs == "ramdisk") "tmpfs" else v.fs} rw 0 0\n")
        }
        if (text.isNotEmpty()) runCatching { f.parentFile?.mkdirs(); f.writeText(text.toString()); f.setReadable(true, false) }
    }

    
    private fun hasFiles(d: File): Boolean = d.listFiles().orEmpty().any {
        java.nio.file.Files.isSymbolicLink(it.toPath()) || !it.isDirectory || hasFiles(it)
    }
}
