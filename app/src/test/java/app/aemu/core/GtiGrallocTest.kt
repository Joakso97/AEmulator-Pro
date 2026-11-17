package app.aemu.core

import app.aemu.core.GrallocProbe.Verdict
import org.junit.Assert.*
import org.junit.Test
import java.io.File

 
class GtiGrallocTest {
    private val kk = File("src/main/assets/engines/kk")
    private val gb = File("src/main/assets/engines/gb")
    private fun bytes(dir: File, name: String) = File(dir, name).readBytes()

    @Test fun kitKatGrallocIsDetectedAsBroken() {
        assertEquals(Verdict.UNGUARDED, GrallocProbe.verdict(bytes(kk, "gralloc.default.so")))
    }

    @Test fun eclairBehaviourGrallocsAreNotBroken() {
        assertEquals(Verdict.GUARDED, GrallocProbe.verdict(bytes(gb, "gralloc.default.so")))
        assertEquals(Verdict.GUARDED, GrallocProbe.verdict(bytes(kk, GtiGralloc.ASSET)))
    }

    @Test fun unreadableInputIsUnknown() {
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(ByteArray(0)))
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict("not an elf at all, just text".toByteArray()))
        val kkBytes = bytes(kk, "gralloc.default.so")
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(kkBytes.copyOf(0x40)))                  
        val notArm = kkBytes.copyOf().also { it[18] = 3 }                                             
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(notArm))
        val noModule = kkBytes.copyOf().also { it[0x2004] = 0 }                                       
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(noModule))
    }

    @Test fun variantOnlyWhenEngineGrallocBreaksTheFirmware() {
        val kkStd = bytes(kk, "gralloc.default.so")
        val eclairLike = bytes(gb, "gralloc.default.so")    
         
        assertTrue(GtiGralloc.applies(eclairLike, kkStd))
         
        assertFalse(GtiGralloc.applies(kkStd, kkStd))
         
        assertFalse(GtiGralloc.applies(null, kkStd))
        assertFalse(GtiGralloc.applies(eclairLike, null))
        assertFalse(GtiGralloc.applies("garbage".toByteArray(), kkStd))
         
        assertFalse(GtiGralloc.applies(eclairLike, eclairLike))
        assertFalse(GtiGralloc.applies(eclairLike, bytes(kk, GtiGralloc.ASSET)))
    }

    @Test fun variantDiffersOnlyInRegisterAndUnregister() {
        val a = bytes(kk, "gralloc.default.so")
        val b = bytes(kk, GtiGralloc.ASSET)
        assertEquals(a.size, b.size)
        val changed = a.indices.filter { a[it] != b[it] }
        assertTrue(changed.isNotEmpty())
         
         
        for (i in changed) assertTrue("unexpected change at 0x${i.toString(16)}", i in 0x1584 until 0x1594 || i in 0x15a0 until 0x15c2)
    }
}
