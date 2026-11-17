/* Added for AEmulator Plus, 2026-11-09: two machines on one tab. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class SplitLayoutTest {
    @Test fun oppositeSides() {
        assertEquals('B', SplitLayout.opposite('T'))
        assertEquals('T', SplitLayout.opposite('B'))
        assertEquals('R', SplitLayout.opposite('L'))
        assertEquals('L', SplitLayout.opposite('R'))
    }

    @Test fun nearestEdgeWinsLikeSnappingAWindow() {
         
        assertEquals('T', SplitLayout.zoneAt(500f, 100f, 1000f, 2000f))
        assertEquals('B', SplitLayout.zoneAt(500f, 1900f, 1000f, 2000f))
        assertEquals('L', SplitLayout.zoneAt(100f, 1000f, 1000f, 2000f))
        assertEquals('R', SplitLayout.zoneAt(900f, 1000f, 1000f, 2000f))
         
        assertEquals('T', SplitLayout.zoneAt(100f, 100f, 1000f, 2000f))
    }

    @Test fun theMiddleIsNoSide() {
        assertNull(SplitLayout.zoneAt(500f, 1000f, 1000f, 2000f))
        assertNull(SplitLayout.zoneAt(10f, 10f, 0f, 0f))
    }

    @Test fun halvesLeaveAGapAndTheOtherDirectionIsFull() {
        assertEquals(SplitLayout.MATCH to 999, SplitLayout.paneSize('T', 1000, 2000, 2))
        assertEquals(SplitLayout.MATCH to 999, SplitLayout.paneSize('B', 1000, 2000, 2))
        assertEquals(499 to SplitLayout.MATCH, SplitLayout.paneSize('L', 1000, 2000, 2))
        assertEquals(499 to SplitLayout.MATCH, SplitLayout.paneSize('R', 1000, 2000, 2))
        assertEquals(SplitLayout.MATCH to SplitLayout.MATCH, SplitLayout.paneSize(null, 1000, 2000, 2))
    }

    @Test fun halfRectangles() {
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 100f), SplitLayout.paneRect('T', 100f, 200f), 0f)
        assertArrayEquals(floatArrayOf(0f, 100f, 100f, 200f), SplitLayout.paneRect('B', 100f, 200f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 50f, 200f), SplitLayout.paneRect('L', 100f, 200f), 0f)
        assertArrayEquals(floatArrayOf(50f, 0f, 100f, 200f), SplitLayout.paneRect('R', 100f, 200f), 0f)
    }
}
