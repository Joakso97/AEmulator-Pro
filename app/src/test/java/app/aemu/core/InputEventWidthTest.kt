package app.aemu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputEventWidthTest {
    @Test fun sixtyFourBitFirmwareOnNativeEngineGetsWideEvents() =
        assertTrue(InputService.wideFor(Engine.N64, pure32Tree = false))

     
    @Test fun thirtyTwoBitFirmwareOnNativeEngineGetsNarrowEvents() =
        assertFalse(InputService.wideFor(Engine.N64, pure32Tree = true))

    @Test fun universalEngineGetsNarrowEvents() {
        assertFalse(InputService.wideFor(Engine.KK, pure32Tree = false))
        assertFalse(InputService.wideFor(Engine.GB, pure32Tree = true))
    }
}
