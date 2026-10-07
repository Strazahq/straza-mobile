package dev.straza.approver.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A small fake strazad for developing and testing the app without a real
 * server. It is as strict as the real one: it verifies signatures, burns
 * challenges, enforces the timestamp window and answers 409 to a second
 * decide. It builds the canonical signing message itself instead of importing
 * the app's builder, so a bug in that builder fails here. By default it serves
 * TLS from a self-signed certificate, which exercises SPKI pinning.
 */
class MockStrazad(
    private val enrollToken: String = "test-enroll-token",
    private val keystoreFile: File = File.createTempFile("mock-strazad", ".p12").apply { delete() },
    /** The username this server attributes a decision made through the app to. */
    private val selfUsername: String = "you",
    /** The deployment id the app keys on. A second mock needs a different one. */
    private val projectId: String = "prj_mock",
    private val projectName: String = "Mock Straza",
    /**
     * The VAPID public key sent as `webpush.vapid_public_key` in the enroll
     * 201. Null models a server older than openapi 0.40.0 or one with Web Push
     * off, which answers 400 to any keyed push registration.
     */
    private val webPushVapidPublicKey: String? = null,
    /** Firebase app config sent as the `fcm` block of the enroll 201. Public values only. */
    private val fcmAppConfig: MockFcmConfig? = null,
    /**
     * The URL phones use when a TLS-terminating reverse proxy fronts this
     * instance, as in review mode. When set, [qrPayload] advertises it and
     * omits the `pin` key: the phone sees the proxy's certificate, so a pin
     * for this instance's certificate would fail every enrollment.
     */
    private val publicUrl: String? = null,
    /**
     * Serve plain HTTP, for use behind a TLS-terminating proxy on the same host.
     * The self-signed certificate lasts 30 days and is not regenerated.
     */
    private val plainHttp: Boolean = false,
    /** Serve the unauthenticated `/review` page: instructions, enrollment QR, copyable payload. */
    private val reviewPage: Boolean = false,
    /**
     * Lifetime of the device tokens minted here, sent as `expires_in`. An
     * older bearer gets `401 {code: token_expired}`. The default is the real
     * server's 30 days. Under 7 days the app shows its "renews soon" banner.
     */
    initialTokenTtlSeconds: Long = DEFAULT_TOKEN_TTL_SECONDS,
    /** Register the unauthenticated `/mock/...` controls. Off in review mode, which is public. */
    private val devControls: Boolean = false,
) : AutoCloseable {

    data class MockFcmConfig(
        val projectId: String,
        val appId: String,
        val apiKey: String,
        val senderId: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private lateinit var server: HttpServer

    /** Device rows created by enrollment: device_token -> enrolled public key. */
    private val devices = ConcurrentHashMap<String, java.security.PublicKey>()

    /**
     * Enrolled keys by approver_device_id, for token refresh. Entries stay
     * after revocation, so a revoked device's signature still verifies and it
     * can be told `device_revoked`.
     */
    private val enrolledKeys = ConcurrentHashMap<String, java.security.PublicKey>()

    /** device_token -> approver_device_id, so a revoked device's bearer tokens can be refused. */
    private val tokenDeviceIds = ConcurrentHashMap<String, String>()

    /** device_token -> mint time. Backs [bearerExpired]; [expireTokens] zeroes every entry. */
    private val tokenIssuedAtMillis = ConcurrentHashMap<String, Long>()

    /** A declared outage ([setOutage]): what every `/v1/approver/...` call answers until it lapses. */
    private class Outage(val status: Int, val untilMillis: Long, val retryAfterSeconds: Long?)

    @Volatile
    private var outage: Outage? = null

    /** The current token lifetime. [setTokenTtl] changes it. */
    @Volatile
    private var tokenTtlSeconds: Long = initialTokenTtlSeconds

    /** Live refresh challenges: nonce -> approver_device_id. Removed on use. */
    private val refreshChallenges = ConcurrentHashMap<String, Minted>()

    /** Revoked device ids, refused on every authenticated route and at refresh. */
    private val revokedDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Live decision challenges: nonce -> request id. Removed on use. */
    private val challenges = ConcurrentHashMap<String, Minted>()

    /** A challenge nonce's target and mint time. The time lets unclaimed nonces be swept. */
    private class Minted(val target: String, val atMillis: Long)

    /** Rotation cursor for the seeds [reviewTick] picks. */
    private val reviewSeedCounter = java.util.concurrent.atomic.AtomicInteger()

    /** Request id -> final state, once decided. Backs the 409 in [handleDecide]. */
    private val resolved = ConcurrentHashMap<String, String>()

    private val pending = ConcurrentHashMap<String, JsonObject>()

    /** Full resolved rows for `/v1/approver/history`, served newest first by decided_at. */
    private val history = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()

    /** Registered push routes, keyed `kind|endpoint`. Registering one again is a no-op. */
    private val pushRoutes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Resolved rows that [handleHistory] scans but does not return. See [hideFromHistory]. */
    private val hiddenFromHistory = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    var port: Int = 0
        private set

    /** The SPKI pin for this instance's certificate, in QR format (`sha256/…`). */
    lateinit var spkiPin: String
        private set

    fun baseUrl(): String = publicUrl ?: "${if (plainHttp) "http" else "https"}://localhost:$port"

    /** The enrollment QR payload an admin console would render for this server. */
    fun qrPayload(): String = buildJsonObject {
        put("v", 1)
        put("servers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(baseUrl())) })
        put("token", enrollToken)
        // The pin applies only when the phone terminates TLS at this instance.
        if (publicUrl == null && !plainHttp) put("pin", spkiPin)
        put("project", buildJsonObject { put("id", projectId); put("name", projectName) })
    }.toString()

    fun start(requestedPort: Int = 0): MockStrazad {
        val sslContext = if (plainHttp) {
            null
        } else {
            val password = "mock-strazad".toCharArray()
            val keyStore = SelfSignedCert.loadOrCreate(keystoreFile, password)
            val cert = keyStore.getCertificate(SelfSignedCert.ALIAS) as X509Certificate

            // The pin is SHA-256 over the SubjectPublicKeyInfo, the bytes
            // publicKey.encoded returns.
            spkiPin = "sha256/" + Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        server = try {
            // Loopback only in both modes: dev reaches it through adb reverse,
            // review mode through a reverse proxy on the same host.
            if (sslContext == null) {
                HttpServer.create(InetSocketAddress("127.0.0.1", requestedPort), 0)
            } else {
                HttpsServer.create(InetSocketAddress("127.0.0.1", requestedPort), 0)
                    .apply { httpsConfigurator = HttpsConfigurator(sslContext) }
            }
        } catch (e: java.net.BindException) {
            throw IllegalStateException(
                "port $requestedPort is already in use - another mock strazad is probably still " +
                    "running (check your other terminal), or something else holds the port. " +
                    "Stop it, or start this one on a different port: " +
                    "gradlew :mock-strazad:run --args=\"8444\"",
                e,
            )
        }
        server.createContext("/v1/approver/enroll", ::handleEnroll)
        // HttpServer matches the longest prefix, so /refresh/challenge wins over /refresh.
        server.createContext("/v1/approver/refresh/challenge", ::handleRefreshChallenge)
        server.createContext("/v1/approver/refresh", ::handleRefresh)
        server.createContext("/v1/approver/pending", ::handlePending)
        server.createContext("/v1/approver/decide", ::handleDecide)
        server.createContext("/v1/approver/history", ::handleHistory)
        server.createContext("/v1/approver/push", ::handlePush)
        server.createContext("/v1/approver/enrollment", ::handleEnrollment)
        if (reviewPage) server.createContext("/review", ::handleReview)
        if (devControls) {
            server.createContext("/mock/outage", ::handleOutageControl)
            server.createContext("/mock/expire-tokens", ::handleExpireTokensControl)
            server.createContext("/mock/token-ttl", ::handleTokenTtlControl)
        }
        server.executor = null
        server.start()
        port = server.address.port
        return this
    }

    /** Seeds a pending approval request and returns its id. [ttlSeconds] is the decision window. */
    fun seedRequest(
        tool: String = "midpoint:disable_user",
        requester: String = "nova",
        ttlSeconds: Long = 90,
        isTicket: Boolean = false,
        argsPreview: String? = null,
        bindingScope: String = "tool_identity",
        /** The preview was cut to head and tail server-side; the marker is inline. */
        argsTruncated: Boolean = false,
        /** Redacted length before truncation. Pass it when [argsTruncated] is set. */
        argsBytes: Long? = null,
        /** Subject kind: `nhi` or `human`. */
        requesterKind: String = "nhi",
        /** The agent session and its harness. Null models a server older than openapi 0.69.0. */
        sessionId: String? = null,
        harness: String? = null,
        ruleId: String = "disable-user-needs-human",
        justification: String = "agent says: offboarding ticket INC-42",
    ): String {
        val id = "apr_" + randomToken(8)
        val createdAt = Instant.now()
        pending[id] = buildJsonObject {
            put("id", id)
            put("created_at", DateTimeFormatter.ISO_INSTANT.format(createdAt))
            put("expires_at", DateTimeFormatter.ISO_INSTANT.format(createdAt.plusSeconds(ttlSeconds)))
            put("requester", buildJsonObject { put("username", requester); put("kind", requesterKind) })
            put("origin", buildJsonObject { put("actor", "agent_session") })
            put("rule_id", ruleId)
            put("set_name", "prod-gates")
            putSummary(this, tool)
            sessionId?.let { put("session_id", it) }
            harness?.let { put("harness", it) }
            put("class", if (isTicket) "ticket" else "hold")
            argsPreview?.let { putArgsPreview(this, it, bindingScope, argsTruncated, argsBytes) }
            put("justification", justification)
        }
        return id
    }

    /**
     * Writes the `summary` object. An `app:tool_name` seed becomes an MCP call
     * with both parts. A seed without a colon becomes a bare kind carrying
     * only `tool`, the shape the server sends for shell, file and net calls.
     */
    private fun putSummary(b: JsonObjectBuilder, tool: String) {
        b.put(
            "summary",
            buildJsonObject {
                if (':' in tool) {
                    put("tool", "mcp.call")
                    put("app", tool.substringBefore(':'))
                    put("tool_name", tool.substringAfter(':'))
                } else {
                    put("tool", tool)
                }
            },
        )
    }

    /**
     * Writes the args-preview fields. `args_bytes` is the length before
     * truncation and defaults to the preview's own size.
     */
    private fun putArgsPreview(
        b: JsonObjectBuilder,
        preview: String,
        bindingScope: String,
        truncated: Boolean = false,
        bytes: Long? = null,
    ) {
        b.put("args_preview", preview)
        b.put("args_truncated", truncated)
        b.put("args_bytes", bytes ?: preview.encodeToByteArray().size.toLong())
        b.put("argv_hash_prefix", "a3f19c4b2e00")
        b.put("binding_scope", bindingScope)
    }

    /** Removes [field] from a seeded request, to model a server that does not send it. */
    fun dropFieldFromPending(id: String, field: String) {
        val row = pending[id] ?: return
        pending[id] = JsonObject(row.filterKeys { it != field })
    }

    /**
     * Seeds an already-resolved request, as if it had been decided on another
     * channel or had timed out. [decidedBy] is null for an expired row.
     */
    fun seedResolved(
        tool: String,
        requester: String,
        state: String,
        decidedBy: String?,
        decidedSecondsAgo: Long,
        justification: String = "",
        isTicket: Boolean = false,
        // Use window, relative to now: a ticket's grant or a hold's retry
        // window. Positive is still open, negative has lapsed. A non-null
        // consumedSecondsAgo means it was used.
        grantExpiresSecondsFromNow: Long? = null,
        consumedSecondsAgo: Long? = null,
        consumedBy: String? = null,
        argsPreview: String? = null,
        bindingScope: String = "tool_identity",
        /**
         * Surface the decision was made on: `phone`, `browser`, `console` or
         * `slack`, or the legacy `api` on rows written before openapi 0.63.0.
         * Null when the server does not say, as on expired rows.
         */
        decidedViaSurface: String? = null,
        decidedViaDeviceId: String? = null,
        /** The decider's own words, if any. */
        decidedReason: String? = null,
        requesterKind: String = "nhi",
        sessionId: String? = null,
        harness: String? = null,
        ruleId: String = "disable-user-needs-human",
    ): String {
        val id = "apr_" + randomToken(8)
        val now = Instant.now()
        val decidedAt = now.minusSeconds(decidedSecondsAgo)
        val createdAt = decidedAt.minusSeconds(120)
        recordHistory(
            buildJsonObject {
                put("id", id)
                put("created_at", DateTimeFormatter.ISO_INSTANT.format(createdAt))
                put("expires_at", DateTimeFormatter.ISO_INSTANT.format(createdAt.plusSeconds(90)))
                put("requester", buildJsonObject { put("username", requester); put("kind", requesterKind) })
                put("origin", buildJsonObject { put("actor", "agent_session") })
                put("rule_id", ruleId)
                put("set_name", "prod-gates")
                putSummary(this, tool)
                sessionId?.let { put("session_id", it) }
                harness?.let { put("harness", it) }
                put("state", state)
                if (decidedBy != null) put("decided_by", decidedBy)
                if (justification.isNotBlank()) put("justification", justification)
                put("decided_at", DateTimeFormatter.ISO_INSTANT.format(decidedAt))
                decidedViaSurface?.let { surface ->
                    put(
                        "decided_via",
                        buildJsonObject {
                            put("surface", surface)
                            decidedViaDeviceId?.let { put("device_id", it) }
                        },
                    )
                }
                decidedReason?.let { put("decided_reason", it) }
                put("class", if (isTicket) "ticket" else "hold")
                grantExpiresSecondsFromNow?.let { put("grant_expires_at", DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(it))) }
                consumedSecondsAgo?.let { put("consumed_at", DateTimeFormatter.ISO_INSTANT.format(now.minusSeconds(it))) }
                consumedBy?.let { put("consumed_by", it) }
                argsPreview?.let { putArgsPreview(this, it, bindingScope) }
            },
        )
        // A later decide against this row answers 409, as on the real server.
        resolved[id] = state
        return id
    }

    // Endpoints

    private fun handleEnroll(ex: HttpExchange) = ex.handle { body ->
        val req = json.parseToJsonElement(body).jsonObject
        if (req["enroll_token"]?.jsonPrimitive?.content != enrollToken) {
            return@handle 401 to error("invalid enroll token")
        }

        // The review-mode enrollment code is public, so enrollment is capped.
        // Over the cap the answer is 429; enrolled devices are not evicted.
        if (devices.size >= MAX_DEVICES) {
            return@handle 429 to error("enrollment capacity reached, try again later")
        }

        val device = req["device"]?.jsonObject ?: return@handle 400 to error("missing device")
        if (device["key_alg"]?.jsonPrimitive?.content != "ecdsa-p256") {
            return@handle 400 to error("unsupported key_alg")
        }

        val spki = try {
            Base64.getDecoder().decode(device["public_key"]!!.jsonPrimitive.content)
        } catch (_: Exception) {
            return@handle 400 to error("malformed public_key")
        }

        val publicKey = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        } catch (_: Exception) {
            return@handle 400 to error("public_key is not a parseable P-256 SPKI")
        }

        val deviceToken = randomToken(24)
        val deviceId = "apd_" + randomToken(8)
        devices[deviceToken] = publicKey
        enrolledKeys[deviceId] = publicKey
        tokenDeviceIds[deviceToken] = deviceId
        tokenIssuedAtMillis[deviceToken] = System.currentTimeMillis()

        201 to buildJsonObject {
            put("approver_device_id", deviceId)
            put("device_token", deviceToken)
            put("expires_in", tokenTtlSeconds)
            put(
                "project",
                buildJsonObject {
                    put("id", projectId)
                    put("name", projectName)
                },
            )
            // Optional blocks are omitted when not configured, not sent as null.
            webPushVapidPublicKey?.let {
                put("webpush", buildJsonObject { put("vapid_public_key", it) })
            }
            fcmAppConfig?.let {
                put(
                    "fcm",
                    buildJsonObject {
                        put("project_id", it.projectId)
                        put("app_id", it.appId)
                        put("api_key", it.apiKey)
                        put("sender_id", it.senderId)
                    },
                )
            }
        }
    }

    /**
     * Step one of token refresh: issues a nonce for the device to sign. No
     * bearer is required, because the caller's token may have expired. An
     * unknown device id gets 404. A revoked device still gets a challenge;
     * [handleRefresh] reports the revocation after proof of possession.
     */
    private fun handleRefreshChallenge(ex: HttpExchange) = ex.handle { body ->
        val req = json.parseToJsonElement(body).jsonObject
        val deviceId = req["approver_device_id"]?.jsonPrimitive?.content
            ?: return@handle 400 to error("missing approver_device_id")
        if (!enrolledKeys.containsKey(deviceId)) return@handle 404 to error("unknown device")

        val nonce = mintChallenge(refreshChallenges, deviceId)
        200 to buildJsonObject {
            put("challenge", nonce)
            put("expires_in", 300)
        }
    }

    /**
     * Step two of token refresh: exchanges a challenge signed with the
     * enrolled device key for a new device_token. Each 401 carries a `code`,
     * so the app can tell a refresh to retry from a revoked enrollment.
     */
    private fun handleRefresh(ex: HttpExchange) = ex.handle { body ->
        val req = json.parseToJsonElement(body).jsonObject
        val deviceId = req["approver_device_id"]?.jsonPrimitive?.content
            ?: return@handle 400 to error("missing approver_device_id")
        val challenge = req["challenge"]?.jsonPrimitive?.content
            ?: return@handle 400 to error("missing challenge")
        val signature = try {
            Base64.getDecoder().decode(req["signature"]!!.jsonPrimitive.content)
        } catch (_: Exception) {
            return@handle 400 to error("malformed signature")
        }

        // Single-use challenge, bound to the device it was issued for. It is
        // removed on first use, so a captured request cannot be replayed.
        val boundTo = refreshChallenges.remove(challenge)
        if (boundTo == null || boundTo.target != deviceId) {
            return@handle 401 to errorWithCode("stale or unknown challenge", "token_invalid")
        }

        val key = enrolledKeys[deviceId]
            ?: return@handle 401 to errorWithCode("stale or unknown challenge", "token_invalid")

        // The "refresh" domain tag keeps a refresh signature from being
        // accepted as a decision signature.
        val message = "refresh\n$deviceId\n$challenge".toByteArray(Charsets.UTF_8)
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) return@handle 401 to errorWithCode("signature did not verify", "token_invalid")

        // Checked after the signature, as on the real server: a revoked device
        // that can still sign is told so.
        if (deviceId in revokedDevices) {
            return@handle 401 to errorWithCode("device revoked", "device_revoked")
        }

        val deviceToken = randomToken(24)
        devices[deviceToken] = key
        tokenDeviceIds[deviceToken] = deviceId
        tokenIssuedAtMillis[deviceToken] = System.currentTimeMillis()
        200 to buildJsonObject {
            put("device_token", deviceToken)
            put("expires_in", tokenTtlSeconds)
        }
    }

    private fun handlePending(ex: HttpExchange) = ex.handle {
        if (bearerExpired(ex)) return@handle 401 to errorWithCode("device token expired", "token_expired")
        if (bearerRevoked(ex)) return@handle 401 to errorWithCode("device revoked", "device_revoked")
        bearerKey(ex) ?: return@handle 401 to error("bad device token")

        val list = buildJsonArray {
            pending.forEach { (id, row) ->
                if (resolved.containsKey(id)) return@forEach
                // A fresh nonce per fetch.
                val nonce = mintChallenge(challenges, id)
                add(
                    buildJsonObject {
                        row.forEach { (k, v) -> put(k, v) }
                        put("challenge", nonce)
                    },
                )
            }
        }
        200 to list
    }

    private fun handleDecide(ex: HttpExchange) = ex.handle { body ->
        if (bearerExpired(ex)) return@handle 401 to errorWithCode("device token expired", "token_expired")
        if (bearerRevoked(ex)) return@handle 401 to errorWithCode("device revoked", "device_revoked")
        val publicKey = bearerKey(ex) ?: return@handle 401 to error("bad device token")
        val req = json.parseToJsonElement(body).jsonObject

        val requestId = req["request_id"]?.jsonPrimitive?.content ?: return@handle 400 to error("missing request_id")
        val verdict = req["verdict"]?.jsonPrimitive?.content ?: return@handle 400 to error("missing verdict")
        val challenge = req["challenge"]?.jsonPrimitive?.content ?: return@handle 400 to error("missing challenge")
        val ts = req["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@handle 400 to error("missing ts")
        val signature = try {
            Base64.getDecoder().decode(req["signature"]!!.jsonPrimitive.content)
        } catch (_: Exception) {
            return@handle 400 to error("malformed signature")
        }

        if (verdict != "approve" && verdict != "deny") return@handle 400 to error("bad verdict")

        // Optional decider reason. An empty string means no reason. An
        // invalid reason is rejected, not cleaned.
        val reason = req["reason"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        if (reason != null && !validReason(reason)) return@handle 400 to error("bad reason")

        resolved[requestId]?.let { return@handle 409 to buildJsonObject { put("state", it) } }

        // Single-use nonce, bound to the request it was issued for.
        val boundTo = challenges.remove(challenge)
        // Every verification failure below carries the one coarse code
        // `challenge_rejected`, so the response is not a signature oracle.
        if (boundTo == null || boundTo.target != requestId) {
            return@handle 401 to errorWithCode("stale or unknown challenge", "challenge_rejected")
        }

        if (abs(System.currentTimeMillis() / 1000 - ts) > TS_WINDOW_SECONDS) {
            return@handle 401 to errorWithCode("timestamp outside window", "challenge_rejected")
        }

        // When a reason is sent, the signed message has a fifth line: the
        // lowercase hex SHA-256 of the reason's UTF-8 bytes. A reason the
        // signature does not cover, or a signed reason that is not sent,
        // fails verification.
        val message = buildString {
            append("$requestId\n$verdict\n$challenge\n$ts")
            if (reason != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(reason.toByteArray(Charsets.UTF_8))
                append('\n').append(digest.joinToString("") { "%02x".format(it) })
            }
        }.toByteArray(Charsets.UTF_8)
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) return@handle 401 to errorWithCode("signature did not verify", "challenge_rejected")

        val state = if (verdict == "approve") "approved" else "denied"
        resolved[requestId] = state

        // Record the decision in the history feed.
        pending[requestId]?.let { row ->
            recordHistory(
                buildJsonObject {
                    row.forEach { (k, v) -> if (k != "challenge") put(k, v) }
                    put("state", state)
                    put("decided_by", selfUsername)
                    put("decided_at", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    if (state == "approved") {
                        // The use window set on approval: an hour for a ticket,
                        // 60 s for a hold. A held call runs at once, so the
                        // hold is marked used.
                        val now = Instant.now()
                        val ticket = row["class"]?.jsonPrimitive?.content == "ticket"
                        put("grant_expires_at", DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(if (ticket) 3600 else 60)))
                        if (!ticket) {
                            put("consumed_at", DateTimeFormatter.ISO_INSTANT.format(now))
                            row["session_id"]?.let { put("consumed_by", it) }
                        }
                    }
                    put(
                        "decided_via",
                        buildJsonObject {
                            put("surface", "phone")
                            bearerToken(ex)?.let { tokenDeviceIds[it] }?.let { put("device_id", it) }
                        },
                    )
                    if (reason != null) put("decided_reason", reason)
                },
            )
        }

        200 to buildJsonObject { put("state", state) }
    }

    /**
     * The server's rule for a decider reason: at most 500 UTF-8 bytes, no
     * control characters except newline and tab (DEL is refused), and none of
     * the 14 invisible formatting code points.
     */
    private fun validReason(reason: String): Boolean {
        if (reason.toByteArray(Charsets.UTF_8).size > 500) return false
        val formatting = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200E\u200F\u061C\u200B\uFEFF"
        return reason.none {
            (it.code < 0x20 && it != '\n' && it != '\t') || it.code == 0x7F || it in formatting
        }
    }

    private fun handleHistory(ex: HttpExchange) = ex.handle {
        if (bearerExpired(ex)) return@handle 401 to errorWithCode("device token expired", "token_expired")
        if (bearerRevoked(ex)) return@handle 401 to errorWithCode("device revoked", "device_revoked")
        bearerKey(ex) ?: return@handle 401 to error("bad device token")

        // Keyset pagination: limit clamps at 200, the cursor is opaque, and a
        // malformed or stale cursor is 400 invalid_cursor. next_cursor is ""
        // on the last page.
        val limit = queryParam(ex, "limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val sorted = history.sortedByDescending { decidedAtEpoch(it) }
        val offset = decodeHistoryCursor(queryParam(ex, "cursor"))
            ?: return@handle 400 to errorWithCode("malformed cursor", "invalid_cursor")
        if (offset > sorted.size) return@handle 400 to errorWithCode("stale cursor", "invalid_cursor")
        // The cursor advances over every scanned row, and hidden rows are
        // filtered afterwards, as on the real server. A page can be short or
        // empty while next_cursor is still set.
        val scanned = sorted.drop(offset).take(limit)
        val page = scanned.filterNot { it["id"]?.jsonPrimitive?.content in hiddenFromHistory }
        val nextOffset = offset + scanned.size
        val nextCursor = if (nextOffset < sorted.size) encodeHistoryCursor(nextOffset) else ""
        200 to buildJsonObject {
            put("items", buildJsonArray { page.forEach { add(it) } })
            put("next_cursor", nextCursor)
        }
    }

    private fun encodeHistoryCursor(offset: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("1\u0000$offset".toByteArray())

    /** Returns null for a malformed cursor and 0 for an absent or empty one. */
    private fun decodeHistoryCursor(raw: String?): Int? {
        if (raw.isNullOrEmpty()) return 0
        return try {
            val s = String(Base64.getUrlDecoder().decode(raw))
            if (!s.startsWith("1\u0000")) null else s.substring(2).toIntOrNull()?.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `DELETE /v1/approver/enrollment`: the device retires itself with its
     * bearer. Answers 204. Afterwards the bearer and any refresh get
     * `device_revoked`, as after an admin revoke.
     */
    private fun handleEnrollment(ex: HttpExchange) = ex.handle { _ ->
        if (ex.requestMethod != "DELETE") return@handle 405 to error("method not allowed")
        if (bearerExpired(ex)) return@handle 401 to errorWithCode("device token expired", "token_expired")
        if (bearerRevoked(ex)) return@handle 401 to errorWithCode("device revoked", "device_revoked")
        bearerKey(ex) ?: return@handle 401 to error("bad device token")
        val deviceId = bearerToken(ex)?.let { tokenDeviceIds[it] } ?: return@handle 401 to error("bad device token")
        revokedDevices.add(deviceId)
        204 to ""
    }

    /**
     * Push route registration: PUT adds, DELETE removes, both idempotent. The
     * mock delivers nothing. Web Push subscription keys are validated with
     * the real server's rules.
     */
    private fun handlePush(ex: HttpExchange) = ex.handle { body ->
        if (bearerExpired(ex)) return@handle 401 to errorWithCode("device token expired", "token_expired")
        if (bearerRevoked(ex)) return@handle 401 to errorWithCode("device revoked", "device_revoked")
        bearerKey(ex) ?: return@handle 401 to error("bad device token")
        val req = json.parseToJsonElement(body).jsonObject
        val kind = req["kind"]?.jsonPrimitive?.content ?: return@handle 400 to error("missing kind")
        val endpoint = req["token_or_endpoint"]?.jsonPrimitive?.content
            ?: return@handle 400 to error("missing token_or_endpoint")
        val p256dh = req["p256dh"]?.jsonPrimitive?.content
        val auth = req["auth"]?.jsonPrimitive?.content

        val hasKeys = p256dh != null || auth != null
        if (hasKeys) {
            if (p256dh == null || auth == null) {
                return@handle 400 to error("p256dh and auth travel together (got one without the other)")
            }
            if (kind != "webpush" && kind != "unifiedpush") {
                return@handle 400 to
                    error("kind \"$kind\" does not take subscription keys (p256dh/auth belong to webpush/unifiedpush)")
            }
            if (webPushVapidPublicKey == null) {
                // The real server's refusal text. The app answers this 400
                // with one retry without keys.
                return@handle 400 to error(
                    "subscription keys need the WebPush sender - set approval.push.webpush.vapidKeyFile " +
                        "(or register without keys for the legacy lane)",
                )
            }
            val point = decodeUnpaddedBase64Url(p256dh)
                ?: return@handle 400 to error("p256dh is not unpadded base64url")
            if (point.size != 65 || point[0] != 0x04.toByte()) {
                return@handle 400 to error("p256dh is not a 65-octet uncompressed P-256 point")
            }
            val secret = decodeUnpaddedBase64Url(auth)
                ?: return@handle 400 to error("auth is not unpadded base64url")
            if (secret.size != 16) return@handle 400 to error("auth is not exactly 16 octets")
        } else if (kind == "webpush") {
            return@handle 400 to error("kind webpush requires p256dh and auth (the PushSubscription's keys)")
        }

        val key = if (hasKeys) "$kind|$endpoint|$p256dh|$auth" else "$kind|$endpoint"
        when (ex.requestMethod) {
            "PUT" -> pushRoutes.add(key)
            "DELETE" -> pushRoutes.remove(key)
            else -> return@handle 405 to error("method not allowed")
        }
        200 to buildJsonObject { put("status", "ok") }
    }

    /** Decodes unpadded base64url, or returns null. The real server refuses padded input. */
    private fun decodeUnpaddedBase64Url(value: String): ByteArray? {
        if (value.contains('=')) return null
        return try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** For tests: the currently registered push routes, as `kind|endpoint` keys. */
    fun registeredPushRoutes(): Set<String> = pushRoutes.toSet()

    /**
     * For tests: hides a resolved row from history pages while the pagination
     * scan still counts it. This produces a short or empty page with a live
     * cursor (see [handleHistory]).
     */
    fun hideFromHistory(id: String) {
        hiddenFromHistory.add(id)
    }

    /**
     * Dev control: expires every live device token, so the next authenticated
     * call answers `401 token_expired`. Refresh still works, because it does
     * not use the bearer. Returns the number of tokens expired.
     */
    fun expireTokens(): Int {
        val n = tokenIssuedAtMillis.size
        tokenIssuedAtMillis.replaceAll { _, _ -> 0L }
        return n
    }

    /**
     * Dev control: for [seconds], every `/v1/approver/...` call answers
     * [status], with a `Retry-After` header when [retryAfterSeconds] is
     * non-null. The `/mock/...` controls stay reachable, so [clearOutage] can
     * end the outage early.
     */
    fun setOutage(status: Int = 503, seconds: Long = 60, retryAfterSeconds: Long? = 20) {
        require(status in OUTAGE_STATUSES) { "outage status must be one of $OUTAGE_STATUSES" }
        require(seconds > 0) { "outage seconds must be positive" }
        outage = Outage(status, System.currentTimeMillis() + seconds * 1000, retryAfterSeconds)
    }

    fun clearOutage() {
        outage = null
    }

    /** Dev control: sets the token lifetime that later enroll and refresh responses report. */
    fun setTokenTtl(seconds: Long) {
        require(seconds > 0) { "token ttl must be positive" }
        tokenTtlSeconds = seconds
    }

    fun tokenTtl(): Long = tokenTtlSeconds

    /**
     * Revokes a device, as an admin console would. Idempotent. Its bearer
     * tokens stop authenticating and its refresh attempts get `device_revoked`.
     */
    fun revokeDevice(approverDeviceId: String) {
        revokedDevices.add(approverDeviceId)
    }

    // Review mode

    /**
     * One maintenance pass for review mode: drops pending rows that are
     * expired or decided, sweeps unclaimed challenges, and reseeds so that one
     * hold and one ticket are always pending. Main runs it once a minute.
     */
    fun reviewTick() {
        val now = Instant.now()
        pending.entries.removeIf { entry ->
            resolved.containsKey(entry.key) || (expiresAtOf(entry.value)?.isBefore(now) ?: false)
        }
        sweepChallenges(challenges)
        sweepChallenges(refreshChallenges)

        val liveTickets = pending.values.count { it["class"]?.jsonPrimitive?.content == "ticket" }
        val liveHolds = pending.size - liveTickets
        if (liveHolds == 0) {
            val pick = REVIEW_HOLDS[reviewSeedCounter.getAndIncrement().mod(REVIEW_HOLDS.size)]
            // 30 minutes, so a reviewer who is still setting up does not see
            // the row expire.
            seedRequest(tool = pick.tool, requester = pick.requester, ttlSeconds = 1800, argsPreview = pick.argsPreview)
        }
        if (liveTickets == 0) {
            val pick = REVIEW_TICKETS[reviewSeedCounter.getAndIncrement().mod(REVIEW_TICKETS.size)]
            seedRequest(
                tool = pick.tool, requester = pick.requester, ttlSeconds = 82_800, isTicket = true,
                argsPreview = pick.argsPreview,
            )
        }
    }

    private fun expiresAtOf(row: JsonObject): Instant? =
        row["expires_at"]?.jsonPrimitive?.content?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                null
            }
        }

    private fun mintChallenge(map: ConcurrentHashMap<String, Minted>, target: String): String {
        // Most nonces are never claimed, so a long-lived instance sweeps them
        // once the map is large.
        if (map.size > CHALLENGE_SWEEP_AT) sweepChallenges(map)
        val nonce = randomToken(16)
        map[nonce] = Minted(target, System.currentTimeMillis())
        return nonce
    }

    private fun sweepChallenges(map: ConcurrentHashMap<String, Minted>) {
        val cutoff = System.currentTimeMillis() - CHALLENGE_TTL_MILLIS
        map.entries.removeIf { it.value.atMillis < cutoff }
    }

    /**
     * Appends to the resolved feed. Beyond [HISTORY_CAP] the oldest rows are
     * dropped, and their 409 markers with them.
     */
    private fun recordHistory(row: JsonObject) {
        history.add(row)
        while (history.size > HISTORY_CAP) {
            val dropped = history.removeAt(0)
            dropped["id"]?.jsonPrimitive?.content?.let {
                resolved.remove(it)
                hiddenFromHistory.remove(it)
            }
        }
    }

    /**
     * The unauthenticated review page: enrollment instructions, the QR and the
     * payload for manual entry. GET only. Nothing on it is sensitive: the
     * review-mode enrollment code is public and the data behind it is fictional.
     */
    private fun handleReview(ex: HttpExchange) {
        if (ex.requestMethod != "GET") {
            ex.respond(405, "application/json", error("method not allowed").toString().encodeToByteArray())
            return
        }
        when (ex.requestURI.path) {
            "/review", "/review/" ->
                ex.respond(200, "text/html; charset=utf-8", reviewHtml().encodeToByteArray())
            "/review/qr.png" ->
                ex.respond(200, "image/png", QrPng.render(qrPayload()))
            else ->
                ex.respond(404, "application/json", error("not found").toString().encodeToByteArray())
        }
    }

    private fun reviewHtml(): String {
        val payload = htmlEscape(qrPayload())
        val url = htmlEscape(baseUrl())
        val token = htmlEscape(enrollToken)
        return """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Straza demo enrollment</title>
<style>
body{font-family:system-ui,sans-serif;max-width:36rem;margin:2rem auto;padding:0 1rem;color:#1a1a1a;background:#fff}
img{width:16rem;height:16rem;image-rendering:pixelated}
code{background:#f0f0f0;padding:.1rem .3rem;border-radius:4px;word-break:break-all}
textarea{width:100%;min-height:7rem;font-family:monospace;font-size:.8rem}
li{margin-bottom:.6rem}
</style>
</head><body>
<h1>Straza demo enrollment</h1>
<p>This is a demo deployment of the Straza approval server with fictional
data, provided so the Straza app can be reviewed without access to a
production server. The enrollment code below is reusable and does not
expire.</p>
<p><img src="/review/qr.png" alt="Enrollment QR code"></p>
<ol>
<li>Install the Straza app and open it.</li>
<li>Tap <strong>Scan enrollment code</strong> and point the camera at the QR
above. Or tap <strong>Enter the code manually</strong> and paste the
enrollment text below into the field.</li>
<li>Demo approval requests appear on the Decide tab within a minute. Open
one and tap Approve or Deny. The confirmation prompt accepts the device PIN
if no fingerprint is enrolled.</li>
</ol>
<p>Enrollment text for manual entry (server <code>$url</code>, code
<code>$token</code>):</p>
<textarea readonly>$payload</textarea>
<p>All requests shown are generated demo data. Decisions here affect
nothing.</p>
</body></html>
"""
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    // Plumbing

    private fun bearerToken(ex: HttpExchange): String? {
        val header = ex.requestHeaders.getFirst("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ")
    }

    private fun bearerKey(ex: HttpExchange): java.security.PublicKey? =
        bearerToken(ex)?.let { devices[it] }

    /**
     * True when the presented bearer is older than [tokenTtlSeconds] or was
     * expired by [expireTokens]. Callers check this before revocation, as the
     * real server does. An unknown token is not expired and gets a plain 401.
     */
    private fun bearerExpired(ex: HttpExchange): Boolean {
        val token = bearerToken(ex) ?: return false
        val issued = tokenIssuedAtMillis[token] ?: return false
        return (System.currentTimeMillis() - issued) / 1000 >= tokenTtlSeconds
    }

    /** The declared outage if it has not lapsed; a lapsed one is forgotten. */
    private fun activeOutage(): Outage? {
        val o = outage ?: return null
        if (System.currentTimeMillis() < o.untilMillis) return o
        outage = null
        return null
    }

    private fun outageBody(status: Int) = when (status) {
        503 -> errorWithCode("mock strazad is pretending to be down", "service_unavailable")
        429 -> error("mock strazad asks this phone to slow down")
        else -> error("mock strazad is pretending to fail")
    }

    /**
     * `POST /mock/outage?status=503&seconds=60&retry_after=20` declares an
     * outage. `seconds=0` or DELETE clears it; `retry_after=0` omits the header.
     */
    private fun handleOutageControl(ex: HttpExchange) = ex.handle { _ ->
        when (ex.requestMethod) {
            "DELETE" -> {
                clearOutage()
                200 to outageStatus()
            }
            "POST" -> {
                val seconds = queryParam(ex, "seconds")?.toLongOrNull() ?: 60L
                val status = queryParam(ex, "status")?.toIntOrNull() ?: 503
                val retryAfter = (queryParam(ex, "retry_after")?.toLongOrNull() ?: 20L).takeIf { it > 0 }
                if (status !in OUTAGE_STATUSES) return@handle 400 to error("status must be one of $OUTAGE_STATUSES")
                if (seconds <= 0) clearOutage() else setOutage(status, seconds, retryAfter)
                200 to outageStatus()
            }
            else -> 405 to error("POST to declare, DELETE to clear")
        }
    }

    private fun outageStatus() = buildJsonObject {
        val o = activeOutage()
        put("active", o != null)
        if (o != null) {
            put("status", o.status)
            put("seconds_left", (o.untilMillis - System.currentTimeMillis() + 999) / 1000)
            o.retryAfterSeconds?.let { put("retry_after", it) }
        }
    }

    /** `POST /mock/token-ttl?seconds=N` calls [setTokenTtl]; GET reads the value. */
    private fun handleTokenTtlControl(ex: HttpExchange) = ex.handle { _ ->
        when (ex.requestMethod) {
            "GET" -> 200 to buildJsonObject { put("token_ttl_seconds", tokenTtlSeconds) }
            "POST" -> {
                val seconds = queryParam(ex, "seconds")?.toLongOrNull()
                if (seconds == null || seconds <= 0) return@handle 400 to error("seconds must be a positive number")
                setTokenTtl(seconds)
                200 to buildJsonObject { put("token_ttl_seconds", tokenTtlSeconds) }
            }
            else -> 405 to error("POST to set, GET to read")
        }
    }

    /** `POST /mock/expire-tokens` calls [expireTokens]. */
    private fun handleExpireTokensControl(ex: HttpExchange) = ex.handle { _ ->
        if (ex.requestMethod != "POST") return@handle 405 to error("POST")
        200 to buildJsonObject { put("expired", expireTokens()) }
    }

    /** True when the presented bearer belongs to a revoked device. Callers answer `device_revoked`. */
    private fun bearerRevoked(ex: HttpExchange): Boolean {
        val token = bearerToken(ex) ?: return false
        val deviceId = tokenDeviceIds[token] ?: return false
        return deviceId in revokedDevices
    }

    private fun error(reason: String) = buildJsonObject { put("error", reason) }

    /**
     * The error envelope with a machine-readable `code`. [error] stays
     * separate because a server older than openapi 0.23.0 sends its 401s
     * without a code, and the app has to handle those too.
     */
    private fun errorWithCode(reason: String, code: String) =
        buildJsonObject { put("error", reason); put("code", code) }

    private fun queryParam(ex: HttpExchange, key: String): String? =
        ex.requestURI.query
            ?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it.firstOrNull() == key }
            ?.getOrNull(1)

    private fun decidedAtEpoch(row: JsonObject): Long =
        row["decided_at"]?.jsonPrimitive?.content?.let {
            try {
                Instant.parse(it).epochSecond
            } catch (_: Exception) {
                0L
            }
        } ?: 0L

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private inline fun HttpExchange.handle(block: (String) -> Pair<Int, Any>) {
        val down = activeOutage()
        if (down != null && requestURI.path.startsWith("/v1/approver/")) {
            // A declared outage answers before the handler runs, on every
            // route including enroll.
            val bytes = outageBody(down.status).toString().toByteArray(Charsets.UTF_8)
            down.retryAfterSeconds?.let { responseHeaders.add("Retry-After", it.toString()) }
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(down.status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
            trace(down.status)
            return
        }
        val (status, payload) = try {
            val raw = requestBody.readNBytes(MAX_BODY_BYTES + 1)
            if (raw.size > MAX_BODY_BYTES) {
                // The enroll route is public in review mode, so bodies are bounded.
                413 to error("request body too large")
            } else {
                block(raw.decodeToString())
            }
        } catch (e: Exception) {
            500 to buildJsonObject { put("error", e.message ?: "mock failure") }
        }
        trace(status)
        if (status == 204) {
            // HttpServer needs -1 for a response without a body, not 0.
            sendResponseHeaders(204, -1L)
            responseBody.close()
            return
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Dev trace: one line per request, with no headers, bodies or tokens. */
    private fun HttpExchange.trace(status: Int) {
        if (devControls) println("${requestMethod} ${requestURI.path} -> $status")
    }

    override fun close() {
        if (::server.isInitialized) server.stop(0)
    }

    private class ReviewSeed(val tool: String, val requester: String, val argsPreview: String?)

    private companion object {
        const val TS_WINDOW_SECONDS = 300L

        /** The real server's default token lifetime, 30 days. */
        const val DEFAULT_TOKEN_TTL_SECONDS = 30L * 24 * 3600

        /** The statuses [setOutage] accepts. The app backs off on 429 and 503. */
        val OUTAGE_STATUSES = setOf(429, 500, 502, 503, 504)

        const val MAX_BODY_BYTES = 64 * 1024

        const val MAX_DEVICES = 512

        const val HISTORY_CAP = 300

        /**
         * A sweep removes unclaimed nonces older than this. Outside review
         * mode a sweep runs only once a map holds more than
         * [CHALLENGE_SWEEP_AT] entries.
         */
        const val CHALLENGE_TTL_MILLIS = 15 * 60 * 1000L
        const val CHALLENGE_SWEEP_AT = 5_000

        // Fictional requests shown in review mode, rotated by reviewTick.
        val REVIEW_HOLDS = listOf(
            ReviewSeed(
                "midpoint:disable_user", "nova",
                """{
  "user_id": "alice@corp.example",
  "reason": "offboarding",
  "cascade": true,
  "session_token": "[REDACTED]"
}""",
            ),
            ReviewSeed(
                "aws:delete_bucket", "atlas",
                """{
  "bucket": "staging-artifacts-old",
  "force": true
}""",
            ),
            ReviewSeed("okta:suspend_user", "scout", null),
        )
        val REVIEW_TICKETS = listOf(
            ReviewSeed(
                "gh:add_org_member", "nova",
                """{
  "org": "nightjar",
  "username": "contractor-jo",
  "role": "member"
}""",
            ),
            ReviewSeed(
                "aws:rotate_key", "atlas",
                """{
  "key_id": "AKIA...7Q",
  "force": true
}""",
            ),
        )
    }
}
