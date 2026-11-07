package dev.mrkcnd.sillclient.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON payloads, shaped as Swift's synthesized Codable writes them (enums with associated
 * values become `{"case":{"_0":…, "label":…}}`). See the Swift files in Sources/StreamProtocol.
 */

const val PROTOCOL_VERSION = 1

// MARK: client → host

sealed class StreamSource {
    object None : StreamSource()
    object Desktop : StreamSource()
    data class Window(val id: Long) : StreamSource()

    fun json(): JSONObject = when (this) {
        None -> JSONObject().put("none", JSONObject())
        Desktop -> JSONObject().put("desktop", JSONObject())
        is Window -> JSONObject().put("window", JSONObject().put("_0", id))
    }

    companion object {
        fun parse(o: JSONObject?): StreamSource = when {
            o == null -> None
            o.has("desktop") -> Desktop
            o.has("window") -> o.optJSONObject("window")?.optLong("_0", -1)?.takeIf { it >= 0 }?.let { Window(it) } ?: None
            else -> None
        }
    }
}

enum class PointerAction(val wire: String) { MOVE("move"), LEFT_DOWN("leftDown"), LEFT_UP("leftUp"), RIGHT_DOWN("rightDown"), RIGHT_UP("rightUp") }

enum class ScrollPhase(val wire: String) { BEGAN("began"), ENDED("ended"), MOMENTUM_BEGAN("momentumBegan"), MOMENTUM_ENDED("momentumEnded") }

/** InputEvent (Input.swift). Positions are fractions of the streamed frame, 0…1. */
sealed class InputEvent {
    data class Pointer(val action: PointerAction, val x: Double, val y: Double) : InputEvent()
    /** dx, dy: fractions of the frame; positive dy moves the content down, as a finger dragged down does. */
    data class Scroll(val x: Double, val y: Double, val dx: Double, val dy: Double) : InputEvent()
    data class Text(val text: String) : InputEvent()
    data class ScrollGesture(val phase: ScrollPhase, val x: Double, val y: Double) : InputEvent()
    data class Key(val hidUsage: Int, val down: Boolean, val modifiers: Long) : InputEvent()

    fun json(): JSONObject = when (this) {
        is Pointer -> JSONObject().put("pointer", JSONObject().put("_0", action.wire).put("x", x).put("y", y))
        is Scroll -> JSONObject().put("scroll", JSONObject().put("x", x).put("y", y).put("dx", dx).put("dy", dy))
        is Text -> JSONObject().put("text", JSONObject().put("_0", text))
        is ScrollGesture -> JSONObject().put("scrollGesture", JSONObject().put("_0", phase.wire).put("x", x).put("y", y))
        is Key -> JSONObject().put("key", JSONObject().put("hidUsage", hidUsage).put("down", down).put("modifiers", modifiers))
    }
}

/** Kind 23. `audio` lists the codecs this device plays, so the Mac sends it its sound (kind 43). */
fun helloJson(appVersion: String, build: String, device: String, audio: List<String> = AudioCodec.DEVICE_PLAYS): JSONObject =
    JSONObject().put("appVersion", appVersion).put("build", build).put("protocol", PROTOCOL_VERSION).put("device", device)
        .put("audio", JSONArray(audio))

/**
 * Kind 9. `scale` is the text scale (Aa), device points per Mac point, not the screen's density:
 * null leaves the Mac window alone, and a scale has the Mac size the window to the panel ÷ scale.
 */
fun viewportJson(width: Double, height: Double, scale: Double?, fps: Int?): JSONObject {
    val o = JSONObject()
        .put("width", width.coerceIn(1.0, 16384.0))
        .put("height", height.coerceIn(1.0, 16384.0))
        .put("localCursor", false)
    if (scale != null && scale.isFinite() && scale > 0) o.put("scale", scale.coerceIn(0.25, 4.0))
    if (fps != null) o.put("fps", fps)
    return o
}

fun clientStatsJson(fps: Int, frameAgeMs: Int, rttMs: Int, device: String): JSONObject =
    JSONObject().put("fps", fps).put("frameAgeMs", frameAgeMs).put("rttMs", rttMs).put("device", device)

fun windowCommandJson(id: Long, action: String): JSONObject = JSONObject().put("id", id).put("action", action)

fun gestureJson(name: String, fingers: Int): JSONObject = JSONObject().put("gesture", name).put("fingers", fingers)

object Gestures {
    const val SWIPE_UP = "swipeUp"
    const val SWIPE_DOWN = "swipeDown"
    const val SWIPE_LEFT = "swipeLeft"
    const val SWIPE_RIGHT = "swipeRight"
    const val PINCH = "pinch"
    const val SPREAD = "spread"
}

// MARK: host → client

data class WindowInfo(val id: Long, val title: String, val appName: String, val bundleID: String, val width: Int, val height: Int)

data class WindowList(
    val macName: String,
    val windows: List<WindowInfo>,
    val active: StreamSource,
    val hostVersion: String?,
    val gestures: Int,
    val clipboard: Int,
    val locked: Boolean,
    val offConsole: Boolean,
) {
    companion object {
        fun parse(bytes: ByteArray): WindowList? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until arr.length()).mapNotNull { i ->
                val w = arr.optJSONObject(i) ?: return@mapNotNull null
                WindowInfo(
                    w.optLong("id"), w.optString("title"), w.optString("appName"), w.optString("bundleID"),
                    w.optInt("width"), w.optInt("height"),
                )
            }
            WindowList(
                macName = o.optString("macName"),
                windows = windows,
                active = StreamSource.parse(o.optJSONObject("active")),
                hostVersion = o.optString("hostVersion").takeIf { it.isNotEmpty() },
                gestures = o.optInt("gestures", 0),
                clipboard = o.optInt("clipboard", 0),
                locked = o.optBoolean("locked", false),
                offConsole = o.optBoolean("offConsole", false),
            )
        }.getOrNull()
    }
}

data class AppInfo(val name: String, val bundleID: String)

fun parseAppList(bytes: ByteArray): List<AppInfo> = runCatching {
    val arr = JSONArray(String(bytes, Charsets.UTF_8))
    (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { AppInfo(it.optString("name"), it.optString("bundleID")) }
    }
}.getOrDefault(emptyList())

data class Goodbye(val reason: String, val message: String?, val reconnect: Boolean?) {
    /** The words to show, as the Mac's message when it sent one, else our own for a known reason. */
    fun text(): String = message?.takeIf { it.isNotBlank() } ?: when (reason) {
        "removed" -> "This device was removed from the Mac's paired devices. Pair it again to connect."
        "remoteOff" -> "Remote Access was turned off on the Mac."
        "internetOff" -> "The Mac stopped taking connections from the internet."
        "quit" -> "Sill was quit on the Mac."
        "busy" -> "The Mac is busy with another device."
        "pairingRequired" -> "The Mac now requires pairing. Pair this device to keep using it."
        "update" -> "The Mac needs a newer version of this app."
        else -> "The Mac ended the session ($reason)."
    }

    companion object {
        fun parse(bytes: ByteArray): Goodbye? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Goodbye(
                o.optString("reason"),
                o.optString("message").takeIf { o.has("message") },
                if (o.has("reconnect")) o.optBoolean("reconnect") else null,
            )
        }.getOrNull()
    }
}

/**
 * Kind 26: the Mac's pointer while this device is not the one moving it. `seen` is how many input
 * messages (kind 8) the Mac had read on this connection when it sent this: a report with fewer than
 * this device has sent is older than its latest input, and is dropped.
 */
data class MacPointer(val x: Double?, val y: Double?, val inside: Boolean, val seen: Long = 0) {
    /** Where to draw it, a fraction of the frame; null when it is off the stream. */
    val position: Pair<Double, Double>?
        get() = if (inside && x != null && y != null && x.isFinite() && y.isFinite()) x to y else null
}

fun parseMacPointer(bytes: ByteArray): MacPointer? = runCatching {
    val o = JSONObject(String(bytes, Charsets.UTF_8))
    MacPointer(
        if (o.has("x")) o.optDouble("x") else null,
        if (o.has("y")) o.optDouble("y") else null,
        o.optBoolean("inside", false),
        o.optLong("seen", 0),
    )
}.getOrNull()

fun parseClipboardText(bytes: ByteArray): String? = runCatching {
    val o = JSONObject(String(bytes, Charsets.UTF_8))
    if (o.has("text")) o.getString("text") else null
}.getOrNull()

/** HostSettingsState: only the few fields this client shows. */
data class HostSettingsSummary(val maxFPS: Int?, val bitrate: Int?, val captureScale: Double?)

fun parseHostSettings(bytes: ByteArray): HostSettingsSummary? = runCatching {
    val o = JSONObject(String(bytes, Charsets.UTF_8))
    val s = o.optJSONObject("settings") ?: o
    HostSettingsSummary(
        if (s.has("maxFPS")) s.optInt("maxFPS") else null,
        if (s.has("bitrate")) s.optInt("bitrate") else null,
        if (s.has("captureScale")) s.optDouble("captureScale") else null,
    )
}.getOrNull()
