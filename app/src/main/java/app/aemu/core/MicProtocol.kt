
package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt


object MicProtocol {
    const val HEADER_BYTES = 16
    const val MAGIC = "MIC1"
    const val FORMAT_PCM16 = 1
    const val RATE_MIN = 4000
    const val RATE_MAX = 192000
    const val MAX_CHANNELS = 2

    
    data class Format(val rate: Int, val channels: Int, val format: Int = FORMAT_PCM16) {
        
        val frameBytes: Int get() = channels * 2
    }

    
    fun format(bytes: ByteArray): Format? {
        if (bytes.size != HEADER_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC.toByteArray())) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(4)
        val channels = b.getInt(8)
        val format = b.getInt(12)
        if (rate !in RATE_MIN..RATE_MAX || channels !in 1..MAX_CHANNELS || format != FORMAT_PCM16) return null
        return Format(rate, channels, format)
    }
}


class MicResampler(private val captureRate: Int, private val format: MicProtocol.Format) {
    private val step = captureRate.toDouble() / format.rate
    private val box = if (captureRate % format.rate == 0) captureRate / format.rate else 1
    
    private var carry = ShortArray(0)
    
    private var prev = 0
    
    private var phase = 0.0

    init {
        require(captureRate > 0 && format.rate in MicProtocol.RATE_MIN..MicProtocol.RATE_MAX) { "bad rate" }
    }

    
    fun process(src: ShortArray, count: Int): ByteArray {
        require(count in 0..src.size)
        if (count == 0) return ByteArray(0)
        val frames = when {
            box > 1 -> decimate(src, count)
            step != 1.0 -> interpolate(src, count)
            else -> src.copyOf(count)
        }
        if (frames.isEmpty()) return ByteArray(0)
        val out = ByteBuffer.allocate(frames.size * format.frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in frames) {
            out.putShort(s)
            if (format.channels == 2) out.putShort(s)
        }
        return out.array()
    }

    
    private fun decimate(src: ShortArray, count: Int): ShortArray {
        val head = carry
        carry = ShortArray(0)
        val total = head.size + count
        val frames = total / box
        if (frames == 0) {
            carry = ShortArray(total) { k -> if (k < head.size) head[k] else src[k - head.size] }
            return ShortArray(0)
        }
        val out = ShortArray(frames)
        for (o in 0 until frames) {
            var sum = 0L
            for (k in 0 until box) {
                val i = o * box + k
                sum += if (i < head.size) head[i].toLong() else src[i - head.size].toLong()
            }
            out[o] = (sum / box).toInt().toShort()
        }
        val used = frames * box
        carry = ShortArray(total - used) { k ->
            val i = used + k
            if (i < head.size) head[i] else src[i - head.size]
        }
        return out
    }

    
    private fun interpolate(src: ShortArray, count: Int): ShortArray {
        var out = ShortArray(((count - phase) / step).toInt().coerceIn(16, 1 shl 16))
        var o = 0
        var p = phase
        while (p < count - 1) {
            if (o == out.size) out = out.copyOf(out.size * 2)
            val i = floor(p).toInt()
            val frac = p - i
            val a = if (i < 0) prev.toDouble() else src[i].toDouble()
            val b = src[i + 1].toDouble()
            out[o++] = (a + (b - a) * frac).roundToInt().toShort()
            p += step
        }
        prev = src[count - 1].toInt()
        phase = p - count
        return if (o == out.size) out else out.copyOf(o)
    }
}
