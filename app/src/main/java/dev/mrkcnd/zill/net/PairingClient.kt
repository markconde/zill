package dev.mrkcnd.zill.net

import android.os.Build
import dev.mrkcnd.zill.data.SavedMac
import dev.mrkcnd.zill.protocol.Base64Url
import dev.mrkcnd.zill.protocol.Kind
import dev.mrkcnd.zill.protocol.MacId
import dev.mrkcnd.zill.protocol.PairLink
import dev.mrkcnd.zill.protocol.PairResult
import dev.mrkcnd.zill.protocol.PairingProof
import dev.mrkcnd.zill.protocol.Wire
import dev.mrkcnd.zill.protocol.pairRequestJson
import java.io.DataInputStream
import java.security.MessageDigest

/**
 * One pairing connection (ALPN sill-pair/1) per call: a kind 19 out, a kind 20 back, then the Mac
 * closes it (docs/remote-access-plan.md §3.5–3.6, docs/home-pairing-plan.md §3). Blocking: call
 * from a background thread.
 */
class PairingClient(private val identity: DeviceIdentity, private val deviceName: String) {
    private val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    sealed class Outcome {
        data class Paired(val mac: SavedMac) : Outcome()
        /** The Mac shows a code (reason "shown"): pair with it next, pinned to [macFingerprint]. */
        data class CodeShown(val macFingerprint: ByteArray) : Outcome()
        data class Failed(val text: String, val result: PairResult? = null) : Outcome()
    }

    /** A tap on an unpaired Mac at home: "pair me" (method ask). Any key is taken; the next step pins it. */
    fun ask(host: String, port: Int): Outcome {
        val (result, fp) = exchange(host, port, null, pairRequestJson("ask", "", deviceName, model))
        result ?: return Outcome.Failed("The Mac did not answer.")
        if (result.ok) return Outcome.Failed("The Mac answered an ask with an unexpected pairing; nothing saved.")
        if (result.reason == "shown" && fp != null) return Outcome.CodeShown(fp)
        return Outcome.Failed(result.failureText(), result)
    }

    /** The device's Cancel after a "shown": the Mac closes its window. Best effort. */
    fun cancel(host: String, port: Int, pin: ByteArray?) {
        runCatching { exchange(host, port, pin, pairRequestJson("cancel", "", deviceName, model)) }
    }

    /** Pairs with the 12 digits the Mac shows. [pin] null takes any key: the proofs bind both keys. */
    fun pairWithCode(host: String, port: Int, code: String, pin: ByteArray?): Outcome {
        val conn = Tls.connect(host, port, Tls.PAIRING_ALPN, pin, identity)
        val macFp = conn.macFingerprint ?: return Outcome.Failed("The Mac presented no key.")
        val key = PairingProof.codeKey(code, macFp)
        return finish(conn, macFp, key, "code", host)
    }

    /** Pairs with a sill://pair link (the QR code's contents), pinned to its key before a byte is sent. */
    fun pairWithLink(link: PairLink, host: String, port: Int): Outcome {
        val conn = Tls.connect(host, port, Tls.PAIRING_ALPN, link.fingerprint, identity)
        return finish(conn, link.fingerprint, link.secret, "qr", host)
    }

    private fun finish(conn: Tls.Connected, macFp: ByteArray, key: ByteArray, method: String, host: String): Outcome {
        val proof = PairingProof.deviceProof(key, identity.fingerprint, macFp)
        val r = conn.socket.use { s ->
            s.getOutputStream().apply {
                write(Wire.serialize(Kind.PAIR_REQUEST, pairRequestJson(method, Base64Url.encode(proof), deviceName, model)))
                flush()
            }
            s.soTimeout = 15_000
            val m = Wire.read(DataInputStream(s.getInputStream().buffered())) { Wire.MAX_PAIRING_PAYLOAD }
            if (m.kind != Kind.PAIR_RESULT) null else PairResult.parse(m.payload)
        } ?: return Outcome.Failed("The Mac did not answer.")
        if (!r.ok) return Outcome.Failed(r.failureText(), r)
        val want = PairingProof.macProof(key, macFp, identity.fingerprint)
        val got = r.proof?.let { Base64Url.decode(it) }
        if (got == null || !MessageDigest.isEqual(got, want)) return Outcome.Failed("The Mac's proof doesn't check. Nothing was saved.")
        if (r.macID != MacId.make(macFp)) return Outcome.Failed("The Mac named another key. Nothing was saved.")
        return Outcome.Paired(
            SavedMac(
                macID = r.macID,
                fingerprint = Base64Url.encode(macFp),
                name = r.name?.takeIf { it.isNotBlank() } ?: "Mac",
                recognitionKey = r.recognitionKey,
            ),
        )
    }

    private fun exchange(host: String, port: Int, pin: ByteArray?, request: ByteArray): Pair<PairResult?, ByteArray?> {
        val conn = Tls.connect(host, port, Tls.PAIRING_ALPN, pin, identity)
        conn.socket.use { s ->
            s.getOutputStream().apply { write(Wire.serialize(Kind.PAIR_REQUEST, request)); flush() }
            s.soTimeout = 15_000
            val m = Wire.read(DataInputStream(s.getInputStream().buffered())) { Wire.MAX_PAIRING_PAYLOAD }
            val r = if (m.kind == Kind.PAIR_RESULT) PairResult.parse(m.payload) else null
            return r to conn.macFingerprint
        }
    }
}
