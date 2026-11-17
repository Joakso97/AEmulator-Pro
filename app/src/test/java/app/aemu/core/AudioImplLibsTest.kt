package app.aemu.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

 
 
 
 
 
class AudioImplLibsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(root: File, path: String, text: String = path): File =
        File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }

     

    @Test fun mediatekImplInHw_isCopiedIntoVendorLib() {
        val root = tmp.newFolder()
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so", "mtk-impl")
        val logs = ArrayList<String>()
        val r = AudioImplLibs.expose(root, { logs += it })
        assertEquals(listOf("vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so"), r.copied)
        assertEquals("mtk-impl", File(root, "vendor/lib/android.hardware.audio@2.0-impl-mediatek.so").readText())
         
        assertTrue(File(root, "vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so").isFile)
        assertTrue(logs.any { it.contains("reachable by bare name") })
    }

    @Test fun implAlreadyInVendorLib_isNotTouched() {
        val root = tmp.newFolder()
        put(root, "vendor/lib/android.hardware.audio@2.0-impl.so", "keep")
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl.so", "other")
        val r = AudioImplLibs.expose(root, {})
        assertTrue(r.copied.isEmpty())
        assertTrue(AudioImplLibs.IMPL_PLAIN in r.alreadyReachable)
        assertEquals("keep", File(root, "vendor/lib/android.hardware.audio@2.0-impl.so").readText())
    }

    @Test fun everyHostImplThatTheTreeHasIsExposed() {
        val root = tmp.newFolder()
        for (n in AudioImplLibs.HOST_IMPLS) put(root, "vendor/lib/hw/$n")
        val r = AudioImplLibs.expose(root, {})
        assertEquals(AudioImplLibs.HOST_IMPLS.size, r.copied.size)
        for (n in AudioImplLibs.HOST_IMPLS) assertTrue(n, File(root, "vendor/lib/$n").isFile)
    }

    @Test fun treeWithoutAnyAudioImpl_saysSoAndCopiesNothing() {
        val root = tmp.newFolder()
        val logs = ArrayList<String>()
        val r = AudioImplLibs.expose(root, { logs += it })
        assertTrue(r.copied.isEmpty())
        assertEquals(AudioImplLibs.HOST_IMPLS, r.absent)
        assertTrue(logs.any { it.contains("no android.hardware.audio@2.0 implementation") })
    }

    @Test fun systemVendorCopyIsFoundToo() {
        val root = tmp.newFolder()
        put(root, "system/vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so")
        val r = AudioImplLibs.expose(root, {})
        assertEquals(1, r.copied.size)
        assertTrue(File(root, "vendor/lib/android.hardware.audio@2.0-impl-mediatek.so").isFile)
    }

    @Test fun secondRunChangesNothing() {
        val root = tmp.newFolder()
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so")
        AudioImplLibs.expose(root, {})
        val logs = ArrayList<String>()
        val r = AudioImplLibs.expose(root, { logs += it })
        assertTrue(r.copied.isEmpty())
        assertFalse(logs.any { it.contains("copied into /vendor/lib") })
    }

     

    @Test fun mediatekBoard_plainRcGivesWay() {
        assertEquals(AudioImplLibs.RC_PLAIN, AudioImplLibs.rcToSetAside(
            hasPlainImpl = false, hasMediatekImpl = true, hasPlainRc = true, hasMediatekRc = true, hasMediatekService = true))
    }

    @Test fun boardWithThePlainImpl_nothingMoves() {
        assertNull(AudioImplLibs.rcToSetAside(
            hasPlainImpl = true, hasMediatekImpl = true, hasPlainRc = true, hasMediatekRc = true, hasMediatekService = true))
        assertNull(AudioImplLibs.rcToSetAside(
            hasPlainImpl = true, hasMediatekImpl = false, hasPlainRc = true, hasMediatekRc = false, hasMediatekService = false))
    }

    @Test fun noMediatekRcOrService_plainRcIsNotParked() {
         
        assertNull(AudioImplLibs.rcToSetAside(
            hasPlainImpl = false, hasMediatekImpl = true, hasPlainRc = true, hasMediatekRc = false, hasMediatekService = true))
        assertNull(AudioImplLibs.rcToSetAside(
            hasPlainImpl = false, hasMediatekImpl = true, hasPlainRc = true, hasMediatekRc = true, hasMediatekService = false))
    }

    @Test fun rcsToPark_onTheGoboLayout() {
        val root = tmp.newFolder()
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so")
        put(root, "vendor/bin/hw/android.hardware.audio@2.0-service-mediatek")
        put(root, "vendor/bin/hw/android.hardware.audio@2.0-service")
        put(root, "vendor/etc/init/android.hardware.audio@2.0-service-mediatek.rc")
        put(root, "vendor/etc/init/android.hardware.audio@2.0-service.rc")
        val park = AudioImplLibs.rcsToPark(root)
        assertEquals(listOf("vendor/etc/init/android.hardware.audio@2.0-service.rc"), park.map { it.relativeTo(root).path })
    }

    @Test fun rcsToPark_whenThePlainImplExists_isEmpty() {
        val root = tmp.newFolder()
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl.so")
        put(root, "vendor/lib/hw/android.hardware.audio@2.0-impl-mediatek.so")
        put(root, "vendor/bin/hw/android.hardware.audio@2.0-service-mediatek")
        put(root, "vendor/etc/init/android.hardware.audio@2.0-service-mediatek.rc")
        put(root, "vendor/etc/init/android.hardware.audio@2.0-service.rc")
        assertTrue(AudioImplLibs.rcsToPark(root).isEmpty())
    }
}
