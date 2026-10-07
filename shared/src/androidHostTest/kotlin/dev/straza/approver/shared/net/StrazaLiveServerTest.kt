package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.SpkiPin
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DevicePublicKey
import dev.straza.approver.shared.security.KeySecurityLevel
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * Runs [StrazaClient] against a real local `strazad` instead of the mock. Gated
 * on the `-Pstraza.live.*` properties, which `shared/build.gradle.kts` forwards
 * as system properties. When they are unset the test is skipped, so `allTests`
 * needs no running server. The signing key is a software JVM P-256 key.
 */
class StrazaLiveServerTest {

    private val baseUrl: String? = System.getProperty("straza.live.baseUrl")
    private val pin: String? = System.getProperty("straza.live.pin")
    private val enrollToken: String? = System.getProperty("straza.live.enrollToken")

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun devicePublicKey() = DevicePublicKey(keyPair.public.encoded, KeySecurityLevel.SOFTWARE)

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }

    private fun now() = System.currentTimeMillis() / 1000

    @Test
    fun `enroll, decide a staged row, and read it back - against real strazad`() = runTest {
        assumeTrue(
            "live strazad not configured: set -Pstraza.live.baseUrl, .pin and .enrollToken",
            baseUrl != null && pin != null && enrollToken != null,
        )
        val spki = assertNotNull(SpkiPin.parse(pin!!), "the provided pin must be a valid sha256/ pin")
        val client = StrazaClient(listOf(baseUrl!!), spki)

        // The enroll token is a one-time token minted for this run.
        val enrolled = client.enroll(enrollToken!!, "live-host-test", devicePublicKey())
            .expectOk("enroll")
        assertTrue(enrolled.deviceToken.isNotEmpty())

        // One decidable row is staged on the server beforehand. It carries a
        // fresh challenge issued to this fetch.
        val pending = client.pending(enrolled.deviceToken).expectOk("pending")
        val request = pending.firstOrNull { it.challenge.isNotEmpty() }
            ?: error("no decidable row staged - the harness should have created one")

        // The real server verifies this signature with ecdsa.VerifyASN1.
        val state = client.decide(
            deviceToken = enrolled.deviceToken,
            requestId = request.id,
            verdict = Verdict.APPROVE,
            challenge = request.challenge,
            unixTs = now(),
            sign = ::sign,
        ).expectOk("decide")
        assertEquals("approved", state)

        val resolved = client.history(enrolled.deviceToken).expectOk("history").items
            .single { it.id == request.id }
        assertEquals(ResolvedState.APPROVED, resolved.state)
        assertNotNull(resolved.decidedBy, "a decided row names its decider")

        // The challenge was consumed, so a replay of the same signed decision
        // is refused: 401 on the spent nonce, or 409 after a fresh fetch.
        val replay = client.decide(
            deviceToken = enrolled.deviceToken,
            requestId = request.id,
            verdict = Verdict.APPROVE,
            challenge = request.challenge,
            unixTs = now(),
            sign = ::sign,
        )
        assertTrue(
            replay is ApiResult.Unauthorized || replay is ApiResult.AlreadyResolved,
            "a replayed decision must be refused, got $replay",
        )

        // FCM tokens are opaque and not host-validated, so a made-up one registers.
        val fcmToken = "fake-fcm-token-abc123"
        client.registerPush(enrolled.deviceToken, PushKind.FCM, fcmToken).expectOk("registerPush")
        client.unregisterPush(enrolled.deviceToken, PushKind.FCM, fcmToken).expectOk("unregisterPush")

        // A UnifiedPush endpoint whose host is not in the server's
        // approval.push.allowedPushHosts is refused. An empty allowlist refuses
        // every UnifiedPush route.
        val rejected = client.registerPush(enrolled.deviceToken, PushKind.UNIFIEDPUSH, "https://ntfy.example/live")
        assertTrue(
            rejected !is ApiResult.Ok,
            "an un-allowlisted UnifiedPush endpoint must be refused, got $rejected",
        )
    }

    private fun <T> ApiResult<T>.expectOk(what: String): T =
        (this as? ApiResult.Ok)?.value ?: error("$what failed: $this")
}
