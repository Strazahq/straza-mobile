package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.EnrollmentPayload
import dev.straza.approver.shared.protocol.SpkiPin
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android client: the shared [StrazaApi] protocol engine over an
 * `HttpsURLConnection` transport whose TLS trust decision is [SpkiPinning].
 * This file holds only the transport and its failure classification; paths,
 * bodies, parsing and status mapping live in the common [StrazaApi].
 */
class StrazaClient(
    servers: List<String>,
    pin: SpkiPin?,
    timeoutMillis: Int = 15_000,
) : ApprovalApi by StrazaApi(
    transport = HttpUrlConnectionTransport(pin, timeoutMillis),
    servers = servers,
    platform = "android",
) {
    constructor(payload: EnrollmentPayload) : this(payload.servers, payload.pin)
}

private class HttpUrlConnectionTransport(
    pin: SpkiPin?,
    private val timeoutMillis: Int,
) : PinnedTransport {

    private val socketFactory = SpkiPinning.socketFactory(pin)

    override suspend fun exchange(
        server: String,
        path: String,
        method: String,
        body: String?,
        bearer: String?,
    ): HttpOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(server + path).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                requestMethod = method
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }

            val status = connection.responseCode
            val payload = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpOutcome.Answer(status, payload, parseRetryAfterSeconds(connection.getHeaderField("Retry-After")))
        } catch (e: IOException) {
            // Either classification fails closed. The split only decides which
            // way out the user is offered.
            classify(e)
        } catch (e: Exception) {
            classify(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Separates "could not get there" from "got there, wrong key". JSSE reports
     * both as [IOException] subtypes, and a pin mismatch arrives as an
     * `SSLHandshakeException` wrapping what the trust manager threw, so the
     * cause chain is walked. Classification is by exception type only: message
     * text changes with locale and JDK version.
     */
    private fun classify(e: Throwable): HttpOutcome {
        var cause: Throwable? = e
        // Depth-bounded, so a cyclic cause chain cannot hang the request.
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
            when (cause) {
                // The pin check's own message names no host or key material.
                is SpkiPinning.PinMismatchException -> return HttpOutcome.Untrusted(cause.message.orEmpty())

                // Any other certificate rejection (system-trust path building,
                // expiry) or an unverified peer (hostname mismatch). Reported by
                // type name only: platform messages can carry certificate
                // subject detail.
                is CertificateException, is SSLPeerUnverifiedException ->
                    return HttpOutcome.Untrusted(cause.javaClass.simpleName)
            }
            cause = cause.cause.takeIf { it !== cause }
        }
        return HttpOutcome.Unreachable(e.javaClass.simpleName)
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 16
    }
}
