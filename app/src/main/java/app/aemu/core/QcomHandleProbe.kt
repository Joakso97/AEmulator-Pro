package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder


object QcomHandleProbe {
    enum class Layout { METADATA_POINTER, VALUE, UNKNOWN }

    private const val HANDLE_OFFSET = 0x60
    private const val SLOT_OFFSET = 0x48
    private const val WINDOW = 6

    private fun isLdrX(w: Int) = (w and 0xffc00000.toInt()) == 0xf9400000.toInt()
    private fun isLdrW(w: Int) = (w and 0xffc00000.toInt()) == 0xb9400000.toInt()
    
    private fun isLoadImm(w: Int): Boolean {
        val top = w and 0xffc00000.toInt()
        return top == 0x39400000 || top == 0x79400000 || top == 0xb9400000.toInt() || top == 0xf9400000.toInt()
    }
    private fun rt(w: Int) = w and 31
    private fun rn(w: Int) = (w ushr 5) and 31
    private fun imm12(w: Int) = (w ushr 10) and 0xfff

    fun classify(d: ByteArray): Layout {
        if (d.size < 64 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 2.toByte()) return Layout.UNKNOWN
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(18).toInt() and 0xffff) != 183) return Layout.UNKNOWN            
        val words = d.size / 4
        fun w(i: Int) = b.getInt(i * 4)
        var sawValue = false
        for (i in 0 until words - 2) {
            val h = w(i)
            
            if (!isLdrX(h) || imm12(h) != HANDLE_OFFSET / 8) continue
            val handleReg = rt(h)
            if (handleReg == 31) continue
            for (j in i + 1..minOf(i + WINDOW, words - 1)) {
                val s = w(j)
                if (rn(s) != handleReg) continue
                if (isLdrX(s) && imm12(s) == SLOT_OFFSET / 8) {
                    val slotReg = rt(s)
                    
                    for (k in j + 1..minOf(j + WINDOW, words - 1)) {
                        val u = w(k)
                        if (isLoadImm(u) && rn(u) == slotReg && slotReg != 31) return Layout.METADATA_POINTER
                        if (rt(u) == slotReg && isLoadImm(u)) break
                    }
                } else if (isLdrW(s) && imm12(s) == SLOT_OFFSET / 4) sawValue = true
            }
        }
        return if (sawValue) Layout.VALUE else Layout.UNKNOWN
    }

    fun classify(f: File): Layout = runCatching { classify(f.readBytes()) }.getOrDefault(Layout.UNKNOWN)
}
