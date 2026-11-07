package dev.mrkcnd.zill.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.mrkcnd.zill.protocol.ParameterSets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes the Mac's HEVC stream onto a Surface with MediaCodec. The Mac sends its parameter sets
 * (VPS, SPS, PPS) as kind 0 and each access unit as kind 1 with length-prefixed NAL units (hvcC
 * style); MediaCodec wants Annex B, so the length prefixes become start codes.
 *
 * Frames wait in a short queue for an input buffer; when the decoder falls behind, the queue is
 * dropped up to the next keyframe, so the picture stays current rather than late.
 */
class HevcDecoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onFailure: () -> Unit,
    private val onFrameRendered: () -> Unit,
) {
    private class Pending(val data: ByteArray, val key: Boolean)

    private val thread = HandlerThread("hevc").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var params: ParameterSets? = null
    private var csd: ByteArray? = null
    private val queue = ConcurrentLinkedQueue<Pending>()
    private val freeInputs = ConcurrentLinkedQueue<Int>()
    private val queued = AtomicInteger(0)
    @Volatile private var waitingForKeyframe = true
    @Volatile private var released = false
    private var pts = 0L

    /** Kind 0. Rebuilds the decoder when the sets change (a new size or a new encoder). */
    fun configure(ps: ParameterSets) {
        val annexB = ps.sets.fold(ByteArray(0)) { acc, s -> acc + START + s }
        handler.post {
            if (released) return@post
            if (csd != null && csd.contentEquals(annexB) && codec != null) return@post
            params = ps
            csd = annexB
            rebuild()
        }
    }

    /** Kind 1. */
    fun decode(payload: ByteArray, keyframe: Boolean) {
        val ps = params
        val nalLen = ps?.nalLengthSize ?: 4
        val unit = toAnnexB(payload, nalLen) ?: return
        if (queued.get() > MAX_QUEUE) {
            if (!keyframe) { waitingForKeyframe = true; return }
            queue.clear(); queued.set(0)
        }
        queue.add(Pending(unit, keyframe))
        queued.incrementAndGet()
        handler.post { feed() }
    }

    /** Whether a keyframe is due (after a rebuild, a failure or a drop). */
    val needsKeyframe: Boolean get() = waitingForKeyframe

    fun release() {
        released = true
        handler.post {
            teardown()
            thread.quitSafely()
        }
    }

    private fun rebuild() {
        teardown()
        val sets = params ?: return
        val sps = sets.sets.firstOrNull { it.isNotEmpty() && ((it[0].toInt() shr 1) and 0x3F) == 33 }
        val size = sps?.let { SpsParser.size(it) } ?: (1920 to 1080)
        onVideoSize(size.first, size.second)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.first, size.second)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    if (mc !== codec) return
                    freeInputs.add(index); feed()
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (mc !== codec) return
                    runCatching { mc.releaseOutputBuffer(index, info.size > 0) }
                    if (info.size > 0) onFrameRendered()
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "decoder error", e)
                    if (mc !== codec) return
                    waitingForKeyframe = true
                    handler.post { rebuild(); onFailure() }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                    else format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                    else format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w > 0 && h > 0) onVideoSize(w, h)
                }
            }, handler)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            waitingForKeyframe = true
        } catch (e: Exception) {
            Log.e(TAG, "could not start the HEVC decoder", e)
            codec = null
            onFailure()
        }
    }

    private fun feed() {
        val c = codec ?: return
        while (true) {
            val next = queue.peek() ?: return
            if (waitingForKeyframe && !next.key) { queue.poll(); queued.decrementAndGet(); continue }
            val index = freeInputs.poll() ?: return
            queue.poll(); queued.decrementAndGet()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                if (next.data.size > buf.capacity()) {
                    c.queueInputBuffer(index, 0, 0, pts, 0)
                    waitingForKeyframe = true
                    continue
                }
                buf.put(next.data)
                pts += 16_666
                c.queueInputBuffer(index, 0, next.data.size, pts, if (next.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                if (next.key) waitingForKeyframe = false
            } catch (e: Exception) {
                Log.w(TAG, "queue failed", e)
                waitingForKeyframe = true
                return
            }
        }
    }

    private fun teardown() {
        val c = codec
        codec = null
        freeInputs.clear()
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    companion object {
        private const val TAG = "SillHevc"
        private const val MAX_QUEUE = 8
        private val START = byteArrayOf(0, 0, 0, 1)

        /** Length-prefixed NAL units (big-endian lengths of [lenSize] bytes) → Annex B. */
        fun toAnnexB(d: ByteArray, lenSize: Int): ByteArray? {
            if (lenSize !in 1..4) return null
            val out = java.io.ByteArrayOutputStream(d.size + 16)
            var off = 0
            while (off + lenSize <= d.size) {
                var n = 0
                for (i in 0 until lenSize) n = (n shl 8) or (d[off + i].toInt() and 0xFF)
                off += lenSize
                if (n < 0 || off + n > d.size) return null
                out.write(START)
                out.write(d, off, n)
                off += n
            }
            return out.toByteArray()
        }
    }
}

/** Reads the picture size (after the conformance window) from an HEVC SPS NAL unit. */
object SpsParser {
    fun size(nal: ByteArray): Pair<Int, Int>? = runCatching {
        val rbsp = unescape(nal, 2)
        val r = BitReader(rbsp)
        r.bits(4)                         // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.bits(1)                         // temporal_id_nesting
        // profile_tier_level(1, maxSubLayersMinus1)
        r.skip(2 + 1 + 5 + 32 + 4 + 43 + 1 + 8)
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) { subProfile[i] = r.bits(1) == 1; subLevel[i] = r.bits(1) == 1 }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue()                            // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var w = r.ue()
        var h = r.ue()
        if (r.bits(1) == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            w -= subW * (left + right)
            h -= subH * (top + bottom)
        }
        if (w in 16..16384 && h in 16..16384) w to h else null
    }.getOrNull()

    private fun unescape(d: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(d.size)
        var zeros = 0
        for (i in from until d.size) {
            val b = d[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) { pos += n }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) { zeros++; if (zeros > 31) error("bad ue") }
            return (1 shl zeros) - 1 + bits(zeros)
        }
    }
}
