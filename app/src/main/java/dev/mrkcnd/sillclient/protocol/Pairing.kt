package dev.mrkcnd.sillclient.protocol

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Pairing.swift, RemoteIdentity.swift and Remote.swift, ported. */
object Base64Url {
    fun encode(d: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(d)

    fun decode(t: String): ByteArray? {
        if (!t.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } || t.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()?.takeIf { encode(it) == t }
    }
}

object MacId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Crockford base32 of the fingerprint's first 10 bytes: 16 characters. */
    fun make(fingerprint: ByteArray): String {
        if (fingerprint.size < 10) return ""
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (i in 0 until 10) {
            buffer = (buffer shl 8) or (fingerprint[i].toInt() and 0xFF); bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return sb.toString()
    }

    fun isWellFormed(t: String) = t.length == 16 && t.all { it in ALPHABET }
}

object Spki {
    /** DER prefix of a P-256 SubjectPublicKeyInfo, before the 65-byte uncompressed point. */
    val P256_PREFIX: ByteArray = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")

    fun der(point: ByteArray): ByteArray = P256_PREFIX + point
    fun fingerprint(spkiDer: ByteArray): ByteArray = sha256(spkiDer)
    fun fingerprintOfPoint(point: ByteArray): ByteArray = sha256(der(point))
}

object PairingCode {
    private val TABLE = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3), intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
        intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6), intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7), intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
        intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )
    const val LENGTH = 12

    fun checkDigit(digits: List<Int>): Int = digits.fold(0) { i, d -> TABLE[i][d] }

    sealed class Check {
        data class Ok(val code: String) : Check()
        object Length : Check()
        object Typo : Check()
    }

    fun check(input: String): Check {
        val kept = input.filter { it != ' ' && it != '-' && it != ' ' }
        if (kept.length != LENGTH || !kept.all { it in '0'..'9' }) return Check.Length
        if (checkDigit(kept.map { it - '0' }) != 0) return Check.Typo
        return Check.Ok(kept)
    }
}

object PairingProof {
    const val ROUNDS = 600_000
    private const val LABEL = "sill-pair-v1"

    /** PBKDF2-HMAC-SHA256(code, "sill-pair-v1" + macFingerprint, 600 000, 32). Slow on purpose: call off the main thread. */
    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray {
        val salt = LABEL.toByteArray() + macFingerprint
        val spec = PBEKeySpec(code.toCharArray(), salt, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deviceProof(key: ByteArray, deviceFp: ByteArray, macFp: ByteArray): ByteArray =
        hmac(key, "$LABEL device".toByteArray() + byteArrayOf(0) + deviceFp + macFp)

    fun macProof(key: ByteArray, macFp: ByteArray, deviceFp: ByteArray): ByteArray =
        hmac(key, "$LABEL mac".toByteArray() + byteArrayOf(0) + macFp + deviceFp)
}

object RecognitionTag {
    const val TXT_KEY = "r"
    private const val LABEL = "sill-tag-v1"

    /** A Bonjour TXT `r` tag: base64url(6-byte nonce + first 6 bytes of HMAC(key, label + nonce)). */
    fun matches(tag: String, recognitionKey: ByteArray): Boolean {
        val bytes = Base64Url.decode(tag) ?: return false
        if (bytes.size != 12) return false
        val nonce = bytes.copyOfRange(0, 6)
        val expected = hmac(recognitionKey, LABEL.toByteArray() + nonce).copyOfRange(0, 6)
        return MessageDigest.isEqual(expected, bytes.copyOfRange(6, 12))
    }
}

/** The TXT record's `p`: absent → a plain (non-TLS) home door; "0" → TLS, pairing not required; else TLS, pairing required. */
enum class HomeDoor { PLAIN, OPEN, PAIRING_REQUIRED;

    companion object {
        fun from(txt: Map<String, String?>): HomeDoor {
            if (!txt.containsKey("p") && !txt.containsKey("P")) return PLAIN
            val v = txt["p"] ?: txt["P"]
            return if (v == "0") OPEN else PAIRING_REQUIRED
        }
    }
}

/** A sill://pair link, as the Mac's QR code carries it. */
data class PairLink(
    val macID: String,
    val fingerprint: ByteArray,
    val secret: ByteArray,
    val name: String,
    val port: Int,
    val addresses: List<String>,
) {
    companion object {
        fun parse(text: String): PairLink? = runCatching {
            val uri = URI(text.trim())
            if (!uri.scheme.equals("sill", true) || !uri.host.equals("pair", true)) return null
            val items = (uri.rawQuery ?: return null).split('&').filter { it.isNotEmpty() }.map {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
            fun single(k: String): String? = items.filter { it.first == k }.takeIf { it.size == 1 }?.first()?.second?.takeIf { it.isNotEmpty() }
            if (single("v") != "1") return null
            val m = single("m")?.takeIf { MacId.isWellFormed(it) } ?: return null
            val fp = single("k")?.let { Base64Url.decode(it) }?.takeIf { it.size == 32 } ?: return null
            val s = single("s")?.let { Base64Url.decode(it) }?.takeIf { it.size == 16 } ?: return null
            val p = single("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (m != MacId.make(fp)) return null
            val name = items.firstOrNull { it.first == "n" }?.second?.takeIf { it.isNotBlank() } ?: "Mac"
            PairLink(m, fp, s, name, p, items.filter { it.first == "a" }.map { it.second }.take(5))
        }.getOrNull()
    }
}

/** A kind 19 payload. */
fun pairRequestJson(method: String, proof: String, name: String, model: String, cable: Boolean = false): ByteArray {
    val o = JSONObject().put("v", 1).put("method", method).put("proof", proof).put("name", name).put("model", model)
    if (cable) o.put("cable", true)
    return o.toString().toByteArray()
}

data class PairResult(
    val ok: Boolean,
    val proof: String?,
    val macID: String?,
    val name: String?,
    val recognitionKey: String?,
    val reason: String?,
    val triesLeft: Int?,
    val retryAfter: Double?,
    val method: String?,
    val message: String?,
) {
    fun failureText(): String = when (reason) {
        "code" -> "That code doesn't match the Mac's." + (triesLeft?.let { " $it tries left." } ?: "")
        "closed" -> "The Mac isn't showing a pairing code. Choose Pair iPhone or iPad… on the Mac and try again."
        "expired" -> "The code expired. Show a new one on the Mac."
        "stopped" -> "The Mac stopped pairing after too many wrong codes." + (retryAfter?.let { " Try again in ${it.toInt()} s." } ?: "")
        "busy" -> "The Mac is pairing another device. Try again in a moment."
        "openOnMac" -> "On the Mac, choose Pair iPhone or iPad… from Sill's menu, then try again."
        "locked" -> "The Mac is locked. Unlock it and try again."
        "shown" -> "The Mac shows a pairing code now."
        else -> message?.takeIf { it.isNotBlank() }?.take(300) ?: "Pairing failed (${reason ?: "unknown"})."
    }

    companion object {
        fun parse(bytes: ByteArray): PairResult? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            PairResult(
                o.optBoolean("ok", false), s("proof"), s("macID"), s("name"), s("recognitionKey"), s("reason"),
                if (o.has("triesLeft")) o.optInt("triesLeft") else null,
                if (o.has("retryAfter")) o.optDouble("retryAfter") else null,
                s("method"), s("message"),
            )
        }.getOrNull()
    }
}

data class MacAddress(val host: String, val port: Int?, val kind: String, val via: String)

data class MacInfo(
    val macID: String,
    val name: String,
    val remoteAccess: Boolean,
    val remotePort: Int,
    val addresses: List<MacAddress>,
    val fingerprint: ByteArray,
) {
    companion object {
        /** Kind 18 (SignedMacInfo): verifies the ECDSA signature over the info bytes and that macID is the key's. */
        fun verify(payload: ByteArray): MacInfo? = runCatching {
            val o = JSONObject(String(payload, Charsets.UTF_8))
            val info = Base64.getDecoder().decode(o.getString("info"))
            val point = Base64.getDecoder().decode(o.getString("key"))
            val sig = Base64.getDecoder().decode(o.getString("sig"))
            if (point.size != 65) return null
            val spki = Spki.der(point)
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(info)
            if (!verifier.verify(sig)) return null
            val fp = Spki.fingerprint(spki)
            val i = JSONObject(String(info, Charsets.UTF_8))
            if (i.optString("macID") != MacId.make(fp)) return null
            val arr = i.optJSONArray("addresses")
            val addrs = (0 until (arr?.length() ?: 0)).mapNotNull { n ->
                arr!!.optJSONObject(n)?.let {
                    MacAddress(it.optString("host"), if (it.has("port")) it.optInt("port") else null, it.optString("kind"), it.optString("via"))
                }
            }
            MacInfo(i.optString("macID"), i.optString("name"), i.optBoolean("remoteAccess"), i.optInt("remotePort", 7455), addrs, fp)
        }.getOrNull()
    }
}

// MARK: helpers

fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

fun hmac(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(message)
}

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
