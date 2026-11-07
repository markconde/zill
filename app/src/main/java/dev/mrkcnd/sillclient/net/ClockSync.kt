package dev.mrkcnd.sillclient.net

import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How far this device's clock is from true time, so a frame's age can be measured: the Mac stamps
 * each frame with its own wall clock (kept by NTP, typically within a few ms), and Sill's
 * `frameAgeMs` assumes both clocks agree. A phone's clock is often off by tens or hundreds of ms,
 * which made the age negative (reported as 0) or too large.
 *
 * Off by default (the setting "Ask time servers for the delay"): Android's own network time (API
 * 33+) only, so Zill contacts nothing but the Mac, and the delay is unknown on older Android. On:
 * SNTP (RFC 4330) against a public time server, four samples, the one with the shortest round trip
 * kept, as NTP clients do, falling back to Android's network time. Null until known.
 */
object ClockSync {
    /** Add to System.currentTimeMillis() for true time, in ms; null while unknown. */
    @Volatile var offsetMs: Double? = null
        private set
    /** ± this much, half the best sample's round trip. */
    @Volatile var errorMs: Double? = null
        private set
    @Volatile private var measuredAt = 0L
    private val lock = Any()

    private const val PREFS = "device"
    private const val KEY = "askTimeServers"
    private val _useServers = MutableStateFlow(false)
    /** Whether to ask public time servers; off until the user turns it on. */
    val useServers: StateFlow<Boolean> = _useServers

    fun load(ctx: Context) {
        _useServers.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    /** Remembered; measures again on the next stats tick. */
    fun setUseServers(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        _useServers.value = on
        // Not under the lock, which a measurement holds for seconds: one already running keeps its
        // result only if the switch hasn't moved since it began (refreshIfStale).
        offsetMs = null; errorMs = null; measuredAt = 0L
    }

    private val SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
    private const val REFRESH_MS = 10 * 60_000L
    private const val TAG = "SillClock"
    /** Seconds from 1900 (NTP's epoch) to 1970. */
    private const val NTP_TO_UNIX = 2_208_988_800L

    /** Measure now if never measured or older than 10 minutes. Blocking: call off the main thread. */
    fun refreshIfStale() {
        synchronized(lock) {
            if (offsetMs != null && SystemClock.elapsedRealtime() - measuredAt < REFRESH_MS) return
            val asked = _useServers.value
            val servers = if (asked) SERVERS else emptyList()
            val m = servers.firstNotNullOfOrNull { runCatching { measure(it) }.onFailure { e -> Log.i(TAG, "$it: $e") }.getOrNull() }
                ?: networkTime()
            if (m != null && asked == _useServers.value) {
                offsetMs = m.first; errorMs = m.second; measuredAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "clock offset ${"%.1f".format(m.first)} ms ± ${"%.1f".format(m.second)}")
            }
        }
    }

    /** (offset, error) in ms from the best of four SNTP samples. */
    private fun measure(host: String): Pair<Double, Double>? {
        val addr = InetAddress.getByName(host)
        DatagramSocket().use { sock ->
            sock.soTimeout = 1500
            var best: Pair<Double, Double>? = null
            repeat(4) {
                val req = ByteArray(48).also { it[0] = 0x23 }               // LI 0, version 4, mode 3 (client)
                val t0 = System.currentTimeMillis()
                val n0 = System.nanoTime()
                ntpWrite(req, 40, t0)                                     // transmit time, echoed as "originate"
                sock.send(DatagramPacket(req, req.size, addr, 123))
                val buf = ByteArray(48)
                val p = DatagramPacket(buf, buf.size)
                runCatching { sock.receive(p) }.getOrNull() ?: return@repeat
                val t3 = t0 + (System.nanoTime() - n0) / 1e6                // arrival, on the same clock as t0
                val mode = buf[0].toInt() and 7
                val stratum = buf[1].toInt() and 0xFF
                if (p.length < 48 || mode != 4 || stratum == 0 || stratum > 15) return@repeat
                val t1 = ntpRead(buf, 32)                                 // server received
                val t2 = ntpRead(buf, 40)                                 // server sent
                val offset = ((t1 - t0) + (t2 - t3)) / 2
                val delay = (t3 - t0) - (t2 - t1)
                if (delay >= 0 && (best == null || delay / 2 < best!!.second)) best = offset to delay / 2
            }
            return best
        }
    }

    /** Android's own network-synced clock (API 33+), when no time server answers. */
    private fun networkTime(): Pair<Double, Double>? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            val net = SystemClock.currentNetworkTimeClock().millis()
            (net - System.currentTimeMillis()).toDouble() to 50.0
        }.getOrNull()
    }

    private fun ntpRead(b: ByteArray, at: Int): Double {
        val bb = ByteBuffer.wrap(b, at, 8)
        val secs = bb.int.toLong() and 0xFFFFFFFFL
        val frac = bb.int.toLong() and 0xFFFFFFFFL
        return (secs - NTP_TO_UNIX) * 1000.0 + frac * 1000.0 / 4294967296.0
    }

    private fun ntpWrite(b: ByteArray, at: Int, unixMs: Long) {
        val secs = unixMs / 1000 + NTP_TO_UNIX
        val frac = (unixMs % 1000) * 4294967296L / 1000
        ByteBuffer.wrap(b, at, 8).putInt(secs.toInt()).putInt(frac.toInt())
    }
}
