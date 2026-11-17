package app.aemu.core

import java.io.File
import java.io.RandomAccessFile


object BroadcomGpu {
    
    fun hasV3dComposer(lib: File): Boolean = runCatching {
        String(lib.readBytes(), Charsets.ISO_8859_1).contains("V3D Composer creation failed")
    }.getOrDefault(false)

    
    fun ensurePoolFile(root: File): Boolean = runCatching {
        val dev = File(root, "dev").apply { mkdirs() }
        dev.setReadable(true, false); dev.setExecutable(true, false)
        val f = File(dev, POOL_FILE)
        if (!f.isFile) { f.delete(); f.createNewFile() }
        
        
        
        runCatching { java.io.RandomAccessFile(f, "rw").use { it.setLength(0) } }
        f.setReadable(true, false); f.setWritable(true, false)
        f.isFile
    }.getOrDefault(false)

    
    fun resetPoolHeader(root: File): Boolean = runCatching {
        val f = File(root, "dev/$POOL_FILE")
        if (!f.isFile || f.length() < 4096) return@runCatching false
        java.io.RandomAccessFile(f, "rw").use { it.seek(0); it.write(ByteArray(4096)) }
        true
    }.getOrDefault(false)

    const val POOL_FILE = ".aemu-gememalloc"

    
    fun v3dFirmware(root: File): Boolean =
        File(root, "system/lib/libsurfaceflinger.so").let { it.isFile && hasV3dComposer(it) }

    
    fun restoreParkedGralloc(root: File): Int {
        var n = 0
        File(root, "system/.aemu-parked").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gememalloc") && it.name.contains("#gralloc.") }
            ?.forEach { parked ->
                val dst = File(root, parked.name.removeSuffix(".gememalloc").replace('#', '/'))
                if (dst.exists()) { parked.delete(); return@forEach }      
                if (parked.renameTo(dst)) n++
            }
        return n
    }

    
    private const val SF_V3D_FROM = 0x24800
    private const val SF_V3D_TO = 0x26c40

    
    fun undoLegacyComposerPatch(lib: File, pristine: File): Int {
        if (!pristine.isFile || pristine.length() != lib.length() || !hasV3dComposer(pristine)) return 0
        val a = runCatching { lib.readBytes() }.getOrNull() ?: return 0
        val b = runCatching { pristine.readBytes() }.getOrNull() ?: return 0
        if (SF_V3D_TO > a.size) return 0
        var same = true
        for (i in SF_V3D_FROM until SF_V3D_TO) if (a[i] != b[i]) { same = false; break }
        if (same) return 0
        if (!lib.canWrite()) lib.setWritable(true, true)
        return if (runCatching { pristine.copyTo(lib, overwrite = true) }.isSuccess) 1 else 0
    }

    
    private val LIBUI = listOf(0x1b2c6 to (0x3098 to 0x3090), 0x1b09a to (0x3594 to 0x358c), 0x1b0b2 to (0x339c to 0x3394),
        0x1b0ca to (0x3290 to 0x3288), 0x1b5c4 to (0x3394 to 0x338c))

    
    fun undoLegacyLibuiPatch(ui: File, backup: File): Int {
        val d = runCatching { ui.readBytes() }.getOrNull() ?: return 0
        fun h(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        if (LIBUI.any { (o, _) -> o + 2 > d.size }) return 0
        if (!LIBUI.all { (o, v) -> h(o) == v.second }) return 0
        if (!ui.canWrite()) ui.setWritable(true, true)
        RandomAccessFile(ui, "rw").use { raf ->
            for ((o, v) in LIBUI) { raf.seek(o.toLong()); raf.write(byteArrayOf((v.first and 0xff).toByte(), (v.first shr 8).toByte())) }
        }
        return 1
    }
}
