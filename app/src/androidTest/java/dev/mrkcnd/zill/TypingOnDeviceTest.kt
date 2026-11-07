package dev.mrkcnd.zill

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The soft keyboard's calls, as an input method makes them, on the stream's own input connection,
 * against the stand-in Mac (StandInServe) already paired as "Stand-in Mac". Read the stand-in's log
 * (kind 8) for what reached the Mac; the test logs each step to logcat (tag SillTest).
 *
 * Expected, in order: text c, a, f, é · 한 once (composition withheld) · 日本 once · 😀 · " teh",
 * 3 backspaces, "the" (a correction) · 1 backspace · Return · 1 backspace (past what was typed).
 */
class TypingOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val ctx = inst.targetContext

    @Test fun keyboard_calls_reach_the_mac_as_text_and_backspaces() {
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val row = device.wait(Until.findObject(By.text("Stand-in Mac")), 10_000)
        assumeTrue("pair the stand-in Mac first", row != null)
        row.click()
        device.wait(Until.findObject(By.desc("Mac screen")), 10_000)!!
        Thread.sleep(3000)

        var ic: InputConnection? = null
        inst.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first() as Activity
            val view = find(activity.window.decorView) { it.contentDescription == "Mac screen" }!!
            view.requestFocus()
            ic = view.onCreateInputConnection(EditorInfo())
        }
        val c = ic!!
        fun step(name: String, block: InputConnection.() -> Unit) {
            android.util.Log.i("SillTest", "typing: $name")
            inst.runOnMainSync { c.block() }
            Thread.sleep(300)
        }
        step("café") { listOf("c", "a", "f", "é").forEach { commitText(it, 1) } }
        step("Korean 한") { setComposingText("ㅎ", 1); setComposingText("하", 1); setComposingText("한", 1); finishComposingText() }
        step("Japanese 日本") { setComposingText("に", 1); setComposingText("にほ", 1); setComposingText("にほん", 1); commitText("日本", 1) }
        step("emoji") { commitText("😀", 1) }
        step("autocorrect teh→the") {
            commitText(" teh", 1)
            val n = getTextBeforeCursor(1000, 0)!!.length
            beginBatchEdit(); setComposingRegion(n - 3, n); commitText("the", 1); endBatchEdit()
        }
        step("backspace") { deleteSurroundingText(1, 0) }
        step("return") { commitText("\n", 1) }
        Thread.sleep(500)
        step("backspace past what was typed") { deleteSurroundingText(1, 0) }
        Thread.sleep(1000)
    }

    private fun find(v: View, match: (View) -> Boolean): View? {
        if (match(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), match)?.let { return it }
        return null
    }
}
