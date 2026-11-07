package dev.mrkcnd.sillclient

import dev.mrkcnd.sillclient.net.SelfSigned
import dev.mrkcnd.sillclient.net.Tls
import dev.mrkcnd.sillclient.protocol.Base64Url
import dev.mrkcnd.sillclient.protocol.Kind
import dev.mrkcnd.sillclient.protocol.MacId
import dev.mrkcnd.sillclient.protocol.PairingProof
import dev.mrkcnd.sillclient.protocol.Spki
import dev.mrkcnd.sillclient.protocol.Wire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A stand-in Mac for trying the app on an emulator or a phone, built from the published wire rules
 * (not Sill's code): a TLS 1.3 door on port 17455 that pairs with code 4829 1355 7208, lists two
 * windows, logs every input it receives and streams a looping HEVC test clip as the Desktop.
 *
 *   SILL_STANDIN_SECONDS=300 ./gradlew testDebugUnitTest --tests '*StandInServe*' -i
 *
 * In the app: Add a Mac → 10.0.2.2:17455 (the emulator's address for this computer) → the code.
 * Skipped unless SILL_STANDIN_SECONDS is set.
 */
class StandInServe {
    private val code = "482913557208"
    private val log = File(System.getenv("SILL_STANDIN_LOG") ?: "build/standin.log")

    private fun say(line: String) {
        val t = "%.3f".format(System.currentTimeMillis() / 1000.0 % 1000)
        log.appendText("$t $line\n")
        println("$t $line")
    }

    @Test fun serve() {
        val seconds = System.getenv("SILL_STANDIN_SECONDS")?.toIntOrNull()
        assumeTrue("set SILL_STANDIN_SECONDS to run the stand-in Mac", seconds != null)
        log.parentFile?.mkdirs(); log.writeText("")
        // One key for every run (build/standin-key.p12), so a paired app doesn't have to pair again.
        val keyFile = File(log.parentFile, "standin-key.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (keyFile.isFile) keyFile.inputStream().use { ks.load(it, CharArray(0)) } else {
            val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = kpg.generateKeyPair()
            val der = SelfSigned.make(pair.private, pair.public.encoded)
            val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            ks.load(null); ks.setKeyEntry("mac", pair.private, CharArray(0), arrayOf(c))
            keyFile.outputStream().use { ks.store(it, CharArray(0)) }
        }
        val cert = ks.getCertificate("mac") as X509Certificate
        val macFp = Spki.fingerprint(cert.publicKey.encoded)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf(trustAny), null) }
        val ss = ctx.serverSocketFactory.createServerSocket(17455, 5, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        ss.enabledProtocols = arrayOf("TLSv1.3")
        ss.needClientAuth = true
        ss.sslParameters = ss.sslParameters.also { it.applicationProtocols = arrayOf(Tls.SESSION_ALPN, Tls.PAIRING_ALPN) }
        say("stand-in Mac ${MacId.make(macFp)} listening on 17455; code 4829 1355 7208")
        val clip = parseClip(javaClass.classLoader!!.getResourceAsStream("testclip.hevc").readBytes())
        say("clip: ${clip.second.size} access units, ${clip.first.size} parameter sets")

        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.startHandshake()
                        val deviceFp = Spki.fingerprint(s.session.peerCertificates[0].publicKey.encoded)
                        say("connection ${s.applicationProtocol} from ${s.inetAddress.hostAddress}, device key ${Base64Url.encode(deviceFp).take(10)}")
                        val input = DataInputStream(s.inputStream)
                        when (s.applicationProtocol) {
                            Tls.PAIRING_ALPN -> {
                                val m = Wire.read(input)
                                val req = JSONObject(String(m.payload))
                                say("kind ${m.kind} ${req.getString("method")} from ${req.optString("name")} (${req.optString("model")})")
                                val k = PairingProof.codeKey(code, macFp)
                                val ok = MessageDigest.isEqual(Base64Url.decode(req.getString("proof")), PairingProof.deviceProof(k, deviceFp, macFp))
                                val answer = when {
                                    req.getString("method") == "ask" -> JSONObject().put("ok", false).put("reason", "shown")
                                    ok -> JSONObject().put("ok", true).put("proof", Base64Url.encode(PairingProof.macProof(k, macFp, deviceFp)))
                                        .put("macID", MacId.make(macFp)).put("name", "Stand-in Mac")
                                        .put("recognitionKey", Base64Url.encode(ByteArray(32) { 7 }))
                                    else -> JSONObject().put("ok", false).put("reason", "code").put("triesLeft", 4)
                                }
                                say("answer $answer")
                                s.outputStream.write(Wire.serialize(Kind.PAIR_RESULT, answer.toString().toByteArray())); s.outputStream.flush()
                            }
                            Tls.SESSION_ALPN -> session(s, input, clip)
                        }
                    }.onFailure { say("connection ended: $it") }
                    runCatching { s.close() }
                }
            }
        }
        Thread.sleep(seconds!! * 1000L)
        ss.close()
    }

    private fun session(s: SSLSocket, input: DataInputStream, clip: Pair<ByteArray, List<Pair<ByteArray, Boolean>>>) {
        val out = s.outputStream
        val lock = Any()
        fun send(kind: Int, p: ByteArray, key: Boolean = false) = synchronized(lock) { out.write(Wire.serialize(kind, p, key)); out.flush() }
        val windows = JSONArray()
            .put(JSONObject().put("id", 42).put("title", "Notes.txt").put("appName", "TextEdit").put("bundleID", "com.apple.TextEdit").put("width", 800).put("height", 600))
            .put(JSONObject().put("id", 43).put("title", "Downloads").put("appName", "Finder").put("bundleID", "com.apple.finder").put("width", 900).put("height", 560))
        // The list names what streams, as a Mac's does: re-sent after each select (kind 6).
        fun list(active: JSONObject) = send(Kind.WINDOW_LIST, JSONObject().put("macName", "Stand-in Mac").put("windows", windows)
            .put("active", active).put("protocol", 1).put("gestures", 1).put("clipboard", 1)
            .put("hostVersion", "stand-in").toString().toByteArray())
        list(JSONObject().put("desktop", JSONObject()))
        send(Kind.APP_LIST, JSONArray().put(JSONObject().put("name", "TextEdit").put("bundleID", "com.apple.TextEdit")).toString().toByteArray())
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val inputsRead = java.util.concurrent.atomic.AtomicLong(0)
        val lastInputAt = java.util.concurrent.atomic.AtomicLong(0)
        // The Mac's cursor (kind 14): an arrow, 17×23 points, drawn at 2×, hotspot at its tip.
        send(Kind.CURSOR_SHAPE, cursorBlob())
        // The Mac's own mouse (kind 26): circles the picture while this device hasn't moved it for 2 s.
        thread(isDaemon = true) {
            var t = 0.0
            var wasInside = false
            while (running.get()) {
                Thread.sleep(50)
                if (System.currentTimeMillis() - lastInputAt.get() < 2000) { wasInside = false; continue }
                t += 0.05
                val inside = (t % 8) < 6       // two seconds of every eight off the stream
                val o = JSONObject().put("inside", inside).put("seen", inputsRead.get())
                if (inside) o.put("x", 0.5 + 0.3 * Math.cos(t)).put("y", 0.5 + 0.3 * Math.sin(t))
                if (inside || wasInside) runCatching { send(Kind.MAC_POINTER, o.toString().toByteArray()) }
                wasInside = inside
            }
        }
        val keyframeWanted = java.util.concurrent.atomic.AtomicBoolean(true)
        thread(isDaemon = true) {
            var i = 0
            send(Kind.PARAMETER_SETS, clip.first)
            while (running.get()) {
                val (au, key) = clip.second[i % clip.second.size]
                if (keyframeWanted.get() && !key) { i++; continue }
                if (key) keyframeWanted.set(false)
                runCatching { send(Kind.FRAME, au, key) }.onFailure { running.set(false) }
                i++
                Thread.sleep(33)
            }
        }
        try {
            while (true) {
                val m = Wire.read(input)
                when (m.kind) {
                    Kind.PING -> send(Kind.PONG, m.payload)
                    Kind.CLIENT_STATS -> say("stats ${String(m.payload)}")
                    Kind.HELLO -> {
                        say("kind 23 ${String(m.payload)}")
                        val audio = JSONObject(String(m.payload)).optJSONArray("audio")
                        if (audio != null && (0 until audio.length()).any { audio.getString(it) == "aac-eld" }) {
                            thread(isDaemon = true) { streamSound({ k, b -> send(k, b) }, running) }
                        }
                    }
                    Kind.INPUT -> { inputsRead.incrementAndGet(); lastInputAt.set(System.currentTimeMillis()); say("kind 8 ${String(m.payload).take(300)}") }
                    Kind.SELECT_SOURCE -> { say("kind 6 ${String(m.payload)}"); list(JSONObject(String(m.payload))) }
                    Kind.KEYFRAME_REQUEST -> { keyframeWanted.set(true); say("kind 44 keyframe request") }
                    else -> say("kind ${m.kind} ${String(m.payload).take(300)}")
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** An arrow cursor as kind 14 carries it: hotspot and size in points (Float32 BE), then a 2× PNG. */
    private fun cursorBlob(): ByteArray {
        val png = javaClass.classLoader!!.getResourceAsStream("cursor.png").readBytes()
        return java.nio.ByteBuffer.allocate(16 + png.size).putFloat(1f).putFloat(1f).putFloat(17f).putFloat(23f).put(png).array()
    }

    /**
     * The Mac's sound (kind 43) from testclip.eld (tools/eld-clip.swift: AAC-ELD as Sill's
     * AudioEncoder makes it): the format, then one packet every 10 ms, looping.
     */
    private fun streamSound(send: (Int, ByteArray) -> Unit, running: java.util.concurrent.atomic.AtomicBoolean) {
        val d = java.nio.ByteBuffer.wrap(javaClass.classLoader!!.getResourceAsStream("testclip.eld").readBytes())
        val cookie = ByteArray(d.int).also { d.get(it) }
        val priming = d.int
        val packets = ArrayList<ByteArray>()
        while (d.remaining() >= 2) packets += ByteArray(d.short.toInt() and 0xFFFF).also { d.get(it) }
        val format = JSONObject().put("codec", "aac-eld").put("sampleRate", 48000).put("channels", 2)
            .put("framesPerPacket", 480).put("primingFrames", priming).put("bitrate", 128000).put("epoch", 1)
            .put("cookie", java.util.Base64.getEncoder().encodeToString(cookie)).put("source", "test").put("app", "Stand-in tone")
        runCatching { send(Kind.AUDIO, byteArrayOf(1) + format.toString().toByteArray()) }.onFailure { return }
        say("sound: ${packets.size} AAC-ELD packets, cookie ${cookie.size} bytes, priming $priming")
        var seq = 0L
        val start = System.nanoTime()
        while (running.get()) {
            val p = packets[(seq % packets.size).toInt()]
            val out = java.nio.ByteBuffer.allocate(9 + 2 + p.size).put(2).putShort(1).putInt(seq.toInt())
                .put(if (seq == 0L) 1 else 0).put(1).putShort(p.size.toShort()).put(p).array()
            if (runCatching { send(Kind.AUDIO, out) }.isFailure) return
            seq++
            val due = start + seq * 10_000_000L
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    /** Annex B with AUDs → (ParameterSets.encoded(), access units as 4-byte length-prefixed NALs with their keyframe flag). */
    internal fun parseClip(d: ByteArray): Pair<ByteArray, List<Pair<ByteArray, Boolean>>> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 3 <= d.size) {
            val sc3 = d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()
            if (sc3) {
                if (start >= 0) {
                    var end = i
                    while (end > start && d[end - 1] == 0.toByte()) end--
                    nals += d.copyOfRange(start, end)
                }
                i += 3; start = i
            } else i++
        }
        if (start >= 0) nals += d.copyOfRange(start, d.size)
        val sets = LinkedHashMap<Int, ByteArray>()
        val aus = ArrayList<Pair<ByteArray, Boolean>>()
        var cur = ByteArrayOutputStream()
        var key = false
        fun flush() { if (cur.size() > 0) aus += cur.toByteArray() to key; cur = ByteArrayOutputStream(); key = false }
        for (n in nals) {
            val type = (n[0].toInt() shr 1) and 0x3F
            when (type) {
                35 -> flush()
                32, 33, 34 -> sets.putIfAbsent(type, n)
                else -> {
                    if (type in 16..21) key = true
                    cur.write(byteArrayOf((n.size ushr 24).toByte(), (n.size ushr 16).toByte(), (n.size ushr 8).toByte(), n.size.toByte()))
                    cur.write(n)
                }
            }
        }
        flush()
        val ps = ByteArrayOutputStream()
        ps.write(4); ps.write(sets.size)
        for (s in sets.values) { ps.write(s.size ushr 8); ps.write(s.size and 0xFF); ps.write(s) }
        return ps.toByteArray() to aus
    }
}
