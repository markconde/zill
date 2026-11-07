package dev.mrkcnd.zill.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.mrkcnd.zill.protocol.Spki
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's P-256 identity (RemoteIdentity.swift): the Mac trusts the key itself, pinned as the
 * SHA-256 of its SubjectPublicKeyInfo, so the certificate is a bare self-signed one, built here the
 * way SelfSignedCertificate.make builds it. The private key is kept in app storage wrapped with an
 * AES key that lives in the Android Keystore.
 */
class DeviceIdentity internal constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** SHA-256 of the SPKI: what the Mac pins. */
    val fingerprint: ByteArray = Spki.fingerprint(certificate.publicKey.encoded)

    companion object {
        private const val PREFS = "identity"
        private const val WRAP_ALIAS = "sill-identity-wrap"

        @Volatile private var cached: DeviceIdentity? = null

        fun get(context: Context): DeviceIdentity = cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

        private fun load(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wrapped = prefs.getString("key", null)
            val certB64 = prefs.getString("cert", null)
            if (wrapped != null && certB64 != null) {
                runCatching {
                    val pkcs8 = unwrap(Base64.decode(wrapped, Base64.NO_WRAP))
                    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                    val cert = parseCert(Base64.decode(certB64, Base64.NO_WRAP))
                    return DeviceIdentity(key, cert)
                }
            }
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val pair = kpg.generateKeyPair()
            val spki = pair.public.encoded
            require(spki.size == 91) { "unexpected SPKI encoding" }
            val der = SelfSigned.make(pair.private, spki)
            val cert = parseCert(der)
            prefs.edit()
                .putString("key", Base64.encodeToString(wrap(pair.private.encoded), Base64.NO_WRAP))
                .putString("cert", Base64.encodeToString(der, Base64.NO_WRAP))
                .apply()
            return DeviceIdentity(pair.private, cert)
        }

        private fun parseCert(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        private fun wrapKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        private fun wrap(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapKey())
            return c.iv + c.doFinal(plain)
        }

        private fun unwrap(blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }
}

/** A minimal DER X.509 v3 self-signed certificate, as RemoteIdentity.swift's SelfSignedCertificate. */
internal object SelfSigned {
    private val ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun make(key: PrivateKey, spki: ByteArray): ByteArray {
        val rnd = SecureRandom()
        val cn = ByteArray(8).also(rnd::nextBytes)
        val serial = ByteArray(16).also(rnd::nextBytes)
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
        val hexName = cn.joinToString("") { "%02x".format(it) }
        val name = seq(set(seq(oid(COMMON_NAME), tlv(0x0C, hexName.toByteArray()))))
        val algorithm = seq(oid(ECDSA_WITH_SHA256))
        val tbs = seq(
            tlv(0xA0, integer(byteArrayOf(2))),
            integer(serial),
            algorithm,
            name,
            seq(tlv(0x17, "260101000000Z".toByteArray()), tlv(0x18, "99991231235959Z".toByteArray())),
            name,
            spki,
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(tbs); sign()
        }
        return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var v = n
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v shr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + length(content.size) + content
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun oid(b: ByteArray) = tlv(0x06, b)
    private fun integer(raw: ByteArray): ByteArray {
        var b = if (raw.isEmpty()) byteArrayOf(0) else raw
        while (b.size > 1 && b[0].toInt() == 0 && (b[1].toInt() and 0x80) == 0) b = b.copyOfRange(1, b.size)
        if ((b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
        return tlv(0x02, b)
    }
}
