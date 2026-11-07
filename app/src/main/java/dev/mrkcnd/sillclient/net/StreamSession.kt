package dev.mrkcnd.sillclient.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.mrkcnd.sillclient.protocol.AppInfo
import dev.mrkcnd.sillclient.protocol.Goodbye
import dev.mrkcnd.sillclient.protocol.InputEvent
import dev.mrkcnd.sillclient.protocol.Kind
import dev.mrkcnd.sillclient.protocol.MacInfo
import dev.mrkcnd.sillclient.protocol.MacPointer
import dev.mrkcnd.sillclient.protocol.ParameterSets
import dev.mrkcnd.sillclient.protocol.StreamSource
import dev.mrkcnd.sillclient.protocol.Wire
import dev.mrkcnd.sillclient.protocol.WindowList
import dev.mrkcnd.sillclient.protocol.clientStatsJson
import dev.mrkcnd.sillclient.protocol.gestureJson
import dev.mrkcnd.sillclient.protocol.helloJson
import dev.mrkcnd.sillclient.protocol.median
import dev.mrkcnd.sillclient.protocol.MacMenu
import dev.mrkcnd.sillclient.protocol.HostSettingsState
import dev.mrkcnd.sillclient.protocol.SettingsChange
import dev.mrkcnd.sillclient.protocol.MenuItem
import dev.mrkcnd.sillclient.protocol.fetchMenuJson
import dev.mrkcnd.sillclient.protocol.menuRows
import dev.mrkcnd.sillclient.protocol.pressMenuItemJson
import dev.mrkcnd.sillclient.protocol.AudioMessage
import dev.mrkcnd.sillclient.protocol.CursorShape
import dev.mrkcnd.sillclient.audio.AudioPlayer
import dev.mrkcnd.sillclient.protocol.parseAppList
import dev.mrkcnd.sillclient.protocol.parseClipboardText
import dev.mrkcnd.sillclient.protocol.parseMacPointer
import dev.mrkcnd.sillclient.protocol.viewportJson
import dev.mrkcnd.sillclient.protocol.windowCommandJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Where and how to reach a Mac for one session. */
data class Route(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val pin: ByteArray?,       // null with tls: any key (a home door with pairing off)
    val label: String,         // "Wi-Fi", "Remote", "Manual"
)

/**
 * One session with a Mac (ALPN sill/1 over TLS, or a plain old home door): the hello first, then the
 * source and the viewport; video, the window catalog and the rest come back. Pings four times a
 * second, stats once a second, and asks for a keyframe when the picture waits for one.
 */
class StreamSession(
    private val route: Route,
    private val identity: DeviceIdentity,
    private val deviceName: String,
    private val appVersion: String,
    private val build: String,
) {
    interface VideoSink {
        fun onParameterSets(ps: ParameterSets)
        fun onFrame(payload: ByteArray, keyframe: Boolean)
        val needsKeyframe: Boolean
    }

    sealed class State {
        object Connecting : State()
        object Live : State()
        data class Ended(val message: String, val goodbye: Goodbye? = null, val error: Boolean = false) : State()
    }

    val state = MutableStateFlow<State>(State.Connecting)
    val windowList = MutableStateFlow<WindowList?>(null)
    val thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val icons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val rttMs = MutableStateFlow(0)
    val fps = MutableStateFlow(0)
    /**
     * The Mac's pointer while the Mac (or another device) moved it last; null while this device
     * did, or before the Mac said where it is. Draw the sprite only while this has a position.
     */
    val macPointer = MutableStateFlow<MacPointer?>(null)
    /** The Mac's cursor image (kind 14): the sprite's shape, and a mouse's pointer icon here. */
    val cursorShape = MutableStateFlow<CursorShape?>(null)
    /** The Mac's sound (kind 43). Muting is this device's alone: the Mac keeps sending. */
    val audio = AudioPlayer()
    /** Input messages (kind 8) sent on this connection: a kind 26 report with fewer is stale. */
    private val inputsSent = java.util.concurrent.atomic.AtomicLong(0)
    val macInfo = MutableStateFlow<MacInfo?>(null)
    private val _clipboardFromMac = MutableStateFlow<String?>(null)
    val clipboardFromMac: StateFlow<String?> = _clipboardFromMac
    /** The Mac's key as this connection saw it (TLS only). */
    @Volatile var macFingerprint: ByteArray? = null
        private set

    @Volatile var videoSink: VideoSink? = null
    private var pendingParams: ParameterSets? = null

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sill-write") }
    private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "sill-timer") }
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    private val framesThisSecond = AtomicInteger(0)
    /** This second's samples: frame ages (true time minus the Mac's stamp) and round trips, in ms. */
    private val ageSamples = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val rttSamples = java.util.Collections.synchronizedList(ArrayList<Int>())
    /** How far behind the Mac the picture arrives: last second's median, null while unknown. */
    val frameAgeMs = MutableStateFlow<Int?>(null)
    @Volatile private var lastKeyframeAsk = 0L
    @Volatile private var lastKeyframeAt = System.nanoTime()
    private var pinger: ScheduledFuture<*>? = null
    private var stats: ScheduledFuture<*>? = null
    private var source: StreamSource = StreamSource.Desktop
    private var lastViewport: JSONObject? = null
    private var subscribedClipboard = false
    /** The window list's `clipboard`: 1 or more when the Mac shares its clipboard. */
    private var hostClipboard = 0

    // MARK: the Mac's settings (kinds 16, 17)

    /** The Mac's settings as it last reported them to this connection; null from a Mac without settings. */
    val hostSettings = MutableStateFlow<HostSettingsState?>(null)
    /** Changes sent and not yet answered, by token: the panel shows them over the Mac's values. */
    val pendingSettings = MutableStateFlow<Map<Int, SettingsChange>>(emptyMap())
    /** A change the Mac didn't take, for a one-line notice; the UI clears it once shown. */
    val settingsNotice = MutableStateFlow<String?>(null)

    /** Set one setting on the Mac. Never a field the Mac didn't report. */
    fun changeSettings(c: SettingsChange) {
        val now = hostSettings.value?.settings ?: return
        if ((c.directWireless != null && now.directWireless == null) || (c.sendAudio != null && now.sendAudio == null)) return
        val token = settingsTokens.incrementAndGet()
        pendingSettings.value = pendingSettings.value + (token to c)
        send(Kind.CHANGE_SETTINGS, c.json(token).toString().toByteArray())
        runCatching {
            timers.schedule({
                if (pendingSettings.value.containsKey(token)) {
                    pendingSettings.value = pendingSettings.value - token
                    settingsNotice.value = "${windowList.value?.macName?.ifBlank { null } ?: "The Mac"} didn’t answer that change."
                }
            }, 6, TimeUnit.SECONDS)
        }
    }

    private fun onHostSettings(h: HostSettingsState) {
        hostSettings.value = h
        val token = h.answering ?: return
        // The answer to one change: whatever is still pending from before it was answered too.
        val pending = pendingSettings.value
        val answered = pending.filterKeys { it <= token }
        pendingSettings.value = pending - answered.keys
        val mine = answered[token] ?: return
        if (!mine.takenBy(h.settings)) settingsNotice.value = "The Mac kept its setting."
    }

    // MARK: the Mac's menus (kinds 24, 25, 27)

    /** The streamed app's menu bar as the Mac last sent it; null until the first one (an older Mac sends none). */
    val macMenu = MutableStateFlow<MacMenu?>(null)
    /** A refused choice, for a one-line notice; the UI clears it once shown. */
    val menuNotice = MutableStateFlow<String?>(null)

    /** What opening one menu gives: its rows, or a note to show instead (or above them, when stale). */
    class MenuContents(val items: List<MenuItem>, val more: Int, val note: String?, val stale: Boolean)

    private val menuTokens = java.util.concurrent.atomic.AtomicInteger(0)
    private val settingsTokens = SETTINGS_TOKENS
    private var menusAsked = false
    private class PendingFetch(val version: Int?, val done: (MenuContents) -> Unit)
    private val pendingFetches = java.util.concurrent.ConcurrentHashMap<Int, PendingFetch>()
    private val pendingPresses = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val menusChanged = "The menus changed. Open the menu again."

    /**
     * One menu's items, fetched now (the Mac reads it from at most 1 s ago). `done` runs once, on
     * the session's reader or timer thread: with the items, the Mac's note, or a timeout's words.
     */
    fun fetchMenu(item: MenuItem, version: Int?, done: (MenuContents) -> Unit) {
        val top = macMenu.value
        val id = item.id
        when {
            top == null || id == null -> { done(MenuContents(emptyList(), 0, "Not connected.", false)); return }
            top.version != version -> { done(MenuContents(emptyList(), 0, menusChanged, false)); return }
            top.stale -> { done(MenuContents(emptyList(), 0, top.note ?: "The app isn't answering.", true)); return }
        }
        val token = menuTokens.incrementAndGet()
        pendingFetches[token] = PendingFetch(version, done)
        send(Kind.FETCH_MENU, fetchMenuJson(token, version, id, item.title).toString().toByteArray())
        val wait = maxOf(4000L, 4L * (rttMs.value.toLong()))
        runCatching {
            timers.schedule({
                pendingFetches.remove(token)?.done?.invoke(
                    MenuContents(emptyList(), 0, "${windowList.value?.macName?.ifBlank { null } ?: "The Mac"} didn’t answer. Open the menu again.", false))
            }, wait, TimeUnit.MILLISECONDS)
        }
    }

    /** Choose an item. Never a disabled one; while the app isn't answering, refused here, unsent. */
    fun pressMenuItem(item: MenuItem, version: Int?) {
        val top = macMenu.value ?: return
        val id = item.id ?: return
        if (!item.enabled || item.submenu || item.separator) return
        if (top.stale) { menuNotice.value = top.note ?: "The app isn't answering."; return }
        val token = menuTokens.incrementAndGet()
        pendingPresses += token
        send(Kind.PRESS_MENU_ITEM, pressMenuItemJson(token, version, id, item.title).toString().toByteArray())
    }

    private fun onMacMenu(m: MacMenu) {
        if (m.isTopLevel) {
            val was = macMenu.value?.version
            macMenu.value = m
            if (was != m.version) {
                // A new version: every menu still being fetched names a place in the old tree.
                for (t in pendingFetches.keys.toList()) {
                    val f = pendingFetches[t] ?: continue
                    if (f.version != m.version) pendingFetches.remove(t)?.done?.invoke(MenuContents(emptyList(), 0, menusChanged, false))
                }
            }
        }
        val token = m.answering ?: return
        pendingFetches.remove(token)?.let { f ->
            val contents = if (m.version != f.version) MenuContents(emptyList(), 0, menusChanged, false)
            else MenuContents(menuRows(m.items ?: emptyList()), m.more, m.note, m.stale)
            f.done(contents)
        }
        if (pendingPresses.remove(token) && m.pressed == false) {
            menuNotice.value = m.note ?: "The Mac didn’t choose that item."
        }
    }

    fun start(initialSource: StreamSource) {
        source = initialSource
        thread(name = "sill-read") { run() }
    }

    private fun run() {
        try {
            val conn = if (route.tls) Tls.connect(route.host, route.port, Tls.SESSION_ALPN, route.pin, identity)
            else Tls.plain(route.host, route.port)
            macFingerprint = conn.macFingerprint
            val s = conn.socket
            socket = s
            out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            if (closed) { s.close(); return }
            synchronized(this) {
                // The hello is the first message of every session connection (Compatibility.swift).
                send(Kind.HELLO, helloJson(appVersion, build, deviceName).toString().toByteArray())
                send(Kind.SELECT_SOURCE, source.json().toString().toByteArray())
                lastViewport?.let { send(Kind.VIEWPORT, it.toString().toByteArray()) }
                ready = true
            }
            pinger = timers.scheduleAtFixedRate({ ping() }, 250, 250, TimeUnit.MILLISECONDS)
            stats = timers.scheduleAtFixedRate({ reportStats() }, 1, 1, TimeUnit.SECONDS)
            val input = DataInputStream(s.getInputStream().buffered(256 * 1024))
            while (!closed) {
                val m = Wire.read(input)
                handle(m.kind, m.timestamp, m.isKeyframe, m.payload)
            }
        } catch (e: Exception) {
            if (!closed) {
                Log.w(TAG, "session ended", e)
                val current = state.value
                if (current !is State.Ended) {
                    val text = when {
                        e is EOFException -> "The Mac closed the connection."
                        e.cause is Tls.PinMismatch || e is Tls.PinMismatch ->
                            "This Mac presented a different key than the one you paired with. Forget it and pair again if you trust it."
                        e is javax.net.ssl.SSLHandshakeException ->
                            "The Mac refused this device. If it requires pairing, pair first. (${e.message})"
                        else -> "Connection lost: ${e.message ?: e.javaClass.simpleName}"
                    }
                    state.value = State.Ended(text, error = true)
                }
            }
        } finally {
            shutdown()
        }
    }

    private fun handle(kind: Int, ts: Double, key: Boolean, p: ByteArray) {
        when (kind) {
            Kind.PARAMETER_SETS -> ParameterSets.decode(p)?.let { ps ->
                pendingParams = ps
                videoSink?.onParameterSets(ps)
            }
            Kind.FRAME -> {
                framesThisSecond.incrementAndGet()
                // The Mac's stamp is its NTP-kept clock; this device's clock is corrected by ClockSync.
                // Unknown until measured: a raw difference would mostly be the clocks' disagreement.
                ClockSync.offsetMs?.let { off ->
                    val age = (Wire.now() * 1000 + off - ts * 1000)
                    if (age > -50 && age < 60_000) ageSamples += age.toInt().coerceAtLeast(0)
                }
                if (key) lastKeyframeAt = System.nanoTime()
                videoSink?.onFrame(p, key)
            }
            Kind.WINDOW_LIST -> WindowList.parse(p)?.let {
                windowList.value = it
                if (state.value is State.Connecting) state.value = State.Live
                hostClipboard = it.clipboard
                updateClipboardSubscription()
                // The menus: asked once, after the first window list; an older Mac ignores it.
                if (!menusAsked) {
                    menusAsked = true
                    send(Kind.FETCH_MENU, fetchMenuJson(menuTokens.incrementAndGet()).toString().toByteArray())
                }
            }
            Kind.MAC_MENU -> MacMenu.parse(p)?.let(::onMacMenu)
            Kind.HOST_SETTINGS -> HostSettingsState.parse(p)?.let(::onHostSettings)
            Kind.THUMBNAIL -> dev.mrkcnd.sillclient.protocol.ImageBlob.thumbnail(p)?.let { (id, jpeg) ->
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { thumbnails.value = thumbnails.value + (id to it) }
            }
            Kind.APP_ICON -> dev.mrkcnd.sillclient.protocol.ImageBlob.icon(p)?.let { (id, png) ->
                BitmapFactory.decodeByteArray(png, 0, png.size)?.let { icons.value = icons.value + (id to it) }
            }
            Kind.APP_LIST -> apps.value = parseAppList(p)
            Kind.PONG -> if (p.size == 8) {
                val sent = ByteBuffer.wrap(p).double
                rttMs.value = ((System.nanoTime() / 1e9 - sent) * 1000).toInt().coerceAtLeast(0)
                rttSamples += rttMs.value
            }
            Kind.MAC_INFO -> MacInfo.verify(p)?.let { info ->
                // Only a Mac whose key is the one this connection pinned may tell us where to find it.
                val fp = macFingerprint
                if (fp != null && fp.contentEquals(info.fingerprint)) macInfo.value = info
            }
            Kind.GOODBYE -> Goodbye.parse(p)?.let { g -> state.value = State.Ended(g.text(), g) }
            Kind.MAC_POINTER -> parseMacPointer(p)?.let { mp ->
                // Built before the Mac read this device's latest input: this device moved it since.
                if (mp.seen >= inputsSent.get()) macPointer.value = mp
            }
            Kind.CURSOR_SHAPE -> CursorShape.parse(p)?.let { cursorShape.value = it }
            Kind.AUDIO -> when (val a = AudioMessage.parse(p)) {
                is AudioMessage.Format -> audio.format(a.format)
                is AudioMessage.Packets -> audio.packets(a.packets)
                null -> Unit
            }
            Kind.CLIPBOARD -> if (ClipboardShare.on.value) parseClipboardText(p)?.let { _clipboardFromMac.value = it }
            else -> Unit // ticks, settings, menus: not used by this client
        }
    }

    // MARK: sending

    fun send(kind: Int, payload: ByteArray = ByteArray(0)) {
        if (closed) return
        val bytes = Wire.serialize(kind, payload)
        writer.execute {
            val o = out ?: return@execute
            try {
                o.write(bytes); o.flush()
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                close()
            }
        }
    }

    /** Kind 8. This device now has the pointer: the Mac's sprite hides until the Mac moves it. */
    fun sendInput(e: InputEvent) {
        if (closed) return
        inputsSent.incrementAndGet()
        macPointer.value = null
        send(Kind.INPUT, e.json().toString().toByteArray())
    }

    fun sendInputs(events: List<InputEvent>) = events.forEach(::sendInput)

    fun select(s: StreamSource) {
        source = s
        send(Kind.SELECT_SOURCE, s.json().toString().toByteArray())
    }

    fun launch(bundleID: String) = send(Kind.LAUNCH_APP, JSONObject().put("bundleID", bundleID).toString().toByteArray())

    fun windowCommand(id: Long, action: String) = send(Kind.WINDOW_COMMAND, windowCommandJson(id, action).toString().toByteArray())

    fun gesture(name: String, fingers: Int) = send(Kind.GESTURE, gestureJson(name, fingers).toString().toByteArray())

    /**
     * The panel's size in dp (Sill's points) and the Aa text scale, null for Off (the Mac leaves
     * the window alone). Sent when it changes; re-sent on connect.
     */
    fun viewport(widthDp: Double, heightDp: Double, textScale: Double?, refreshRate: Int?) {
        val v = viewportJson(widthDp, heightDp, textScale, refreshRate)
        synchronized(this) {
            if (lastViewport?.toString() == v.toString()) return
            lastViewport = v
            if (ready) send(Kind.VIEWPORT, v.toString().toByteArray())
        }
    }

    /**
     * Subscribes to the Mac's clipboard while the Mac shares it and Share Clipboard is on here, and
     * stops when the switch goes off. From each window list and the switch.
     */
    fun updateClipboardSubscription() {
        val want = hostClipboard >= 1 && ClipboardShare.on.value
        if (want && !subscribedClipboard) {
            subscribedClipboard = true
            send(Kind.CLIPBOARD, "{}".toByteArray())
        } else if (!want && subscribedClipboard) {
            subscribedClipboard = false
            send(Kind.CLIPBOARD, JSONObject().put("stop", true).toString().toByteArray())
        }
    }

    /** Puts text on the Mac's clipboard (kind 32), while Share Clipboard is on. */
    fun sendClipboard(text: String) {
        if (!ClipboardShare.on.value) return
        val bytes = text.toByteArray()
        if (bytes.size > 256 * 1024) return
        send(Kind.CLIPBOARD, JSONObject().put("text", text).toString().toByteArray())
    }

    fun requestKeyframe() {
        val now = System.nanoTime()
        if (now - lastKeyframeAsk < 2_000_000_000L) return
        lastKeyframeAsk = now
        send(Kind.KEYFRAME_REQUEST)
    }

    /** A new decoder: hand it the last parameter sets and ask for a keyframe. */
    fun attach(sink: VideoSink?) {
        if (sink === videoSink) return
        videoSink = sink
        if (sink != null) {
            pendingParams?.let(sink::onParameterSets)
            lastKeyframeAsk = 0
            requestKeyframe()
        }
    }

    private fun ping() {
        val p = ByteBuffer.allocate(8).putDouble(System.nanoTime() / 1e9).array()
        send(Kind.PING, p)
        // Keyframe recovery: the picture waits for one (a new decoder, a failure, a drop) and none came.
        val sink = videoSink
        if (sink != null && sink.needsKeyframe && System.nanoTime() - lastKeyframeAt > 1_000_000_000L) requestKeyframe()
    }

    private var statsCount = 0
    @Volatile private var clockAsking = false

    private fun reportStats() {
        val n = framesThisSecond.getAndSet(0)
        fps.value = n
        val ages = synchronized(ageSamples) { ageSamples.toList().also { ageSamples.clear() } }
        val rtts = synchronized(rttSamples) { rttSamples.toList().also { rttSamples.clear() } }
        val age = median(ages)
        frameAgeMs.value = age.takeIf { it >= 0 } ?: frameAgeMs.value.takeIf { ClockSync.offsetMs != null && n == 0 }
        send(Kind.CLIENT_STATS, clientStatsJson(n, age, median(rtts), deviceName,
            frameAgeMaxMs = ages.maxOrNull() ?: -1, rttMaxMs = rtts.maxOrNull() ?: -1).toString().toByteArray())
        // Measure this device's clock (at the start, then every 10 minutes): off the reader thread.
        if ((ClockSync.offsetMs == null || statsCount++ % 60 == 0) && !clockAsking) {
            clockAsking = true
            thread(name = "sill-clock", isDaemon = true) { try { ClockSync.refreshIfStale() } finally { clockAsking = false } }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (state.value !is State.Ended) state.value = State.Ended("Disconnected.")
        shutdown()
    }

    private fun shutdown() {
        closed = true
        // Every menu still opening ends here (its timeout's timer goes with the session).
        for (t in pendingFetches.keys.toList()) pendingFetches.remove(t)?.done?.invoke(MenuContents(emptyList(), 0, "Not connected.", false))
        pinger?.cancel(false); stats?.cancel(false)
        timers.shutdownNow()
        writer.shutdown()
        runCatching { socket?.close() }
        audio.release()
    }

    companion object {
        private const val TAG = "SillSession"
        /** Kind 17's tokens: strictly increasing for the process, never reset (HostSettings.swift). */
        private val SETTINGS_TOKENS = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
