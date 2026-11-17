package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

 
class EngineAssetsX86Test {
    private val engines = File("src/main/assets/engines")

    @Test fun layersOfAnAndroid11Guest() {
        assertEquals(listOf("engines/common-x86_64", "engines/n64-30/x86_64"), EngineAssets.x86_64Dirs("30", 30))
    }

    @Test fun androidTenGuestAlsoGetsTheAndroid11Stacks() {
        assertEquals(listOf("engines/common-x86_64", "engines/n64-29/x86_64", "engines/n64-30/x86_64"), EngineAssets.x86_64Dirs("29", 29))
    }

    @Test fun laterReleasesGetOnlyTheirOwnAndTheCommonFiles() {
        assertEquals(listOf("engines/common-x86_64", "engines/n64-34/x86_64"), EngineAssets.x86_64Dirs("34", 34))
    }

    @Test fun noOldDirectoryLeft() {
        assertEquals(false, File(engines, "n64-x86_64").exists())
    }

    @Test fun androidElevenStacksAreInTheirDirectory() {
        val d = File(engines, "n64-30/x86_64")
        for (n in listOf("vendor#bin#hw#android.hardware.audio@2.0-service", "vendor#bin#hw#android.hardware.graphics.composer@2.1-service",
            "vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so", "vendor#lib64#android.hardware.graphics.composer@2.1-resources.so",
            "vendor#etc#vintf#manifest#aemu.composer.xml", "vendor#lib64#hw#android.hardware.graphics.mapper@2.0-impl.so")) {
            assertEquals(n, true, File(d, n).isFile)
        }
    }

    @Test fun releaseIndependentFilesAreInCommon() {
        val d = File(engines, "common-x86_64")
        for (n in listOf("vendor#lib64#hw#hwcomposer.default.so", "vendor#lib64#hw#sensors.default.so", "system#lib64#egl#libGLES_aemu.so", "libc_scudo_stub.so")) {
            assertEquals(n, true, File(d, n).isFile)
        }
    }

     
    @Test fun x86ComposerServiceAsksForTheSixtyFourBitThreadpoolSymbol() {
        val b = File(engines, "n64-30/x86_64/vendor#bin#hw#android.hardware.graphics.composer@2.1-service").readBytes().toString(Charsets.ISO_8859_1)
        assertEquals(true, b.contains("_ZN7android8hardware22configureRpcThreadpoolEmb"))
        assertEquals(false, b.contains("_ZN7android8hardware22configureRpcThreadpoolEjb"))
    }
     
 
 
 
 
    @Test fun x86GrallocLeavesTheNoteAndHwcShowsTheHostFrame() {
        fun text(f: File) = f.readBytes().toString(Charsets.ISO_8859_1)
        for (d in listOf("common-x86_64", "n64-30/x86_64")) {
            assertEquals("$d gralloc", true, text(File(engines, "$d/vendor#lib64#hw#gralloc.default.so")).contains("/dev/socket/aemu_gl"))
        }
        val hwc = text(File(engines, "common-x86_64/vendor#lib64#hw#hwcomposer.default.so"))
        assertEquals(true, hwc.contains("debug.aemu.gl"))
        assertEquals(true, hwc.contains("/dev/socket/aemu_gl"))
    }
}
