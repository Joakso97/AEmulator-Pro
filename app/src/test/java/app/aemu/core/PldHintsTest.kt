package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

 
 
 
 
 
class PldHintsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val codeOff = 0x100
    private val nop = 0xE1A00000.toInt()

     
    private fun elf(words: IntArray): ByteArray {
        val b = ByteBuffer.allocate(codeOff + words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x7f.toByte()); b.put(1, 'E'.code.toByte()); b.put(2, 'L'.code.toByte()); b.put(3, 'F'.code.toByte())
        b.put(4, 1.toByte()); b.put(5, 1.toByte())
        b.putShort(18, 40.toShort())
        b.putInt(28, 52); b.putShort(42, 32.toShort()); b.putShort(44, 1.toShort())
        b.putInt(52, 1); b.putInt(56, codeOff); b.putInt(60, codeOff); b.putInt(64, codeOff)
        b.putInt(68, words.size * 4); b.putInt(72, words.size * 4); b.putInt(76, 5)
        for ((i, w) in words.withIndex()) b.putInt(codeOff + i * 4, w)
        return b.array()
    }

     
    private fun thumb(vararg h: Int): IntArray {
        val padded = if (h.size % 2 == 0) h else h + 0
        return IntArray(padded.size / 2) { (padded[it * 2 + 1] shl 16) or padded[it * 2] }
    }

    private val armPrologue = intArrayOf(
        nop, nop, nop, nop, nop, nop,
        0xF5D10000.toInt(),  
        0xF5900000.toInt(),  
        0xE92D4001.toInt(),  
        nop, nop, nop, nop, nop, nop
    )

    @Test fun armMemcpyPrologueIsFixed() {
        assertEquals(listOf(codeOff + 6 * 4, codeOff + 7 * 4), ElfPatch.pldHintOffsets(elf(armPrologue)))
    }

    @Test fun hintAtFunctionStartAfterThumbPaddingIsFixed() {
        val code = intArrayOf(
            0x47702000, 0x47702000, 0xE12FFF1E.toInt(),  
            0xF5D10000.toInt(), 0xE92D4001.toInt(), 0xF5D1F040.toInt(),
            nop, nop, nop, nop, nop, nop, nop, nop
        )
        assertEquals(listOf(codeOff + 3 * 4), ElfPatch.pldHintOffsets(elf(code)))
    }

    @Test fun thumbCallsThatLookLikeHintsAreLeftAlone() {
         
        val code = thumb(
            0x6835, 0x6928, 0x9000, 0x696f, 0x9701, 0xb15f, 0xe638, 0xf5de, 0xef3a, 0xa802, 0x466a, 0x4629,
            0xf5d2, 0xef28, 0x4620, 0xf5d1, 0xef26
        )
        assertEquals(0xf5dee638.toInt(), code[3])
        assertTrue(ElfPatch.pldHintOffsets(elf(code)).isEmpty())
    }

    @Test fun alreadyFixedWordsAreIgnored() {
        val fixed = armPrologue.copyOf().also { it[6] = 0xF5D1F000.toInt(); it[7] = 0xF5D0F000.toInt() }
        assertTrue(ElfPatch.pldHintOffsets(elf(fixed)).isEmpty())
    }

    @Test fun nonElfAndNonArmGiveNothing() {
        assertTrue(ElfPatch.pldHintOffsets(ByteArray(0)).isEmpty())
        assertTrue(ElfPatch.pldHintOffsets(ByteArray(4096)).isEmpty())
        val x86 = elf(armPrologue).also { it[18] = 62 }
        assertTrue(ElfPatch.pldHintOffsets(x86).isEmpty())
    }

    @Test fun rewritesTheFileInPlaceAndIsIdempotent() {
        val f = tmp.newFile("libc.so")
        f.writeBytes(elf(armPrologue))
        assertEquals(2, ElfPatch.fixPldHints(f))
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xF5D1F000.toInt(), b.getInt(codeOff + 6 * 4))
        assertEquals(0xF5D0F000.toInt(), b.getInt(codeOff + 7 * 4))
        assertEquals(0, ElfPatch.fixPldHints(f))
    }

    @Test fun thumbFileIsNotModified() {
        val code = thumb(
            0x6835, 0x6928, 0x9000, 0x696f, 0x9701, 0xb15f, 0xe638, 0xf5de, 0xef3a, 0xa802, 0x466a, 0x4629,
            0xf5d2, 0xef28, 0x4620, 0xf5d1, 0xef26
        )
        val f = tmp.newFile("libart.so")
        val bytes = elf(code)
        f.writeBytes(bytes)
        assertEquals(0, ElfPatch.fixPldHints(f))
        assertTrue(bytes.contentEquals(f.readBytes()))
    }

    @Test fun onlyBionicWithoutSymbolsIsPatched() {
         
        assertTrue(ElfPatch.pldHintOffsets(elf(armPrologue), "libart.so").isEmpty())
        assertTrue(ElfPatch.pldHintOffsets(elf(armPrologue), "libLLVM.so").isEmpty())
        assertEquals(listOf(codeOff + 6 * 4, codeOff + 7 * 4), ElfPatch.pldHintOffsets(elf(armPrologue), "libc.so"))
        assertEquals(listOf(codeOff + 6 * 4, codeOff + 7 * 4), ElfPatch.pldHintOffsets(elf(armPrologue), "linker"))
    }

     

     
    private val copyLoop = intArrayOf(
        0x1A000004, 0x0A000002, 0x3A000001, 0xCA000000.toInt(), 0xDA000001.toInt(), 0xAA000002.toInt(),  
        0xF5900000.toInt(), 0xF5900040.toInt(), 0xE2522040.toInt(), 0x3A00000A,
        0x8A000003.toInt(), 0x28B150F8, 0x28A050F8, 0x2A000001, 0x4A000000, 0x5A000002  
    )

    @Test fun conditionalHeavyCopyLoopIsFixed() {
        assertEquals(listOf(codeOff + 6 * 4, codeOff + 7 * 4), ElfPatch.pldHintOffsets(elf(copyLoop), "linker"))
    }

    @Test fun loneHintLookalikeInADataTableIsLeftAlone() {
         
        val table = intArrayOf(
            0x64B7A030, 0xF3216D23.toInt(), 0xC45236D2.toInt(), 0xF59E6D5E.toInt(), 0x2D500584, 0x4D0D905D, 0x6B6F335A, 0x1B2C3D4E
        )
        assertTrue(ElfPatch.pldHintOffsets(elf(table), "libc.so").isEmpty())
        assertTrue(ElfPatch.pldHintOffsets(elf(table), "linker").isEmpty())
        assertTrue(ElfPatch.pldHintOffsets(elf(table)).isEmpty())
    }

    @Test fun loneNotThumbPldwWithoutAnotherHintIsLeftAlone() {
        val code = copyLoop.copyOf().also { it[7] = nop }  
        assertTrue(ElfPatch.pldHintOffsets(elf(code), "libc.so").isEmpty())
    }

    @Test fun copyLoopOutsideBionicIsLeftAlone() {
         
        assertTrue(ElfPatch.pldHintOffsets(elf(copyLoop), "libcrypto.so").isEmpty())
        assertTrue(ElfPatch.pldHintOffsets(elf(copyLoop), "libart.so").isEmpty())
    }

    @Test fun malformedPldNextToAnArmPldwIsFixedToo() {
        val code = intArrayOf(
            0x1A000004, 0x0A000002, 0x3A000001, 0xCA000000.toInt(), 0xDA000001.toInt(), 0xAA000002.toInt(),
            0xF5D10040.toInt(), 0xF5900000.toInt(), 0xF5900040.toInt(), 0xE2522040.toInt(), 0x3A00000A,
            0x8A000003.toInt(), 0x28B150F8, 0x28A050F8, 0x2A000001, 0x4A000000, 0x5A000002
        )
        assertEquals(listOf(codeOff + 6 * 4, codeOff + 7 * 4, codeOff + 8 * 4), ElfPatch.pldHintOffsets(elf(code), "linker"))
    }

    @Test fun fixedCopyLoopRunsOnAsArmPld() {
        val f = tmp.newFile("linker")
        f.writeBytes(elf(copyLoop))
        assertEquals(2, ElfPatch.fixPldHints(f))
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xF5D0F000.toInt(), b.getInt(codeOff + 6 * 4))
        assertEquals(0xF5D0F040.toInt(), b.getInt(codeOff + 7 * 4))
        assertEquals(0xE2522040.toInt(), b.getInt(codeOff + 8 * 4))  
        assertEquals(0, ElfPatch.fixPldHints(f))
    }

    @Test fun thumbBranchesWithAnF59xPrefixAreLeftAlone() {
         
        val code = thumb(0x4620, 0xf590, 0xf000, 0xb510, 0xf590, 0xd001, 0x4770, 0xf5d1, 0xef26, 0x4620)
        assertTrue(ElfPatch.pldHintOffsets(elf(code), "libc.so").isEmpty())
    }

    @Test fun thumbSecondHalfOfA32BitInstructionIsLeftAlone() {
         
        val code = thumb(0xf8d0, 0xf590, 0x4620, 0xe92d, 0xf590, 0x4770)
        assertTrue(ElfPatch.pldHintOffsets(elf(code), "libc.so").isEmpty())
    }
}
