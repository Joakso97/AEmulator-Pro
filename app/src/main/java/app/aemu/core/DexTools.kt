package app.aemu.core

import java.security.MessageDigest
import java.util.zip.Adler32


object DexTools {
    

    internal fun u2(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
    internal fun u4(d: ByteArray, o: Int) = u2(d, o) or (u2(d, o + 2) shl 16)

    
    internal fun uleb(d: ByteArray, o: Int): Pair<Int, Int> {
        var r = 0; var shift = 0; var n = 0
        while (true) {
            val b = d[o + n].toInt() and 0xff; n++
            r = r or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IllegalArgumentException("bad uleb128")
        }
        return r to n
    }

    internal class Dex(val d: ByteArray) {
        val stringIds = u4(d, 56); val stringIdsOff = u4(d, 60)
        val typeIds = u4(d, 64); val typeIdsOff = u4(d, 68)
        val protoIdsOff = u4(d, 76)
        val methodIds = u4(d, 88); val methodIdsOff = u4(d, 92)
        val classDefs = u4(d, 96); val classDefsOff = u4(d, 100)

        fun string(idx: Int): String {
            var o = u4(d, stringIdsOff + 4 * idx)
            o += uleb(d, o).second
            var e = o
            while (d[e] != 0.toByte()) e++
            return String(d, o, e - o, Charsets.UTF_8)
        }

        fun type(idx: Int) = string(u4(d, typeIdsOff + 4 * idx))
        fun methodClass(m: Int) = type(u2(d, methodIdsOff + 8 * m))
        fun methodProto(m: Int) = u2(d, methodIdsOff + 8 * m + 2)
        fun methodName(m: Int) = string(u4(d, methodIdsOff + 8 * m + 4))
        fun protoShorty(p: Int) = string(u4(d, protoIdsOff + 12 * p))
        fun protoReturn(p: Int) = type(u4(d, protoIdsOff + 12 * p + 4))
    }

    
    internal fun methodCode(x: Dex, name: String, cls: String, voidOnly: Boolean = false, returns: String? = null): List<Int>? {
        val d = x.d
        for (c in 0 until x.classDefs) {
            val def = x.classDefsOff + 32 * c
            if (x.type(u4(d, def)) != cls) continue
            var o = u4(d, def + 24)                      
            if (o == 0) return emptyList()
            val sizes = IntArray(4)
            for (i in 0 until 4) { val (v, n) = uleb(d, o); sizes[i] = v; o += n }
            for (i in 0 until sizes[0] + sizes[1]) { o += uleb(d, o).second; o += uleb(d, o).second }   
            val out = ArrayList<Int>()
            var method = 0
            for (list in 2..3) {                         
                method = 0
                for (i in 0 until sizes[list]) {
                    val (diff, n1) = uleb(d, o); o += n1
                    val (_, n2) = uleb(d, o); o += n2
                    val (code, n3) = uleb(d, o); o += n3
                    method += diff
                    if (code != 0 && x.methodName(method) == name && (!voidOnly || x.protoReturn(x.methodProto(method)) == "V") &&
                        (returns == null || x.protoReturn(x.methodProto(method)) == returns)) out += code
                }
            }
            return out
        }
        return null
    }

    
    internal fun stringsStartingWith(x: Dex, prefix: String): Set<Int> {
        val out = HashSet<Int>()
        for (i in 0 until x.stringIds) if (x.string(i).startsWith(prefix)) out += i
        return out
    }

    
    internal fun insnLength(op: Int): Int = when (op) {
        in 0x01..0x0d -> when (op) { 0x02, 0x05, 0x08 -> 2; 0x03, 0x06, 0x09 -> 3; else -> 1 }
        0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29 -> 2
        0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c -> 3
        0x18 -> 5
        in 0x2d..0x3d, in 0x44..0x6d, in 0x90..0xaf, in 0xd0..0xe2, 0xfe, 0xff -> 2
        in 0x6e..0x72, in 0x74..0x78, 0xfc, 0xfd -> 3
        0xfa, 0xfb -> 4
        else -> 1
    }

    
    internal fun fixHeader(d: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-1").apply { update(d, 32, d.size - 32) }.digest()
        System.arraycopy(sha, 0, d, 12, 20)
        val ck = Adler32().apply { update(d, 12, d.size - 12) }.value.toInt()
        for (k in 0..3) d[8 + k] = (ck ushr (8 * k)).toByte()
    }
}
