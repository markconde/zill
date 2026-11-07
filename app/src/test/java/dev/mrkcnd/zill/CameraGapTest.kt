package dev.mrkcnd.zill

import dev.mrkcnd.zill.ui.CameraGap
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The Fold8 inner camera's preset box, turned with the screen, and the edge it pushes the bars from. */
class CameraGapTest {
    private val w = 2448; private val h = 1848   // the inner screen as a book (natural)

    @Test fun naturalIsTopRightOfCentre() {
        val r = CameraGap.fold8Rect(0, w, h)
        assertArrayEquals(floatArrayOf(1750.3f, 0f, 1933.9f, 138.6f), r, 0.5f)
        assertArrayEquals(intArrayOf(0, 138, 0, 0), CameraGap.insetsFor(r, w, h))
    }

    @Test fun turnedEachWayItFindsItsEdge() {
        // 90: the natural top shows at the screen's left; 270: at its right; 180: at its bottom.
        assertArrayEquals(intArrayOf(138, 0, 0, 0), CameraGap.insetsFor(CameraGap.fold8Rect(1, h, w), h, w))
        assertArrayEquals(intArrayOf(0, 0, 138, 0), CameraGap.insetsFor(CameraGap.fold8Rect(3, h, w), h, w))
        assertArrayEquals(intArrayOf(0, 0, 0, 138), CameraGap.insetsFor(CameraGap.fold8Rect(2, w, h), w, h))
    }
}
