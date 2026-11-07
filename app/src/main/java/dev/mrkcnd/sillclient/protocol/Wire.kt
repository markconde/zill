package dev.mrkcnd.sillclient.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Sill's wire format (Sources/StreamProtocol/StreamMessage.swift): a 14-byte header — kind (u8),
 * timestamp (f64 BE, seconds since 1970), keyframe flag (u8), payload length (u32 BE) — and the
 * payload. Unknown kinds are skipped, never fatal, so newer hosts keep working.
 */
object Kind {
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    const val WINDOW_LIST = 2
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    const val SELECT_SOURCE = 6
    const val LAUNCH_APP = 7
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val CLIENT_STATS = 12
    const val TICK = 13
    const val CURSOR_SHAPE = 14
    const val WINDOW_COMMAND = 15
    const val HOST_SETTINGS = 16
    const val CHANGE_SETTINGS = 17
    const val MAC_INFO = 18
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val PAIRING_WANTED = 21
    const val GOODBYE = 22
    const val HELLO = 23
    const val MAC_MENU = 24
    const val PRESS_MENU_ITEM = 25
    const val MAC_POINTER = 26
    const val FETCH_MENU = 27
    const val GESTURE = 28
    const val CLIPBOARD = 32
    const val AUDIO = 43
    const val KEYFRAME_REQUEST = 44
}

class WireMessage(val kind: Int, val timestamp: Double, val isKeyframe: Boolean, val payload: ByteArray)

object Wire {
    const val HEADER_LENGTH = 14
    const val MAX_FRAME_PAYLOAD = 32 shl 20
    const val MAX_OTHER_HOST_PAYLOAD = 4 shl 20
    const val MAX_PAIRING_PAYLOAD = 4096

    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun serialize(kind: Int, payload: ByteArray = ByteArray(0), keyframe: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        b.put(kind.toByte())
        b.putDouble(now())
        b.put(if (keyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    /** Reads one message, or throws EOFException at a clean end of stream. */
    @Throws(IOException::class)
    fun read(input: DataInputStream, maxPayload: (Int) -> Int = ::defaultCap): WireMessage {
        val header = ByteArray(HEADER_LENGTH)
        input.readFully(header)
        val b = ByteBuffer.wrap(header)
        val kind = b.get().toInt() and 0xFF
        val ts = b.getDouble()
        val key = b.get().toInt() == 1
        val len = b.getInt().toLong() and 0xFFFFFFFFL
        if (len > maxPayload(kind)) throw IOException("kind $kind announces $len bytes, over the cap")
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        return WireMessage(kind, ts, key, payload)
    }

    fun defaultCap(kind: Int): Int = if (kind == Kind.FRAME) MAX_FRAME_PAYLOAD else MAX_OTHER_HOST_PAYLOAD
}

/** ParameterSets.encoded(): nalUnitHeaderLength (u8), count (u8), then u16-BE-length-prefixed sets. */
class ParameterSets(val nalLengthSize: Int, val sets: List<ByteArray>) {
    companion object {
        fun decode(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nal = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var off = 2
            val out = ArrayList<ByteArray>()
            repeat(count) {
                if (d.size < off + 2) return null
                val len = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
                off += 2
                if (d.size < off + len) return null
                out += d.copyOfRange(off, off + len)
                off += len
            }
            return ParameterSets(nal, out)
        }
    }
}

object ImageBlob {
    /** windowID (u32 BE) + JPEG. */
    fun thumbnail(d: ByteArray): Pair<Long, ByteArray>? {
        if (d.size <= 4) return null
        val id = ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xFFFFFFFFL
        return id to d.copyOfRange(4, d.size)
    }

    /** bundleID length (u16 BE) + bundleID UTF-8 + PNG. */
    fun icon(d: ByteArray): Pair<String, ByteArray>? {
        if (d.size <= 2) return null
        val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        if (d.size <= 2 + len) return null
        return String(d, 2, len, Charsets.UTF_8) to d.copyOfRange(2 + len, d.size)
    }
}

/** Kind 14: the Mac's cursor. Hotspot and size in Mac points (Float32 BE each), then the PNG. */
class CursorShape(val hotX: Float, val hotY: Float, val width: Float, val height: Float, val png: ByteArray) {
    companion object {
        fun parse(d: ByteArray): CursorShape? {
            if (d.size <= 16) return null
            val b = ByteBuffer.wrap(d)
            val s = CursorShape(b.getFloat(0), b.getFloat(4), b.getFloat(8), b.getFloat(12), d.copyOfRange(16, d.size))
            val ok = listOf(s.hotX, s.hotY, s.width, s.height).all { it.isFinite() } && s.width > 0 && s.height > 0 &&
                s.width <= 512 && s.height <= 512
            return s.takeIf { ok }
        }
    }
}

class EndOfStream : EOFException()
