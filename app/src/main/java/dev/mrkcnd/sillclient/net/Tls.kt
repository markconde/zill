package dev.mrkcnd.sillclient.net

import dev.mrkcnd.sillclient.protocol.Spki
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Connections to a Mac (RemoteTLS.swift): TLS 1.3 only, this install's key as the client
 * certificate, the Mac's key pinned by its SPKI SHA-256 (the pin is the whole of trust: no CA, no
 * hostname, no dates), one ALPN, SNI "sill". A plain TCP connection for an old plain home door.
 */
object Tls {
    const val SESSION_ALPN = "sill/1"
    const val PAIRING_ALPN = "sill-pair/1"
    private const val SERVER_NAME = "sill"
    private const val DIAL_TIMEOUT_MS = 10_000

    class PinMismatch(val seen: ByteArray) : CertificateException("the Mac presented a key other than the one paired")

    class Connected(val socket: Socket, val macFingerprint: ByteArray?)

    fun plain(host: String, port: Int): Connected = Connected(tcp(host, port), null)

    /**
     * Dials [host]:[port] and completes the handshake. [pin] null accepts any P-256 key (the typed
     * code path, whose proofs bind both keys, and a home door with pairing off); otherwise a
     * different key fails the handshake before any byte of ours is sent.
     */
    fun connect(host: String, port: Int, alpn: String, pin: ByteArray?, identity: DeviceIdentity): Connected {
        val raw = tcp(host, port)
        try {
            val trust = PinningTrust(pin)
            val ctx = SSLContext.getInstance("TLSv1.3")
            ctx.init(arrayOf(OneKey(identity)), arrayOf(trust), null)
            val s = ctx.socketFactory.createSocket(raw, SERVER_NAME, port, true) as SSLSocket
            s.enabledProtocols = arrayOf("TLSv1.3")
            val params = s.sslParameters
            params.applicationProtocols = arrayOf(alpn)
            params.serverNames = listOf(SNIHostName(SERVER_NAME))
            s.sslParameters = params
            s.soTimeout = DIAL_TIMEOUT_MS
            s.startHandshake()
            s.soTimeout = 0
            if (s.applicationProtocol != alpn) {
                s.close()
                throw java.io.IOException("the Mac did not agree to $alpn")
            }
            return Connected(s, trust.seen)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    private fun tcp(host: String, port: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), DIAL_TIMEOUT_MS)
        return s
    }

    private class PinningTrust(private val pin: ByteArray?) : X509ExtendedTrustManager() {
        @Volatile var seen: ByteArray? = null

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val spki = leaf.publicKey.encoded
            if (spki.size != 91 || !spki.copyOfRange(0, Spki.P256_PREFIX.size).contentEquals(Spki.P256_PREFIX)) {
                throw CertificateException("not a P-256 key")
            }
            val fp = Spki.fingerprint(spki)
            seen = fp
            if (pin != null && !MessageDigest.isEqual(fp, pin)) throw PinMismatch(fp)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Offers this install's certificate to any request for one. */
    private class OneKey(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "me"
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "me"
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("me")
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
