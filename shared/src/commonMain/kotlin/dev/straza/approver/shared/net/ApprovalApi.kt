package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.ProjectRef
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DevicePublicKey

/**
 * The `code` on an approver 401 or 403 envelope. Only [DEVICE_REVOKED]
 * destroys the device key; every other value, [UNKNOWN] included, keeps it.
 */
enum class AuthRejection(val wire: String) {
    /** The token expired. Renew it; the device key is unaffected. */
    TOKEN_EXPIRED("token_expired"),

    /** The credential failed verification. Renewal re-authenticates with the device key. */
    TOKEN_INVALID("token_invalid"),

    /** No bearer reached the server: an app bug, not a key problem. */
    MISSING_TOKEN("missing_token"),

    /** The device row is gone. The one code that destroys the key. */
    DEVICE_REVOKED("device_revoked"),

    /** The bound user is deactivated. The key is kept, so reactivation restores the pairing. */
    USER_INACTIVE("user_inactive"),

    /**
     * A signature endpoint rejected the request: stale `ts` (clock skew), a
     * signature that did not verify, or a spent or unknown challenge. The key
     * is fine. Older servers send no code for this, which reads as [UNKNOWN].
     */
    CHALLENGE_REJECTED("challenge_rejected"),

    /**
     * A 403: the bound user no longer holds an approve role for this rule.
     * Mapped from the status, because older servers send no code. A role can
     * be granted again, so the key is kept.
     */
    FORBIDDEN("not_authorized"),

    /**
     * No `code` (a server before openapi 0.23.0, or a proxy's non-JSON 401),
     * or a code newer than this build. The key is kept: an unreadable
     * rejection is not proof of revocation.
     */
    UNKNOWN("");

    /**
     * True when the app must destroy the device key and retire the deployment.
     * Every other rejection goes to token renewal. If the device row is gone,
     * a current server answers renewal with `device_revoked`; an older one
     * answers 404, and the user is told to re-pair.
     */
    val destroysKey: Boolean get() = this == DEVICE_REVOKED

    companion object {
        /** Wire code to reason. Absent, blank, or unrecognised is [UNKNOWN]. */
        fun fromWire(code: String?): AuthRejection =
            entries.firstOrNull { it != UNKNOWN && it.wire == code } ?: UNKNOWN
    }
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 409: already resolved elsewhere. Show the final state, not an Approve button. */
    data class AlreadyResolved(val state: String) : ApiResult<Nothing>

    /** 401 or 403: the credential was rejected. [AuthRejection] says what [reason] means for the key. */
    data class Unauthorized(val reason: AuthRejection = AuthRejection.UNKNOWN) : ApiResult<Nothing>

    /** Could not reach the server: no route, refused connection, timeout. No decision is possible. */
    data class Unreachable(val reason: String) : ApiResult<Nothing>

    /**
     * Reached something that did not present the pinned key: a rotated
     * certificate, a replaced private CA, or an attacker in the path. Waiting
     * does not fix it. The exit is re-enrollment, since a fresh QR carries a
     * fresh pin.
     */
    data class Untrusted(val reason: String) : ApiResult<Nothing>

    /**
     * Any other 4xx/5xx. [message] is the server's `error` text when the body
     * was a parseable error envelope. Some screens show it as is, such as the
     * push allowlist 400, where only that text names the host and the setting.
     */
    data class ServerError(
        val status: Int,
        val message: String? = null,
        /** `Retry-After` in seconds when the server sent one (429/503); see [parseRetryAfterSeconds]. */
        val retryAfterSeconds: Int? = null,
    ) : ApiResult<Nothing>
}

data class RenewedToken(
    val deviceToken: String,
    /** Seconds until the new token expires (about 30 days). */
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "RenewedToken(token=<redacted>, expiresIn=$expiresInSeconds)"
}

/**
 * The deployment's public Firebase app config. None of the four values is a
 * credential, and the FCM sending credential never reaches this app. The
 * enroll response carries it only when the deployment configured all four;
 * without it this pairing has no FCM route. Serializable because the play
 * flavor persists it.
 */
@kotlinx.serialization.Serializable
data class FcmAppConfig(
    /** `project_info.project_id`. Also the identity a hard reset is keyed on. */
    val projectId: String,
    /** `mobilesdk_app_id` (`1:NNN:android:HEX`), not the package name. */
    val appId: String,
    /** `api_key[0].current_key`, the public Firebase services key. Not a secret. */
    val apiKey: String,
    /** `project_info.project_number`. Derivable from [appId]; sent so a mismatch fails at enroll. */
    val senderId: String,
)

data class EnrollResponse(
    val approverDeviceId: String,
    val deviceToken: String,
    val expiresInSeconds: Long,
    /** The deployment paired with, when the server sent it. Enrollments are keyed on `project.id`. */
    val project: ProjectRef? = null,
    /**
     * The deployment's VAPID public key (`webpush.vapid_public_key`), as
     * unpadded base64url of the 65-octet uncompressed P-256 point, handed to
     * the UnifiedPush distributor at registration. Null when the server has no
     * Web Push key or predates openapi 0.40.0; the app must then register
     * without subscription keys.
     */
    val webpushVapidPublicKey: String? = null,
    val fcm: FcmAppConfig? = null,
) {
    override fun toString(): String = "EnrollResponse($approverDeviceId, token=<redacted>)"
}

/**
 * The server's redacted, capped render of a call's arguments, absent when the
 * server is older or has the preview turned off. [preview] is shown verbatim
 * with no client-side filtering, because the server escapes control, ANSI,
 * bidi and zero-width characters before sending. It is not part of any signed
 * message.
 */
data class ArgsPreview(
    val preview: String,
    /** The preview is a head-and-tail cut, with an inline `…[N bytes elided]…` marker. */
    val truncated: Boolean = false,
    /** Redacted length before truncation, or null if unknown. */
    val bytes: Long? = null,
    /** First 12 hex digits of the binding fingerprint, without the `sha256:` prefix. */
    val hashPrefix: String = "",
    /** `call` or `tool_identity`: what the approval's hash binds. An unrecognised value gets generic wording. */
    val bindingScope: String = "",
)

/** One row of the pending list, already redacted by the server. */
data class PendingRequest(
    val id: String,
    val requester: String,
    val toolLabel: String,
    val ruleId: String,
    /** The agent's own stated reason. Unverified, so the UI labels it as a claim. */
    val justification: String,
    val challenge: String,
    /**
     * When the server's decision window closes, in epoch seconds, or null if
     * the server sent no `expires_at`. Null is neither expired nor unlimited;
     * see [dev.straza.approver.shared.flow.Expiry].
     */
    val expiresAtEpochSeconds: Long? = null,
    /**
     * True for a day-scale ticket (wire `class == "ticket"`), false for a hold.
     * An absent `class` is a hold. For a ticket, [expiresAtEpochSeconds] is the
     * decision window; the grant window exists only after approval.
     */
    val isTicket: Boolean = false,
    val argsPreview: ArgsPreview? = null,
    /** `human` or `nhi`: the subject is a person or a workload identity. Empty on older servers. */
    val requesterKind: String = "",
    /** Who raised the request (`origin.actor`). Currently always `agent_session`; absent means the same. */
    val originActor: String = "",
    /** The humanized tool name ([SummaryText.title]), also shown in the biometric prompt. */
    val toolTitle: String = "",
    /** The machine identity ([SummaryText.wire]), shown under the title on the decision screen. */
    val toolWire: String = "",
    /** The agent session that raised the request, and its harness. Blank on older servers. */
    val sessionId: String = "",
    val harness: String = "",
)

/** A push transport (`ApproverPushRequest.kind`). The app reports only one it can receive on. */
enum class PushKind(val wire: String) {
    FCM("fcm"),
    APNS("apns"),
    UNIFIEDPUSH("unifiedpush"),
    WEBPUSH("webpush"),
}

enum class ResolvedState(val wire: String) {
    APPROVED("approved"),
    DENIED("denied"),

    /** Timed out, the server's fail-closed default. Nobody decided it. */
    EXPIRED("expired"),

    /** A state this build does not recognise. Shown as unknown, not dropped. */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): ResolvedState =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * One row of the resolved feed (`/v1/approver/history`). Like [PendingRequest],
 * with the outcome and who reached it, and without a `challenge`: there is
 * nothing left to sign.
 */
data class ResolvedRequest(
    val id: String,
    val requester: String,
    val toolLabel: String,
    val ruleId: String,
    val state: ResolvedState,
    /** The human who decided it. Null on an expired row and on rows the server left unattributed. */
    val decidedBy: String?,
    val decidedAtEpochSeconds: Long?,
    /** The agent's stated reason, unverified. Blank when the server omits it. */
    val justification: String = "",
    /**
     * True for a day-scale ticket (wire `class == "ticket"`), false for a hold.
     * Servers before openapi 0.126.0 omit `class` on a hold and name it on a
     * ticket, so an absent value is a hold on every server.
     */
    val isTicket: Boolean = false,
    /**
     * Use fields, sent from openapi 0.25.0 on a ticket and 0.126.0 on a hold,
     * and null before that. The window is a ticket's grant, or the short retry
     * window in which a held call runs once. [state] stays `approved` after
     * use, so the outcome is derived from these fields; see
     * [dev.straza.approver.shared.flow.ActivityOutcome].
     */
    val grantExpiresAtEpochSeconds: Long? = null,
    val consumedAtEpochSeconds: Long? = null,
    val consumedBy: String? = null,
    val argsPreview: ArgsPreview? = null,
    /**
     * The surface the decision came from (`decided_via`): `phone`, `browser`,
     * `console` or `slack`, or `api` on rows resolved before openapi 0.63.0.
     * Null on older servers and on expired rows. An unrecognised value is
     * shown as a generic enrolled device.
     */
    val decidedViaSurface: String? = null,
    /**
     * The enrolled device whose key signed the decision, on signed decisions
     * only. Compared with this enrollment's own id to show "this phone".
     */
    val decidedViaDeviceId: String? = null,
    /**
     * The decider's own words (`decided_reason`), blank when none were given.
     * Shown as the decider's quote, apart from the agent's [justification].
     */
    val decidedReason: String = "",
    /** This field and those below mean the same as on [PendingRequest]. */
    val requesterKind: String = "",
    val originActor: String = "",
    val toolTitle: String = "",
    val toolWire: String = "",
    val sessionId: String = "",
    val harness: String = "",
)

/**
 * One page of the resolved feed. [nextCursor] is an opaque continuation sent
 * back as `?cursor=`, and is empty (not null) on the last page. A page can be
 * shorter than `limit`, or empty, and still carry a cursor, because the server
 * filters visibility after the keyset seek. A walk ends only on an empty cursor.
 */
data class ResolvedPage(
    val items: List<ResolvedRequest>,
    val nextCursor: String,
)

/**
 * The strazad approver API. An interface so the flow logic can be tested
 * against fakes. Every method suspends and implementations own their
 * dispatching, so a caller does not need to know whether a call blocks.
 */
interface ApprovalApi {

    suspend fun enroll(
        enrollToken: String,
        deviceName: String,
        publicKey: DevicePublicKey,
    ): ApiResult<EnrollResponse>

    suspend fun pending(deviceToken: String): ApiResult<List<PendingRequest>>

    /**
     * One page of resolved requests, newest first. A null or empty [cursor]
     * asks for the newest page; otherwise it is the previous page's
     * [ResolvedPage.nextCursor], sent back unchanged.
     */
    suspend fun history(
        deviceToken: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ApiResult<ResolvedPage>

    /**
     * Registers or re-registers the push route for this device. The server
     * dedupes on `(device, kind, endpoint)`, so the app calls this again
     * whenever its push token rotates.
     *
     * [p256dh] and [auth] are the Web Push subscription keys (RFC 8291 §3.2,
     * unpadded base64url). On `kind=unifiedpush` they opt in to encrypted Web
     * Push. The server requires both or neither, refuses them on fcm and apns,
     * and answers 400 when the deployment has no VAPID key. They are sent as
     * given; [dev.straza.approver.shared.push.PushRegistrar] retries without keys.
     */
    suspend fun registerPush(
        deviceToken: String,
        kind: PushKind,
        tokenOrEndpoint: String,
        p256dh: String? = null,
        auth: String? = null,
    ): ApiResult<Unit>

    /**
     * Removes a push route. Deleting a route that is already gone succeeds.
     * A keyed route is addressed with the same [p256dh] and [auth] it was
     * registered with; a keyless DELETE does not match a keyed row.
     */
    suspend fun unregisterPush(
        deviceToken: String,
        kind: PushKind,
        tokenOrEndpoint: String,
        p256dh: String? = null,
        auth: String? = null,
    ): ApiResult<Unit>

    /**
     * `DELETE /v1/approver/enrollment`: the device retires its own server row
     * with its bearer. Afterwards the bearer and any refresh answer
     * `device_revoked`. Best effort: the local teardown does not wait for it,
     * an older server answers 404 and a revoked token 401.
     */
    suspend fun unenroll(deviceToken: String): ApiResult<Unit>

    /**
     * Renews the device token with the device key: fetches a single-use
     * challenge, signs the [dev.straza.approver.shared.protocol.RefreshSigning]
     * message over it, and exchanges the signature for a fresh token. Neither
     * request carries a bearer, so an expired token cannot block its own
     * refresh.
     *
     * @param sign as in [decide]. If it throws, the refresh request is not sent.
     */
    suspend fun renewToken(
        approverDeviceId: String,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<RenewedToken>

    /**
     * @param sign produces the ECDSA signature over the canonical message. The
     *   caller supplies it so the OS authentication prompt wraps the signing
     *   operation itself. It suspends because the prompt is asynchronous and
     *   has to be shown on the main thread.
     * @param reason the decider's words in wire form
     *   ([dev.straza.approver.shared.protocol.ReasonValidation.forWire]), or
     *   null. The signed message's fifth line and the body's `reason` field
     *   both come from this value, so one cannot be sent without the other.
     */
    suspend fun decide(
        deviceToken: String,
        requestId: String,
        verdict: Verdict,
        challenge: String,
        unixTs: Long,
        reason: String? = null,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<String>
}
