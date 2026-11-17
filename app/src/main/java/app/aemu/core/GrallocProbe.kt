package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder


object GrallocProbe {
    enum class Verdict {
        
        GUARDED,

        
        UNGUARDED,

        
        UNKNOWN,
    }

    
    const val AOSP_ALLOC_DEVICE_SIZE = 0x68

    
    fun allocDeviceSize(elf: ByteArray): Int? {
      return try {
        val d = elf
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(o: Int) = b.getInt(o)
        fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) null
        else {
            val loads = ArrayList<IntArray>()
            val phoff = u32(0x1c); val phent = u16(0x2a); val phnum = u16(0x2c)
            var dynOff = -1; var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                if (u32(p) == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (u32(p) == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]; return null }
            val tags = HashMap<Int, Int>()
            var i = 0
            while (dynOff >= 0 && i + 8 <= dynSize) { val t = u32(dynOff + i); if (t == 0) break; if (!tags.containsKey(t)) tags[t] = u32(dynOff + i + 4); i += 8 }
            val symtab = fo(tags[6] ?: return null) ?: return null
            val strtab = fo(tags[5] ?: return null) ?: return null
            val nsym = (strtab - symtab) / 16
            fun name(s: Int): String { var e = strtab + u32(symtab + s * 16); val st = e; while (d[e] != 0.toByte()) e++; return String(d, st, e - st, Charsets.ISO_8859_1) }
            val hmiSym = (0 until nsym).firstOrNull { name(it) == "HMI" && u16(symtab + it * 16 + 14) != 0 } ?: return null
            val hmi = fo(u32(symtab + hmiSym * 16 + 4)) ?: return null
            if (u32(hmi) != 0x48574d54) return null
            val methods = fo(u32(hmi + 20)) ?: return null      
            val open = u32(methods)
            if ((open and 1) == 0) return null                    
            val o = fo(open and 1.inv()) ?: return null
            
            var p = 0
            var size: Int? = null
            while (p < 0x60 && o + p + 6 <= d.size) {
                val h = u16(o + p); val n1 = u16(o + p + 2); val n2 = u16(o + p + 4)
                if ((h and 0xff00) == 0x2000 && (n1 and 0xf800) == 0xf000 && (n2 and 0xc000) == 0xc000) { size = h and 0xff; break }
                p += 2
            }
            size
        }
    } catch (_: Exception) { null }
    }

    
    fun withFreeAtSlot48(elf: ByteArray): ByteArray? {
        val start = openFunctionOffset(elf) ?: return null
        
        val want = intArrayOf(0x6420, 0x4628, 0x447b, 0x6461, 0x63e3, 0x6034, 0xbdf8, 0xbf00)
        
        
        
        
        
        
        val repl = intArrayOf(0x6420, 0xe9c4, 0x0111, 0x4628, 0x447b, 0x63e3, 0x6034, 0xbdf8)
        var p = start
        val end = minOf(start + 0x80, elf.size - 2 * want.size)
        while (p <= end) {
            var ok = true
            for (k in want.indices) {
                val h = (elf[p + 2 * k].toInt() and 0xff) or ((elf[p + 2 * k + 1].toInt() and 0xff) shl 8)
                if (h != want[k]) { ok = false; break }
            }
            if (ok) {
                val out = elf.copyOf()
                for (k in repl.indices) {
                    out[p + 2 * k] = (repl[k] and 0xff).toByte()
                    out[p + 2 * k + 1] = ((repl[k] shr 8) and 0xff).toByte()
                }
                return out
            }
            p += 2
        }
        return null
    }

    
    fun libuiFreeSlot(libui: ByteArray): Int? {
        
        
        var at44 = 0; var at48 = 0
        fun h(o: Int) = (libui[o].toInt() and 0xff) or ((libui[o + 1].toInt() and 0xff) shl 8)
        var p = 0
        while (p + 8 <= libui.size) {
            if (h(p + 6) == 0x4798 && (h(p + 4) and 0xf83f) == 0x6803 ) {
                val a = h(p); val b = h(p + 2)
                fun ldr0(x: Int) = (x and 0xffc0) == 0x6800 && (x and 7) == 0    
                fun mov1(x: Int) = (x and 0xff87) == 0x4601                       
                if ((ldr0(a) && mov1(b)) || (mov1(a) && ldr0(b))) when (((h(p + 4) shr 6) and 0x1f) * 4) { 0x44 -> at44++; 0x48 -> at48++ }
            }
            p += 2
        }
        return when { at44 > 0 && at48 == 0 -> 0x44; at48 > 0 && at44 == 0 -> 0x48; else -> null }
    }

    
    private fun openFunctionOffset(d: ByteArray): Int? {
        return try {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(o: Int) = b.getInt(o)
            fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
            if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) return null
            val loads = ArrayList<IntArray>()
            val phoff = u32(0x1c); val phent = u16(0x2a); val phnum = u16(0x2c)
            var dynOff = -1; var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                if (u32(p) == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (u32(p) == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]; return null }
            val tags = HashMap<Int, Int>()
            var i = 0
            while (dynOff >= 0 && i + 8 <= dynSize) { val t = u32(dynOff + i); if (t == 0) break; if (!tags.containsKey(t)) tags[t] = u32(dynOff + i + 4); i += 8 }
            val symtab = fo(tags[6] ?: return null) ?: return null
            val strtab = fo(tags[5] ?: return null) ?: return null
            val nsym = (strtab - symtab) / 16
            fun name(s: Int): String { var e = strtab + u32(symtab + s * 16); val st = e; while (d[e] != 0.toByte()) e++; return String(d, st, e - st, Charsets.ISO_8859_1) }
            val hmiSym = (0 until nsym).firstOrNull { name(it) == "HMI" && u16(symtab + it * 16 + 14) != 0 } ?: return null
            val hmi = fo(u32(symtab + hmiSym * 16 + 4)) ?: return null
            if (u32(hmi) != 0x48574d54) return null
            val methods = fo(u32(hmi + 20)) ?: return null
            val open = u32(methods)
            if ((open and 1) == 0) return null
            fo(open and 1.inv())
        } catch (_: Exception) { null }
    }

    fun verdict(elf: ByteArray): Verdict = try {
        Analysis(elf).run()
    } catch (_: Exception) {
        Verdict.UNKNOWN
    }

    private const val MAX_FUNCTION = 0x180
    private const val HMI_REGISTER = 128
    private const val HMI_UNREGISTER = 132
    private const val HMI_LOCK = 136
    private const val HMI_UNLOCK = 140

    private class Analysis(private val d: ByteArray) {
        private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        private fun u32(o: Int) = b.getInt(o)
        private fun u16(o: Int) = b.getShort(o).toInt() and 0xffff

        private val loads = ArrayList<IntArray>() 
        private fun fileOffset(va: Int): Int? {
            for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]
            return null
        }

        private fun cString(o: Int): String {
            var e = o
            while (d[e] != 0.toByte()) e++
            return String(d, o, e - o, Charsets.ISO_8859_1)
        }

        fun run(): Verdict {
            
            if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return Verdict.UNKNOWN
            if (d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) return Verdict.UNKNOWN
            val phoff = u32(0x1c)
            val phent = u16(0x2a)
            val phnum = u16(0x2c)
            var dynOff = -1
            var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                val type = u32(p)
                if (type == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (type == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            if (dynOff < 0) return Verdict.UNKNOWN
            val tags = HashMap<Int, Int>()
            var i = 0
            while (i + 8 <= dynSize) {
                val tag = u32(dynOff + i)
                if (tag == 0) break
                if (!tags.containsKey(tag)) tags[tag] = u32(dynOff + i + 4)
                i += 8
            }
            val symtab = fileOffset(tags[6] ?: return Verdict.UNKNOWN) ?: return Verdict.UNKNOWN
            val strtab = fileOffset(tags[5] ?: return Verdict.UNKNOWN) ?: return Verdict.UNKNOWN
            val nsym = (strtab - symtab) / 16 
            if (nsym !in 1..65536) return Verdict.UNKNOWN
            val names = Array(nsym) { s -> cString(strtab + u32(symtab + s * 16)) }
            val values = IntArray(nsym) { s -> u32(symtab + s * 16 + 4) }
            val defined = BooleanArray(nsym) { s -> u16(symtab + s * 16 + 14) != 0 }

            
            val hmiSym = (0 until nsym).firstOrNull { names[it] == "HMI" && defined[it] } ?: return Verdict.UNKNOWN
            val hmi = fileOffset(values[hmiSym]) ?: return Verdict.UNKNOWN
            if (u32(hmi) != 0x48574d54) return Verdict.UNKNOWN
            val unregister = u32(hmi + HMI_UNREGISTER)
            if (unregister == 0 || fileOffset(unregister and 1.inv()) == null) return Verdict.UNKNOWN

            
            val gotToName = HashMap<Int, String>()
            val jmprel = tags[23]?.let { fileOffset(it) }
            val relsz = tags[2] ?: 0
            if (jmprel != null) {
                var r = 0
                while (r + 8 <= relsz) {
                    val info = u32(jmprel + r + 4)
                    if ((info and 0xff) == 22 && (info ushr 8) in 0 until nsym) gotToName[u32(jmprel + r)] = names[info ushr 8]
                    r += 8
                }
            }

            val starts = java.util.TreeSet<Int>()
            for (off in intArrayOf(HMI_REGISTER, HMI_UNREGISTER, HMI_LOCK, HMI_UNLOCK)) {
                val fn = u32(hmi + off)
                if (fn != 0) starts.add(fn and 1.inv())
            }
            for (s in 0 until nsym) if (defined[s] && values[s] != 0) starts.add(values[s] and 1.inv())

            fun armImm(w: Int): Int = Integer.rotateRight(w and 0xff, ((w shr 8) and 0xf) * 2)
            
            fun pltName(target: Int): String? {
                val o = fileOffset(target) ?: return null
                if (o + 12 > d.size) return null
                val w0 = u32(o)
                val w1 = u32(o + 4)
                val w2 = u32(o + 8)
                if ((w0 and 0xfffff000.toInt()) != 0xe28fc000.toInt() || (w1 and 0xfffff000.toInt()) != 0xe28cc000.toInt() ||
                    (w2 and 0xfffff000.toInt()) != 0xe5bcf000.toInt()) return null
                return gotToName[target + 8 + armImm(w0) + armImm(w1) + (w2 and 0xfff)]
            }

            
            fun callsOf(entry: Int): List<Int> {
                val thumb = (entry and 1) != 0
                val a = entry and 1.inv()
                val o = fileOffset(a) ?: return emptyList()
                val end = minOf(starts.higher(a) ?: (a + MAX_FUNCTION), a + MAX_FUNCTION)
                val out = ArrayList<Int>()
                var p = 0
                if (thumb) {
                    while (a + p + 4 <= end && o + p + 4 <= d.size) {
                        val h1 = u16(o + p)
                        val h2 = u16(o + p + 2)
                        if ((h1 and 0xf800) == 0xf000 && (h2 and 0xc000) == 0xc000) {
                            val toThumb = (h2 and 0x1000) != 0
                            if (toThumb || (h2 and 1) == 0) {
                                val s = (h1 shr 10) and 1
                                val i1 = ((h2 shr 13) and 1 xor s) xor 1
                                val i2 = ((h2 shr 11) and 1 xor s) xor 1
                                var imm = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3ff) shl 12) or ((h2 and 0x7ff) shl 1)
                                if (s != 0) imm -= 1 shl 25
                                val base = if (toThumb) a + p + 4 else (a + p + 4) and 3.inv()
                                out.add((base + imm) or (if (toThumb) 1 else 0))
                                p += 4
                                continue
                            }
                        }
                        p += 2
                    }
                } else {
                    while (a + p + 4 <= end && o + p + 4 <= d.size) {
                        val w = u32(o + p)
                        var imm = w and 0xffffff
                        if (imm and 0x800000 != 0) imm -= 1 shl 24
                        if (((w shr 24) and 0xf) == 0xb && (w ushr 28) != 0xf) out.add(a + p + 8 + imm * 4)                     
                        else if ((w ushr 25) == 0x7d) out.add((a + p + 8 + imm * 4 + (if ((w shr 24) and 1 != 0) 2 else 0)) or 1) 
                        p += 4
                    }
                }
                return out
            }

            fun reachesGetpid(entry: Int, depth: Int, seen: MutableSet<Int>): Boolean {
                if (!seen.add(entry and 1.inv())) return false
                for (t in callsOf(entry)) {
                    val plt = pltName(t and 1.inv())
                    if (plt == "getpid") return true
                    if (plt == null && depth > 0 && fileOffset(t and 1.inv()) != null && reachesGetpid(t, depth - 1, seen)) return true
                }
                return false
            }
            return if (reachesGetpid(unregister, 2, HashSet())) Verdict.GUARDED else Verdict.UNGUARDED
        }
    }
}
