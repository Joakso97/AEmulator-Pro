
package app.aemu.core


internal object LauncherProximityPatch {
    const val PATCHED = 1
    const val ALREADY = 0
    
    const val ABSENT = -1
    
    const val UNEXPECTED = -2

    private const val MARKER = "Create sensors_proximity"
    private const val LIST = "Ljava/util/List;"
    private const val REGION = 15 
    private const val SPAN = 17   

    fun apply(d: ByteArray): Int {
        val r = DexStringPatch.region(d) ?: return ABSENT
        val t = Tables.parse(d, r.first, r.second) ?: return ABSENT
        val marker = t.stringIndex(MARKER)
        if (marker < 0) return ABSENT
        val get = t.methodIndex(LIST, "get")
        val isEmpty = t.methodIndex(LIST, "isEmpty")
        if (marker > 0xffff || get < 0 || isEmpty < 0) return UNEXPECTED
        val units = (r.second - r.first) / 2
        val w: (Int) -> Int = { i -> (d[r.first + 2 * i].toInt() and 0xff) or ((d[r.first + 2 * i + 1].toInt() and 0xff) shl 8) }
        for (s in 0..units - SPAN) {
            if (w(s) == 0x1072 && w(s + 1) == isEmpty && patchedAt(w, s, get)) return ALREADY
            if (w(s + 11) != marker || !originalAt(w, s, get, units)) continue
            val valid = DexStringPatch.adler(d, r) == u32(d, r.first + 8)
            val list = w(s + 2) and 0xf
            val x = w(s + 3) ushr 8
            val old = IntArray(8) { w(s + it) } 
            fun put(i: Int, v: Int) { d[r.first + 2 * (s + i)] = v.toByte(); d[r.first + 2 * (s + i) + 1] = (v ushr 8).toByte() }
            put(0, 0x1072); put(1, isEmpty); put(2, list)           
            put(3, (x shl 8) or 0x0a)                                
            put(4, (x shl 8) or 0x39); put(5, REGION - 4)            
            put(6, old[0]); put(7, old[1]); put(8, old[2])           
            put(9, old[3]); put(10, old[4]); put(11, old[5])         
            put(12, old[6]); put(13, old[7])                         
            put(14, 0)                                               
            if (valid) {
                val ck = DexStringPatch.adler(d, r)
                for (k in 0..3) d[r.first + 8 + k] = (ck ushr (8 * k)).toByte()
            }
            return PATCHED
        }
        return UNEXPECTED
    }

    
    private fun originalAt(w: (Int) -> Int, s: Int, get: Int, units: Int): Boolean {
        val x = w(s + 3) ushr 8
        val target = s + REGION + w(s + REGION + 1).toShort() 
        val iput = w(s + 6)
        return w(s) == 0x2072 && w(s + 1) == get &&
            (w(s + 3) and 0xff) == 0x0c &&
            (w(s + 4) and 0xff) == 0x1f && (w(s + 4) ushr 8) == x &&
            x < 16 && ((iput and 0xff) == 0xf7 || (iput and 0xff) == 0x5b) && ((iput ushr 8) and 0xf) == x &&
            (w(s + 8) and 0xff) == 0x1a && (w(s + 10) and 0xff) == 0x1a &&
            w(s + 12) == 0x2071 && w(s + REGION) == 0x0029 &&
            target in 0 until units && (w(target) and 0xff).let { it == 0x0e || it == 0xf1 } 
    }

    
    private fun patchedAt(w: (Int) -> Int, s: Int, get: Int): Boolean {
        val x = w(s + 3) ushr 8
        return (w(s + 3) and 0xff) == 0x0a && w(s + 4) == ((x shl 8) or 0x39) && w(s + 5) == REGION - 4 &&
            w(s + 6) == 0x2072 && w(s + 7) == get && (w(s + 9) and 0xff) == 0x0c && (w(s + 9) ushr 8) == x &&
            (w(s + 10) and 0xff) == 0x1f && w(s + REGION) == 0x0029 && w(s + 14) == 0
    }

    private fun u32(d: ByteArray, o: Int) =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

    
    private class Tables(val d: ByteArray, val base: Int, val strings: Int, val stringsOff: Int, val types: Int, val typesOff: Int,
                         val methods: Int, val methodsOff: Int) {
        companion object {
            fun parse(d: ByteArray, base: Int, end: Int): Tables? {
                if (end - base < 112) return null
                fun at(o: Int) = u32(d, base + o)
                val t = Tables(d, base, at(0x38), at(0x3c), at(0x40), at(0x44), at(0x58), at(0x5c))
                val len = end - base
                fun ok(n: Int, off: Int, size: Int) = n in 0..len && off in 0..len && off.toLong() + n.toLong() * size <= len
                return if (ok(t.strings, t.stringsOff, 4) && ok(t.types, t.typesOff, 4) && ok(t.methods, t.methodsOff, 8)) t else null
            }
        }

        private fun stringEquals(i: Int, s: String): Boolean {
            var p = base + u32(d, base + stringsOff + 4 * i)
            if (p < base || p >= d.size) return false
            while (p < d.size && d[p].toInt() and 0x80 != 0) p++ 
            p++
            if (p + s.length >= d.size) return false
            for (k in s.indices) if (d[p + k].toInt() != s[k].toInt()) return false
            return d[p + s.length].toInt() == 0
        }

        fun stringIndex(s: String): Int {
            for (i in 0 until strings) if (stringEquals(i, s)) return i
            return -1
        }

        
        fun methodIndex(cls: String, name: String): Int {
            val descriptor = stringIndex(cls); val nameIdx = stringIndex(name)
            if (descriptor < 0 || nameIdx < 0) return -1
            var type = -1
            for (i in 0 until types) if (u32(d, base + typesOff + 4 * i) == descriptor) { type = i; break }
            if (type < 0) return -1
            var found = -1
            for (i in 0 until methods) {
                val e = base + methodsOff + 8 * i
                val cl = (d[e].toInt() and 0xff) or ((d[e + 1].toInt() and 0xff) shl 8)
                if (cl == type && u32(d, e + 4) == nameIdx) { if (found >= 0) return -1; found = i }
            }
            return found
        }
    }
}
