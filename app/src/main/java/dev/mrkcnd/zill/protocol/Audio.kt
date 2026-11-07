package dev.mrkcnd.zill.protocol


import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * The Mac's sound, kind 43 (Sill's Audio.swift). The first byte says what follows: 1, a JSON
 * [AudioFormat]; 2, codec packets of one epoch ([AudioPackets]). Unknown types are skipped.
 */
object AudioCodec {
    /** AAC-ELD, 48 kHz stereo, 480 or 512 frames a packet: the only codec Sill makes. */
    const val AAC_ELD = "aac-eld"
    /** What this device lists in its hello (kind 23's `audio`), best first. */
    val DEVICE_PLAYS = listOf(AAC_ELD)
}

data class AudioFormat(
    val codec: String?,
    val sampleRate: Int,
    val channels: Int,
    val framesPerPacket: Int,
    val primingFrames: Int,
    val epoch: Int?,
    /** The converter's magic cookie as the Mac sends it: an MPEG-4 ES descriptor (or a bare ASC). */
    val cookie: ByteArray,
    val app: String?,
)

class AudioPackets(val epoch: Int, val seq: Long, val segmentStart: Boolean, val packets: List<ByteArray>)

sealed class AudioMessage {
    class Format(val format: AudioFormat) : AudioMessage()
    class Packets(val packets: AudioPackets) : AudioMessage()

    companion object {
        const val TYPE_FORMAT = 1
        const val TYPE_PACKETS = 2
        const val MAX_FORMAT_PAYLOAD = 4096
        const val MAX_PACKETS_PAYLOAD = 16384
        private const val PACKETS_HEADER = 9

        /** Null for an empty payload, an unknown type, or one that is cut short or too long. */
        fun parse(p: ByteArray): AudioMessage? = runCatching {
            when (p.firstOrNull()?.toInt()) {
                TYPE_FORMAT -> {
                    if (p.size > MAX_FORMAT_PAYLOAD) return null
                    val o = JSONObject(String(p, 1, p.size - 1, Charsets.UTF_8))
                    Format(
                        AudioFormat(
                            codec = o.optString("codec").takeIf { o.has("codec") },
                            sampleRate = o.optInt("sampleRate", 48_000),
                            channels = o.optInt("channels", 2),
                            framesPerPacket = o.optInt("framesPerPacket", 480),
                            primingFrames = o.optInt("primingFrames", 0).coerceAtLeast(0),
                            epoch = if (o.has("epoch")) o.optInt("epoch") else null,
                            cookie = if (o.has("cookie")) java.util.Base64.getMimeDecoder().decode(o.getString("cookie")) else ByteArray(0),
                            app = o.optString("app").takeIf { o.has("app") },
                        )
                    )
                }
                TYPE_PACKETS -> {
                    if (p.size > MAX_PACKETS_PAYLOAD || p.size < PACKETS_HEADER) return null
                    val b = ByteBuffer.wrap(p)
                    val epoch = b.getShort(1).toInt() and 0xFFFF
                    val seq = b.getInt(3).toLong() and 0xFFFFFFFFL
                    val flags = p[7].toInt()
                    val count = p[8].toInt() and 0xFF
                    if (count < 1) return null
                    var at = PACKETS_HEADER
                    val packets = ArrayList<ByteArray>(count)
                    repeat(count) {
                        if (at + 2 > p.size) return null
                        val n = b.getShort(at).toInt() and 0xFFFF
                        at += 2
                        if (at + n > p.size) return null
                        packets += p.copyOfRange(at, at + n)
                        at += n
                    }
                    if (at != p.size) return null
                    Packets(AudioPackets(epoch, seq, flags and 1 == 1, packets))
                }
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * The AudioSpecificConfig Android's AAC decoder wants as csd-0, from AudioToolbox's magic cookie:
 * an ES_Descriptor (tag 3) holding a DecoderConfigDescriptor (4) holding the DecoderSpecificInfo
 * (5), which is the ASC. A cookie that is not a descriptor is taken as the ASC itself.
 */
object Esds {
    fun audioSpecificConfig(cookie: ByteArray): ByteArray? {
        if (cookie.isEmpty()) return null
        if (cookie[0].toInt() != 0x03 && cookie[0].toInt() != 0x04) return cookie
        return find(cookie, 0, cookie.size)
    }

    private fun find(d: ByteArray, from: Int, to: Int): ByteArray? {
        var at = from
        while (at < to) {
            val tag = d[at].toInt() and 0xFF
            at++
            var len = 0
            for (i in 0 until 4) {
                if (at >= to) return null
                val b = d[at++].toInt() and 0xFF
                len = (len shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            if (at + len > to) return null
            when (tag) {
                0x05 -> return d.copyOfRange(at, at + len)
                0x03 -> {
                    // ES_ID (2), flags (1), then what the flags add, then the nested descriptors.
                    if (len < 3) return null
                    val flags = d[at + 2].toInt() and 0xFF
                    var inner = at + 3
                    if (flags and 0x80 != 0) inner += 2                       // dependsOn_ES_ID
                    if (flags and 0x40 != 0) inner += 1 + (d.getOrNull(inner)?.toInt()?.and(0xFF) ?: 0) // URL
                    if (flags and 0x20 != 0) inner += 2                       // OCR_ES_ID
                    find(d, inner, at + len)?.let { return it }
                }
                // objectType (1), streamType (1), bufferSize (3), maxBitrate (4), avgBitrate (4).
                0x04 -> find(d, at + 13, at + len)?.let { return it }
            }
            at += len
        }
        return null
    }
}
