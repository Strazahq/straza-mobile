package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.EnrollmentPayload
import dev.straza.approver.shared.protocol.SpkiPin
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.credentialForTrust
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.serverTrust
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The iOS client: the shared [StrazaApi] protocol engine over an URLSession
 * transport whose TLS trust decision is [DarwinSpkiPinning]. This file holds
 * only the transport and its failure classification; paths, bodies, parsing
 * and status mapping live in the common [StrazaApi].
 */
class StrazaClient(
    servers: List<String>,
    pin: SpkiPin?,
    timeoutSeconds: Double = 15.0,
) : ApprovalApi by StrazaApi(
    transport = DarwinTransport(pin, timeoutSeconds),
    servers = servers,
    platform = "ios",
) {
    constructor(payload: EnrollmentPayload) : this(payload.servers, payload.pin)
}

@OptIn(ExperimentalForeignApi::class)
private class DarwinTransport(
    private val pin: SpkiPin?,
    private val timeoutSeconds: Double,
) : PinnedTransport {

    override suspend fun exchange(
        server: String,
        path: String,
        method: String,
        body: String?,
        bearer: String?,
    ): HttpOutcome {
        val url = NSURL.URLWithString(server + path)
            ?: return HttpOutcome.Unreachable("malformed server URL")

        // A fresh session per exchange: the challenge delegate records why it
        // rejected a trust, and sharing one delegate across concurrent
        // requests would race that verdict.
        val delegate = PinningDelegate(pin)
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            // Ephemeral: no disk cache and no persistent cookies.
            timeoutIntervalForRequest = timeoutSeconds
            timeoutIntervalForResource = timeoutSeconds
        }
        val session = NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue = null)
        try {
            return suspendCancellableCoroutine { continuation ->
                val request = NSMutableURLRequest.requestWithURL(url).apply {
                    HTTPMethod = method
                    setValue("application/json", forHTTPHeaderField = "Accept")
                    bearer?.let { setValue("Bearer $it", forHTTPHeaderField = "Authorization") }
                    if (body != null) {
                        setValue("application/json", forHTTPHeaderField = "Content-Type")
                        HTTPBody = body.encodeToByteArray().toNSData()
                    }
                }
                val task = session.dataTaskWithRequest(
                    request,
                ) { data: NSData?, response: NSURLResponse?, error: NSError? ->
                    continuation.resume(interpret(delegate, data, response, error))
                }
                continuation.invokeOnCancellation { task.cancel() }
                task.resume()
            }
        } finally {
            session.finishTasksAndInvalidate()
        }
    }

    private fun interpret(
        delegate: PinningDelegate,
        data: NSData?,
        response: NSURLResponse?,
        error: NSError?,
    ): HttpOutcome {
        // The delegate's verdict outranks the error code: a cancelled challenge
        // surfaces as NSURLErrorCancelled, which would otherwise read as a
        // network failure instead of a trust failure.
        delegate.trustRejection?.let { return HttpOutcome.Untrusted(it) }

        if (error != null) {
            // Classified by code, not by localizedDescription, which varies
            // with locale.
            return when (error.code) {
                NSURLErrorSecureConnectionFailed,
                NSURLErrorServerCertificateHasBadDate,
                NSURLErrorServerCertificateUntrusted,
                NSURLErrorServerCertificateHasUnknownRoot,
                NSURLErrorServerCertificateNotYetValid,
                NSURLErrorClientCertificateRejected,
                NSURLErrorClientCertificateRequired,
                -> HttpOutcome.Untrusted("NSURLError ${error.code}")
                NSURLErrorCancelled -> HttpOutcome.Unreachable("cancelled")
                else -> HttpOutcome.Unreachable("NSURLError ${error.code}")
            }
        }

        val http = response as? NSHTTPURLResponse
            ?: return HttpOutcome.Unreachable("non-HTTP response")
        return HttpOutcome.Answer(
            http.statusCode.toInt(),
            data?.toUtf8String().orEmpty(),
            parseRetryAfterSeconds(http.valueForHTTPHeaderField("Retry-After")),
        )
    }
}

/**
 * Session-level challenge handling and redirect refusal. Pinned mode:
 * [DarwinSpkiPinning] decides; a rejection records its reason and cancels the
 * challenge, so the connection never completes. Unpinned mode: default
 * handling, which is ordinary system trust.
 *
 * Redirects are refused (the completion handler gets null): following one
 * would re-send the bearer to wherever the response pointed.
 */
@OptIn(ExperimentalForeignApi::class)
private class PinningDelegate(
    private val pin: SpkiPin?,
) : NSObject(), NSURLSessionTaskDelegateProtocol {

    @Volatile
    var trustRejection: String? = null
        private set

    override fun URLSession(
        session: NSURLSession,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        val space = didReceiveChallenge.protectionSpace
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            // The app never authenticates with TLS client certificates or HTTP
            // auth, so any other challenge gets the platform default.
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val activePin = pin
        if (activePin == null) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val trust = space.serverTrust
        if (trust == null) {
            trustRejection = "no server trust presented"
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }
        when (val verdict = DarwinSpkiPinning.evaluate(trust, activePin)) {
            DarwinSpkiPinning.Verdict.Trusted ->
                completionHandler(
                    NSURLSessionAuthChallengeUseCredential,
                    NSURLCredential.credentialForTrust(trust),
                )
            is DarwinSpkiPinning.Verdict.Rejected -> {
                trustRejection = verdict.reason
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        completionHandler(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), size.convert())
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toUtf8String(): String {
    val size = length.toInt()
    if (size == 0) return ""
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out.decodeToString()
}
