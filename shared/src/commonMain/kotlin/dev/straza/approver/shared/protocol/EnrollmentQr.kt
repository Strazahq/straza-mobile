package dev.straza.approver.shared.protocol

import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An SPKI pin: SHA-256 over the server's SubjectPublicKeyInfo. The admin
 * console puts it in the enrollment QR, which is how a private-CA or
 * self-signed strazad is trusted without a public certificate. This is not
 * trust on first use: the pin arrives out of band, in the QR.
 */
class SpkiPin private constructor(val sha256: ByteArray) {

    /** Constant-time comparison. A pin is not a secret; this is a precaution. */
    fun matches(candidateSha256: ByteArray): Boolean {
        if (candidateSha256.size != sha256.size) return false
        var diff = 0
        for (i in sha256.indices) diff = diff or (sha256[i].toInt() xor candidateSha256[i].toInt())
        return diff == 0
    }

    /** The QR and wire form, used to persist the enrollment. */
    val encoded: String get() = PREFIX + Base64.Default.encode(sha256)

    override fun toString(): String = "SpkiPin(sha256/…)"

    companion object {
        internal const val PREFIX = "sha256/"
        private const val SHA256_BYTES = 32

        /** Returns null for anything that is not a well-formed `sha256/<base64>` pin. */
        fun parse(value: String): SpkiPin? {
            if (!value.startsWith(PREFIX)) return null
            val decoded = try {
                Base64.Default.decode(value.removePrefix(PREFIX))
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (decoded.size != SHA256_BYTES) return null
            return SpkiPin(decoded)
        }
    }
}

/**
 * A deployment's identity from the server: a stable [id] (`prj_<uuidv7>`) that
 * enrollments are keyed on, and a display [name] that may collide across
 * deployments and change over time. Not a secret.
 */
data class ProjectRef(val id: String, val name: String)

/** A validated enrollment QR payload. */
data class EnrollmentPayload(
    /** Tried in order: internal/VPN URL first, external second. */
    val servers: List<String>,
    val enrollToken: String,
    val pin: SpkiPin?,
    /**
     * The deployment being enrolled, when the QR carries it, so the app can
     * show its name before pairing. Older servers omit it. The enroll response
     * is the authoritative source.
     */
    val project: ProjectRef? = null,
) {
    // Redacted so the enroll token cannot reach a log or a crash report.
    override fun toString(): String =
        "EnrollmentPayload(servers=$servers, token=<redacted>, pin=$pin, project=$project)"
}

/**
 * Parses the enrollment QR payload. Camera input is untrusted, so parsing
 * returns a result and does not throw, accepts nothing partially, and rejects
 * anything it does not fully understand.
 */
object EnrollmentQr {

    sealed interface Result {
        data class Ok(val payload: EnrollmentPayload) : Result

        /** [reason] is safe to log: it contains no payload values. */
        data class Invalid(val reason: String) : Result
    }

    /** Supported payload version. Any other version is refused. */
    private const val SUPPORTED_VERSION = 1

    /** A deployment lists an internal and an external URL, perhaps a couple more. */
    private const val MAX_SERVERS = 8

    private val json = Json {
        // A field added by a newer server must not break enrollment on an older app.
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Serializable
    private data class Wire(
        val v: Int? = null,
        val servers: List<String>? = null,
        val token: String? = null,
        val pin: String? = null,
        @SerialName("device_name") val deviceName: String? = null,
        val project: WireProject? = null,
    )

    @Serializable
    private data class WireProject(val id: String? = null, val name: String? = null)

    fun parse(raw: String): Result {
        val wire = try {
            json.decodeFromString<Wire>(unwrap(raw))
        } catch (_: Exception) {
            // Broad catch: kotlinx-serialization raises several exception
            // types, and the scanner must not crash on an unrelated QR code.
            return Result.Invalid("not a valid enrollment payload")
        }

        if (wire.v == null) return Result.Invalid("missing version")
        if (wire.v != SUPPORTED_VERSION) {
            // No best-effort read of a future format: the same field names
            // may mean something else, and a wrong guess trusts the wrong server.
            return Result.Invalid("unsupported enrollment version ${wire.v}; update the app")
        }

        val servers = wire.servers.orEmpty()
        if (servers.isEmpty()) return Result.Invalid("no servers in payload")
        if (servers.size > MAX_SERVERS) return Result.Invalid("too many servers in payload")

        // One non-https entry rejects the whole list. Servers are tried in
        // order when the first is unreachable, so a plaintext fallback would
        // be used under conditions an attacker can force.
        servers.forEachIndexed { i, s ->
            if (!isHttpsUrl(s)) return Result.Invalid("server #${i + 1} is not an https URL")
        }

        val token = wire.token
        if (token.isNullOrBlank()) return Result.Invalid("missing enrollment token")

        val pin = wire.pin?.let {
            SpkiPin.parse(it) ?: return Result.Invalid("malformed SPKI pin")
        }

        // `project` is optional; older servers omit it. Without an id it is
        // treated as absent, and the app falls back to a host-derived key.
        val project = wire.project?.let { p ->
            if (p.id.isNullOrBlank()) null else ProjectRef(p.id, p.name.orEmpty())
        }

        return Result.Ok(
            EnrollmentPayload(servers = servers, enrollToken = token, pin = pin, project = project),
        )
    }

    /**
     * Unwraps a base64-packaged payload, if that is what this is. The decoded
     * bytes go through the same validation as raw JSON. Real QR codes carry
     * the JSON directly; base64 is for delivering a payload by other means,
     * such as `adb shell input text`, where the shell strips the quotes.
     */
    private fun unwrap(raw: String): String {
        val trimmed = raw.trim()
        // Anything not starting with '{' is tried as base64 once, falling back
        // to the original so the error is about the payload, not the encoding.
        if (trimmed.startsWith("{")) return trimmed
        return try {
            Base64.Default.decode(trimmed).decodeToString()
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * Scheme check only, done by hand: URL parsers differ across platforms on
     * `//host`, userinfo and backslashes, and both platforms must behave the
     * same here. Full URL validity is left to the HTTP client. This decides
     * only whether the bytes could travel in the clear.
     */
    private fun isHttpsUrl(value: String): Boolean {
        if (!value.startsWith("https://", ignoreCase = true)) return false
        val host = value.substring("https://".length).substringBefore('/')
        return host.isNotBlank() && '\n' !in value && '\r' !in value && ' ' !in value
    }
}
