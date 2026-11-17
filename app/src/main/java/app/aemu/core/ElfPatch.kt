package app.aemu.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder


object ElfPatch {
    private const val ARM_RET0 = 0xE3A00000.toInt()   
    private const val ARM_BX_LR = 0xE12FFF1E.toInt()  
    private const val THUMB_RET0 = 0x47702000         

    
    fun symbols(f: File, names: Set<String>, includeSymtab: Boolean = false): Map<String, Long>? =
        runCatching { f.readBytes() }.getOrNull()?.let { symbolsIn(it, names, includeSymtab) }

    
    fun symbolsIn(d: ByteArray, names: Set<String>, includeSymtab: Boolean = false): Map<String, Long>? =
        symbolsMatching(d, includeSymtab) { it in names }

    
    fun symbolsMatching(d: ByteArray, includeSymtab: Boolean = false, wanted: (String) -> Boolean): Map<String, Long>? =
        elfSymbols(d, 40, includeSymtab, wanted) 

    private const val EM_386 = 3
    private const val EM_ARM = 40

    
    private fun elfSymbols(d: ByteArray, machine: Int, includeSymtab: Boolean, wanted: (String) -> Boolean): Map<String, Long>? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != machine) return null
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return null
        val out = HashMap<String, Long>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            val shType = b.getInt(sh + 4)
            
            if (shType != 11 && !(includeSymtab && shType == 2)) continue
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s + 16 <= symOff + symSize && s + 16 <= d.size) {
                val nameOff = b.getInt(s); val value = b.getInt(s + 4).toLong() and 0xffffffffL
                val info = d[s + 12].toInt()
                if ((info and 0xf) == 2 && value != 0L) { 
                    var e = strOff + nameOff
                    while (e < d.size && d[e] != 0.toByte()) e++
                    val name = String(d, strOff + nameOff, e - strOff - nameOff, Charsets.US_ASCII)
                    if (wanted(name)) {
                        
                        val va = if (machine == EM_ARM) value and 0xfffffffeL else value
                        for (p in 0 until phnum) {
                            val ph = phoff + p * phentsize
                            if (b.getInt(ph) != 1) continue
                            val off = b.getInt(ph + 4).toLong() and 0xffffffffL
                            val vaddr = b.getInt(ph + 8).toLong() and 0xffffffffL
                            val filesz = b.getInt(ph + 16).toLong() and 0xffffffffL
                            if (va >= vaddr && va + 8 <= vaddr + filesz) {
                                out[name] = (off + va - vaddr) or (if (machine == EM_ARM) (value and 1L) shl 40 else 0L) 
                            }
                        }
                    }
                }
                s += 16
            }
        }
        return out
    }

    
    class DynSyms(val defined: Set<String>, val undefined: Set<String>)

    fun dynSyms64(f: File): DynSyms? {
        if (f.length() < 64 || f.length() > 64L shl 20) return null
        val d = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 2.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getLong(40); val shentsize = b.getShort(58).toInt() and 0xffff; val shnum = b.getShort(60).toInt() and 0xffff
        if (shoff <= 0 || shentsize < 64 || shoff + shnum.toLong() * shentsize > d.size) return null
        for (i in 0 until shnum) {
            val sh = (shoff + i.toLong() * shentsize).toInt()
            if (b.getInt(sh + 4) != 11) continue                                  
            val off = b.getLong(sh + 24); val size = b.getLong(sh + 32); val link = b.getInt(sh + 40)
            if (link <= 0 || link >= shnum || off < 0 || off + size > d.size) return null
            val strSh = (shoff + link.toLong() * shentsize).toInt()
            val strOff = b.getLong(strSh + 24); val strSize = b.getLong(strSh + 32)
            if (strOff < 0 || strOff + strSize > d.size) return null
            fun name(n: Int): String {
                var e = (strOff + n).toInt(); val s0 = e
                while (e < d.size && d[e] != 0.toByte()) e++
                return String(d, s0, e - s0, Charsets.US_ASCII)
            }
            val defined = HashSet<String>(); val undefined = HashSet<String>()
            var o = off + 24
            while (o + 24 <= off + size) {
                val nm = b.getInt(o.toInt()); val bind = (d[(o + 4).toInt()].toInt() and 0xff) shr 4; val shndx = b.getShort((o + 6).toInt()).toInt() and 0xffff
                if (nm != 0) {
                    if (shndx != 0) defined.add(name(nm)) else if (bind != 2) undefined.add(name(nm))
                }
                o += 24
            }
            return DynSyms(defined, undefined)
        }
        return null
    }

    
    fun needed64(f: File): List<String>? {
        if (f.length() < 64 || f.length() > 64L shl 20) return null
        val d = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 2.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = b.getLong(32); val phentsize = b.getShort(54).toInt() and 0xffff; val phnum = b.getShort(56).toInt() and 0xffff
        if (phoff <= 0 || phentsize < 56 || phoff + phnum.toLong() * phentsize > d.size) return null
        class Load(val off: Long, val va: Long, val size: Long)
        val loads = ArrayList<Load>()
        var dynOff = -1L; var dynSize = 0L
        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            val off = b.getLong(ph + 8); val va = b.getLong(ph + 16); val filesz = b.getLong(ph + 32)
            when (b.getInt(ph)) { 1 -> loads.add(Load(off, va, filesz)); 2 -> { dynOff = off; dynSize = filesz } }
        }
        if (dynOff < 0 || dynOff + dynSize > d.size) return null
        var strVa = -1L
        val names = ArrayList<Long>()
        var e = dynOff
        while (e + 16 <= dynOff + dynSize) {
            val tag = b.getLong(e.toInt()); val v = b.getLong(e.toInt() + 8)
            if (tag == 0L) break
            if (tag == 5L) strVa = v
            if (tag == 1L) names.add(v)
            e += 16
        }
        val strOff = loads.firstOrNull { strVa >= it.va && strVa < it.va + it.size }?.let { it.off + strVa - it.va } ?: return null
        return names.mapNotNull { n ->
            val start = strOff + n
            if (start < 0 || start >= d.size) return@mapNotNull null
            var end = start.toInt(); while (end < d.size && d[end] != 0.toByte()) end++
            String(d, start.toInt(), end - start.toInt(), Charsets.US_ASCII)
        }
    }

    
    fun needed32(f: File): List<String>? {
        if (f.length() < 52 || f.length() > 64L shl 20) return null
        val d = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (phoff <= 0 || phentsize < 32 || phoff.toLong() + phnum.toLong() * phentsize > d.size) return null
        class Load(val off: Long, val va: Long, val size: Long)
        val loads = ArrayList<Load>()
        var dynOff = -1L; var dynSize = 0L
        for (i in 0 until phnum) {
            val ph = phoff + i * phentsize
            val off = b.getInt(ph + 4).toLong() and 0xffffffffL
            val va = b.getInt(ph + 8).toLong() and 0xffffffffL
            val filesz = b.getInt(ph + 16).toLong() and 0xffffffffL
            when (b.getInt(ph)) { 1 -> loads.add(Load(off, va, filesz)); 2 -> { dynOff = off; dynSize = filesz } }
        }
        if (dynOff < 0 || dynOff + dynSize > d.size) return null
        var strVa = -1L
        val names = ArrayList<Long>()
        var e = dynOff
        while (e + 8 <= dynOff + dynSize) {
            val tag = b.getInt(e.toInt()); val v = b.getInt(e.toInt() + 4).toLong() and 0xffffffffL
            if (tag == 0) break
            if (tag == 5) strVa = v
            if (tag == 1) names.add(v)
            e += 8
        }
        val strOff = loads.firstOrNull { strVa >= it.va && strVa < it.va + it.size }?.let { it.off + strVa - it.va } ?: return null
        return names.mapNotNull { n ->
            val start = strOff + n
            if (start < 0 || start >= d.size) return@mapNotNull null
            var end = start.toInt(); while (end < d.size && d[end] != 0.toByte()) end++
            String(d, start.toInt(), end - start.toInt(), Charsets.US_ASCII)
        }
    }

    
    fun dynSyms32(f: File): DynSyms? {
        if (f.length() < 52 || f.length() > 64L shl 20) return null
        val d = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shentsize < 40 || shoff.toLong() + shnum.toLong() * shentsize > d.size) return null
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 11) continue                                  
            val off = b.getInt(sh + 16).toLong() and 0xffffffffL; val size = b.getInt(sh + 20).toLong() and 0xffffffffL; val link = b.getInt(sh + 24)
            if (link <= 0 || link >= shnum || off + size > d.size) return null
            val strSh = shoff + link * shentsize
            val strOff = b.getInt(strSh + 16).toLong() and 0xffffffffL; val strSize = b.getInt(strSh + 20).toLong() and 0xffffffffL
            if (strOff + strSize > d.size) return null
            fun name(n: Int): String {
                var e = (strOff + n).toInt(); val s0 = e
                while (e < d.size && d[e] != 0.toByte()) e++
                return String(d, s0, e - s0, Charsets.US_ASCII)
            }
            val defined = HashSet<String>(); val undefined = HashSet<String>()
            var o = off
            while (o + 16 <= off + size) {
                val nm = b.getInt(o.toInt()); val bind = (d[(o + 12).toInt()].toInt() and 0xff) shr 4; val shndx = b.getShort((o + 14).toInt()).toInt() and 0xffff
                if (nm != 0) {
                    if (shndx != 0) defined.add(name(nm)) else if (bind != 2) undefined.add(name(nm))
                }
                o += 16
            }
            return DynSyms(defined, undefined)
        }
        return null
    }

    
    fun redirectNeeded(f: File, from: String, to: String): Int {
        if (to.length > from.length || f.length() < 64 || f.length() > 64L shl 20) return 0
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return 0
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != 40) return 0
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (phoff <= 0 || phentsize < 32 || phoff + phnum * phentsize > d.size) return 0
        class Load(val off: Long, val va: Long, val size: Long)
        val loads = ArrayList<Load>()
        var dynOff = -1L; var dynSize = 0L
        for (i in 0 until phnum) {
            val ph = phoff + i * phentsize
            val off = b.getInt(ph + 4).toLong() and 0xffffffffL
            when (b.getInt(ph)) {
                1 -> loads.add(Load(off, b.getInt(ph + 8).toLong() and 0xffffffffL, b.getInt(ph + 16).toLong() and 0xffffffffL))
                2 -> { dynOff = off; dynSize = b.getInt(ph + 16).toLong() and 0xffffffffL }
            }
        }
        if (dynOff < 0 || dynOff + dynSize > d.size) return 0
        fun toFile(va: Long): Long? = loads.firstOrNull { va >= it.va && va < it.va + it.size }?.let { it.off + va - it.va }
        var strVa = -1L
        val needed = ArrayList<Long>()
        var e = dynOff
        while (e + 8 <= dynOff + dynSize) {
            val tag = b.getInt(e.toInt()); val v = b.getInt(e.toInt() + 4).toLong() and 0xffffffffL
            if (tag == 0) break
            if (tag == 5) strVa = v
            if (tag == 1) needed.add(v)
            e += 8
        }
        val strOff = toFile(strVa) ?: return 0
        val want = from.toByteArray(Charsets.US_ASCII)
        val hits = ArrayList<Long>()
        for (n in needed) {
            val p = strOff + n
            if (p < 0 || p + want.size + 1 > d.size) continue
            var same = d[(p + want.size).toInt()] == 0.toByte()
            for (k in want.indices) if (d[(p + k).toInt()] != want[k]) { same = false; break }
            if (same) hits.add(p)
        }
        if (hits.isEmpty()) return 0
        if (!f.canWrite()) f.setWritable(true, true)
        val repl = to.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        RandomAccessFile(f, "rw").use { raf -> for (p in hits) { raf.seek(p); raf.write(repl) } }
        return hits.size
    }

    
    fun disableInit(f: File): Int {
        if (f.length() < 64 || f.length() > 64L shl 20) return 0
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[5] != 1.toByte()) return 0
        val is64 = when (d[4].toInt()) { 2 -> true; 1 -> false; else -> return 0 }
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = if (is64) b.getLong(32) else b.getInt(28).toLong() and 0xffffffffL
        val phentsize = b.getShort(if (is64) 54 else 42).toInt() and 0xffff
        val phnum = b.getShort(if (is64) 56 else 44).toInt() and 0xffff
        if (phoff <= 0 || phentsize < (if (is64) 56 else 32) || phoff + phnum.toLong() * phentsize > d.size) return 0
        var dynOff = -1L; var dynSize = 0L
        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            if (b.getInt(ph) != 2) continue
            if (is64) { dynOff = b.getLong(ph + 8); dynSize = b.getLong(ph + 32) }
            else { dynOff = b.getInt(ph + 4).toLong() and 0xffffffffL; dynSize = b.getInt(ph + 16).toLong() and 0xffffffffL }
        }
        if (dynOff < 0 || dynOff + dynSize > d.size) return 0
        val esz = if (is64) 16 else 8
        var changed = 0
        var e = dynOff
        while (e + esz <= dynOff + dynSize) {
            val at = e.toInt()
            val tag = if (is64) b.getLong(at) else b.getInt(at).toLong()
            if (tag == 0L) break
            val valAt = at + esz / 2
            when (tag) {
                12L -> { if (is64) b.putLong(at, IGNORED_TAG) else b.putInt(at, IGNORED_TAG.toInt()); changed++ }                  
                27L -> {                                                                                                          
                    val v = if (is64) b.getLong(valAt) else b.getInt(valAt).toLong()
                    if (v != 0L) { if (is64) b.putLong(valAt, 0) else b.putInt(valAt, 0); changed++ }
                }
            }
            e += esz
        }
        if (changed == 0) return 0
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf -> raf.seek(dynOff); raf.write(d, dynOff.toInt(), dynSize.toInt()) }
        return 1
    }

    
    private const val IGNORED_TAG = 0x6000000fL

    
    fun fixPldHints(f: File): Int {
        if (f.length() < 64 || f.length() > 64L shl 20) return 0
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val fixes = pldHintOffsets(d, f.name)
        if (fixes.isEmpty()) return 0
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf ->
            for (o in fixes) {
                val w = b.getInt(o) or 0x0040f000
                raf.seek(o.toLong()); raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(w).array())
            }
        }
        return fixes.size
    }

    
    fun pldHintOffsets(d: ByteArray, name: String? = null): List<Int> {
        val modes = functionModes(d)
        
        
        val bionic = name == null || name == "libc.so" || name == "linker"
        if (d.size < 64 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != 40) return emptyList()
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (phoff <= 0 || phentsize < 32 || phoff + phnum * phentsize > d.size) return emptyList()
        val fixes = ArrayList<Int>()
        for (i in 0 until phnum) {
            val ph = phoff + i * phentsize
            if (b.getInt(ph) != 1 || (b.getInt(ph + 24) and 1) == 0) continue 
            val start = (b.getInt(ph + 4) + 3) and 3.inv()
            val segEnd = minOf(b.getInt(ph + 4) + b.getInt(ph + 16), d.size)
            if (start < 0 || segEnd - start < 4) continue
            val end = minOf(segEnd, d.size - 3)
            var o = start
            while (o < end) {
                val w = b.getInt(o)
                
                
                
                
                
                
                if (pldNeedsFix(w) && pldNear(b, o, start, segEnd)) {
                    val proven = notThumb(b, o, segEnd) || anchorNear(b, o, start, segEnd)
                    if (proven || armAround(b, o, start, segEnd)) {
                        val va = (b.getInt(ph + 8).toLong() and 0xffffffffL) + (o - b.getInt(ph + 4))
                        val arm = modes.armAt(va)
                        
                        if (arm == true || (arm == null && bionic) || (proven && bionic)) fixes.add(o)
                    }
                }
                o += 4
            }
        }
        return fixes
    }

    
    class FunctionModes(private val starts: LongArray, private val ends: LongArray, private val thumb: BooleanArray) {
        
        fun armAt(va: Long): Boolean? {
            var lo = 0; var hi = starts.size - 1; var best = -1
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (starts[mid] <= va) { best = mid; lo = mid + 1 } else hi = mid - 1 }
            
            var i = best
            var steps = 0
            while (i >= 0 && steps < 8) { if (va < ends[i]) return !thumb[i]; i--; steps++ }
            return null
        }
    }

    fun functionModes(d: ByteArray): FunctionModes {
        val s = ArrayList<LongArray>()
        if (d.size >= 52 && d[0] == 0x7f.toByte() && d[4] == 1.toByte()) {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
            if (shoff > 0 && shoff + shnum * shentsize <= d.size) for (i in 0 until shnum) {
                val sh = shoff + i * shentsize
                val t = b.getInt(sh + 4)
                if (t != 11 && t != 2) continue 
                val off = b.getInt(sh + 16); val size = b.getInt(sh + 20)
                var q = off
                while (q + 16 <= off + size && q + 16 <= d.size) {
                    val value = b.getInt(q + 4).toLong() and 0xffffffffL; val sz = b.getInt(q + 8).toLong() and 0xffffffffL
                    if ((d[q + 12].toInt() and 0xf) == 2 && value != 0L && sz > 0L)
                        s.add(longArrayOf(value and 1L.inv(), (value and 1L.inv()) + sz, value and 1L))
                    q += 16
                }
            }
        }
        s.sortBy { it[0] }
        return FunctionModes(LongArray(s.size) { s[it][0] }, LongArray(s.size) { s[it][1] }, BooleanArray(s.size) { s[it][2] == 1L })
    }

    
    private fun pldHint(w: Int) = (w and 0xff300000.toInt()) == 0xf5100000.toInt() && ((w ushr 16) and 0xf) != 15

    
    private fun pldNeedsFix(w: Int) = pldHint(w) && ((w and 0x00400000) == 0 || ((w ushr 12) and 0xf) != 0xf)

    
    private fun notThumb(b: ByteBuffer, o: Int, hi: Int): Boolean {
        val w = b.getInt(o)
        if (!pldHint(w) || (w and 0x00c00000) != 0x00800000) return false
        if ((w and 0xffff) >= 0xe800) return false
        if (o + 8 > hi) return false
        return (b.getInt(o + 4) and 0x8000) == 0
    }

    
    private fun anchorNear(b: ByteBuffer, o: Int, lo: Int, hi: Int): Boolean {
        for (k in -4..4) {
            if (k == 0) continue
            val q = o + k * 4
            if (q >= lo && q + 4 <= hi && notThumb(b, q, hi)) return true
        }
        return false
    }

    
    private fun pldNear(b: ByteBuffer, o: Int, lo: Int, hi: Int): Boolean {
        for (k in -4..4) {
            if (k == 0) continue
            val q = o + k * 4
            if (q >= lo && q + 4 <= hi && pldHint(b.getInt(q))) return true
        }
        return false
    }

    
    private fun armAround(b: ByteBuffer, o: Int, lo: Int, hi: Int): Boolean {
        for (step in intArrayOf(-4, 4)) {
            var q = o + step
            var seen = 0
            var always = 0
            var steps = 0
            while (q >= lo && q + 4 <= hi && seen < 6 && steps < 12) {
                val w = b.getInt(q)
                if (!pldHint(w)) {
                    seen++
                    if ((w ushr 28) == 0xe) always++
                }
                q += step
                steps++
            }
            if (seen >= 4 && always * 3 >= seen * 2) return true
        }
        return false
    }

    
    fun retZeroBytes(v: Long): ByteArray {
        val thumb = (v shr 40) and 1L == 1L
        val want = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (thumb) putInt(THUMB_RET0).putInt(THUMB_RET0) else putInt(ARM_RET0).putInt(ARM_BX_LR)
        }.array()
        return want.copyOf(if (thumb) 4 else 8)
    }

    
    val CACHEFLUSH_EXEC = intArrayOf(
        0xE92D4080.toInt(), 0xE3C000FF.toInt(), 0xE3C00C0F.toInt(), 0xE0411000.toInt(),
        0xE3A02007.toInt(), 0xE3A0707D.toInt(), 0xEF000000.toInt(), 0xE8BD8080.toInt()
    )
    private const val ARM_NR_CACHEFLUSH = 0x000F0002
    private const val ARM_SWI_0 = 0xEF000000.toInt()
    private const val ARM_NOP = 0xE1A00000.toInt()

    
    fun cacheflushExecBytes(d: ByteArray, off: Int): ByteArray? {
        if (off < 0 || off % 4 != 0 || off + 4 * 12 > d.size) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(off) == CACHEFLUSH_EXEC[0] && b.getInt(off + 4) == CACHEFLUSH_EXEC[1]) return null 
        var lit = -1
        for (i in 0 until 12) if (b.getInt(off + 4 * i) == ARM_NR_CACHEFLUSH) { lit = i; break }
        if (lit < CACHEFLUSH_EXEC.size - 1) return null
        var ldr = -1; var swi = -1
        for (i in 0 until lit) {
            val w = b.getInt(off + 4 * i)
            
            if ((w and 0xfffff000.toInt()) == 0xE59F7000.toInt() && 4 * i + 8 + (w and 0xfff) == 4 * lit) ldr = i
            if (w == ARM_SWI_0) swi = i
        }
        if (ldr < 0 || swi < ldr) return null
        val out = ByteBuffer.allocate(4 * (lit + 1)).order(ByteOrder.LITTLE_ENDIAN)
        for (w in CACHEFLUSH_EXEC) out.putInt(w)
        while (out.hasRemaining()) out.putInt(ARM_NOP)
        return out.array()
    }

    
    fun execCacheflush(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val sym = symbolsIn(d, setOf("cacheflush"))?.get("cacheflush") ?: return 0
        if ((sym shr 40) and 1L == 1L) return 0 
        val off = (sym and 0xffffffffffL).toInt()
        val code = cacheflushExecBytes(d, off) ?: return 0
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf -> raf.seek(off.toLong()); raf.write(code) }
        return 1
    }

    
    fun revertHwcEventControlPatch(f: File, stock: File): Int {
        val name = "_ZN7android10HWComposer12eventControlEiii"
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val s = runCatching { stock.readBytes() }.getOrNull() ?: return 0
        if (s.size != d.size) return 0
        val v = symbolsIn(d, setOf(name))?.get(name) ?: return 0
        if ((v shr 40) and 1L != 1L) return 0                                   
        val off = (v and 0xffffffffffL).toInt() and 1.inv()
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        var i = off
        while (i + 8 <= minOf(off + 96, d.size)) {
            
            if (u16(d, i) == 0xfa30 && u16(d, i + 2) == 0xf201 && u16(d, i + 4) == 0x421a && u16(d, i + 6) == 0xbf00) {
                if ((i until i + 6).any { d[it] != s[it] } || (u16(s, i + 6) and 0xff00) != 0xd000) return 0   
                if (!f.canWrite()) f.setWritable(true, true)
                RandomAccessFile(f, "rw").use { raf -> raf.seek((i + 6).toLong()); raf.write(s, i + 6, 2) }
                return 1
            }
            i += 2
        }
        return 0
    }

    
    fun returnZero(f: File, names: Set<String>, includeSymtab: Boolean = false): Int =
        returnZeroMatching(f, includeSymtab) { it in names }

    
    fun returnZeroMatching(f: File, includeSymtab: Boolean = false, wanted: (String) -> Boolean): Int =
        returnValueMatching(f, 0, includeSymtab, wanted)

    
    fun returnValue(f: File, names: Set<String>, value: Int, includeSymtab: Boolean = false): Int =
        returnValueMatching(f, value, includeSymtab) { it in names }

    private fun returnValueMatching(f: File, value: Int, includeSymtab: Boolean, wanted: (String) -> Boolean): Int {
        require(value in 0..255)
        val syms = runCatching { f.readBytes() }.getOrNull()?.let { symbolsMatching(it, includeSymtab, wanted) } ?: return 0
        var n = 0
        RandomAccessFile(f, "rw").use { raf ->
            for ((_, v) in syms) {
                val thumb = (v shr 40) and 1L == 1L
                val off = v and 0xffffffffffL
                val thumbCode = THUMB_RET0 or value                       
                val want = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    if (thumb) putInt(thumbCode).putInt(thumbCode) else putInt(ARM_RET0 or value).putInt(ARM_BX_LR)
                }.array()
                val have = ByteArray(if (thumb) 4 else 8)
                raf.seek(off); raf.readFully(have)
                if (have.contentEquals(want.copyOf(have.size))) continue
                raf.seek(off); raf.write(want, 0, have.size)
                n++
            }
        }
        return n
    }

    
    fun returnZeroAnyArch(f: File, names: Set<String>, includeSymtab: Boolean = false): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        if (d.size < 52) return 0
        val machine = (d[18].toInt() and 0xff) or ((d[19].toInt() and 0xff) shl 8)
        if (machine == EM_ARM) return returnZero(f, names, includeSymtab)
        if (machine != EM_386) return 0
        val syms = elfSymbols(d, EM_386, includeSymtab) { it in names } ?: return 0
        val want = byteArrayOf(0x31, 0xC0.toByte(), 0xC3.toByte())   
        var n = 0
        RandomAccessFile(f, "rw").use { raf ->
            for ((_, v) in syms) {
                val off = v and 0xffffffffffL
                val have = ByteArray(want.size)
                raf.seek(off); raf.readFully(have)
                if (have.contentEquals(want)) continue
                raf.seek(off); raf.write(want)
                n++
            }
        }
        return n
    }

    
    private val THUMB_MOV_R0_R5_POP = byteArrayOf(0x28, 0x46, 0x7c, 0xbd.toByte())
    private val THUMB_MOVS_R0_0 = byteArrayOf(0x00, 0x20)

    
    fun unlockAndPostNeverFails(f: File): Int {
        val name = "_ZN7android20SurfaceTextureClient13unlockAndPostEv"
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val sym = symbolsIn(d, setOf(name))?.get(name) ?: return 0
        if ((sym shr 40) and 1L != 1L) return 0 
        val start = (sym and 0xffffffffffL).toInt()
        val size = symbolSize(d, name) ?: return 0
        if (size < 16 || start + size > d.size) return 0
        var hit = -1
        var i = start
        while (i + THUMB_MOV_R0_R5_POP.size <= start + size) {
            if (THUMB_MOV_R0_R5_POP.indices.all { d[i + it] == THUMB_MOV_R0_R5_POP[it] }) {
                if (hit >= 0) return 0 
                hit = i
            }
            i += 2
        }
        if (hit < 0) return 0
        RandomAccessFile(f, "rw").use { raf -> raf.seek(hit.toLong()); raf.write(THUMB_MOVS_R0_0) }
        return 1
    }

    private const val ARM_CMP_R0_0 = 0xE3500000.toInt() 
    private const val POLICY_CTOR_C1 = "_ZN7android18AudioPolicyServiceC1Ev"
    private const val POLICY_CTOR_C2 = "_ZN7android18AudioPolicyServiceC2Ev"
    private const val POLICY_FACTORY = "createAudioPolicyManager"

    
    fun policyBranchIn(words: IntArray, base: Long, isFactory: (Long) -> Boolean): Int? {
        fun target(i: Int): Long {
            var off = words[i] and 0x00ffffff
            if (off and 0x00800000 != 0) off = off or 0xff000000.toInt()
            return base + 4L * i + 8 + off.toLong() * 4
        }
        var found: Int? = null
        for (i in words.indices) {
            if (words[i] ushr 24 != 0xEB) continue 
            if (!isFactory(target(i) and 0xffffffffL)) continue
            val callAt = base + 4L * i
            for (j in 1 until i) {
                if (words[j] ushr 24 != 0x0A) continue 
                if (words[j - 1] != ARM_CMP_R0_0) continue
                val to = target(j)
                if (to <= base + 4L * j || to < callAt - 8 || to > callAt) continue
                if (found != null && found != j) return null 
                found = j
            }
        }
        return found
    }

    
    private fun pltStubs(d: ByteArray): Map<Long, String> {
        val out = HashMap<Long, String>()
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) return out
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return out
        fun rot(x: Int): Long {
            val imm = x and 0xff; val r = ((x shr 8) and 0xf) * 2
            return (Integer.rotateRight(imm, r).toLong()) and 0xffffffffL
        }
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 9) continue 
            val plt = b.getInt(sh + 28) 
            if (plt <= 0 || plt >= shnum) continue
            val ps = shoff + plt * shentsize
            val pType = b.getInt(ps + 4); val pFlags = b.getInt(ps + 8)
            if (pType != 1 || pFlags and 4 == 0) continue 
            val pAddr = b.getInt(ps + 12).toLong() and 0xffffffffL; val pOff = b.getInt(ps + 16); val pSize = b.getInt(ps + 20)
            val dyn = shoff + b.getInt(sh + 24) * shentsize
            val symOff = b.getInt(dyn + 16)
            val strOff = b.getInt(shoff + b.getInt(dyn + 24) * shentsize + 16)
            val got = HashMap<Long, String>()
            val relOff = b.getInt(sh + 16); val relSize = b.getInt(sh + 20)
            var r = relOff
            while (r + 8 <= relOff + relSize && r + 8 <= d.size) {
                val info = b.getInt(r + 4)
                if ((info and 0xff) == 22) { 
                    val nameOff = strOff + b.getInt(symOff + (info ushr 8) * 16)
                    var e = nameOff
                    while (e < d.size && d[e] != 0.toByte()) e++
                    got[b.getInt(r).toLong() and 0xffffffffL] = String(d, nameOff, e - nameOff, Charsets.US_ASCII)
                }
                r += 8
            }
            var p = 0
            while (p + 12 <= pSize && pOff + p + 12 <= d.size) {
                val w0 = b.getInt(pOff + p); val w1 = b.getInt(pOff + p + 4); val w2 = b.getInt(pOff + p + 8)
                
                if ((w0 and 0xfffff000.toInt()) == 0xE28FC000.toInt() && (w1 and 0xfffff000.toInt()) == 0xE28CC000.toInt() &&
                    (w2 and 0xfffff000.toInt()) == 0xE5BCF000.toInt()) {
                    val slot = (pAddr + p + 8 + rot(w0 and 0xfff) + rot(w1 and 0xfff) + (w2 and 0xfff)) and 0xffffffffL
                    got[slot]?.let { out[pAddr + p] = it }
                }
                p += 4
            }
        }
        return out
    }

    
    fun policyBranchOffsets(d: ByteArray): List<Int> {
        val names = setOf(POLICY_CTOR_C1, POLICY_CTOR_C2)
        val syms = symbolsIn(d, names) ?: return emptyList()
        val stubs = pltStubs(d)
        if (stubs.values.none { it == POLICY_FACTORY }) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        val hits = HashSet<Int>()
        for ((name, v) in syms) {
            if ((v shr 40) and 1L == 1L) continue 
            val start = (v and 0xffffffffffL).toInt()
            val size = symbolSize(d, name) ?: continue
            if (size < 16 || start % 4 != 0 || start + size > d.size) continue
            var base = -1L
            for (p in 0 until phnum) {
                val ph = phoff + p * phentsize
                if (b.getInt(ph) != 1) continue
                val off = b.getInt(ph + 4).toLong() and 0xffffffffL
                val vaddr = b.getInt(ph + 8).toLong() and 0xffffffffL
                val filesz = b.getInt(ph + 16).toLong() and 0xffffffffL
                if (start >= off && start < off + filesz) base = vaddr + start - off
            }
            if (base < 0) continue
            val words = IntArray(size / 4) { b.getInt(start + it * 4) }
            val at = policyBranchIn(words, base) { t -> stubs[t] == POLICY_FACTORY } ?: continue
            hits.add(start + at * 4)
        }
        return hits.sorted()
    }

    
    fun policyManagerAlways(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val at = policyBranchOffsets(d)
        if (at.isEmpty()) return 0
        RandomAccessFile(f, "rw").use { raf ->
            for (o in at) { raf.seek(o + 3L); raf.write(0xEA) } 
        }
        return at.size
    }

    
    private fun symbolSize(d: ByteArray, name: String): Int? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return null
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 11) continue
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s + 16 <= symOff + symSize && s + 16 <= d.size) {
                val nameOff = b.getInt(s)
                var e = strOff + nameOff
                while (e < d.size && d[e] != 0.toByte()) e++
                if (String(d, strOff + nameOff, e - strOff - nameOff, Charsets.US_ASCII) == name) return b.getInt(s + 8)
                s += 16
            }
        }
        return null
    }

    
    private val SF_HOOK_CONTEXT = byteArrayOf(
        0x88.toByte(), 0x47, 0xa8.toByte(), 0x68, 0x83.toByte(), 0x6e, 0x13, 0xb1.toByte(),
        0x31, 0x46, 0x22, 0x46, 0x98.toByte(), 0x47,
    )
    private const val SF_HOOK_LOAD_AT = 4 
    private val THUMB_MOVS_R3_0 = byteArrayOf(0x00, 0x23)

    
    fun skipNullDeviceHookOffsets(d: ByteArray): List<Int> {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return emptyList()
        val hits = ArrayList<Int>()
        var i = 0
        val last = d.size - SF_HOOK_CONTEXT.size
        while (i <= last) {
            var ok = true
            for (k in SF_HOOK_CONTEXT.indices) if (d[i + k] != SF_HOOK_CONTEXT[k]) { ok = false; break }
            if (ok) hits.add(i + SF_HOOK_LOAD_AT)
            i += 2
        }
        return if (hits.size == 1) hits else emptyList()
    }

    
    fun skipNullDeviceHook(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val at = skipNullDeviceHookOffsets(d).firstOrNull() ?: return 0
        RandomAccessFile(f, "rw").use { raf -> raf.seek(at.toLong()); raf.write(THUMB_MOVS_R3_0) }
        return 1
    }

    private val THUMB_NOP2 = byteArrayOf(0x00, 0xbf.toByte(), 0x00, 0xbf.toByte()) 

    
    fun skipUndrawnLayerClearOffset(d: ByteArray): Int? {
        val onDraw = "_ZNK7android5Layer6onDrawERKNS_6RegionE"
        val clear = "_ZNK7android9LayerBase15clearWithOpenGLERKNS_6RegionE"
        val syms = symbolsIn(d, setOf(onDraw, clear)) ?: return null
        val a = syms[onDraw] ?: return null
        val c = syms[clear] ?: return null
        if ((a shr 40) and 1L != 1L || (c shr 40) and 1L != 1L) return null 
        val start = (a and 0xffffffffffL).toInt()
        val target = (c and 0xffffffffffL).toInt()
        val size = symbolSize(d, onDraw) ?: return null
        if (size < 16 || start + size > d.size) return null
        fun u16(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        var hit = -1
        var i = start
        while (i + 4 <= start + size) {
            val h1 = u16(i); val h2 = u16(i + 2)
            if ((h1 and 0xf800) == 0xf000 && (h2 and 0xd000) == 0xd000) { 
                val s = (h1 shr 10) and 1
                val i1 = ((h2 shr 13) and 1 xor s) xor 1
                val i2 = ((h2 shr 11) and 1 xor s) xor 1
                var off = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3ff) shl 12) or ((h2 and 0x7ff) shl 1)
                if (s == 1) off = off or (-1 shl 25)
                if (i + 4 + off == target) { if (hit >= 0) return null; hit = i } 
                i += 4
            } else i += 2
        }
        return if (hit >= 0) hit else null
    }

    
    fun skipUndrawnLayerClear(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val at = skipUndrawnLayerClearOffset(d) ?: return 0
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf -> raf.seek(at.toLong()); raf.write(THUMB_NOP2) }
        return 1
    }

    
    fun dropBtiIfBroken(f: File, force: Boolean = false, dryRun: Boolean = false): Int {
        val len = f.length()
        if (len < 64 || len > 256L shl 20) return 0
        var bitPos = -1L
        try {
            RandomAccessFile(f, "r").use { raf ->
                val h = ByteArray(64); raf.readFully(h)
                if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[4] != 2.toByte() || h[5] != 1.toByte()) return 0
                val hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                if ((hb.getShort(18).toInt() and 0xffff) != 183) return 0            
                val phoff = hb.getLong(32); val phentsize = hb.getShort(54).toInt() and 0xffff; val phnum = hb.getShort(56).toInt() and 0xffff
                if (phoff <= 0 || phentsize < 56 || phnum > 128 || phoff + phnum.toLong() * phentsize > len) return 0
                val ph = ByteArray(phnum * phentsize); raf.seek(phoff); raf.readFully(ph)
                val pb = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                var noteOff = -1L; var noteSize = 0L
                for (i in 0 until phnum) if (pb.getInt(i * phentsize) == 0x6474e553) { noteOff = pb.getLong(i * phentsize + 8); noteSize = pb.getLong(i * phentsize + 32) }
                if (noteOff <= 0 || noteSize < 16 || noteSize > 4096 || noteOff + noteSize > len) return 0
                val nt = ByteArray(noteSize.toInt()); raf.seek(noteOff); raf.readFully(nt)
                val nb = ByteBuffer.wrap(nt).order(ByteOrder.LITTLE_ENDIAN)
                var o = 0; var found = -1L
                while (found < 0 && o + 12 <= nt.size) {
                    val namesz = nb.getInt(o); val descsz = nb.getInt(o + 4); val type = nb.getInt(o + 8)
                    if (namesz < 0 || descsz < 0) break
                    val desc = o + 12 + ((namesz + 3) and 3.inv())
                    if (type == 5 && namesz == 4 && nt[o + 12] == 'G'.code.toByte() && nt[o + 13] == 'N'.code.toByte()) { 
                        var q = desc
                        while (q + 8 <= desc + descsz && q + 8 <= nt.size) {
                            val prType = nb.getInt(q); val prSize = nb.getInt(q + 4)
                            if (prSize < 0) break
                            if (prType == 0xc0000000.toInt() && prSize >= 4 && q + 12 <= nt.size) {   
                                if ((nb.getInt(q + 8) and 1) != 0) found = noteOff + q + 8     
                                break
                            }
                            q += 8 + ((prSize + 7) and 7.inv())
                        }
                    }
                    o = desc + ((descsz + 7) and 7.inv())
                }
                if (found < 0) return 0
                bitPos = found
            }
        } catch (_: Exception) { return 0 }
        if (bitPos < 0) return 0
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = b.getLong(32); val phentsize = b.getShort(54).toInt() and 0xffff; val phnum = b.getShort(56).toInt() and 0xffff
        val shoff = b.getLong(40); val shentsize = b.getShort(58).toInt() and 0xffff; val shnum = b.getShort(60).toInt() and 0xffff
        val haveSections = shoff > 0 && shentsize >= 64 && shoff + shnum.toLong() * shentsize <= d.size
        if (!haveSections && !force) return 0
        class Load(val off: Long, val va: Long, val size: Long)
        val loads = (0 until phnum).map { phoff.toInt() + it * phentsize }.filter { b.getInt(it) == 1 }
            .map { Load(b.getLong(it + 8), b.getLong(it + 16), b.getLong(it + 32)) }
        fun fileOff(v: Long): Int? = loads.firstOrNull { v >= it.va && v < it.va + it.size }?.let { (v - it.va + it.off).toInt() }
        var broken = false
        for (i in 0 until (if (haveSections) shnum else 0)) {
            val sh = (shoff + i.toLong() * shentsize).toInt()
            if (b.getInt(sh + 4) != 11) continue                                       
            val off = b.getLong(sh + 24); val size = b.getLong(sh + 32)
            if (off < 0 || off + size > d.size) return 0
            var o = off + 24
            while (o + 24 <= off + size) {
                val info = d[(o + 4).toInt()].toInt() and 0xf; val shndx = b.getShort((o + 6).toInt()).toInt() and 0xffff
                val value = b.getLong((o + 8).toInt())
                if (info == 2 && shndx != 0 && value != 0L) {                          
                    val fo = fileOff(value)
                    if (fo != null && fo + 4 <= d.size) {
                        val ins = b.getInt(fo)
                        
                        val pad = (ins and 0xffffff3f.toInt()) == 0xd503241f.toInt() || ins == 0xd503233f.toInt() || ins == 0xd503237f.toInt()
                        if (!pad) { broken = true; break }
                    }
                }
                o += 24
            }
            break
        }
        if (!broken && !force) return 0
        if (dryRun) return 1                                                       
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf ->
            val cur = ByteArray(1); raf.seek(bitPos); raf.readFully(cur)
            cur[0] = (cur[0].toInt() and 1.inv()).toByte()
            raf.seek(bitPos); raf.write(cur)
        }
        return 1
    }
}
