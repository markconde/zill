package dev.mrkcnd.zill.ui

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A camera the screen doesn't report. The Galaxy Z Fold8's inner screen (SM-F971) has a punch-hole
 * camera but gives apps no display cutout at all (window and display both empty), so the stream's
 * bar ran under it. Samsung publishes no position, so this preset is measured from a photo of the
 * inner screen held as a book (the hinge upright, its natural orientation): the camera's ring spans
 * about 72.7–77.8% across and 0–6% down. With margin it is kept as a share of the panel, so it
 * survives a different resolution, and turned with the screen.
 *
 * Used only on that screen (600 dp and wider at its narrowest), only while the phone reports no
 * cutout, and only while the switch in Settings is on (it is by default).
 */
object CameraGap {
    /** The camera with a margin, as shares of the inner screen in its natural orientation. */
    private val FOLD8_INNER = floatArrayOf(0.715f, 0f, 0.790f, 0.075f)   // left, top, right, bottom

    private const val PREFS = "camera-gap"
    private const val KEY = "fold-preset"
    private val _on = MutableStateFlow(true)
    /** The Settings switch: leave room for the Fold's inner camera. */
    val on: StateFlow<Boolean> = _on
    private var loaded = false

    /** A debug build with `files/pretend-fold8` (made with run-as): an emulator acts as the Fold8's inner screen, for testing. */
    @Volatile private var pretend = false
    /** Pretending: the emulator's own cutout is ignored too, as the Fold8 reports none. */
    val pretending: Boolean get() = pretend

    /** This phone has a preset (the Fold8's inner screen), whatever screen is in use now. */
    val hasPreset: Boolean get() = pretend || Build.MANUFACTURER.equals("samsung", true) && Build.MODEL.uppercase().startsWith("SM-F971")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        pretend = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            java.io.File(app.filesDir, "pretend-fold8").exists()
        _on.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, on: Boolean) {
        _on.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * The preset camera on the screen now, in px of the current orientation, or null when it does
     * not apply (another phone, the cover screen, switched off). `w` and `h` are the display's real
     * size as it is turned now.
     */
    fun presetRect(rotation: Int, w: Int, h: Int, smallestWidthDp: Int): FloatArray? {
        if (!hasPreset || !_on.value || (smallestWidthDp < 600 && !pretend)) return null
        return fold8Rect(rotation, w, h)
    }

    /** The Fold8 inner camera's box (left, top, right, bottom px) on a screen of `w`×`h` px turned to `rotation`. Pure. */
    fun fold8Rect(rotation: Int, w: Int, h: Int): FloatArray {
        val natW = if (rotation % 2 == 0) w else h
        val natH = if (rotation % 2 == 0) h else w
        val r = FOLD8_INNER
        val l = r[0] * natW; val t = r[1] * natH; val rt = r[2] * natW; val b = r[3] * natH
        // Natural (x, y) on screen: 90 → (y, W − x); 180 → (W − x, H − y); 270 → (H − y, x).
        return when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(t, natW - rt, b, natW - l)
            Surface.ROTATION_180 -> floatArrayOf(natW - rt, natH - b, natW - l, natH - t)
            Surface.ROTATION_270 -> floatArrayOf(natH - b, l, natH - t, rt)
            else -> floatArrayOf(l, t, rt, b)
        }
    }

    /**
     * Insets (left, top, right, bottom, px) that keep clear of `rect`: the screen edge it is nearest,
     * by the depth it reaches from that edge.
     */
    fun insetsFor(rect: FloatArray, w: Int, h: Int): IntArray {
        val (left, top, right, bottom) = rect.toList()
        val toEdge = floatArrayOf(left, top, w - right, h - bottom)
        val edge = toEdge.indices.minBy { toEdge[it] }
        val out = IntArray(4)
        out[edge] = when (edge) {
            0 -> right; 1 -> bottom; 2 -> w - left; else -> h - top
        }.toInt().coerceAtLeast(0)
        return out
    }

    /** What Android's own configuration says the cutouts are (Samsung may keep the inner one there), for the report. */
    fun systemConfig(): String {
        val res = Resources.getSystem()
        fun str(name: String) = runCatching {
            val id = res.getIdentifier(name, "string", "android")
            if (id == 0) null else res.getString(id).trim().ifEmpty { null }
        }.getOrNull()
        fun arr(name: String) = runCatching {
            val id = res.getIdentifier(name, "array", "android")
            if (id == 0) null else res.getStringArray(id).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
        }.getOrNull()
        val parts = buildList {
            str("config_mainBuiltInDisplayCutout")?.let { add("main: $it") }
            str("config_mainBuiltInDisplayCutoutRectApproximation")?.let { add("main rect: $it") }
            arr("config_displayCutoutPathArray")?.forEachIndexed { i, p -> add("path $i: $p") }
            arr("config_displayCutoutApproximationRectArray")?.forEachIndexed { i, p -> add("rect $i: $p") }
            arr("config_displayUniqueIdArray")?.let { add("ids: ${it.joinToString()}") }
        }
        return if (parts.isEmpty()) "none" else parts.joinToString(" · ") { it.take(160) }
    }

    @Suppress("DEPRECATION")
    fun realSize(display: Display?): Pair<Int, Int>? {
        display ?: return null
        val p = android.graphics.Point()
        display.getRealSize(p)
        return p.x to p.y
    }
}
