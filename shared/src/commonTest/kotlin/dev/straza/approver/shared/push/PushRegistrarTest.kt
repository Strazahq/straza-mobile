package dev.straza.approver.shared.push

import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.EnrollResponse
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.PushKind
import dev.straza.approver.shared.net.RenewedToken
import dev.straza.approver.shared.net.ResolvedPage
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DevicePublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class PushRegistrarTest {

    private val keyed = PushRoute(
        PushKind.UNIFIEDPUSH,
        "https://ntfy.example/abc",
        p256dh = "BPubKey",
        auth = "authSecret",
    )

    /** An [ApprovalApi] that answers registerPush from a queue and records the calls. */
    private class ScriptedApi(vararg answers: ApiResult<Unit>) : ApprovalApi {
        val queue = ArrayDeque(answers.toList())
        val calls = mutableListOf<Triple<PushKind, String, Pair<String?, String?>>>()

        override suspend fun registerPush(
            deviceToken: String,
            kind: PushKind,
            tokenOrEndpoint: String,
            p256dh: String?,
            auth: String?,
        ): ApiResult<Unit> {
            calls += Triple(kind, tokenOrEndpoint, p256dh to auth)
            return queue.removeFirst()
        }

        override suspend fun unregisterPush(
            deviceToken: String,
            kind: PushKind,
            tokenOrEndpoint: String,
            p256dh: String?,
            auth: String?,
        ): ApiResult<Unit> = ApiResult.Ok(Unit)

        override suspend fun unenroll(deviceToken: String): ApiResult<Unit> = ApiResult.Ok(Unit)

        override suspend fun enroll(
            enrollToken: String,
            deviceName: String,
            publicKey: DevicePublicKey,
        ): ApiResult<EnrollResponse> = throw UnsupportedOperationException()

        override suspend fun pending(deviceToken: String): ApiResult<List<PendingRequest>> =
            throw UnsupportedOperationException()

        override suspend fun history(deviceToken: String, limit: Int, cursor: String?): ApiResult<ResolvedPage> =
            throw UnsupportedOperationException()

        override suspend fun renewToken(
            approverDeviceId: String,
            sign: suspend (ByteArray) -> ByteArray,
        ): ApiResult<RenewedToken> = throw UnsupportedOperationException()

        override suspend fun decide(
            deviceToken: String,
            requestId: String,
            verdict: Verdict,
            challenge: String,
            unixTs: Long,
            reason: String?,
            sign: suspend (ByteArray) -> ByteArray,
        ): ApiResult<String> = throw UnsupportedOperationException()
    }

    @Test
    fun keyedAcceptanceRegistersTheKeyedRouteOnce() = runTest {
        val api = ScriptedApi(ApiResult.Ok(Unit))
        val outcome = PushRegistrar.register(api, "tok", keyed)
        assertEquals(keyed, outcome.registered)
        assertIs<ApiResult.Ok<Unit>>(outcome.result)
        assertEquals(1, api.calls.size)
        assertEquals("BPubKey" to "authSecret", api.calls[0].third)
    }

    @Test
    fun keyedRefusalRetriesOnceWithoutKeys() = runTest {
        // Enrollment advertised WebPush, but the server has since lost its
        // `webpush.vapidKeyFile` and answers a keyed registration with 400.
        val api = ScriptedApi(ApiResult.ServerError(400, "subscription keys need the WebPush sender"), ApiResult.Ok(Unit))
        val outcome = PushRegistrar.register(api, "tok", keyed)
        assertEquals(keyed.withoutKeys(), outcome.registered)
        assertIs<ApiResult.Ok<Unit>>(outcome.result)
        assertEquals(2, api.calls.size)
        assertEquals(null to null, api.calls[1].third)
    }

    @Test
    fun doubleRefusalSurfacesTheKeylessAttemptsVerdictVerbatim() = runTest {
        // If the 400 was the host allowlist, the keyless retry gets the same
        // 400, and its server text is the one reported.
        val allowlist = "push endpoint host \"evil.example\" is not in approval.push.allowedPushHosts"
        val api = ScriptedApi(ApiResult.ServerError(400, allowlist), ApiResult.ServerError(400, allowlist))
        val outcome = PushRegistrar.register(api, "tok", keyed)
        assertEquals(keyed.withoutKeys(), outcome.registered)
        val err = outcome.result
        assertIs<ApiResult.ServerError>(err)
        assertEquals(allowlist, err.message)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun nonRefusalFailuresNeverRetry() = runTest {
        // Transient failures are retried on the next foreground, and a 401 must
        // reach the auth handling unchanged.
        for (failure in listOf<ApiResult<Unit>>(
            ApiResult.Unreachable("timeout"),
            ApiResult.ServerError(503),
            ApiResult.Unauthorized(),
            ApiResult.Untrusted("pin mismatch"),
        )) {
            val api = ScriptedApi(failure)
            val outcome = PushRegistrar.register(api, "tok", keyed)
            assertEquals(keyed, outcome.registered)
            assertEquals(failure, outcome.result)
            assertEquals(1, api.calls.size)
        }
    }

    @Test
    fun keylessRoutesAndOtherKindsPassStraightThrough() = runTest {
        // A keyless route has nothing to fall back to, and an FCM 400 is not a
        // keyed refusal.
        val keyless = PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/abc")
        val apiA = ScriptedApi(ApiResult.ServerError(400, "not allowlisted"))
        assertEquals(keyless, PushRegistrar.register(apiA, "tok", keyless).registered)
        assertEquals(1, apiA.calls.size)

        val fcmRoute = PushRoute(PushKind.FCM, "fcm-token")
        val apiB = ScriptedApi(ApiResult.ServerError(400, "bad token"))
        assertEquals(fcmRoute, PushRegistrar.register(apiB, "tok", fcmRoute).registered)
        assertEquals(1, apiB.calls.size)
    }
}
