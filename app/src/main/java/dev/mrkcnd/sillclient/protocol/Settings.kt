package dev.mrkcnd.sillclient.protocol

import org.json.JSONObject

/**
 * The Mac's settings as a device sees and changes them (Sill's HostSettings.swift, kinds 16 and 17):
 * the five for the stream, Direct Wireless and Send Audio. A device knows the Mac has settings when
 * a kind 16 arrives. A field the Mac did not report (null here: an older Mac) is never sent.
 */
data class StreamSettings(
    val maxFPS: Int,
    /** Bits per second per 60 fps. */
    val bitrate: Int,
    /** 2 = Retina, 1 = Standard. */
    val captureScale: Double,
    val prioritizeSpeed: Boolean,
    val virtualDisplay: Boolean,
    val directWireless: Boolean?,
    val sendAudio: Boolean?,
)

/** What runs, after the devices' rates and the encoder's caps. */
data class RunningStream(val width: Int, val height: Int, val fps: Int, val mbps: Int, val onVirtualDisplay: Boolean)

/** The link can't keep up: what it carried and what would fit. */
data class LinkReport(val state: String, val bitrate: Int, val carriedKbps: Int?, val suggestedBitrate: Int?, val suggestedCaptureScale: Double?) {
    val isBehind get() = state == "behind"
}

data class AwayQuality(val thisConnectionAway: Boolean, val awayRunning: Boolean)

/** Kind 16: on connect, whenever it changes, and (with `answering`) as the answer to a kind 17. */
data class HostSettingsState(
    val settings: StreamSettings,
    val persistent: Boolean,
    val virtualDisplayAvailable: Boolean,
    val virtualDisplayNote: String?,
    val softwareEncoder: Boolean,
    val stream: RunningStream?,
    val answering: Int?,
    val away: AwayQuality?,
    val link: LinkReport?,
    val audioNote: String?,
    /** Null from Macs before 2026-10-29, which took Direct Wireless from any session at home. */
    val sessionPaired: Boolean?,
) {
    companion object {
        fun parse(bytes: ByteArray): HostSettingsState? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val s = o.getJSONObject("settings")
            fun JSONObject.optBool(k: String) = if (has(k) && !isNull(k)) getBoolean(k) else null
            fun JSONObject.optText(k: String, max: Int = 300) =
                if (has(k) && !isNull(k)) getString(k).filter { !it.isISOControl() }.trim().take(max).ifEmpty { null } else null
            HostSettingsState(
                settings = StreamSettings(
                    maxFPS = s.getInt("maxFPS"), bitrate = s.getInt("bitrate"), captureScale = s.getDouble("captureScale"),
                    prioritizeSpeed = s.getBoolean("prioritizeSpeed"), virtualDisplay = s.getBoolean("virtualDisplay"),
                    directWireless = s.optBool("directWireless"), sendAudio = s.optBool("sendAudio"),
                ),
                persistent = o.optBoolean("persistent", false),
                virtualDisplayAvailable = o.optBoolean("virtualDisplayAvailable", false),
                virtualDisplayNote = o.optText("virtualDisplayNote"),
                softwareEncoder = o.optBoolean("softwareEncoder", false),
                stream = o.optJSONObject("stream")?.let {
                    RunningStream(it.getInt("width"), it.getInt("height"), it.getInt("fps"), it.getInt("mbps"), it.getBoolean("onVirtualDisplay"))
                },
                answering = if (o.has("answering") && !o.isNull("answering")) o.getInt("answering") else null,
                away = o.optJSONObject("away")?.let { AwayQuality(it.getBoolean("thisConnectionAway"), it.getBoolean("awayRunning")) },
                link = o.optJSONObject("link")?.let {
                    LinkReport(it.getString("state"), it.getInt("bitrate"),
                        if (it.has("carriedKbps")) it.getInt("carriedKbps") else null,
                        if (it.has("suggestedBitrate")) it.getInt("suggestedBitrate") else null,
                        if (it.has("suggestedCaptureScale")) it.getDouble("suggestedCaptureScale") else null)
                },
                audioNote = o.optText("audioNote"),
                sessionPaired = o.optBool("sessionPaired"),
            )
        }.getOrNull()
    }
}

/** Kind 17: only the fields one control changed, as absolute values. */
class SettingsChange(
    val maxFPS: Int? = null, val bitrate: Int? = null, val captureScale: Double? = null,
    val prioritizeSpeed: Boolean? = null, val virtualDisplay: Boolean? = null,
    val directWireless: Boolean? = null, val sendAudio: Boolean? = null,
) {
    fun json(token: Int): JSONObject = JSONObject().put("token", token).apply {
        maxFPS?.let { put("maxFPS", it) }; bitrate?.let { put("bitrate", it) }; captureScale?.let { put("captureScale", it) }
        prioritizeSpeed?.let { put("prioritizeSpeed", it) }; virtualDisplay?.let { put("virtualDisplay", it) }
        directWireless?.let { put("directWireless", it) }; sendAudio?.let { put("sendAudio", it) }
    }

    /** `s` with this laid over it: what the panel shows while the Mac's answer is on its way. */
    fun appliedTo(s: StreamSettings) = s.copy(
        maxFPS = maxFPS ?: s.maxFPS, bitrate = bitrate ?: s.bitrate, captureScale = captureScale ?: s.captureScale,
        prioritizeSpeed = prioritizeSpeed ?: s.prioritizeSpeed, virtualDisplay = virtualDisplay ?: s.virtualDisplay,
        directWireless = directWireless ?: s.directWireless, sendAudio = sendAudio ?: s.sendAudio,
    )

    /** The Mac took it: every field this sets is what the answer says. */
    fun takenBy(s: StreamSettings) = appliedTo(s) == s
}

/** What a device may pick: exactly the Mac menu's choices (SettingsChoices, QualityPreset). */
object SettingsChoices {
    val maxFPS = listOf(60, 120)
    val captureScale = listOf(2.0, 1.0)
    /** Per 60 fps, ascending, with the Mac's names. */
    val quality = listOf(
        4_000_000 to "Low", 8_000_000 to "Efficient", 15_000_000 to "Balanced", 25_000_000 to "High",
        40_000_000 to "Pro", 80_000_000 to "Ultra", 150_000_000 to "Extreme",
    )

    fun mbps(bps: Int): String = if (bps % 1_000_000 == 0) "${bps / 1_000_000}" else "%.1f".format(bps / 1_000_000.0)
    fun qualityName(bps: Int) = quality.firstOrNull { it.first == bps }?.second ?: "${mbps(bps)} Mbps"
    /** "Balanced — 15 Mbps", or "Custom — 12 Mbps" for one set by hand on the Mac. */
    fun qualityTitle(bps: Int) = quality.firstOrNull { it.first == bps }?.let { "${it.second} — ${mbps(bps)} Mbps" } ?: "Custom — ${mbps(bps)} Mbps"
    fun resolutionName(scale: Double) = if (scale >= 1.5) "Retina" else "Standard"
    const val FAST_LINK_NOTE = "Ultra and Extreme need the USB cable or very fast Wi‑Fi; if the picture lags, step down."
}
