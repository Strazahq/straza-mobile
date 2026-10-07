package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.AuthRejection
import dev.straza.approver.shared.net.EnrollResponse
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.RenewedToken
import dev.straza.approver.shared.net.ResolvedPage
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import dev.straza.approver.shared.protocol.DecisionSigning
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DevicePublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private val REQUEST = PendingRequest(
    id = "apr_1",
    requester = "nova",
    toolLabel = "midpoint:disable_user",
    ruleId = "disable-user-needs-human",
    justification = "offboarding",
    challenge = "nonce-1",
)

private val RESOLVED = ResolvedRequest(
    id = "apr_9",
    requester = "nova",
    toolLabel = "midpoint:disable_user",
    ruleId = "disable-user-needs-human",
    state = ResolvedState.APPROVED,
    decidedBy = "kim",
    decidedAtEpochSeconds = 1788999000L,
)

private class FakeApi(
    var pendingResult: ApiResult<List<PendingRequest>> = ApiResult.Ok(listOf(REQUEST)),
    var decideResult: ApiResult<String> = ApiResult.Ok("approved"),
    var historyResult: ApiResult<ResolvedPage> = ApiResult.Ok(ResolvedPage(listOf(RESOLVED), "")),
) : ApprovalApi {
    var decideCalls = 0
    var signedMessages = mutableListOf<ByteArray>()
    var lastReason: String? = null

    override suspend fun enroll(enrollToken: String, deviceName: String, publicKey: DevicePublicKey) =
        error("not used")

    override suspend fun pending(deviceToken: String) = pendingResult

    override suspend fun unenroll(deviceToken: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun history(deviceToken: String, limit: Int, cursor: String?) = historyResult

    override suspend fun registerPush(
        deviceToken: String,
        kind: dev.straza.approver.shared.net.PushKind,
        tokenOrEndpoint: String,
        p256dh: String?,
        auth: String?,
    ) = ApiResult.Ok(Unit)

    override suspend fun unregisterPush(
        deviceToken: String,
        kind: dev.straza.approver.shared.net.PushKind,
        tokenOrEndpoint: String,
        p256dh: String?,
        auth: String?,
    ) = ApiResult.Ok(Unit)

    override suspend fun decide(
        deviceToken: String,
        requestId: String,
        verdict: Verdict,
        challenge: String,
        unixTs: Long,
        reason: String?,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<String> {
        decideCalls++
        lastReason = reason
        // The fake signs like the real client, with the reason it received
        // inside the message.
        signedMessages += sign(
            DecisionSigning.canonicalMessage(requestId, verdict, challenge, unixTs, reason),
        )
        return decideResult
    }

    override suspend fun renewToken(
        approverDeviceId: String,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<RenewedToken> {
        // As in decide: the real client signs before sending.
        sign("refresh\n$approverDeviceId\nnonce".encodeToByteArray())
        return ApiResult.Ok(RenewedToken("fresh-token", 2_592_000))
    }
}

class ApprovalFlowTest {

    private var revokedCalls = 0

    private fun flow(api: FakeApi) = ApprovalFlow(
        api = api,
        onRevoked = { revokedCalls++ },
        now = { 1789000000L },
    )

    // Refresh

    @Test
    fun `shows pending requests`() = runTest {
        val screen = flow(FakeApi()).refresh("token")
        assertEquals(ApprovalScreen.Pending(listOf(REQUEST)), screen)
    }

    @Test
    fun `unreachable is not an empty list`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Unreachable("SSLHandshakeException"))
        val screen = flow(api).refresh("token")
        // Checked before assertIs, whose smart cast would make this check static.
        assertFalse(screen is ApprovalScreen.Pending)
        assertIs<ApprovalScreen.CannotReach>(screen)
    }

    @Test
    fun `a trust failure is distinct from an unreachable server`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Untrusted("PinMismatchException"))
        val screen = flow(api).refresh("token")
        assertFalse(screen is ApprovalScreen.CannotReach, "a stale pin never heals by waiting")
        assertIs<ApprovalScreen.Untrusted>(screen)
    }

    /**
     * Anyone able to intercept traffic can induce a trust failure, so an
     * automatic wipe would let them unpair devices remotely.
     */
    @Test
    fun `a trust failure does not wipe local state`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Untrusted("PinMismatchException"))
        flow(api).refresh("token")
        assertEquals(0, revokedCalls, "only the user may discard an enrollment over a bad pin")
    }

    @Test
    fun `server error is not an empty list either`() = runTest {
        val screen = flow(FakeApi(pendingResult = ApiResult.ServerError(503))).refresh("token")
        assertIs<ApprovalScreen.CannotReach>(screen)
    }

    // 429 and 503: the server asked for a pause

    @Test
    fun `a 503 says unavailable and holds the poll for the default pause`() = runTest {
        val screen = flow(FakeApi(pendingResult = ApiResult.ServerError(503, "store unavailable"))).refresh("token")
        assertIs<ApprovalScreen.CannotReach>(screen)
        assertTrue(screen.reason.contains("unavailable"), screen.reason)
        assertEquals(SERVER_BACKOFF_SECONDS, screen.retryAfterSeconds)
        assertEquals(0, revokedCalls, "an outage must never touch the key")
    }

    @Test
    fun `a 429 honours the server's Retry-After`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.ServerError(429, null, retryAfterSeconds = 7))
        val screen = flow(api).refresh("token")
        assertIs<ApprovalScreen.CannotReach>(screen)
        assertEquals(7, screen.retryAfterSeconds)
    }

    @Test
    fun `other server errors do not hold the poll`() = runTest {
        val screen = flow(FakeApi(pendingResult = ApiResult.ServerError(500))).refresh("token")
        assertIs<ApprovalScreen.CannotReach>(screen)
        assertEquals(null, screen.retryAfterSeconds)
    }

    @Test
    fun `a 410 during decide says the request expired and a 404 says it is gone`() = runTest {
        val gone = flow(FakeApi(decideResult = ApiResult.ServerError(410))).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(gone)
        assertTrue(gone.reason.contains("expired"), gone.reason)
        val missing = flow(FakeApi(decideResult = ApiResult.ServerError(404))).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(missing)
        assertTrue(missing.reason.contains("no longer exists"), missing.reason)
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `a bare 401 on pending routes to renewal and destroys nothing`() = runTest {
        // An uncoded 401 comes from a pre-0.23 server or from a proxy. It is not
        // proof of revocation; the signed renewal tells a revoked device so.
        val screen = flow(FakeApi(pendingResult = ApiResult.Unauthorized())).refresh("token")
        assertEquals(ApprovalScreen.RenewNeeded, screen)
        assertEquals(0, revokedCalls, "an uncoded 401 must never destroy the key")
    }

    @Test
    fun `an unrecognised 401 code on pending routes to renewal and destroys nothing`() = runTest {
        // The server's code enum may grow, so an unnamed code must keep the key.
        val reason = AuthRejection.fromWire("some_future_code")
        assertEquals(AuthRejection.UNKNOWN, reason)
        val screen = flow(FakeApi(pendingResult = ApiResult.Unauthorized(reason))).refresh("token")
        assertEquals(ApprovalScreen.RenewNeeded, screen)
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `only device_revoked destroys the key`() {
        assertEquals(listOf(AuthRejection.DEVICE_REVOKED), AuthRejection.entries.filter { it.destroysKey })
        assertEquals(AuthRejection.DEVICE_REVOKED, AuthRejection.fromWire("device_revoked"))
        assertEquals(AuthRejection.CHALLENGE_REJECTED, AuthRejection.fromWire("challenge_rejected"))
        assertEquals(AuthRejection.FORBIDDEN, AuthRejection.fromWire("not_authorized"))
        assertEquals(AuthRejection.UNKNOWN, AuthRejection.fromWire(null))
        assertEquals(AuthRejection.UNKNOWN, AuthRejection.fromWire(""))
        assertEquals(AuthRejection.UNKNOWN, AuthRejection.fromWire("project_disabled"))
    }

    @Test
    fun `explicit revocation wipes the enrollment`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Unauthorized(AuthRejection.DEVICE_REVOKED))
        assertEquals(ApprovalScreen.Revoked, flow(api).refresh("token"))
        assertEquals(1, revokedCalls)
    }

    @Test
    fun `an expired token routes to renewal and destroys nothing`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Unauthorized(AuthRejection.TOKEN_EXPIRED))
        assertEquals(ApprovalScreen.RenewNeeded, flow(api).refresh("token"))
        assertEquals(0, revokedCalls, "expiry must never destroy the key")
    }

    @Test
    fun `an invalid token routes to renewal not revocation`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Unauthorized(AuthRejection.TOKEN_INVALID))
        assertEquals(ApprovalScreen.RenewNeeded, flow(api).refresh("token"))
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `an inactive user suspends without destroying anything`() = runTest {
        // Reactivating the user must restore the pairing untouched.
        val api = FakeApi(pendingResult = ApiResult.Unauthorized(AuthRejection.USER_INACTIVE))
        assertIs<ApprovalScreen.Suspended>(flow(api).refresh("token"))
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `an expired token on the feed points at renewal without wiping`() = runTest {
        val api = FakeApi(historyResult = ApiResult.Unauthorized(AuthRejection.TOKEN_EXPIRED))
        // The feed has no renew action of its own, so it says where that action lives.
        assertIs<ActivityScreen.CannotReach>(flow(api).activity("token"))
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `an expired token during decide routes to renewal and records nothing`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unauthorized(AuthRejection.TOKEN_EXPIRED))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertEquals(DecisionOutcome.RenewNeeded, outcome)
        assertEquals(0, revokedCalls, "an expired token mid-decision must not destroy the key")
    }

    @Test
    fun `an empty pending list is a real empty list`() = runTest {
        val screen = flow(FakeApi(pendingResult = ApiResult.Ok(emptyList()))).refresh("token")
        assertEquals(ApprovalScreen.Pending(emptyList()), screen)
    }

    // Activity feed

    @Test
    fun `shows the resolved feed`() = runTest {
        val screen = flow(FakeApi()).activity("token")
        assertEquals(ActivityScreen.Resolved(listOf(RESOLVED)), screen)
    }

    @Test
    fun `an empty feed is a real empty feed`() = runTest {
        val screen = flow(FakeApi(historyResult = ApiResult.Ok(ResolvedPage(emptyList(), "")))).activity("token")
        assertEquals(ActivityScreen.Resolved(emptyList()), screen)
    }

    @Test
    fun `an unreachable feed is not an empty feed`() = runTest {
        val screen = flow(FakeApi(historyResult = ApiResult.Unreachable("timeout"))).activity("token")
        assertIs<ActivityScreen.CannotReach>(screen)
    }

    @Test
    fun `a trust failure on the feed routes to re-pairing`() = runTest {
        val api = FakeApi(historyResult = ApiResult.Untrusted("PinMismatchException"))
        assertIs<ActivityScreen.Untrusted>(flow(api).activity("token"))
    }

    @Test
    fun `device_revoked on the feed wipes the enrollment`() = runTest {
        val api = FakeApi(historyResult = ApiResult.Unauthorized(AuthRejection.DEVICE_REVOKED))
        assertEquals(ActivityScreen.Revoked, flow(api).activity("token"))
        assertEquals(1, revokedCalls)
    }

    @Test
    fun `a bare 401 on the feed reports without wiping`() = runTest {
        val screen = flow(FakeApi(historyResult = ApiResult.Unauthorized())).activity("token")
        assertIs<ActivityScreen.CannotReach>(screen)
        assertEquals(0, revokedCalls)
    }

    // Decide

    @Test
    fun `a reason travels in wire form and rides the signed message`() = runTest {
        val api = FakeApi()
        val outcome = flow(api).decide("token", REQUEST, Verdict.DENY, reason = "  needs a migration first  ") { it }
        assertIs<DecisionOutcome.Recorded>(outcome)
        assertEquals("needs a migration first", api.lastReason, "the wire reason is the trimmed draft")
        assertContentEquals(
            DecisionSigning.canonicalMessage(
                REQUEST.id, Verdict.DENY, REQUEST.challenge, 1789000000L, "needs a migration first",
            ),
            api.signedMessages.single(),
            "the signed message must be the 5-line form carrying this exact reason's hash",
        )
    }

    @Test
    fun `an effectively empty draft signs the canonical 4-line message`() = runTest {
        val api = FakeApi()
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE, reason = " \n ") { it }
        assertIs<DecisionOutcome.Recorded>(outcome)
        assertEquals(null, api.lastReason, "whitespace is not a reason; the field must be absent")
        assertContentEquals(
            DecisionSigning.canonicalMessage(REQUEST.id, Verdict.APPROVE, REQUEST.challenge, 1789000000L),
            api.signedMessages.single(),
        )
    }

    /** The key must not sign a reason the server would reject with a 400. */
    @Test
    fun `an invalid reason refuses before the prompt and releases the guard`() = runTest {
        val api = FakeApi()
        val f = flow(api)
        val outcome = f.decide("token", REQUEST, Verdict.DENY, reason = "x".repeat(501)) { it }
        assertIs<DecisionOutcome.NotSigned>(outcome)
        assertEquals(0, api.decideCalls, "nothing may reach the network")
        assertEquals(0, api.signedMessages.size, "nothing may reach the hardware key")
        // The refused attempt must release the in-flight guard.
        assertIs<DecisionOutcome.Recorded>(f.decide("token", REQUEST, Verdict.DENY, reason = "short") { it })
    }

    @Test
    fun `records an approval`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Ok("approved"))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertEquals(DecisionOutcome.Recorded("approved"), outcome)
        assertEquals(1, api.signedMessages.size)
    }

    /** A cancelled prompt is not a denial. The server's own timeout is the fail-closed path. */
    @Test
    fun `cancelled authentication submits nothing`() = runTest {
        val api = FakeApi()
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { null }
        assertIs<DecisionOutcome.NotSigned>(outcome)
        assertEquals(0, api.decideCalls.let { if (api.signedMessages.isEmpty()) 0 else it })
        assertTrue(api.signedMessages.isEmpty(), "no signature may leave the device")
    }

    @Test
    fun `signing failure submits nothing`() = runTest {
        val api = FakeApi()
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) {
            throw IllegalStateException("key invalidated")
        }
        assertIs<DecisionOutcome.NotSigned>(outcome)
        assertTrue(api.signedMessages.isEmpty())
    }

    /** The request was resolved elsewhere, or a double-tap reached the server. */
    @Test
    fun `409 surfaces the final state`() = runTest {
        val api = FakeApi(decideResult = ApiResult.AlreadyResolved("denied"))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertEquals(DecisionOutcome.AlreadyResolved("denied"), outcome)
    }

    /**
     * Decide rejections for a stale timestamp, bad signature or spent challenge
     * arrive as `challenge_rejected` from a current server and uncoded from an
     * older one. Clock skew over five minutes must not cost the device key.
     */
    @Test
    fun `an uncoded 401 during decide is a failure never a revocation`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unauthorized())
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("clock"), "the hint must point at the clock: ${outcome.reason}")
        assertEquals(0, revokedCalls, "clock skew must never cost a hardware key")
    }

    @Test
    fun `a challenge_rejected during decide is a failure never a revocation`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unauthorized(AuthRejection.CHALLENGE_REJECTED))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("clock"), "the hint must point at the clock: ${outcome.reason}")
        assertEquals(0, revokedCalls, "a coded verification failure must never cost a hardware key")
    }

    @Test
    fun `explicit device revocation during decide still wipes`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unauthorized(AuthRejection.DEVICE_REVOKED))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertEquals(DecisionOutcome.Revoked, outcome)
        assertEquals(1, revokedCalls, "the coded revocation is still authoritative")
    }

    /** A 403 is role loss, which is reversible, so the key is kept. */
    @Test
    fun `a 403 during decide keeps the key and reports not-authorized`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unauthorized(AuthRejection.FORBIDDEN))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(outcome)
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `a 403 on pending suspends without destroying anything`() = runTest {
        val api = FakeApi(pendingResult = ApiResult.Unauthorized(AuthRejection.FORBIDDEN))
        assertIs<ApprovalScreen.Suspended>(flow(api).refresh("token"))
        assertEquals(0, revokedCalls)
    }

    @Test
    fun `a 403 on the feed reports without destroying anything`() = runTest {
        val api = FakeApi(historyResult = ApiResult.Unauthorized(AuthRejection.FORBIDDEN))
        assertIs<ActivityScreen.CannotReach>(flow(api).activity("token"))
        assertEquals(0, revokedCalls)
    }

    @Test
    // No commas in backtick test names: Kotlin/Native rejects them.
    fun `unreachable during decide is a failure and not a denial`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Unreachable("timeout"))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Failed>(outcome)
    }

    @Test
    fun `a trust failure during decide is not a generic failure`() = runTest {
        val api = FakeApi(decideResult = ApiResult.Untrusted("PinMismatchException"))
        val outcome = flow(api).decide("token", REQUEST, Verdict.APPROVE) { it }
        assertFalse(outcome is DecisionOutcome.Failed, "this exit offers re-pairing, not a retry")
        assertIs<DecisionOutcome.Untrusted>(outcome)
    }

    /** A second signing attempt would mean a second authentication prompt for one decision. */
    @Test
    fun `a second decision while one is in flight is refused`() = runTest {
        val api = FakeApi()
        var reentered: DecisionOutcome? = null
        val flow = flow(api)

        flow.decide("token", REQUEST, Verdict.APPROVE) { message ->
            // Re-enter from the signing callback, which is when a double-tap
            // lands: the prompt is up and the button is live.
            reentered = flow.decide("token", REQUEST, Verdict.DENY) { it }
            message
        }

        assertIs<DecisionOutcome.NotSigned>(reentered)
        assertEquals(1, api.signedMessages.size, "only one signature may be produced")
    }

    @Test
    fun `in-flight guard is released after completion`() = runTest {
        val api = FakeApi()
        val flow = flow(api)
        flow.decide("token", REQUEST, Verdict.APPROVE) { it }
        val second = flow.decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Recorded>(second)
    }

    @Test
    fun `different requests are not blocked by each other`() = runTest {
        val api = FakeApi()
        val flow = flow(api)
        val other = REQUEST.copy(id = "apr_2", challenge = "nonce-2")

        var inner: DecisionOutcome? = null
        flow.decide("token", REQUEST, Verdict.APPROVE) { message ->
            inner = flow.decide("token", other, Verdict.APPROVE) { it }
            message
        }
        assertIs<DecisionOutcome.Recorded>(inner)
    }

    // Concurrency

    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    fun `a concurrent second decision produces no second signature`() = runTest {
        val api = FakeApi()
        val flow = flow(api)
        val prompt = CompletableDeferred<Unit>()

        val first = launch {
            flow.decide("token", REQUEST, Verdict.APPROVE) { message ->
                prompt.await() // The prompt is on screen, awaiting the user.
                message
            }
        }
        advanceUntilIdle()

        val second = flow.decide("token", REQUEST, Verdict.DENY) { it }
        assertIs<DecisionOutcome.NotSigned>(second)

        prompt.complete(Unit)
        first.join()
        assertEquals(1, api.signedMessages.size, "only one signature may be produced")
    }

    /**
     * The release runs in a `finally`. A suspending release there would throw in
     * a cancelled coroutine and leave the request locked out.
     */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
    fun `cancelling a decision releases the in-flight guard`() = runTest {
        val api = FakeApi()
        val flow = flow(api)
        val reached = CompletableDeferred<Unit>()

        val job = launch {
            flow.decide("token", REQUEST, Verdict.APPROVE) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        advanceUntilIdle()
        assertTrue(reached.isCompleted, "the decision must be parked on the prompt")
        job.cancelAndJoin()

        val afterwards = flow.decide("token", REQUEST, Verdict.APPROVE) { it }
        assertIs<DecisionOutcome.Recorded>(afterwards)
    }
}
