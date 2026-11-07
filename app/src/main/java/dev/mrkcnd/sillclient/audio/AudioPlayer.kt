package dev.mrkcnd.sillclient.audio

import android.media.AudioAttributes
import android.media.AudioFormat as AFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.mrkcnd.sillclient.protocol.AudioCodec
import dev.mrkcnd.sillclient.protocol.AudioFormat
import dev.mrkcnd.sillclient.protocol.AudioPackets
import dev.mrkcnd.sillclient.protocol.Esds
import java.nio.ByteBuffer

/**
 * Plays the Mac's sound (kind 43): AAC-ELD through MediaCodec into a low-latency AudioTrack, all on
 * one thread. Sound that falls more than [MAX_BEHIND_MS] behind is dropped rather than played late,
 * so it stays with the picture; a gap starts again with a short cushion of silence.
 */
class AudioPlayer {
    private val thread = HandlerThread("sill-audio").apply { start() }
    private val h = Handler(thread.looper)

    private var format: AudioFormat? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var written = 0L          // frames written to the track
    private var dropFrames = 0        // a segment's priming, still to drop
    private var pts = 0L
    private var released = false
    private var announced = false
    private var lastHead = 0L
    private var headMovedAt = 0L
    @Volatile var muted = false
        set(value) { field = value; h.post { track?.setVolume(if (value) 0f else 1f) } }

    /** What plays now, for the stats line: "Safari", or null when nothing does. */
    @Volatile var playing: String? = null
        private set
    /** Why sound the Mac sends can't play here, for the status line; null when it plays or none came. */
    @Volatile var problem: String? = null
        private set
    @Volatile private var heard = false

    /**
     * For the status line: "♪ Safari" while the Mac's sound plays, "♪ can't play (…)" when it came but
     * won't play, null while the Mac sends none (Send Audio off, or a window of a silent app).
     */
    fun statusText(): String? = when {
        problem != null -> "♪ can't play ($problem)"
        playing != null -> if (heard) "♪ $playing" else "♪ $playing…"
        else -> null
    }

    fun format(f: AudioFormat) = h.post {
        if (released) return@post
        teardown()
        problem = null
        heard = false
        if (f.codec != AudioCodec.AAC_ELD) { Log.i(TAG, "no player for ${f.codec}"); problem = "the Mac sends ${f.codec}"; return@post }
        val asc = Esds.audioSpecificConfig(f.cookie) ?: run { Log.w(TAG, "no AudioSpecificConfig"); problem = "no decoder setup"; return@post }
        try {
            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, f.sampleRate, f.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(mf, null, null, 0)
            c.start()
            codec = c
            val mask = if (f.channels == 1) AFormat.CHANNEL_OUT_MONO else AFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(f.sampleRate, mask, AFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AFormat.Builder().setSampleRate(f.sampleRate).setChannelMask(mask)
                    .setEncoding(AFormat.ENCODING_PCM_16BIT).build())
                // Room for MAX_BEHIND_MS, no more: a fuller track only adds delay.
                .setBufferSizeInBytes(maxOf(min, msToFrames(f, MAX_BEHIND_MS) * bytesPerFrame(f)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build().apply {
                    // A stream track otherwise waits for a full buffer before it starts (Android 12+).
                    if (android.os.Build.VERSION.SDK_INT >= 31) setStartThresholdInFrames(msToFrames(f, CUSHION_MS))
                    setVolume(if (muted) 0f else 1f)
                    play()
                }
            format = f
            written = 0
            lastHead = 0
            headMovedAt = System.nanoTime()
            dropFrames = f.primingFrames
            playing = f.app ?: "the Mac"
            announced = false
        } catch (e: Exception) {
            Log.w(TAG, "AAC-ELD decoder failed", e)
            teardown()
            problem = "this device has no AAC-ELD decoder: ${e.javaClass.simpleName}"
        }
    }

    fun packets(p: AudioPackets) = h.post {
        val f = format ?: return@post
        val c = codec ?: return@post
        if (f.epoch != null && f.epoch != p.epoch) return@post
        try {
            if (p.segmentStart) {
                c.flush()
                dropFrames = f.primingFrames
            }
            for (packet in p.packets) {
                val i = c.dequeueInputBuffer(20_000)
                if (i < 0) continue
                c.getInputBuffer(i)?.apply { clear(); put(packet) }
                c.queueInputBuffer(i, 0, packet.size, pts, 0)
                pts += f.framesPerPacket * 1_000_000L / f.sampleRate
                drain(c, f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio decode failed", e)
            problem = "decoding failed"
        }
    }

    private fun drain(c: MediaCodec, f: AudioFormat) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) return
            val buf = c.getOutputBuffer(o)
            if (buf != null && info.size > 0) play(buf, info, f)
            c.releaseOutputBuffer(o, false)
        }
    }

    private fun play(buf: ByteBuffer, info: MediaCodec.BufferInfo, f: AudioFormat) {
        val t = track ?: return
        val frameBytes = bytesPerFrame(f)
        var offset = info.offset
        var size = info.size
        if (dropFrames > 0) {
            val skip = minOf(dropFrames * frameBytes, size)
            offset += skip; size -= skip
            dropFrames -= skip / frameBytes
            if (size <= 0) return
        }
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val now = System.nanoTime()
        if (head != lastHead) { lastHead = head; headMovedAt = now }
        var queued = written - head
        if (queued >= msToFrames(f, MAX_BEHIND_MS) - f.framesPerPacket) {
            if (now - headMovedAt < STALL_NS) return          // behind: drop rather than play late
            // The track stopped consuming (an underrun can leave it idle with sound still queued, and it
            // stays idle while nothing new is written): start it again from empty.
            Log.i(TAG, "track stalled with ${queued * 1000 / f.sampleRate} ms queued: restarting it")
            t.pause(); t.flush(); t.play()
            written = 0; lastHead = 0; headMovedAt = now; queued = 0
        }
        if (queued <= 0) {
            // Starting, or after a gap: a short cushion so the next packet's jitter doesn't click.
            val pad = msToFrames(f, CUSHION_MS) * frameBytes
            written += t.write(ByteArray(pad), 0, pad, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0) / frameBytes
        }
        buf.position(offset); buf.limit(offset + size)
        written += t.write(buf, size, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0) / frameBytes
        heard = true
        if (!announced) { announced = true; Log.i(TAG, "playing ${f.codec} ${f.sampleRate} Hz ×${f.channels} from $playing") }
    }

    private fun teardown() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { track?.stop() }; runCatching { track?.release() }
        codec = null; track = null; format = null; playing = null
    }

    fun release() = h.post {
        released = true
        teardown()
        thread.quitSafely()
    }

    private fun msToFrames(f: AudioFormat, ms: Int) = f.sampleRate * ms / 1000
    private fun bytesPerFrame(f: AudioFormat) = 2 * f.channels

    companion object {
        private const val TAG = "SillAudio"
        /** The most the sound may trail before packets are dropped. */
        const val MAX_BEHIND_MS = 150
        const val CUSHION_MS = 40
        /** How long the track's position may stand still, with sound queued, before it is restarted. */
        private const val STALL_NS = 400_000_000L
    }
}
