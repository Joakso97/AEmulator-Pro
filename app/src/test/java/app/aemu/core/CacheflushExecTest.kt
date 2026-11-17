package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

 
 
 
 
 
class CacheflushExecTest {
    private val stubOff = 0x100
    private val next = intArrayOf(0xE92D4010.toInt(), 0xE1A04000.toInt(), 0xE3A07001.toInt(), 0xEF000000.toInt())  

     
    private val stock = intArrayOf(
        0xE92D0090.toInt(), 0xE59F7010.toInt(), 0xEF000000.toInt(), 0xE8BD0090.toInt(),
        0xE1B00000.toInt(), 0x512FFF1E, 0xEAFFFFE0.toInt(), 0x000F0002
    )

     
    private fun elf(code: IntArray, thumb: Boolean = false): ByteArray {
        val d = ByteArray(0x300)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        d[0] = 0x7f; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1; d[5] = 1; d[6] = 1
        b.putShort(16, 3); b.putShort(18, 40); b.putInt(20, 1)
        b.putInt(28, 52); b.putInt(32, 0x200)
        b.putShort(40, 52); b.putShort(42, 32); b.putShort(44, 1); b.putShort(46, 40); b.putShort(48, 3)
         
        b.putInt(52, 1); b.putInt(52 + 4, 0); b.putInt(52 + 8, 0); b.putInt(52 + 12, 0)
        b.putInt(52 + 16, d.size); b.putInt(52 + 20, d.size); b.putInt(52 + 24, 5); b.putInt(52 + 28, 0x1000)
        var o = stubOff
        for (w in code) { b.putInt(o, w); o += 4 }
        for (w in next) { b.putInt(o, w); o += 4 }
         
        val str = "\u0000cacheflush\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(str, 0, d, 0x160, str.size)
         
        b.putInt(0x190, 1); b.putInt(0x190 + 4, stubOff or if (thumb) 1 else 0); b.putInt(0x190 + 8, 32)
        d[0x190 + 12] = 0x12; b.putShort(0x190 + 14, 1)
         
        val s1 = 0x200 + 40
        b.putInt(s1 + 4, 11); b.putInt(s1 + 16, 0x180); b.putInt(s1 + 20, 32); b.putInt(s1 + 24, 2); b.putInt(s1 + 36, 16)
        val s2 = 0x200 + 80
        b.putInt(s2 + 4, 3); b.putInt(s2 + 16, 0x160); b.putInt(s2 + 20, str.size)
        return d
    }

    private fun words(d: ByteArray, off: Int, n: Int): IntArray {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(n) { b.getInt(off + 4 * it) }
    }

    private fun temp(d: ByteArray) = File.createTempFile("libc", ".so").apply { deleteOnExit(); writeBytes(d) }

    @Test fun replacementIsAsLongAsTheStockStub() {
        assertEquals(stock.size, ElfPatch.CACHEFLUSH_EXEC.size)
         
        assertEquals(0xEF000000.toInt(), ElfPatch.CACHEFLUSH_EXEC[6])
        assertEquals(0xE8BD8080.toInt(), ElfPatch.CACHEFLUSH_EXEC[7])
    }

    @Test fun patchesTheStockStubAndNothingElse() {
        val f = temp(elf(stock))
        assertEquals(1, ElfPatch.execCacheflush(f))
        val d = f.readBytes()
        assertArrayEquals(ElfPatch.CACHEFLUSH_EXEC, words(d, stubOff, stock.size))
        assertArrayEquals(next, words(d, stubOff + 4 * stock.size, next.size))  
        assertEquals(0, ElfPatch.execCacheflush(f))  
        assertArrayEquals(d, f.readBytes())
    }

    @Test fun longerStubIsPaddedWithNops() {
         
        val v = intArrayOf(
            0xE1A0C007.toInt(), 0xE59F7018.toInt(), 0xEF000000.toInt(), 0xE1A0700C.toInt(), 0xE1A00000.toInt(),
            0xE1A00000.toInt(), 0xE1A00000.toInt(), 0xE1A00000.toInt(), 0xE12FFF1E.toInt(), 0x000F0002
        )
        val code = ElfPatch.cacheflushExecBytes(elf(v), stubOff)!!
        assertEquals(v.size * 4, code.size)
        val w = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN)
        for (i in ElfPatch.CACHEFLUSH_EXEC.indices) assertEquals(ElfPatch.CACHEFLUSH_EXEC[i], w.getInt(4 * i))
        for (i in ElfPatch.CACHEFLUSH_EXEC.size until v.size) assertEquals(0xE1A00000.toInt(), w.getInt(4 * i))
    }

    @Test fun leavesAThumbSymbolAlone() {
        val d = elf(stock, thumb = true)
        val f = temp(d)
        assertEquals(0, ElfPatch.execCacheflush(f))
        assertArrayEquals(d, f.readBytes())
    }

    @Test fun needsTheSyscallInstruction() {
        val s = stock.copyOf().also { it[2] = 0xE1A00000.toInt() }
        assertNull(ElfPatch.cacheflushExecBytes(elf(s), stubOff))
    }

    @Test fun ldrMustLoadThatLiteral() {
        val s = stock.copyOf().also { it[1] = 0xE59F7008.toInt() }  
        assertNull(ElfPatch.cacheflushExecBytes(elf(s), stubOff))
    }

    @Test fun needsTheLiteral() {
        val s = stock.copyOf().also { it[7] = 0x000F0003 }
        assertNull(ElfPatch.cacheflushExecBytes(elf(s), stubOff))
    }

    @Test fun refusesAStubShorterThanTheReplacement() {
         
        val s = intArrayOf(0xE59F700C.toInt(), 0xE1A00000.toInt(), 0xEF000000.toInt(), 0xE1A00000.toInt(), 0xE12FFF1E.toInt(), 0x000F0002)
        assertNull(ElfPatch.cacheflushExecBytes(elf(s + IntArray(10)), stubOff))
    }

    @Test fun rejectsNonElfAndMissingSymbol() {
        assertEquals(0, ElfPatch.execCacheflush(temp(ByteArray(0))))
        assertEquals(0, ElfPatch.execCacheflush(temp(ByteArray(4096))))
        assertNull(ElfPatch.cacheflushExecBytes(ByteArray(16), 0))
    }
}
