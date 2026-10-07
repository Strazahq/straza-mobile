package dev.straza.approver.shared.net

/**
 * The result of one HTTPS exchange with one server, before protocol
 * interpretation. Statuses are not mapped here: a 401 is still an [Answer],
 * and [StrazaApi] does the mapping.
 */
sealed interface HttpOutcome {

    /**
     * The server answered, with any status. [retryAfterSeconds] is the
     * `Retry-After` header when the server sent it as delta-seconds (429/503).
     */
    data class Answer(val status: Int, val body: String, val retryAfterSeconds: Int? = null) : HttpOutcome

    /** Could not get there: no route, refused, timeout, DNS. */
    data class Unreachable(val reason: String) : HttpOutcome

    /** The pin did not match, or TLS could not verify the peer. See [ApiResult.Untrusted]. */
    data class Untrusted(val reason: String) : HttpOutcome
}

/**
 * One HTTPS exchange, with the TLS trust decision made in platform code:
 * `javax.net.ssl` and SPKI pinning on Android, URLSession and SecTrust on iOS.
 *
 * Implementations must:
 * - use TLS only, enforce a pinned enrollment with no bypass, and keep
 *   hostname verification on whether or not a pin is set;
 * - classify failures by exception type, not message text, so a locale change
 *   cannot reclassify a trust failure;
 * - do blocking I/O off the caller's thread;
 * - follow no redirects and keep no cache and no cookies.
 */
interface PinnedTransport {
    suspend fun exchange(
        server: String,
        path: String,
        method: String,
        body: String?,
        bearer: String?,
    ): HttpOutcome
}

/**
 * Parses an RFC 3339 timestamp (`expires_at`, `decided_at`; Go's RFC3339Nano
 * included) to epoch seconds, or null if it does not parse. A bad timestamp
 * costs one countdown or label, not the whole list. `java.time` on Android,
 * `NSISO8601DateFormatter` on iOS.
 */
internal expect fun parseRfc3339EpochSeconds(raw: String): Long?

/**
 * Parses the `Retry-After` header (429/503) to seconds, or null. Only the
 * delta-seconds form is honoured; the HTTP-date form, which strazad does not
 * send, reads as null and the caller's default backoff applies. A negative
 * or malformed value is null too.
 */
fun parseRetryAfterSeconds(header: String?): Int? =
    header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
