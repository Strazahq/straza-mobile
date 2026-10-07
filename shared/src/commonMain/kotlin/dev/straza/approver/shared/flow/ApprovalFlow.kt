package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.AuthRejection
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.protocol.ReasonValidation
import dev.straza.approver.shared.protocol.Verdict
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the approval screen shows. Rendered from server state only. */
sealed interface ApprovalScreen {
    data object NotEnrolled : ApprovalScreen

    data object Loading : ApprovalScreen

    data class Pending(val requests: List<PendingRequest>) : ApprovalScreen

    /**
     * The server could not be reached. Not an empty pending list: "nothing to
     * approve" would hide a request that is timing out.
     */
    data class CannotReach(
        val reason: String,
        /**
         * Set when the server asked for a pause (429/503). Only the poll holds;
         * manual and push-triggered fetches are not held.
         */
        val retryAfterSeconds: Int? = null,
    ) : ApprovalScreen

    /**
     * The server did not present the pinned key. Waiting does not fix it: it
     * keeps failing until a fresh QR replaces the pin. Local state is not
     * wiped here; see [dev.straza.approver.shared.ui.AppActions.reEnroll].
     */
    data class Untrusted(val reason: String) : ApprovalScreen

    /** Enrollment is gone server-side. Local state has already been wiped. */
    data object Revoked : ApprovalScreen

    /**
     * The device token was rejected for a reason renewal fixes (expiry, an
     * unreadable credential). The hardware key stays intact. The exit is a
     * device-key-signed refresh, not re-enrollment.
     */
    data object RenewNeeded : ApprovalScreen

    /**
     * The bound user is deactivated, or lost the approver role, server-side.
     * Nothing on the device is destroyed and the poll keeps running, so the
     * pairing works again once the server restores the user.
     */
    data class Suspended(val reason: String) : ApprovalScreen
}

/**
 * What the read-only Activity feed shows. An unreachable server renders as
 * [CannotReach], not as an empty feed.
 */
sealed interface ActivityScreen {

    data object Loading : ActivityScreen

    /** The resolved feed, newest first. An empty list is a real empty feed. */
    data class Resolved(val items: List<ResolvedRequest>) : ActivityScreen

    data class CannotReach(val reason: String) : ActivityScreen

    /** Same trust failure as [ApprovalScreen.Untrusted]; routes to re-pairing. */
    data class Untrusted(val reason: String) : ActivityScreen

    data object Revoked : ActivityScreen
}

sealed interface DecisionOutcome {
    data class Recorded(val state: String) : DecisionOutcome

    /** Resolved elsewhere, or a double-tap. Show [state]; this is not an error. */
    data class AlreadyResolved(val state: String) : DecisionOutcome

    /** Nothing was submitted: the user did not authenticate, or signing failed. */
    data class NotSigned(val reason: String) : DecisionOutcome

    data class Failed(val reason: String) : DecisionOutcome

    /**
     * The decision was signed but could not be delivered: the server did not
     * present the pinned key. Separate from [Failed] because retrying cannot
     * work and the exit is re-enrollment.
     */
    data class Untrusted(val reason: String) : DecisionOutcome

    /**
     * Signed, but the token was rejected for a reason renewal fixes. The
     * decision was not recorded, and the key is untouched.
     */
    data object RenewNeeded : DecisionOutcome

    data object Revoked : DecisionOutcome
}

/**
 * The approval loop's decision logic, free of UI and platform APIs. It decides
 * when the app may show an Approve button and what each server answer does.
 */
class ApprovalFlow(
    private val api: ApprovalApi,
    private val onRevoked: () -> Unit,
    private val now: () -> Long,
) {

    /**
     * Requests currently being decided, so a double-tap cannot produce two
     * signatures and two authentication prompts for one decision.
     */
    private val inFlight = mutableSetOf<String>()

    /** Guards [inFlight]: decisions suspend across the authentication prompt and can overlap. */
    private val lock = Mutex()

    suspend fun refresh(deviceToken: String): ApprovalScreen =
        when (val result = api.pending(deviceToken)) {
            is ApiResult.Ok -> ApprovalScreen.Pending(result.value)
            // The 401 code decides what happens to the hardware key
            // (AuthRejection.destroysKey): only device_revoked retires the
            // deployment. Expiry, credential problems, a bare 401 (a server
            // before 0.23, or a proxy) and unknown codes go to the
            // key-preserving renewal. A deactivated user or a lost role
            // suspends without destroying anything.
            is ApiResult.Unauthorized -> when {
                result.reason.destroysKey -> {
                    onRevoked()
                    ApprovalScreen.Revoked
                }
                result.reason == AuthRejection.USER_INACTIVE ->
                    ApprovalScreen.Suspended("Your account is inactive on this deployment. Ask an administrator.")
                // Role loss (403).
                result.reason == AuthRejection.FORBIDDEN ->
                    ApprovalScreen.Suspended(
                        "You're not authorized to approve on this deployment any more. " +
                            "Ask an administrator about your approver role.",
                    )
                else -> ApprovalScreen.RenewNeeded
            }
            is ApiResult.Unreachable -> ApprovalScreen.CannotReach(result.reason)
            is ApiResult.Untrusted -> ApprovalScreen.Untrusted(result.reason)
            is ApiResult.ServerError -> ApprovalScreen.CannotReach(
                serverErrorReason(result),
                retryAfterSeconds = serverBackoffSeconds(result),
            )
            // A pending fetch has no 409. Treat it as unusable, not as a list.
            is ApiResult.AlreadyResolved -> ApprovalScreen.CannotReach("unexpected server response")
        }

    /**
     * Fetches the resolved feed for the Activity tab. Read-only, so there is
     * no signing and no in-flight guard, but the same revoke and fail-closed
     * rules as [refresh] apply.
     */
    suspend fun activity(deviceToken: String): ActivityScreen =
        when (val result = api.history(deviceToken)) {
            // Page 1 only, the newest window. Export walks the cursor for the rest.
            is ApiResult.Ok -> ActivityScreen.Resolved(result.value.items)
            // Same key-destruction rule as refresh. The Decide tab owns the
            // renew action, so the key-preserving cases only say where to go.
            is ApiResult.Unauthorized -> when {
                result.reason.destroysKey -> {
                    onRevoked()
                    ActivityScreen.Revoked
                }
                result.reason == AuthRejection.USER_INACTIVE ->
                    ActivityScreen.CannotReach("Your account is inactive on this deployment.")
                result.reason == AuthRejection.FORBIDDEN ->
                    ActivityScreen.CannotReach("You're not authorized on this deployment any more.")
                else ->
                    ActivityScreen.CannotReach("This pairing needs to be renewed. See the Decide tab.")
            }
            is ApiResult.Unreachable -> ActivityScreen.CannotReach(result.reason)
            is ApiResult.Untrusted -> ActivityScreen.Untrusted(result.reason)
            is ApiResult.ServerError -> ActivityScreen.CannotReach(serverErrorReason(result))
            is ApiResult.AlreadyResolved -> ActivityScreen.CannotReach("unexpected server response")
        }

    /**
     * Signs and submits one decision.
     *
     * @param sign wraps the hardware signing operation, including the OS
     *   authentication prompt. It returns null when the user cancelled or
     *   authentication failed. Nothing is submitted then, not even a deny.
     */
    suspend fun decide(
        deviceToken: String,
        request: PendingRequest,
        verdict: Verdict,
        reason: String? = null,
        sign: suspend (ByteArray) -> ByteArray?,
    ): DecisionOutcome {
        if (!lock.withLock { inFlight.add(request.id) }) {
            return DecisionOutcome.NotSigned("a decision for this request is already in flight")
        }

        // Normalized once, so the signed message and the body carry the same
        // value. An invalid reason is refused here, before the biometric
        // prompt, so the hardware does not sign a message the server rejects.
        val wireReason = try {
            ReasonValidation.forWire(reason)
        } catch (e: IllegalArgumentException) {
            lock.withLock { inFlight.remove(request.id) }
            return DecisionOutcome.NotSigned(e.message ?: "the reason cannot be sent")
        }

        return try {
            var signatureFailure: String? = null

            val result = api.decide(
                deviceToken = deviceToken,
                requestId = request.id,
                verdict = verdict,
                challenge = request.challenge,
                unixTs = now(),
                reason = wireReason,
                sign = { message ->
                    sign(message) ?: run {
                        signatureFailure = "not authenticated"
                        // No signature, no decision: abort before anything is
                        // sent. The throw is caught below.
                        throw AbortDecision
                    }
                },
            )

            signatureFailure?.let { return DecisionOutcome.NotSigned(it) }

            when (result) {
                is ApiResult.Ok -> DecisionOutcome.Recorded(result.value)
                is ApiResult.AlreadyResolved -> DecisionOutcome.AlreadyResolved(result.state)
                // A stale `ts` (clock skew), a bad signature or a spent challenge
                // arrives as `challenge_rejected` from a current server and
                // uncoded from an older one; both are recoverable. Only
                // DEVICE_REVOKED destroys the key. A revocation that arrives
                // uncoded here is still caught by the next poll's 401.
                is ApiResult.Unauthorized -> when (result.reason) {
                    AuthRejection.DEVICE_REVOKED -> {
                        onRevoked()
                        DecisionOutcome.Revoked
                    }
                    AuthRejection.USER_INACTIVE ->
                        DecisionOutcome.Failed("your account is inactive on this deployment")
                    AuthRejection.FORBIDDEN ->
                        DecisionOutcome.Failed(
                            "you're not authorized to decide this request any more (a role change?)",
                        )
                    AuthRejection.CHALLENGE_REJECTED,
                    AuthRejection.UNKNOWN,
                    ->
                        DecisionOutcome.Failed(
                            "the server rejected this decision's signature (check that this " +
                                "phone's clock is set automatically, then try again)",
                        )
                    AuthRejection.TOKEN_EXPIRED,
                    AuthRejection.TOKEN_INVALID,
                    AuthRejection.MISSING_TOKEN,
                    -> DecisionOutcome.RenewNeeded
                }
                is ApiResult.Unreachable -> DecisionOutcome.Failed(result.reason)
                is ApiResult.Untrusted -> DecisionOutcome.Untrusted(result.reason)
                // 404 and 410 mean the request is gone or expired, not an outage.
                is ApiResult.ServerError -> DecisionOutcome.Failed(
                    when (result.status) {
                        404 -> "this request no longer exists on the server"
                        410 -> "this request has already expired"
                        else -> serverErrorReason(result)
                    },
                )
            }
        } catch (_: AbortDecision) {
            DecisionOutcome.NotSigned("not authenticated")
        } catch (e: Exception) {
            // Includes a hardware key that was invalidated between rendering the
            // screen and signing. Nothing was decided.
            DecisionOutcome.NotSigned(e.message ?: "could not sign")
        } finally {
            // NonCancellable: if the coroutine is cancelled (the user
            // backgrounds the app while the prompt is up), a plain suspending
            // release would throw CancellationException and leave the id in
            // inFlight, locking this request out for the life of the process.
            withContext(NonCancellable) { lock.withLock { inFlight.remove(request.id) } }
        }
    }

    private object AbortDecision : Exception() {
        private fun readResolve(): Any = AbortDecision
    }
}

/** Poll pause when the server answers 429/503 without a usable `Retry-After`. */
const val SERVER_BACKOFF_SECONDS = 60

/** The reason a CannotReach screen shows for a non-2xx answer. */
internal fun serverErrorReason(result: ApiResult.ServerError): String = when (result.status) {
    // `service_unavailable`: the store behind strazad is out; the key and the pairing are fine.
    503 -> "the server is temporarily unavailable (503); retrying"
    429 -> "the server asked this phone to slow down (429); retrying"
    else -> "server error ${result.status}"
}

/** Seconds the poll should hold after [result], or null when it should not. */
internal fun serverBackoffSeconds(result: ApiResult.ServerError): Int? = when (result.status) {
    429, 503 -> result.retryAfterSeconds ?: SERVER_BACKOFF_SECONDS
    else -> null
}
