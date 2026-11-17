package app.aemu.importer

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream


internal object ImageCodecs {
    enum class Codec { GZIP, XZ, LZMA, BZIP2, LZ4, ZSTD, BROTLI }

    
    val SUFFIX = Regex("(?i)\\.(lzma|xz|lz4|zst|zstd|bz2|gz|br)$")

    fun stripSuffix(name: String): String = SUFFIX.replaceFirst(name, "")

    fun detect(h: ByteArray, name: String?): Codec? {
        fun b(i: Int) = if (i < h.size) h[i].toInt() and 255 else -1
        return when {
            b(0) == 0x1f && b(1) == 0x8b -> Codec.GZIP
            b(0) == 0xfd && b(1) == 0x37 && b(2) == 0x7a && b(3) == 0x58 && b(4) == 0x5a && b(5) == 0 -> Codec.XZ
            b(0) == 0x42 && b(1) == 0x5a && b(2) == 0x68 && b(3) in 0x31..0x39 -> Codec.BZIP2
            b(0) == 0x04 && b(1) == 0x22 && b(2) == 0x4d && b(3) == 0x18 -> Codec.LZ4
            b(0) == 0x28 && b(1) == 0xb5 && b(2) == 0x2f && b(3) == 0xfd -> Codec.ZSTD
            isLzma(h, name) -> Codec.LZMA
            isBrotli(h, name) -> Codec.BROTLI
            else -> null
        }
    }

    fun open(codec: Codec, raw: InputStream): InputStream {
        val i = BufferedInputStream(raw, 1 shl 16)
        return when (codec) {
            Codec.GZIP -> GZIPInputStream(i, 1 shl 16)
            Codec.XZ -> XZInputStream(i)
            Codec.LZMA -> LZMAInputStream(i, -1) 
            Codec.BZIP2 -> BZip2CompressorInputStream(i, true)
            Codec.LZ4 -> FramedLZ4CompressorInputStream(i, true)
            Codec.ZSTD -> ZstdInputStream(i)
            Codec.BROTLI -> BrotliInputStream(i)
        }
    }

    
    private fun isLzma(h: ByteArray, name: String?): Boolean {
        if (h.size < 13) return false
        val props = h[0].toInt() and 255
        if (props >= 225) return false
        var dict = 0L
        for (i in 4 downTo 1) dict = (dict shl 8) or (h[i].toLong() and 255)
        if (dict < 4096 || dict > 0x6000_0000L) return false
        var allFf = true
        for (i in 5..12) if (h[i] != 0xff.toByte()) allFf = false
        var highZero = true
        for (i in 9..12) if (h[i] != 0.toByte()) highZero = false
        if (!allFf && !highZero) return false
        if (name != null && name.endsWith(".lzma", true)) return true
        
        return props == 0x5d && dict % 4096 == 0L
    }

    
    private fun isBrotli(h: ByteArray, name: String?): Boolean {
        if (name != null && name.endsWith(".br", true) && !name.endsWith(".new.dat.br", true)) return true
        if (h.size < 256 || fsMagic(h, h.size)) return false
        return try {
            val s = BrotliInputStream(ByteArrayInputStream(h))
            val out = ByteArray(8192)
            var got = 0
            try {
                while (got < out.size) {
                    val r = s.read(out, got, out.size - got)
                    if (r < 0) break
                    got += r
                }
            } catch (_: IOException) {  }
            got >= 1088 && fsMagic(out, got)
        } catch (_: Throwable) { false }
    }

    private fun fsMagic(d: ByteArray, n: Int): Boolean {
        fun at(o: Int, vararg v: Int) = o + v.size <= n && v.indices.all { (d[o + it].toInt() and 255) == v[it] }
        return at(0, 0x3a, 0xff, 0x26, 0xed) || at(0, 0xed, 0x26, 0xff, 0x3a) || 
            at(0, 'h'.code, 's'.code, 'q'.code, 't'.code) || at(0, 0x73, 0x71, 0x73, 0x68) || 
            at(1080, 0x53, 0xef) || 
            at(1024, 0xe2, 0xe1, 0xf5, 0xe0) || 
            at(1024, 0x10, 0x20, 0xf5, 0xf2) 
    }
}
