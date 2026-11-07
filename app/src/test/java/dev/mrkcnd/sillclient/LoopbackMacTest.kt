package dev.mrkcnd.sillclient

import dev.mrkcnd.sillclient.net.DeviceIdentity
import dev.mrkcnd.sillclient.net.PairingClient
import dev.mrkcnd.sillclient.net.Route
import dev.mrkcnd.sillclient.net.SelfSigned
import dev.mrkcnd.sillclient.net.StreamSession
import dev.mrkcnd.sillclient.net.Tls
import dev.mrkcnd.sillclient.protocol.Base64Url
import dev.mrkcnd.sillclient.protocol.Kind
import dev.mrkcnd.sillclient.protocol.MacId
import dev.mrkcnd.sillclient.protocol.PairingProof
import dev.mrkcnd.sillclient.protocol.Spki
import dev.mrkcnd.sillclient.protocol.StreamSource
import dev.mrkcnd.sillclient.protocol.Wire
import dev.mrkcnd.sillclient.protocol.WireMessage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A stand-in Mac on loopback, built from the published wire rules: a TLS 1.3 door that requires a
 * client certificate and offers sill/1 and sill-pair/1, the kind 19/20 pairing exchange with both
 * proofs, and a session that reads the hello first. It checks this client end to end, crypto included.
 */
class LoopbackMacTest {
    private fun keyAndCert(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = kpg.generateKeyPair()
        val der = SelfSigned.make(pair.private, pair.public.encoded)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return pair.private to cert
    }

    private val mac = keyAndCert()
    private val macFp = Spki.fingerprint(mac.second.publicKey.encoded)
    private val device = keyAndCert().let { DeviceIdentity(it.first, it.second) }
    private val recognitionKey = ByteArray(32) { 7 }
    private val code = "482913557208"
    private val received = LinkedBlockingQueue<Pair<String, WireMessage>>()
    private val clientFps = LinkedBlockingQueue<ByteArray>()
    private var server: SSLServerSocket? = null
    private val serverErrors = LinkedBlockingQueue<Throwable>()
    private val alpns = LinkedBlockingQueue<String>()

    private fun startMac(): Int {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("mac", mac.first, CharArray(0), arrayOf(mac.second)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
                clientFps.add(Spki.fingerprint(chain[0].publicKey.encoded))
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf(trustAny), null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        ss.enabledProtocols = arrayOf("TLSv1.3")
        ss.needClientAuth = true
        val p = ss.sslParameters
        p.applicationProtocols = arrayOf(Tls.SESSION_ALPN, Tls.PAIRING_ALPN)
        ss.sslParameters = p
        server = ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.startHandshake()
                        val input = DataInputStream(s.inputStream)
                        alpns.add(s.applicationProtocol ?: "null")
                        when (s.applicationProtocol) {
                            Tls.PAIRING_ALPN -> pairing(s, input)
                            Tls.SESSION_ALPN -> session(s, input)
                        }
                    }.onFailure { serverErrors.add(it); it.printStackTrace() }
                    runCatching { s.close() }
                }
            }
        }
        return ss.localPort
    }

    /** The Mac's side of kind 19: checks proof_D with the code's key, answers kind 20 with proof_M. */
    private fun pairing(s: SSLSocket, input: DataInputStream) {
        val m = Wire.read(input)
        received.add("pair" to m)
        val req = JSONObject(String(m.payload))
        val deviceFp = Spki.fingerprint(s.session.peerCertificates[0].publicKey.encoded)
        val answer = if (req.getString("method") == "ask") {
            JSONObject().put("ok", false).put("reason", "shown")
        } else {
            val k = PairingProof.codeKey(code, macFp)
            val want = PairingProof.deviceProof(k, deviceFp, macFp)
            if (MessageDigest.isEqual(Base64Url.decode(req.getString("proof")), want)) {
                JSONObject().put("ok", true).put("proof", Base64Url.encode(PairingProof.macProof(k, macFp, deviceFp)))
                    .put("macID", MacId.make(macFp)).put("name", "Test Mac").put("recognitionKey", Base64Url.encode(recognitionKey))
            } else {
                JSONObject().put("ok", false).put("reason", "code").put("triesLeft", 4)
            }
        }
        s.outputStream.write(Wire.serialize(Kind.PAIR_RESULT, answer.toString().toByteArray()))
        s.outputStream.flush()
    }

    private fun session(s: SSLSocket, input: DataInputStream) {
        val list = JSONObject().put("macName", "Test Mac").put("active", JSONObject().put("desktop", JSONObject()))
            .put("windows", org.json.JSONArray().put(JSONObject().put("id", 42).put("title", "Notes").put("appName", "TextEdit")
                .put("bundleID", "com.apple.TextEdit").put("width", 800).put("height", 600)))
            .put("clipboard", 1)
        s.outputStream.write(Wire.serialize(Kind.WINDOW_LIST, list.toString().toByteArray()))
        s.outputStream.flush()
        repeat(6) {
            val m = Wire.read(input)
            received.add("session" to m)
            if (m.kind == Kind.PING) { s.outputStream.write(Wire.serialize(Kind.PONG, m.payload)); s.outputStream.flush() }
        }
        val bye = JSONObject().put("reason", "quit").put("message", "Sill was quit on Test Mac.")
        s.outputStream.write(Wire.serialize(Kind.GOODBYE, bye.toString().toByteArray()))
        s.outputStream.flush()
        Thread.sleep(200)
    }

    @After fun stop() { server?.close() }

    @Test fun pairs_with_the_code_then_streams_pinned() {
        val port = startMac()
        val client = PairingClient(device, "Pixel Test")

        // A tap at home asks first; the Mac answers "shown" and we learn its key.
        val asked = client.ask("127.0.0.1", port)
        assertTrue("$asked", asked is PairingClient.Outcome.CodeShown)
        assertArrayEquals(macFp, (asked as PairingClient.Outcome.CodeShown).macFingerprint)

        // A wrong code is refused with the Mac's reason.
        val wrong = client.pairWithCode("127.0.0.1", port, "482913557201", macFp)
        assertTrue("$wrong", wrong is PairingClient.Outcome.Failed)

        // The right code pairs: proof_M checks and the Mac ID is the key's.
        val ok = client.pairWithCode("127.0.0.1", port, code, macFp)
        assertTrue("$ok", ok is PairingClient.Outcome.Paired)
        val saved = (ok as PairingClient.Outcome.Paired).mac
        assertEquals(MacId.make(macFp), saved.macID)
        assertEquals("Test Mac", saved.name)
        assertArrayEquals(macFp, saved.pin)
        assertArrayEquals(recognitionKey, saved.recognitionKeyBytes)

        // Every connection presented this device's certificate.
        repeat(3) { assertArrayEquals(device.fingerprint, clientFps.poll(5, TimeUnit.SECONDS)) }
        val ask = received.poll(5, TimeUnit.SECONDS)!!.second
        assertEquals(Kind.PAIR_REQUEST, ask.kind)
        assertEquals("ask", JSONObject(String(ask.payload)).getString("method"))
        received.clear()

        // A session pinned to the saved key: the hello first, then the source; the window list arrives.
        val session = StreamSession(Route("127.0.0.1", port, true, saved.pin, "test"), device, "Pixel Test", "0.1.0", "1")
        session.viewport(400.0, 800.0, null, 60)
        session.start(StreamSource.Desktop)
        val first = received.poll(5, TimeUnit.SECONDS)?.second ?: error("nothing received; session state ${session.state.value}; server: ${serverErrors.toList()} alpns ${alpns.toList()}")
        assertEquals(Kind.HELLO, first.kind)
        val hello = JSONObject(String(first.payload))
        assertEquals(1, hello.getInt("protocol"))
        assertEquals("Pixel Test", hello.getString("device"))
        val second = received.poll(5, TimeUnit.SECONDS)!!.second
        assertEquals(Kind.SELECT_SOURCE, second.kind)
        assertTrue(JSONObject(String(second.payload)).has("desktop"))
        val third = received.poll(5, TimeUnit.SECONDS)!!.second
        assertEquals(Kind.VIEWPORT, third.kind)
        // Aa Off by default, as Sill's own app: no scale, so the Mac leaves the window alone.
        assertFalse(JSONObject(String(third.payload)).has("scale"))

        // The goodbye ends the session with the Mac's words.
        val deadline = System.currentTimeMillis() + 5000
        while (session.state.value !is StreamSession.State.Ended && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val list = session.windowList.value
        assertNotNull(list)
        assertEquals(42L, list!!.windows.single().id)
        val ended = session.state.value as StreamSession.State.Ended
        assertEquals("Sill was quit on Test Mac.", ended.message)
        // It subscribed to the clipboard, since the list said the Mac has one.
        val kinds = generateSequence { received.poll() }.map { it.second.kind }.toList()
        assertTrue("kinds after the viewport: $kinds", Kind.CLIPBOARD in kinds)
    }

    @Test fun a_different_mac_key_is_refused_before_anything_is_sent() {
        val port = startMac()
        try {
            Tls.connect("127.0.0.1", port, Tls.SESSION_ALPN, ByteArray(32) { 1 }, device)
            fail("connected with the wrong pin")
        } catch (e: Exception) {
            var c: Throwable? = e
            var found = false
            while (c != null) { if (c is Tls.PinMismatch) found = true; c = c.cause }
            assertTrue("not a pin mismatch: $e", found)
        }
        Thread.sleep(200)
        assertTrue("the Mac received a message", received.isEmpty())
    }
}
