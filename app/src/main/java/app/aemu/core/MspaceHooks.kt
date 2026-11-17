package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder


object MspaceHooks {
    private val HOOKS = setOf("_ZN7android18mspace_malloc_statE", "_ZN7android16mspace_free_statE")
    private const val EM_ARM = 40
    private const val SHT_REL = 9
    private const val SHT_DYNSYM = 11
    private const val R_ARM_GLOB_DAT = 21
    private const val R_ARM_JUMP_SLOT = 22
    private const val R_ARM_RELATIVE = 23

    
    fun patched(d: ByteArray): ByteArray? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte() ||
            d[4] != 1.toByte() || d[5] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != EM_ARM) return null
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (shoff <= 0 || shentsize < 40 || shoff.toLong() + shnum.toLong() * shentsize > d.size) return null
        if (phoff <= 0 || phentsize < 32 || phoff.toLong() + phnum.toLong() * phentsize > d.size) return null

        
        val wanted = HashSet<Int>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != SHT_DYNSYM) continue
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            if (link !in 0 until shnum) continue
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s >= 0 && s + 16 <= symOff + symSize && s + 16 <= d.size) {
                if ((b.getShort(s + 14).toInt() and 0xffff) == 0) {
                    var e = strOff + b.getInt(s)
                    val start = e
                    while (e in 0 until d.size && d[e] != 0.toByte()) e++
                    if (start in 0 until d.size && String(d, start, e - start, Charsets.ISO_8859_1) in HOOKS) wanted += (s - symOff) / 16
                }
                s += 16
            }
        }
        if (wanted.isEmpty()) return null

        
        val rels = ArrayList<Int>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != SHT_REL) continue
            val off = b.getInt(sh + 16); val size = b.getInt(sh + 20)
            var e = off
            while (e >= 0 && e + 8 <= off + size && e + 8 <= d.size) {
                val info = b.getInt(e + 4)
                if ((info ushr 8) in wanted && ((info and 0xff) == R_ARM_GLOB_DAT || (info and 0xff) == R_ARM_JUMP_SLOT)) rels += e
                e += 8
            }
        }
        if (rels.isEmpty()) return null

        
        class Seg(val off: Int, val va: Int, val fileSize: Int, val flags: Int)
        val segs = (0 until phnum).map { phoff + it * phentsize }.filter { b.getInt(it) == 1 }
            .map { Seg(b.getInt(it + 4), b.getInt(it + 8), b.getInt(it + 16), b.getInt(it + 24)) }
        
        val ro = segs.firstOrNull { (it.flags and 2) == 0 } ?: return null
        var z = (maxOf(ro.off, 0x200) + 3) and 3.inv()
        var zeroVa = -1
        while (z + 8 <= ro.off + ro.fileSize && z + 8 <= d.size) {
            if ((0 until 8).all { d[z + it] == 0.toByte() } && (ro.va - ro.off + z) % 4 == 0) { zeroVa = ro.va - ro.off + z; break }
            z += 4
        }
        if (zeroVa < 0) return null

        
        val slotOffsets = rels.map { r ->
            val slot = b.getInt(r)
            val seg = segs.firstOrNull { slot >= it.va && slot + 4 <= it.va + it.fileSize } ?: return null
            slot - seg.va + seg.off
        }
        val out = d.copyOf()
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for ((i, r) in rels.withIndex()) {
            ob.putInt(r + 4, R_ARM_RELATIVE)      
            ob.putInt(slotOffsets[i], zeroVa)     
        }
        return out
    }

    
    fun apply(f: File): Boolean {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return false
        val out = patched(d) ?: return false
        if (!f.canWrite()) f.setWritable(true, true)
        f.writeBytes(out)
        return true
    }
}
