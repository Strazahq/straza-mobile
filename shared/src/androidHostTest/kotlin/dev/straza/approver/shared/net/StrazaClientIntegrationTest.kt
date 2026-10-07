package dev.straza.approver.shared.net

import dev.straza.approver.mock.MockStrazad
import dev.straza.approver.shared.protocol.EnrollmentQr
import dev.straza.approver.shared.protocol.SpkiPin
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.push.PushRegistrar
import dev.straza.approver.shared.push.PushRoute
import dev.straza.approver.shared.security.DevicePublicKey
import dev.straza.approver.shared.security.KeySecurityLevel
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Runs against the mock strazad over real TLS with a self-signed certificate,
 * so the SPKI pin is exercised. The device key is a plain JVM P-256 keypair;
 * the Android Keystore is covered by the device tests.
 */
class StrazaClientIntegrationTest {

    private lateinit var mock: MockStrazad
    private lateinit var keyPair: KeyPair

    @BeforeTest
    fun setUp() {
        mock = MockStrazad().start()
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    @AfterTest
    fun tearDown() = mock.close()

    private fun devicePublicKey() =
        DevicePublicKey(keyPair.public.encoded, KeySecurityLevel.SOFTWARE)

    private fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }

    private fun client(pin: SpkiPin? = SpkiPin.parse(mock.spkiPin)) =
        StrazaClient(listOf(mock.baseUrl()), pin)

    private companion object {
        /** Not a real VAPID key: the app relays the value without validating it. */
        const val TEST_VAPID = "BExampleDeploymentVapidPublicKey"

        /** Wire-valid subscription keys: a 65-octet 0x04-prefixed point and a
         *  16-octet secret, as unpadded base64url. */
        val VALID_P256DH: String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(65) { i -> if (i == 0) 0x04 else (i + 1).toByte() })
        val VALID_AUTH: String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16) { i -> (i * 7).toByte() })
    }

    @Test
    fun `enroll, fetch pending, and decide round trip`() = runTest {
        val payload = (EnrollmentQr.parse(mock.qrPayload()) as EnrollmentQr.Result.Ok).payload
        val requestId = mock.seedRequest()
        val client = StrazaClient(payload)

        val enrolled = client.enroll(payload.enrollToken, "test-device", devicePublicKey())
            .expectOk("enroll")
        assertTrue(enrolled.deviceToken.isNotEmpty())

        val pending = client.pending(enrolled.deviceToken).expectOk("pending")
        val request = pending.single { it.id == requestId }
        assertEquals("midpoint:disable_user", request.toolLabel)
        assertTrue(request.challenge.isNotEmpty())

        val state = client.decide(
            deviceToken = enrolled.deviceToken,
            requestId = request.id,
            verdict = Verdict.APPROVE,
            challenge = request.challenge,
            unixTs = System.currentTimeMillis() / 1000,
            sign = ::sign,
        ).expectOk("decide")

        assertEquals("approved", state)
    }

    @Test
    fun `the decision window survives the round trip`() = runTest {
        val client = client()
        val requestId = mock.seedRequest(ttlSeconds = 90)
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val request = client.pending(token).expectOk("pending").single { it.id == requestId }
        val expiresAt = request.expiresAtEpochSeconds
        assertNotNull(expiresAt, "expires_at must be parsed, not dropped")

        // A range, because the mock stamps the expiry from its own clock.
        val remaining = expiresAt - now()
        assertTrue(remaining in 60..90, "expected roughly a 90s window, got ${remaining}s")
    }

    /** Zero would render as already expired and hide the actions on an open request. */
    @Test
    fun `a missing expiry parses as unknown rather than zero`() = runTest {
        val client = client()
        val requestId = mock.seedRequest(ttlSeconds = 90)
        mock.dropFieldFromPending(requestId, "expires_at")
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val request = client.pending(token).expectOk("pending").single { it.id == requestId }
        assertNull(request.expiresAtEpochSeconds)
    }

    @Test
    fun `a decision appears in the resolved feed`() = runTest {
        val client = client()
        val requestId = mock.seedRequest()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        client.decide(token, requestId, Verdict.APPROVE, request.challenge, now(), sign = ::sign).expectOk("decide")

        val resolved = client.history(token).expectOk("history").items.single { it.id == requestId }
        assertEquals(ResolvedState.APPROVED, resolved.state)
        assertEquals("midpoint:disable_user", resolved.toolLabel)
        assertNotNull(resolved.decidedBy, "a decided request must name who decided it")
        assertNotNull(resolved.decidedAtEpochSeconds, "and when")
    }

    @Test
    fun `the feed carries resolutions from other channels including expiry`() = runTest {
        val client = client()
        mock.seedResolved("okta:suspend_user", "atlas", state = "denied", decidedBy = "kim", decidedSecondsAgo = 60)
        val expiredId =
            mock.seedResolved("aws:delete_bucket", "scout", state = "expired", decidedBy = null, decidedSecondsAgo = 30)
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val feed = client.history(token).expectOk("history").items
        val denied = feed.single { it.toolLabel == "okta:suspend_user" }
        assertEquals(ResolvedState.DENIED, denied.state)
        assertEquals("kim", denied.decidedBy)

        val expired = feed.single { it.id == expiredId }
        assertEquals(ResolvedState.EXPIRED, expired.state)
        assertNull(expired.decidedBy, "a timeout has no decider")
    }

    @Test
    fun `history walks the cursor across pages and rejects a bad cursor`() = runTest {
        val client = client()
        repeat(5) { i ->
            mock.seedResolved("aws:op$i", "atlas", state = "approved", decidedBy = "kim", decidedSecondsAgo = (i + 1).toLong())
        }
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val all = mutableListOf<ResolvedRequest>()
        var cursor = "" // An empty cursor requests the newest page.
        var pages = 0
        do {
            val page = client.history(token, limit = 2, cursor = cursor).expectOk("history")
            all += page.items
            cursor = page.nextCursor
            pages++
        } while (cursor.isNotEmpty())

        assertEquals(5, all.size, "the walk must collect every row across pages")
        assertEquals(3, pages, "5 rows at limit 2 ⇒ pages of 2, 2, 1")

        val stale = client.history(token, limit = 2, cursor = "not-a-cursor")
        assertTrue(stale is ApiResult.ServerError && stale.status == 400, "garbled cursor ⇒ 400, got $stale")
    }

    /**
     * The server filters visibility after the keyset seek, so a page can arrive
     * short or empty while `next_cursor` is still live.
     */
    @Test
    fun `a short or empty page with a live cursor continues the walk`() = runTest {
        val client = client()
        val ids = (0 until 5).map { i ->
            mock.seedResolved("aws:op$i", "atlas", state = "approved", decidedBy = "kim", decidedSecondsAgo = (i + 1).toLong())
        }
        // Rows 2 and 3 are the second limit-2 scan window. Hiding them makes
        // page 2 arrive with zero items and a live cursor.
        mock.hideFromHistory(ids[2])
        mock.hideFromHistory(ids[3])
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val all = mutableListOf<ResolvedRequest>()
        var cursor = ""
        var pages = 0
        var sawEmptyPageWithLiveCursor = false
        do {
            val page = client.history(token, limit = 2, cursor = cursor).expectOk("history")
            if (page.items.isEmpty() && page.nextCursor.isNotEmpty()) sawEmptyPageWithLiveCursor = true
            all += page.items
            cursor = page.nextCursor
            pages++
        } while (cursor.isNotEmpty())

        // Checks the setup: the mock must have produced the empty page.
        assertTrue(sawEmptyPageWithLiveCursor, "the walk must have crossed an empty page carrying a cursor")
        assertEquals(3, pages, "the walk stops only on an empty cursor, never on an empty page")
        assertEquals(
            setOf(ids[0], ids[1], ids[4]),
            all.map { it.id }.toSet(),
            "every visible row must land - rows after the empty page are the ones a broken walk loses",
        )
    }

    @Test
    fun `args preview fields survive the round trip and absence renders null`() = runTest {
        val client = client()
        val withArgs = mock.seedRequest(
            argsPreview = "{}",
            bindingScope = "call",
            argsTruncated = true,
            argsBytes = 4096,
        )
        val bare = mock.seedRequest()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val rows = client.pending(token).expectOk("pending")

        val preview = assertNotNull(
            rows.single { it.id == withArgs }.argsPreview,
            "a row carrying args_preview must parse it, not drop it",
        )
        assertEquals("{}", preview.preview, "\"{}\" means no arguments - a real value, not absence")
        assertTrue(preview.truncated, "args_truncated arrives as a JSON boolean and must parse as one")
        assertEquals(4096L, preview.bytes, "args_bytes is the PRE-truncation redacted length, verbatim")
        assertEquals("a3f19c4b2e00", preview.hashPrefix)
        assertEquals("call", preview.bindingScope)

        assertNull(
            rows.single { it.id == bare }.argsPreview,
            "a row without the field renders pre-feature (null), never errors",
        )
    }

    @Test
    fun `execution context and humanized titles survive the round trip`() = runTest {
        val client = client()
        val withContext = mock.seedRequest(sessionId = "01a00145b2c93f70", harness = "claude-code")
        val bareKind = mock.seedRequest(tool = "shell.exec")
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val rows = client.pending(token).expectOk("pending")

        val ctx = rows.single { it.id == withContext }
        assertEquals("01a00145b2c93f70", ctx.sessionId)
        assertEquals("claude-code", ctx.harness)
        assertEquals("disable user in midpoint", ctx.toolTitle, "the underscore word rule: underscores are wire punctuation")
        assertEquals("mcp.call midpoint:disable_user", ctx.toolWire, "the machine identity stays verbatim beside the words")

        val bare = rows.single { it.id == bareKind }
        assertEquals("", bare.sessionId, "absent context parses blank and renders nothing")
        assertEquals("", bare.harness)
        assertEquals("run a shell command", bare.toolTitle, "a bare kind reads as the browser's verb phrase")
        assertEquals("shell.exec", bare.toolWire)
    }

    /**
     * A consumed ticket's `state` stays `approved`: the server does not rewrite
     * it, and the outcome is derived client-side from the grant fields.
     */
    @Test
    fun `use fields parse per class and a plain hold stays field-free`() = runTest {
        val client = client()
        val ticketId = mock.seedResolved(
            "aws:assume_role", "atlas", state = "approved", decidedBy = "kim", decidedSecondsAgo = 120,
            isTicket = true,
            grantExpiresSecondsFromNow = 3600,
            consumedSecondsAgo = 60,
            consumedBy = "deploy-bot",
            argsPreview = """{"role":"deployer"}""",
        )
        val holdId = mock.seedResolved(
            "okta:reset_mfa", "scout", state = "approved", decidedBy = "dana", decidedSecondsAgo = 30,
        )
        val ranHoldId = mock.seedResolved(
            "shell.exec", "scout", state = "approved", decidedBy = "dana", decidedSecondsAgo = 90,
            grantExpiresSecondsFromNow = -30, consumedSecondsAgo = 89, consumedBy = "3f8ee2a1907cc4d2",
        )
        val pendingTicket = mock.seedRequest(isTicket = true)
        val pendingHold = mock.seedRequest()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val feed = client.history(token).expectOk("history").items
        val ticket = feed.single { it.id == ticketId }
        assertEquals(ResolvedState.APPROVED, ticket.state, "consumption never rewrites state - outcome is derived")
        val grant = assertNotNull(ticket.grantExpiresAtEpochSeconds, "grant_expires_at must parse")
        assertTrue(grant - now() in 3540..3660, "grant window ~1h out in epoch SECONDS, got ${grant - now()}s")
        val consumed = assertNotNull(ticket.consumedAtEpochSeconds, "consumed_at must parse")
        assertTrue(now() - consumed in 30..120, "consumed ~60s ago in epoch SECONDS, got ${now() - consumed}s")
        assertEquals("deploy-bot", ticket.consumedBy)
        assertTrue(ticket.isTicket, "class:\"ticket\" must gate isTicket on the feed")
        assertEquals("""{"role":"deployer"}""", ticket.argsPreview?.preview, "history rows carry the preview too")

        val hold = feed.single { it.id == holdId }
        assertFalse(hold.isTicket, "class:\"hold\" is a hold")
        assertNull(hold.grantExpiresAtEpochSeconds, "a hold the server stamped nothing on has no window")
        assertNull(hold.consumedAtEpochSeconds)
        assertNull(hold.consumedBy)

        val ranHold = feed.single { it.id == ranHoldId }
        assertFalse(ranHold.isTicket, "a hold with use fields is still a hold")
        assertNotNull(ranHold.grantExpiresAtEpochSeconds, "the retry window parses on a hold")
        assertNotNull(ranHold.consumedAtEpochSeconds, "the use parses on a hold")
        assertEquals("3f8ee2a1907cc4d2", ranHold.consumedBy)

        val pendingRows = client.pending(token).expectOk("pending")
        assertTrue(pendingRows.single { it.id == pendingTicket }.isTicket, "class:\"ticket\" must gate isTicket")
        assertFalse(pendingRows.single { it.id == pendingHold }.isTicket, "class:\"hold\" or absent ⇒ a hold")
    }

    /** Unregister is a DELETE with a body, which some HTTP stacks refuse. */
    @Test
    fun `push register and unregister round trip`() = runTest {
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val endpoint = "https://ntfy.example/up123"

        client.registerPush(token, PushKind.UNIFIEDPUSH, endpoint).expectOk("register")
        client.registerPush(token, PushKind.UNIFIEDPUSH, endpoint).expectOk("register again")
        assertEquals(
            setOf("unifiedpush|$endpoint"),
            mock.registeredPushRoutes(),
            "a repeat registration must dedupe, not double",
        )

        client.unregisterPush(token, PushKind.UNIFIEDPUSH, endpoint).expectOk("unregister")
        assertTrue(mock.registeredPushRoutes().isEmpty(), "unregister must remove the route")

        // Idempotent: removing an already-gone route still succeeds.
        client.unregisterPush(token, PushKind.UNIFIEDPUSH, endpoint).expectOk("unregister again")
    }

    @Test
    fun `enroll carries the webpush and fcm blocks when the deployment advertises them`() = runTest {
        mock.close()
        mock = MockStrazad(
            webPushVapidPublicKey = TEST_VAPID,
            fcmAppConfig = MockStrazad.MockFcmConfig(
                projectId = "acme-prod",
                appId = "1:123456789:android:abcdef012345",
                apiKey = "AIzaSyExample",
                senderId = "123456789",
            ),
        ).start()

        val enrolled = client().enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        assertEquals(TEST_VAPID, enrolled.webpushVapidPublicKey)
        val fcm = assertNotNull(enrolled.fcm, "the fcm block must be parsed, not dropped")
        assertEquals("acme-prod", fcm.projectId)
        assertEquals("1:123456789:android:abcdef012345", fcm.appId)
        assertEquals("AIzaSyExample", fcm.apiKey)
        assertEquals("123456789", fcm.senderId)
    }

    @Test
    fun `enroll against a server without the lanes yields nulls`() = runTest {
        val enrolled = client().enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        assertNull(enrolled.webpushVapidPublicKey)
        assertNull(enrolled.fcm)
    }

    @Test
    fun `keyed push registration round trips and is addressed by its keys`() = runTest {
        mock.close()
        mock = MockStrazad(webPushVapidPublicKey = TEST_VAPID).start()
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val endpoint = "https://ntfy.example/up123"

        client.registerPush(token, PushKind.UNIFIEDPUSH, endpoint, VALID_P256DH, VALID_AUTH)
            .expectOk("keyed register")
        assertEquals(
            setOf("unifiedpush|$endpoint|$VALID_P256DH|$VALID_AUTH"),
            mock.registeredPushRoutes(),
            "the keys are part of the route's identity",
        )

        client.unregisterPush(token, PushKind.UNIFIEDPUSH, endpoint, VALID_P256DH, VALID_AUTH)
            .expectOk("keyed unregister")
        assertTrue(mock.registeredPushRoutes().isEmpty())
    }

    @Test
    fun `the server refuses malformed or misplaced subscription keys`() = runTest {
        val keyedMock = MockStrazad(webPushVapidPublicKey = TEST_VAPID).start()
        try {
            val keyedClient = StrazaClient(listOf(keyedMock.baseUrl()), SpkiPin.parse(keyedMock.spkiPin))
            val token = keyedClient.enroll("test-enroll-token", "d", devicePublicKey())
                .expectOk("enroll").deviceToken
            val endpoint = "https://ntfy.example/up123"

            // One key without the other.
            val half = keyedClient.registerPush(token, PushKind.UNIFIEDPUSH, endpoint, VALID_P256DH, null)
            assertEquals(400, assertIs<ApiResult.ServerError>(half).status)

            // Padded base64: the server takes unpadded base64url only.
            val padded = keyedClient.registerPush(
                token, PushKind.UNIFIEDPUSH, endpoint, "$VALID_P256DH==", VALID_AUTH,
            )
            assertEquals(400, assertIs<ApiResult.ServerError>(padded).status)

            // Keys on a kind that never takes them.
            val onFcm = keyedClient.registerPush(token, PushKind.FCM, "fcm-tok", VALID_P256DH, VALID_AUTH)
            assertEquals(400, assertIs<ApiResult.ServerError>(onFcm).status)
        } finally {
            keyedMock.close()
        }

        // The default mock has no WebPush sender, so it refuses a keyed
        // registration. PushRegistrar's keyless retry keys on this refusal.
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val refused = client.registerPush(
            token, PushKind.UNIFIEDPUSH, "https://ntfy.example/up9", VALID_P256DH, VALID_AUTH,
        )
        val err = assertIs<ApiResult.ServerError>(refused)
        assertEquals(400, err.status)
        assertTrue(
            "register without keys" in (err.message ?: ""),
            "the refusal must carry the server's own keyless-lane hint",
        )
    }

    @Test
    fun `push registrar falls back to the keyless lane against a keyless server`() = runTest {
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val endpoint = "https://ntfy.example/up123"

        val outcome = PushRegistrar.register(
            client,
            token,
            PushRoute(PushKind.UNIFIEDPUSH, endpoint, VALID_P256DH, VALID_AUTH),
        )
        assertIs<ApiResult.Ok<Unit>>(outcome.result)
        assertFalse(outcome.registered.hasKeys)
        assertEquals(setOf("unifiedpush|$endpoint"), mock.registeredPushRoutes())
    }

    @Test
    fun `token renewal round trip yields a working token`() = runTest {
        val client = client()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")

        val renewed = client.renewToken(enrolled.approverDeviceId, ::sign).expectOk("renew")
        assertTrue(renewed.deviceToken.isNotEmpty())
        assertTrue(renewed.deviceToken != enrolled.deviceToken, "a renewal mints a fresh token")
        assertEquals(2_592_000L, renewed.expiresInSeconds)

        client.pending(renewed.deviceToken).expectOk("pending with the renewed token")
    }

    /** Refresh is keyed by device id and signature, so it works with an expired bearer. */
    @Test
    fun `an expired token answers token_expired and renewal revives the device`() = runTest {
        val client = client()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        client.pending(enrolled.deviceToken).expectOk("pending before expiry")

        assertEquals(1, mock.expireTokens())

        val expired = client.pending(enrolled.deviceToken)
        assertIs<ApiResult.Unauthorized>(expired, "an expired bearer must stop authenticating")
        assertEquals(AuthRejection.TOKEN_EXPIRED, expired.reason, "and say why, so the app renews instead of wiping")

        val renewed = client.renewToken(enrolled.approverDeviceId, ::sign).expectOk("renew after expiry")
        client.pending(renewed.deviceToken).expectOk("pending with the renewed token")
    }

    @Test
    fun `a configured token lifetime is what enroll and refresh advertise`() = runTest {
        MockStrazad(initialTokenTtlSeconds = 120).start().use { short ->
            val client = StrazaClient(listOf(short.baseUrl()), SpkiPin.parse(short.spkiPin))
            val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
            assertEquals(120L, enrolled.expiresInSeconds)
            val renewed = client.renewToken(enrolled.approverDeviceId, ::sign).expectOk("renew")
            assertEquals(120L, renewed.expiresInSeconds)

            // The new lifetime applies to later mints only.
            short.setTokenTtl(2_592_000)
            val long = client.renewToken(enrolled.approverDeviceId, ::sign).expectOk("renew after ttl switch")
            assertEquals(2_592_000L, long.expiresInSeconds)
            client.pending(renewed.deviceToken).expectOk("the 120 s token minted earlier still authenticates")
        }
    }

    @Test
    fun `a declared outage answers 503 with Retry-After and lifts cleanly`() = runTest {
        val client = client()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")

        mock.setOutage(status = 503, seconds = 60, retryAfterSeconds = 20)
        val down = client.pending(enrolled.deviceToken)
        assertIs<ApiResult.ServerError>(down, "an outage is a server error, never an auth failure")
        assertEquals(503, down.status)
        assertEquals(20, down.retryAfterSeconds)

        mock.setOutage(status = 429, seconds = 60, retryAfterSeconds = null)
        val throttled = client.pending(enrolled.deviceToken)
        assertIs<ApiResult.ServerError>(throttled)
        assertEquals(429, throttled.status)
        assertNull(throttled.retryAfterSeconds, "no header, no number - the app applies its own default")

        mock.clearOutage()
        client.pending(enrolled.deviceToken).expectOk("pending after the outage")
    }

    @Test
    fun `self-unenroll retires the device row like an admin revoke`() = runTest {
        val client = client()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        client.unenroll(enrolled.deviceToken).expectOk("unenroll")

        val bearer = client.pending(enrolled.deviceToken)
        assertIs<ApiResult.Unauthorized>(bearer, "the retired bearer must stop authenticating")
        assertEquals(AuthRejection.DEVICE_REVOKED, bearer.reason)

        val refused = client.renewToken(enrolled.approverDeviceId, ::sign)
        assertIs<ApiResult.Unauthorized>(refused, "a retired device must not renew")
        assertEquals(AuthRejection.DEVICE_REVOKED, refused.reason)
    }

    /** A plain bad token gets an uncoded 401, the shape a pre-0.23 server sends. */
    @Test
    fun `revocation and legacy 401s carry the codes the key policy branches on`() = runTest {
        val client = client()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        mock.revokeDevice(enrolled.approverDeviceId)

        val refused = client.renewToken(enrolled.approverDeviceId, ::sign)
        assertIs<ApiResult.Unauthorized>(refused, "a revoked device must not renew")
        assertEquals(AuthRejection.DEVICE_REVOKED, refused.reason)

        val bearer = client.pending(enrolled.deviceToken)
        assertIs<ApiResult.Unauthorized>(bearer)
        assertEquals(AuthRejection.DEVICE_REVOKED, bearer.reason)

        val uncoded = client.pending("not-a-token")
        assertIs<ApiResult.Unauthorized>(uncoded)
        assertEquals(AuthRejection.UNKNOWN, uncoded.reason, "a bare 401 must read as unknown (key-preserving)")
    }

    /**
     * The re-pairing route depends on [ApiResult.Untrusted]. A pin mismatch
     * reported as unreachable would point the user at the network instead.
     */
    @Test
    fun `wrong pin is refused as untrusted rather than unreachable`() = runTest {
        val wrongPin = SpkiPin.parse("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!
        val result = client(wrongPin).enroll("test-enroll-token", "d", devicePublicKey())
        assertTrue(result is ApiResult.Untrusted, "expected Untrusted, got $result")
    }

    /** Without a pin the client uses system trust, which rejects a self-signed certificate. */
    @Test
    fun `self-signed server is refused when unpinned`() = runTest {
        val result = client(pin = null).enroll("test-enroll-token", "d", devicePublicKey())
        assertTrue(result is ApiResult.Untrusted, "system trust must reject self-signed, got $result")
    }

    @Test
    fun `a trust failure outranks an unreachable server in the report`() = runTest {
        val result = StrazaClient(
            listOf("https://127.0.0.1:1", mock.baseUrl()),
            SpkiPin.parse("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")!!,
            timeoutMillis = 2000,
        ).enroll("test-enroll-token", "d", devicePublicKey())
        assertTrue(result is ApiResult.Untrusted, "expected Untrusted, got $result")
    }

    @Test
    fun `bad enroll token is rejected`() = runTest {
        val result = client().enroll("not-the-token", "d", devicePublicKey())
        assertTrue(result is ApiResult.Unauthorized, "expected Unauthorized, got $result")
    }

    @Test
    fun `deciding twice returns the resolved state`() = runTest {
        val requestId = mock.seedRequest()
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val first = client.pending(token).expectOk("pending").single { it.id == requestId }
        client.decide(token, requestId, Verdict.DENY, first.challenge, now(), sign = ::sign).expectOk("decide")

        // The second attempt uses a freshly issued challenge, as a double-tap
        // after a re-fetch would.
        val second = client.pending(token).expectOk("pending").firstOrNull { it.id == requestId }
        val result = client.decide(
            token, requestId, Verdict.APPROVE,
            second?.challenge ?: first.challenge, now(), sign = ::sign,
        )
        assertTrue(result is ApiResult.AlreadyResolved, "expected AlreadyResolved, got $result")
        assertEquals("denied", result.state)
    }

    /**
     * This checks challenge binding, not nonce replay: the same nonce twice on
     * one request is answered 409 by the already-resolved check first.
     */
    @Test
    fun `challenge cannot be spent on a different request`() = runTest {
        val requestId = mock.seedRequest()
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        // The other request is unresolved, so the refusal cannot be the
        // already-resolved check.
        val otherId = mock.seedRequest()
        val result = client.decide(token, otherId, Verdict.APPROVE, request.challenge, now(), sign = ::sign)
        assertTrue(result is ApiResult.Unauthorized, "expected Unauthorized, got $result")
    }

    @Test
    fun `signature from a different key is rejected`() = runTest {
        val requestId = mock.seedRequest()
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        val attacker = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val result = client.decide(token, requestId, Verdict.APPROVE, request.challenge, now()) { message ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(attacker.private)
                update(message)
                sign()
            }
        }
        assertTrue(result is ApiResult.Unauthorized, "expected Unauthorized, got $result")
    }

    @Test
    fun `timestamp outside the window is rejected as challenge_rejected`() = runTest {
        val requestId = mock.seedRequest()
        val client = client()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        val result = client.decide(
            token, requestId, Verdict.APPROVE, request.challenge,
            now() - 3600, sign = ::sign,
        )
        assertIs<ApiResult.Unauthorized>(result, "expected Unauthorized, got $result")
        assertEquals(
            AuthRejection.CHALLENGE_REJECTED, result.reason,
            "a decide-time verification failure is coded challenge_rejected - recoverable, never revocation",
        )
    }

    @Test
    fun `unreachable server fails closed`() = runTest {
        val result = StrazaClient(listOf("https://127.0.0.1:1"), SpkiPin.parse(mock.spkiPin), timeoutMillis = 1000)
            .enroll("test-enroll-token", "d", devicePublicKey())
        assertTrue(result is ApiResult.Unreachable, "expected Unreachable, got $result")
    }

    @Test
    fun `falls back to the second server when the first is dead`() = runTest {
        val client = StrazaClient(
            listOf("https://127.0.0.1:1", mock.baseUrl()),
            SpkiPin.parse(mock.spkiPin),
            timeoutMillis = 2000,
        )
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey())
        assertTrue(enrolled is ApiResult.Ok, "expected fallback to succeed, got $enrolled")
    }

    /**
     * Server A is restarted with the same TLS identity but empty state, so it
     * answers this bearer with a 401, and a definitive HTTP response ends the
     * walk. The second call can only succeed if the client tried B first.
     */
    @Test
    fun `the preferred server is tried first after a success`() = runTest {
        // One shared keystore file means one certificate, so a single pin
        // covers A and B.
        val sharedCert = File.createTempFile("mock-strazad-memo", ".p12").apply { delete() }
        val serverB = MockStrazad(keystoreFile = sharedCert).start()
        try {
            // Start A only to claim a port, then stop it, so the first call
            // falls back to B and the client remembers B.
            val portA = MockStrazad(keystoreFile = sharedCert).start().use { it.port }
            val urlA = "https://localhost:$portA"

            val client = StrazaClient(
                listOf(urlA, serverB.baseUrl()),
                SpkiPin.parse(serverB.spkiPin),
                timeoutMillis = 2000,
            )
            val token = client.enroll("test-enroll-token", "d", devicePublicKey())
                .expectOk("enroll via fallback").deviceToken

            MockStrazad(keystoreFile = sharedCert).start(portA).use { rebornA ->
                assertEquals(serverB.spkiPin, rebornA.spkiPin, "shared keystore must mean one TLS identity")

                val viaMemo = client.pending(token)
                assertTrue(viaMemo is ApiResult.Ok, "memo must route to the server that worked, got $viaMemo")

                // A alone refuses this token, so the Ok above came from trying
                // B first.
                val direct = StrazaClient(listOf(urlA), SpkiPin.parse(serverB.spkiPin)).pending(token)
                assertIs<ApiResult.Unauthorized>(direct, "the reborn A must refuse a token it never issued")
            }
        } finally {
            serverB.close()
            sharedCert.delete()
        }
    }

    // Decided attribution

    @Test
    fun `a decision with a reason records the words and the surface`() = runTest {
        val client = client()
        val requestId = mock.seedRequest()
        val enrolled = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll")
        val token = enrolled.deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        client.decide(
            token, requestId, Verdict.DENY, request.challenge, now(),
            reason = "Migrate the exporter first.\nNothing drops during month-end.",
            sign = ::sign,
        ).expectOk("decide with reason")

        val row = client.history(token).expectOk("history").items.single { it.id == requestId }
        assertEquals("Migrate the exporter first.\nNothing drops during month-end.", row.decidedReason)
        assertEquals("phone", row.decidedViaSurface)
        assertEquals(enrolled.approverDeviceId, row.decidedViaDeviceId)
    }

    /**
     * The client cannot produce this mismatch, because message and body derive
     * from one value, so this test and the next forge it in the signing callback.
     */
    @Test
    fun `a reason beside a four line signature is refused`() = runTest {
        val client = client()
        val requestId = mock.seedRequest()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        val result = client.decide(
            token, requestId, Verdict.APPROVE, request.challenge, now(),
            reason = "attached words",
            // Sign only the first four lines, as if the reason were added
            // after signing.
            sign = { msg -> sign(msg.decodeToString().split("\n").take(4).joinToString("\n").encodeToByteArray()) },
        )
        assertIs<ApiResult.Unauthorized>(result, "expected refusal, got $result")
        // Nothing was recorded: the row is still pending.
        assertTrue(client.pending(token).expectOk("pending").any { it.id == requestId })
    }

    @Test
    fun `a five line signature without the reason is refused`() = runTest {
        val client = client()
        val requestId = mock.seedRequest()
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken
        val request = client.pending(token).expectOk("pending").single { it.id == requestId }

        val result = client.decide(
            token, requestId, Verdict.APPROVE, request.challenge, now(),
            reason = null,
            // Sign a five-line message whose reason the body never sends.
            sign = { msg ->
                sign((msg.decodeToString() + "\n" + "ab".repeat(32)).encodeToByteArray())
            },
        )
        assertIs<ApiResult.Unauthorized>(result, "expected refusal, got $result")
    }

    @Test
    fun `pending rows carry the subject kind and the origin actor`() = runTest {
        val client = client()
        val humanReq = mock.seedRequest(requester = "bob", requesterKind = "human")
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val row = client.pending(token).expectOk("pending").single { it.id == humanReq }
        assertEquals("bob", row.requester)
        assertEquals("human", row.requesterKind)
        assertEquals("agent_session", row.originActor)
    }

    /** An older server sends neither origin nor kind. */
    @Test
    fun `absent attribution fields parse as empty`() = runTest {
        val client = client()
        val requestId = mock.seedRequest()
        mock.dropFieldFromPending(requestId, "origin")
        val token = client.enroll("test-enroll-token", "d", devicePublicKey()).expectOk("enroll").deviceToken

        val row = client.pending(token).expectOk("pending").single { it.id == requestId }
        assertEquals("", row.originActor)
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun <T> ApiResult<T>.expectOk(what: String): T =
        (this as? ApiResult.Ok)?.value ?: error("$what failed: $this")
}
