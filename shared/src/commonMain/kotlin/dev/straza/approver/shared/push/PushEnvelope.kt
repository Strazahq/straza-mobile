package dev.straza.approver.shared.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A push message, after defensive parsing. The push channel is a third-party
 * relay (FCM, APNs, self-hosted ntfy) and its payloads are untrusted input.
 *
 * A push carries an opaque reference only, `{"v":1,"ref":"…","kind":"…"}`:
 * `ref` is a request id and `kind` a UI hint, with no decision content. The
 * only action it may trigger is a fetch of server state over the
 * authenticated, pinned channel. `ref` is a focus hint, matched against ids
 * the server returns. Anything malformed becomes [Ignore] and the poll covers
 * the gap.
 */
sealed interface PushEnvelope {

    /** A well-formed push: go fetch. [hint] may steer which tab refreshes first. */
    data class Fetch(val ref: String, val hint: Hint) : PushEnvelope

    /** Unrecognized or malformed: wrong version, no ref, not JSON. Triggers nothing. */
    data object Ignore : PushEnvelope

    /** The `kind` UI hint. Unknown kinds are still a valid fetch, just untabbed. */
    enum class Hint { Decide, Status, Unknown }
}

object PushPayload {

    private val json = Json { ignoreUnknownKeys = true }

    private const val SUPPORTED_VERSION = 1

    /**
     * Parses a raw push data string. Does not throw: every failure becomes
     * [PushEnvelope.Ignore], so bad input cannot crash a background receiver.
     */
    fun parse(raw: String): PushEnvelope {
        // The payload can have any shape (a nested object where a string is
        // expected), hence `as? JsonPrimitive` on each field and one catch
        // around the whole parse.
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            fromData(
                v = (obj["v"] as? JsonPrimitive)?.content,
                ref = (obj["ref"] as? JsonPrimitive)?.content,
                kind = (obj["kind"] as? JsonPrimitive)?.content,
            )
        } catch (_: Exception) {
            PushEnvelope.Ignore
        }
    }

    /**
     * The already-split form: FCM delivers the envelope as a `data` map of
     * strings (`v`/`ref`/`kind`), not as a JSON string. Same validation as
     * [parse].
     */
    fun fromData(v: String?, ref: String?, kind: String?): PushEnvelope {
        // Version gate first: a future payload shape may reuse these field
        // names for something else.
        if (v?.toIntOrNull() != SUPPORTED_VERSION) return PushEnvelope.Ignore
        if (ref.isNullOrBlank()) return PushEnvelope.Ignore

        val hint = when (kind) {
            "decide" -> PushEnvelope.Hint.Decide
            "status" -> PushEnvelope.Hint.Status
            // An unrecognized or absent kind still fetches.
            else -> PushEnvelope.Hint.Unknown
        }
        return PushEnvelope.Fetch(ref = ref, hint = hint)
    }
}
