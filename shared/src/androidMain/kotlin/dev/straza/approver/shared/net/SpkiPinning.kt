package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.SpkiPin
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * SPKI certificate pinning. Uses only `javax.net.ssl` and `java.security`
 * (no `android.*`), so the same code runs in host tests.
 *
 * Pinned (private CA or self-signed strazad): trust is decided only by
 * whether a certificate's SubjectPublicKeyInfo hashes to the pin from the QR
 * code; the system trust store is not consulted. Unpinned (public
 * certificate): ordinary system trust.
 *
 * Hostname verification stays enabled in both modes. The pin already proves
 * which key is on the other end, and the default verifier costs nothing.
 */
object SpkiPinning {

    /**
     * Thrown when the chain is well-formed but carries the wrong key. A
     * distinct type, so the client can tell this apart from every other TLS
     * failure by type and route the user to re-enrollment.
     */
    class PinMismatchException : CertificateException("server key does not match the pinned key")

    /** Builds a socket factory that enforces [pin], or system trust when [pin] is null. */
    fun socketFactory(pin: SpkiPin?): SSLSocketFactory {
        val trustManagers: Array<TrustManager> = if (pin == null) {
            systemTrustManagers()
        } else {
            arrayOf(PinnedTrustManager(pin))
        }
        return SSLContext.getInstance("TLS")
            .apply { init(null, trustManagers, null) }
            .socketFactory
    }

    /** SHA-256 over the certificate's SubjectPublicKeyInfo, which is what the pin names. */
    fun spkiSha256(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)

    private fun systemTrustManagers(): Array<TrustManager> =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers

    /**
     * Accepts a chain only if one of its certificates matches the pin. The
     * whole chain is checked, not only the leaf, so an operator can pin a
     * private CA and rotate leaf certificates without re-enrolling every phone.
     */
    private class PinnedTrustManager(private val pin: SpkiPin) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")

            val matched = chain.any { pin.matches(spkiSha256(it)) }
            if (!matched) {
                // A pin failure has no bypass. The message carries no key
                // material or host detail.
                throw PinMismatchException()
            }

            // An expired leaf is rejected even when its key matches the pin.
            chain.first().checkValidity()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // The app is never a TLS server.
            throw CertificateException("client authentication is not supported")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
