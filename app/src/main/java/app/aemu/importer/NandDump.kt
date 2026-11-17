
package app.aemu.importer

import java.nio.ByteBuffer


internal object NandDump {
    
    const val MAX_BOOT_BYTES = 16L shl 20

    private val NAME = Regex("(?i)mtd(block)?\\d+(\\.(img|bin|dump))?")

    
    private val GEOMETRIES = listOf(2048 to 64, 4096 to 128, 512 to 16)

    fun isMtdName(base: String) = NAME.matches(base)

    
    fun isBlank(src: RandomSource): Boolean {
        val buf = ByteBuffer.allocate(1 shl 20)
        var pos = 0L
        while (pos < src.size) {
            val n = minOf(buf.capacity().toLong(), src.size - pos).toInt()
            buf.clear(); buf.limit(n)
            src.read(pos, buf)
            val a = buf.array()
            for (i in 0 until n) if (a[i] != 0xff.toByte()) return false
            pos += n
        }
        return true
    }

    
    fun spareGeometry(raw: ByteArray): Pair<Int, Int>? {
        for ((page, spare) in GEOMETRIES) {
            val stride = page + spare
            if (raw.size % stride != 0 || raw.size < stride * 4) continue
            val pages = raw.size / stride
            val sample = minOf(pages, 256)
            var marked = 0
            for (p in 0 until sample) {
                val o = p * stride + page
                if (raw[o] == 0xff.toByte() && raw[o + 1] == 0xff.toByte()) marked++
            }
            if (marked * 10 >= sample * 9) return page to spare
        }
        return null
    }

    
    fun strip(raw: ByteArray, page: Int, spare: Int): ByteArray {
        val stride = page + spare
        val pages = raw.size / stride
        val out = ByteArray(pages * page)
        for (p in 0 until pages) System.arraycopy(raw, p * stride, out, p * page, page)
        return out
    }

    
    fun candidates(raw: ByteArray): List<ByteArray> {
        val g = spareGeometry(raw) ?: return listOf(raw)
        return listOf(strip(raw, g.first, g.second), raw)
    }
}
