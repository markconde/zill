package dev.mrkcnd.sillclient

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pinch to zoom with real two-finger gestures, against the stand-in Mac (StandInServe) already
 * paired as "Stand-in Mac". Screenshots go to the app's files dir (zoom-*.png) for a look.
 * Skipped when that Mac isn't listed.
 *
 *   ./gradlew connectedDebugAndroidTest
 */
class ZoomOnDeviceTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = ctx.getExternalFilesDir(null)!!

    @Test fun pinch_zooms_and_moves_the_picture() {
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val row = device.wait(Until.findObject(By.text("Stand-in Mac")), 10_000)
        assumeTrue("pair the stand-in Mac first", row != null)
        row.click()
        val screen = device.wait(Until.findObject(By.desc("Mac screen")), 10_000)!!
        Thread.sleep(3000)
        shot("zoom-1-whole")
        screen.pinchOpen(0.6f, 400)
        Thread.sleep(800)
        shot("zoom-2-in")
        // A pinch is the picture's alone: the stand-in's log should show no input (kind 8) for it.
        screen.pinchOpen(0.3f, 400)
        Thread.sleep(800)
        shot("zoom-3-more")
        screen.pinchClose(1f, 400)
        Thread.sleep(800)
        shot("zoom-4-out")
    }

    private fun shot(name: String) {
        device.takeScreenshot(File(out, "$name.png"))
    }

    /**
     * Two fingers dragged side by side: on the whole picture the Mac's scroll; on a zoomed one, the
     * picture moves and nothing goes to the Mac. Read the stand-in's log for the scrolls (kind 8):
     * the test logs "two-finger drag" markers to logcat (tag SillTest) around each.
     */
    @Test fun two_finger_drag_scrolls_or_moves() {
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val row = device.wait(Until.findObject(By.text("Stand-in Mac")), 10_000)
        assumeTrue("pair the stand-in Mac first", row != null)
        row.click()
        val screen = device.wait(Until.findObject(By.desc("Mac screen")), 10_000)!!
        Thread.sleep(3000)
        val b = screen.visibleBounds
        android.util.Log.i("SillTest", "two-finger drag, whole picture: start")
        twoFingerDrag(b.centerX().toFloat(), b.centerY().toFloat(), 0f, 300f)
        Thread.sleep(1500)
        android.util.Log.i("SillTest", "two-finger drag, whole picture: end")
        screen.pinchOpen(0.6f, 400)
        Thread.sleep(800)
        shot("drag-1-zoomed")
        android.util.Log.i("SillTest", "two-finger drag, zoomed: start")
        twoFingerDrag(b.centerX().toFloat(), b.centerY().toFloat(), 250f, 0f)
        Thread.sleep(1500)
        android.util.Log.i("SillTest", "two-finger drag, zoomed: end")
        shot("drag-2-moved")
    }

    /** Two fingers 200 px apart, moved together by (dx, dy) over 20 steps. */
    private fun twoFingerDrag(x: Float, y: Float, dx: Float, dy: Float) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(t: Float) = Array(2) { i ->
            MotionEvent.PointerCoords().apply { this.x = x - 100 + 200 * i + dx * t; this.y = y + dy * t; pressure = 1f; size = 1f }
        }
        fun ev(action: Int, n: Int, t: Float, at: Long) = MotionEvent.obtain(down, at, action, n, props, coords(t), 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        var at = down
        ui.injectInputEvent(ev(MotionEvent.ACTION_DOWN, 1, 0f, at), true)
        ui.injectInputEvent(ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f, at), true)
        for (step in 1..20) {
            at += 16
            ui.injectInputEvent(ev(MotionEvent.ACTION_MOVE, 2, step / 20f, at), true)
            SystemClock.sleep(16)
        }
        ui.injectInputEvent(ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f, at), true)
        ui.injectInputEvent(ev(MotionEvent.ACTION_UP, 1, 1f, at), true)
    }
}
