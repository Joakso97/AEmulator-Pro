package app.aemu.core

import java.io.File


object BinderProtocol {
    const val ENV = "AEMU_BINDER_PROTOCOL"
    
    const val GUEST_ENV = "AEMU64_BINDER_PROTOCOL"
    private const val FILE = "binder.protocol"
    private val MISMATCH = listOf(
        Regex("""kernel driver version \((\d+)\) differs from user space version \((\d+)\)"""),
        Regex("""[Bb]inder driver protocol\s*\((\d+)\) does not match user space protocol\s*\((\d+)\)"""),
    )

    
    fun parseMismatch(text: String): Pair<Int, Int>? {
        for (re in MISMATCH) {
            val m = re.findAll(text).lastOrNull() ?: continue
            val driver = m.groupValues[1].toIntOrNull() ?: continue
            val wanted = m.groupValues[2].toIntOrNull() ?: continue
            if (wanted != driver && wanted in 1..32) return driver to wanted
        }
        return null
    }

    
    fun saved(bin: File): Int? = runCatching { File(bin, FILE).readText().trim().toIntOrNull() }.getOrNull()?.takeIf { it in 1..32 }

    fun save(bin: File, version: Int) { runCatching { File(bin, FILE).writeText(version.toString()) } }

    
    fun x86Learned8(bin: File): Boolean = runCatching { File(bin, X86_RECORD).useLines { it.firstOrNull() }?.trim() == "8" }.getOrDefault(false)

    private const val X86_RECORD = "binder.proto"

    fun forget(bin: File) { runCatching { File(bin, FILE).delete() } }

    
    private const val WRITE_READ_32 = 0xc0186201L
    private const val WRITE_READ_64 = 0xc0306201L

    
    fun firmwareWants8(root: File): Boolean = runCatching {
        var has64 = false; var has32 = false
        for (rel in listOf("system/bin/servicemanager", "system/lib/libbinder.so")) {
            val f = File(root, rel)
            if (!f.isFile || f.length() > 16L shl 20) continue
            val d = f.readBytes()
            if (d.size < 64 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) continue      
            if (loadsWriteRead(d, WRITE_READ_64)) has64 = true
            if (loadsWriteRead(d, WRITE_READ_32)) has32 = true
        }
        has64 && !has32
    }.getOrDefault(false)

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
    private fun u32(d: ByteArray, o: Int) = u16(d, o).toLong() or (u16(d, o + 2).toLong() shl 16)

    private fun loadsWriteRead(d: ByteArray, v: Long): Boolean {
        val lo = (v and 0xffff).toInt(); val hi = ((v shr 16) and 0xffff).toInt()
        
        var o = 0
        while (o + 4 <= d.size) { if (u32(d, o) == v) return true; o += 4 }
        
        fun armImm(base: Long, imm: Int, rd: Int) = base or ((imm and 0xf000).toLong() shl 4) or (rd.toLong() shl 12) or (imm and 0xfff).toLong()
        o = 0
        while (o + 8 <= d.size) {
            val w = u32(d, o)
            val rd = ((w shr 12) and 0xf).toInt()
            if (w == armImm(0xe3000000L, lo, rd) && u32(d, o + 4) == armImm(0xe3400000L, hi, rd)) return true
            o += 4
        }
        
        fun thumbPair(op: Int, imm: Int, rd: Int): Pair<Int, Int> {
            val i = (imm shr 11) and 1; val imm4 = (imm shr 12) and 0xf; val imm3 = (imm shr 8) and 7; val imm8 = imm and 0xff
            return (op or (i shl 10) or imm4) to ((imm3 shl 12) or (rd shl 8) or imm8)
        }
        o = 0
        while (o + 8 <= d.size) {
            val h1 = u16(d, o)
            if ((h1 and 0xfbf0) == 0xf240) {                 
                val h2 = u16(d, o + 2); val rd = (h2 shr 8) and 0xf
                if (thumbPair(0xf240, lo, rd) == (h1 to h2) && thumbPair(0xf2c0, hi, rd) == (u16(d, o + 4) to u16(d, o + 6))) return true
            }
            o += 2
        }
        return false
    }

    
    fun wants8(paths: VmPaths): Boolean = saved(paths.bin) == 8 || firmwareWants8(paths.root)
}
