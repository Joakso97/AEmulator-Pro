package app.aemu.importer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption


object Simg2Img {
    const val MAGIC = 0xED26FF3A.toInt()
    const val CHUNK_RAW = 0xCAC1
    const val CHUNK_FILL = 0xCAC2
    const val CHUNK_DONT_CARE = 0xCAC3
    const val CHUNK_CRC32 = 0xCAC4

    private const val SPARSE_HEADER_MAJOR_VER = 1
    private const val SPARSE_HEADER_LEN = 28
    private const val CHUNK_HEADER_LEN = 12
    private const val COPY_BUF_SIZE = 1 shl 20

    
    class Seg(val startBlock: Long, val blocks: Long, val type: Int, val srcOff: Long, val fill: Int)

    class Layout(val blockSize: Int, val totalBlocks: Long, val segs: List<Seg>) {
        
        val size: Long get() = totalBlocks * blockSize
    }

    class SparseFormatException(msg: String) : IOException(msg)

    
    fun probe(src: RandomSource): Boolean = runCatching {
        if (src.size < 4) return false
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        src.read(0, b); b.flip()
        b.getInt(0) == MAGIC
    }.getOrDefault(false)

    private fun u32(b: ByteBuffer, o: Int) = b.getInt(o).toLong() and 0xffffffffL
    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xffff

    
    private fun readAt(src: RandomSource, off: Long, len: Int): ByteBuffer {
        if (off < 0 || off + len > src.size) throw SparseFormatException("EOF while reading file at $off")
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        src.read(off, b); b.flip()
        return b
    }

    
    fun parse(src: RandomSource): Layout {
        val h = readAt(src, 0, SPARSE_HEADER_LEN)
        if (h.getInt(0) != MAGIC) throw SparseFormatException("Invalid sparse file format (header magic)")
        if (u16(h, 4) != SPARSE_HEADER_MAJOR_VER) throw SparseFormatException("Invalid sparse file format (header major version)")
        val fileHdr = u16(h, 8)
        val chunkHdr = u16(h, 10)
        if (fileHdr < SPARSE_HEADER_LEN) throw SparseFormatException("Invalid sparse file format (file header size $fileHdr)")
        if (chunkHdr < CHUNK_HEADER_LEN) throw SparseFormatException("Invalid sparse file format (chunk header size $chunkHdr)")
        val blk = u32(h, 12)
        if (blk == 0L || blk % 4 != 0L || blk > Int.MAX_VALUE) throw SparseFormatException("Invalid sparse file format (block size $blk)")
        val totalBlks = u32(h, 16)
        if (totalBlks == 0L) throw SparseFormatException("Invalid sparse file format (no blocks)")
        val totalChunks = u32(h, 20)

        val segs = ArrayList<Seg>()
        var off = fileHdr.toLong()          
        var curBlock = 0L
        var i = 0L
        while (i < totalChunks) {
            val c = readAt(src, off, CHUNK_HEADER_LEN)
            val type = u16(c, 0)
            val chunkSz = u32(c, 4)
            val totalSz = u32(c, 8)
            off += chunkHdr                  
            val dataSize = (totalSz - chunkHdr) and 0xffffffffL   
            val at = off
            when (type) {
                CHUNK_RAW -> {
                    
                    if (dataSize % blk != 0L || dataSize / blk != chunkSz)
                        throw SparseFormatException("Invalid sparse file format at data block at ${at - chunkHdr}")
                    val len = chunkSz * blk
                    if (at + len > src.size) throw SparseFormatException("EOF while reading file at data block at ${at - chunkHdr}")
                    if (len > 0) segs.add(Seg(curBlock, chunkSz, CHUNK_RAW, at, 0))
                    off += len
                    curBlock += chunkSz
                }
                CHUNK_FILL -> {
                    if (dataSize != 4L) throw SparseFormatException("Invalid sparse file format at fill block at ${at - chunkHdr}")
                    val f = readAt(src, at, 4)
                    if (chunkSz > 0) segs.add(Seg(curBlock, chunkSz, CHUNK_FILL, 0, f.getInt(0)))
                    off += 4
                    curBlock += chunkSz
                }
                CHUNK_DONT_CARE -> {
                    if (dataSize != 0L) throw SparseFormatException("Invalid sparse file format at skip block at ${at - chunkHdr}")
                    curBlock += chunkSz
                }
                CHUNK_CRC32 -> {
                    if (dataSize != 4L) throw SparseFormatException("Invalid sparse file format at crc block at ${at - chunkHdr}")
                    readAt(src, at, 4)       
                    off += 4
                }
                else -> throw SparseFormatException("Invalid sparse file format at unknown block 0x${type.toString(16)} at ${at - chunkHdr}")
            }
            i++
        }
        if (curBlock != totalBlks) throw SparseFormatException("Invalid sparse file format (chunks cover $curBlock blocks, header says $totalBlks)")
        return Layout(blk.toInt(), totalBlks, segs)
    }

    
    fun convert(parts: List<RandomSource>, out: File, progress: ((done: Long, total: Long) -> Unit)? = null) {
        if (parts.isEmpty()) throw IOException("no sparse images given")
        val layouts = parts.map { parse(it) }
        val total = layouts.sumOf { l -> l.segs.sumOf { it.blocks * l.blockSize } }.coerceAtLeast(1)
        var done = 0L
        var ok = false
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                val ch = raf.channel
                val buf = ByteBuffer.allocate(COPY_BUF_SIZE)
                val fillBuf = ByteBuffer.allocate(COPY_BUF_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                var fillFor: Int? = null
                for ((pi, l) in layouts.withIndex()) {
                    for (s in l.segs) {
                        val len = s.blocks * l.blockSize
                        var pos = s.startBlock * l.blockSize
                        var left = len
                        var srcPos = s.srcOff
                        while (left > 0) {
                            val k = minOf(left, COPY_BUF_SIZE.toLong()).toInt()
                            val w: ByteBuffer
                            if (s.type == CHUNK_RAW) {
                                buf.clear(); buf.limit(k)
                                parts[pi].read(srcPos, buf)
                                buf.flip(); w = buf
                                srcPos += k
                            } else {
                                if (fillFor != s.fill) {
                                    fillBuf.clear()
                                    while (fillBuf.hasRemaining()) fillBuf.putInt(s.fill)
                                    fillFor = s.fill
                                }
                                fillBuf.clear(); fillBuf.limit(k); w = fillBuf
                            }
                            while (w.hasRemaining()) pos += ch.write(w, pos)
                            left -= k
                            done += k
                        }
                        progress?.invoke(done, total)
                    }
                    raf.setLength(l.size)     
                }
            }
            ok = true
        } finally {
            if (!ok) out.delete()
        }
    }

    
    fun convertFiles(parts: List<File>, out: File, progress: ((Long, Long) -> Unit)? = null) {
        val chans = ArrayList<FileChannel>()
        try {
            for (f in parts) chans.add(FileChannel.open(f.toPath(), StandardOpenOption.READ))
            convert(chans.map { ChannelSource(it) }, out, progress)
        } finally {
            chans.forEach { runCatching { it.close() } }
        }
    }

    
    fun numericOrder(files: List<File>): List<File> =
        files.sortedWith(compareBy({ it.name.substringBeforeLast('.', it.name) },
            { it.name.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
}
