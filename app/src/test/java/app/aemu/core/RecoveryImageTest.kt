/* AEmulator Plus addition. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

 
class RecoveryImageTest {
    private fun code(vararg hw: Int): ByteArray = ByteArray(hw.size * 2).also { b ->
        hw.forEachIndexed { i, v -> b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() }
    }
    private fun hw(d: ByteArray, i: Int) = (d[i * 2].toInt() and 0xFF) or ((d[i * 2 + 1].toInt() and 0xFF) shl 8)

    private val nop = 0xBF00
    private val movwGetFscreeninfo = intArrayOf(0xF244, 0x6102)           

     
    private fun grInit(format: Int, strideShift: Int, add: IntArray = intArrayOf(0xEB0B, 0x0002)): ByteArray {
        val lsrs = { rm: Int, rd: Int -> 0x0800 or (strideShift shl 6) or (rm shl 3) or rd }
        return code(
            0x4620, *movwGetFscreeninfo,                        
            0xF8D5, 0xE004, 0x6013, nop,
            lsrs(2, 1),                                          
            0x2300 or format, 0x7533,                            
            0xFB0E, 0xF201,                                      
            0xEBB0, 0x0F42,                                      
            0xD315,                                              
            lsrs(1, 1),                                          
            *add,                                                
            0xE8BD, 0x8FF0,                                      
        )
    }

    @Test fun bgraStrideAndSecondPageAreMatchedToTheHost() {
        val d = grInit(format = 5, strideShift = 2)
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(2, fix.format)                        
        assertEquals(3, fix.patched)
        assertEquals(0x0851, hw(d, 7))                     
        assertEquals(0x0849, hw(d, 15))                    
        assertEquals(0xEB0B, hw(d, 16)); assertEquals(0x0042, hw(d, 17))  
    }

    @Test fun secondRunChangesNothing() {
        val d = grInit(format = 5, strideShift = 2)
        RecoveryImage.patchFramebuffer(d)
        val once = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(2, fix.format)
        assertEquals(0, fix.patched)
        assertArrayEquals(once, d)
    }

    @Test fun pageOffsetInTheFirstOperandIsSwapped() {
        val d = grInit(format = 5, strideShift = 2, add = intArrayOf(0xEB02, 0x000B))  
        assertEquals(3, RecoveryImage.patchFramebuffer(d)!!.patched)
        assertEquals(0xEB0B, hw(d, 16)); assertEquals(0x0042, hw(d, 17))                 
    }

    @Test fun rgbxAndRgbaAreDrawnAsRgbxOnTheHost() {
        assertEquals(1, RecoveryImage.patchFramebuffer(grInit(2, 2))!!.format)
        assertEquals(1, RecoveryImage.patchFramebuffer(grInit(1, 2))!!.format)
    }

    @Test fun sixteenBitRecoveryKeepsItsCode() {
        val d = grInit(format = 4, strideShift = 1)
        val before = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(0, fix.format)
        assertEquals(0, fix.patched)
        assertArrayEquals(before, d)
    }

    @Test fun binaryWithoutFbSetupIsLeftAlone() {
        val d = code(0x4620, 0x4621, nop, nop, 0xE8BD, 0x8FF0)
        assertNull(RecoveryImage.patchFramebuffer(d))
    }

    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

     
    @Test fun samsungIcsEvdevProbeIsNeutralised() {
        val d = hexBytes("23f0fffc" + "002803db039b13f0060f03d1" + "2046dff7")
        val mask = RecoveryImage.patchEvdevProbes(d)
        assertEquals(1 shl 2, mask)                        
        assertEquals("23f0fffc" + "0028" + "00bf" + "039b13f0060f" + "03e0" + "2046dff7", d.joinToString("") { "%02x".format(it) })
    }

     
    @Test fun cwmCyanogenModEvdevProbeIsNeutralised() {
        val d = hexBytes("31f0cafe" + "002803db039b13f00e0f03d1" + "204623f0e8ef")
        val mask = RecoveryImage.patchEvdevProbes(d)
        assertEquals(1 shl 3, mask)                        
        assertEquals("31f0cafe" + "0028" + "00bf" + "039b13f00e0f" + "03e0" + "204623f0e8ef", d.joinToString("") { "%02x".format(it) })
        val once = d.copyOf()
        assertEquals(1 shl 3, RecoveryImage.patchEvdevProbes(d))    
        assertArrayEquals(once, d)
    }

    @Test fun evdevProbePatchIsIdempotent() {
        val d = hexBytes("002803db039b13f0060f03d1")
        val first = RecoveryImage.patchEvdevProbes(d)
        val once = d.copyOf()
        assertEquals(first, RecoveryImage.patchEvdevProbes(d))
        assertArrayEquals(once, d)
    }

    @Test fun binaryWithoutEvdevProbesIsLeftAlone() {
        val d = code(0x4620, 0x4621, nop, nop)
        val before = d.copyOf()
        assertEquals(0, RecoveryImage.patchEvdevProbes(d))
        assertArrayEquals(before, d)
    }

     

    private fun w16(d: ByteArray, o: Int, v: Int) { d[o] = v.toByte(); d[o + 1] = (v shr 8).toByte() }
    private fun w32(d: ByteArray, o: Int, v: Int) { w16(d, o, v and 0xFFFF); w16(d, o + 2, v ushr 16) }

     
    private fun elf(vararg hw: Int): ByteArray {
        val d = ByteArray(0x1000)
        d[0] = 0x7F; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1; d[5] = 1
        w16(d, 18, 40); w32(d, 28, 52); w16(d, 42, 32); w16(d, 44, 1)
        val fs = 0x80 + hw.size * 2
        w32(d, 52, 1); w32(d, 56, 0); w32(d, 60, 0x8000); w32(d, 64, 0x8000); w32(d, 68, fs); w32(d, 72, fs); w32(d, 76, 5)
        hw.forEachIndexed { i, v -> w16(d, 0x80 + i * 2, v) }
        return d
    }

     
    private fun samsungGrInit(vararg afterCheck: Int) = elf(
        0xF244, 0x6102,               
        0x4642,                       
        0xF000, 0xF800,               
        0x2800, 0xDA02,               
        nop, nop, nop,                
        *afterCheck,                  
        0x2302, 0x7533,               
        0x0890,                       
        0xE8BD, 0x8FF0,               
    )

    private val hook = 0x80 + 10 * 2

    @Test fun lineLengthHookMovesTwoSixteenBitInstructions() {
        val d = samsungGrInit(0x9500, 0x2203)                             
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(1, fix.format)
        assertEquals(3, fix.patched)
        assertEquals(0xF000, hw(d, hook / 2) and 0xF800)                   
        val start = (0x80 + 2 * 17 + 3) and 3.inv()                         
        assertEquals(0xF84D, hw(d, start / 2))                             
        assertEquals(0x9500, hw(d, (start + 20) / 2))                      
        assertEquals(0x2203, hw(d, (start + 22) / 2))
        assertEquals(0x0890, hw(d, (hook + 4 + 4) / 2))                    
    }

    @Test fun lineLengthHookMovesA16And32BitPairWithANop() {
        val d = samsungGrInit(0x9500, 0xF8C6, 0x5020)                       
        assertEquals(3, RecoveryImage.patchFramebuffer(d)!!.patched)
        assertEquals(0xF000, hw(d, hook / 2) and 0xF800)
        assertEquals(0xBF00, hw(d, (hook + 4) / 2))                        
        val start = (0x80 + 2 * 18 + 3) and 3.inv()
        assertEquals(0x9500, hw(d, (start + 20) / 2))
        assertEquals(0xF8C6, hw(d, (start + 22) / 2)); assertEquals(0x5020, hw(d, (start + 24) / 2))
    }

    @Test fun lineLengthHookIsInstalledOnlyOnce() {
        val d = samsungGrInit(0x9500, 0x2203)
        RecoveryImage.patchFramebuffer(d)
        val once = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(0, fix.patched)
        assertArrayEquals(once, d)
    }

    @Test fun pcRelativeInstructionIsNotMovedSoTheStrideFallbackIsUsed() {
        val d = samsungGrInit(0x4C01, 0x2203)                             
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(1, fix.patched)                                       
        assertEquals(0x0850, hw(d, (hook + 4 + 4) / 2))
    }

     

    private val stockCacheflush = intArrayOf(
        0xE92D0090.toInt(), 0xE59F7010.toInt(), 0xEF000000.toInt(), 0xE8BD0090.toInt(),
        0xE1B00000.toInt(), 0x512FFF1E, 0xEAFFFFE0.toInt(), 0x000F0002
    )

     
    private fun staticBinary(stub: IntArray, at: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(0x200).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (o in 0 until 0x200 step 4) b.putInt(o, 0xE1A00000.toInt())
        b.putInt(0x20, 0x000F0003)  
        stub.forEachIndexed { i, w -> b.putInt(at + 4 * i, w) }
        return b.array()
    }
    private fun words(d: ByteArray, off: Int, n: Int): IntArray {
        val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return IntArray(n) { b.getInt(off + 4 * it) }
    }

    @Test fun staticRecoveryCacheflushStubIsPatched() {
        val d = staticBinary(stockCacheflush, 0x100)
        assertEquals(1, RecoveryImage.patchCacheflushExec(d))
        assertArrayEquals(ElfPatch.CACHEFLUSH_EXEC, words(d, 0x100, ElfPatch.CACHEFLUSH_EXEC.size))
        assertEquals(0, RecoveryImage.patchCacheflushExec(d))  
    }

    @Test fun staticRecoveryWithoutTheStubIsLeftAlone() {
        val d = staticBinary(intArrayOf(0xE92D0090.toInt(), 0xE1A00000.toInt(), 0x000F0002), 0x100)  
        val before = d.copyOf()
        assertEquals(0, RecoveryImage.patchCacheflushExec(d))
        assertArrayEquals(before, d)
    }

     

    @Test fun preIcsRecoveryIsRgb565EvenWhenTheByteHeuristicSaysOtherwise() {
         
        for (api in intArrayOf(5, 7, 8, 10, 13)) assertEquals(0, RecoveryImage.chooseFormat(api, null) { true })
        assertEquals(0, RecoveryImage.chooseFormat(7, null) { false })
    }

    @Test fun newerRecoveriesStillUseTheHeuristicAndThePatchWins() {
        assertEquals(1, RecoveryImage.chooseFormat(19, null) { true })
        assertNull(RecoveryImage.chooseFormat(19, null) { false })
        assertEquals(2, RecoveryImage.chooseFormat(19, 2) { false })
        assertEquals(0, RecoveryImage.chooseFormat(19, 0) { true })
        assertEquals(1, RecoveryImage.chooseFormat(7, 1) { false })    
        assertNull(RecoveryImage.chooseFormat(0, null) { false })       
    }
}
