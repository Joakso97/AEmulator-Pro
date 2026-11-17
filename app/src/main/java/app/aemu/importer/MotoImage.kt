
package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder


class SliceSource(private val inner: RandomSource, private val base: Long, override val size: Long) : RandomSource {
    override fun read(pos: Long, dst: ByteBuffer) {
        if (pos >= size) { while (dst.hasRemaining()) dst.put(0); return }
        
        val inside = minOf(dst.remaining().toLong(), size - pos).toInt()
        val lim = dst.limit()
        dst.limit(dst.position() + inside)
        inner.read(base + pos, dst)
        dst.limit(lim)
        while (dst.hasRemaining()) dst.put(0)
    }
}


object MotoImage {
    private val MAGIC = "MOTO".toByteArray(Charsets.ISO_8859_1)
    
    private val PIV = "MOT_PIV".toByteArray(Charsets.ISO_8859_1)

    
    private const val SEARCH_LIMIT = 1L shl 20
    private const val STEP = 4096L

    private fun read(src: RandomSource, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        src.read(pos, b); b.flip()
        return b
    }

    
    fun probe(src: RandomSource): Boolean = runCatching {
        if (src.size < 4096) return false
        val h = read(src, 0, 16)
        MAGIC.indices.all { h.get(it) == MAGIC[it] } && PIV.indices.all { h.get(8 + it) == PIV[it] }
    }.getOrDefault(false)

    
    private fun ext4SizeAt(src: RandomSource, off: Long): Long? {
        if (off + 2048 > src.size) return null
        val sb = read(src, off + 1024, 1024)
        if (sb.getShort(0x38).toInt() and 0xffff != 0xEF53) return null
        val logBs = sb.getInt(0x18)
        if (logBs !in 0..6) return null
        val bs = 1024L shl logBs
        val incompat = sb.getInt(0x60)
        val lo = sb.getInt(0x4).toLong() and 0xffffffffL
        val hi = if (incompat and 0x80 != 0) sb.getInt(0x150).toLong() and 0xffffffffL else 0L
        val blocks = lo or (hi shl 32)
        if (blocks == 0L) return null
        return blocks * bs
    }

    
    private fun sparseAt(src: RandomSource, off: Long): Boolean {
        if (off + 28 > src.size) return false
        val m = read(src, off, 4)
        if (m.getInt(0) != Simg2Img.MAGIC) return false
        return runCatching { Simg2Img.parse(SliceSource(src, off, src.size - off)); true }.getOrDefault(false)
    }

    
    fun unwrap(src: RandomSource): RandomSource {
        if (!probe(src) || Ext4Reader.probe(src)) return src
        var off = STEP
        val limit = minOf(SEARCH_LIMIT, src.size)
        while (off <= limit) {
            val fsSize = runCatching { ext4SizeAt(src, off) }.getOrNull()
            if (fsSize != null) {
                
                return SliceSource(src, off, minOf(fsSize, src.size - off))
            }
            
            if (runCatching { sparseAt(src, off) }.getOrDefault(false)) {
                return SparseSource(listOf(SliceSource(src, off, src.size - off)))
            }
            off += STEP
        }
        return src
    }
}
