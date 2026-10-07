package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.DecisionSigning
import dev.straza.approver.shared.protocol.ProjectRef
import dev.straza.approver.shared.protocol.RefreshSigning
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DevicePublicKey
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The approver protocol: request shapes, response parsing, status mapping and
 * multi-server failover, over a [PinnedTransport] that performs one pinned
 * HTTPS exchange. The Android and iOS transports share this class. [platform]
 * is the `device.platform` label sent at enrollment ("android", "ios").
 */
class StrazaApi(
    private val transport: PinnedTransport,
    private val servers: List<String>,
    private val platform: String,
) : ApprovalApi {

    private val json = Json { ignoreUnknownKeys = true }

    /** The server that last answered, tried first next time. Phones move on and off the VPN. */
    @Volatile
    private var preferred: String? = null

    override suspend fun enroll(
        enrollToken: String,
        deviceName: String,
        publicKey: DevicePublicKey,
    ): ApiResult<EnrollResponse> {
        val body = buildJsonObject {
            put("enroll_token", enrollToken)
            put(
                "device",
                buildJsonObject {
                    put("name", deviceName)
                    put("platform", platform)
                    put("key_alg", "ecdsa-p256")
                    put("public_key", Base64.Default.encode(publicKey.spkiDer))
                    put("key_security_level", publicKey.securityLevel.wire)
                    // Reported as `none` until Play Integrity and App Attest
                    // are implemented.
                    put("attestation", buildJsonObject { put("kind", "none") })
                },
            )
        }

        return request("/v1/approver/enroll", "POST", body.toString(), bearer = null).map {
            val obj = json.parseToJsonElement(it).jsonObject
            EnrollResponse(
                approverDeviceId = obj["approver_device_id"]!!.jsonPrimitive.content,
                deviceToken = obj["device_token"]!!.jsonPrimitive.content,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                project = obj["project"]?.jsonObject?.let { p ->
                    val id = p["id"]?.jsonPrimitive?.content
                    if (id.isNullOrBlank()) null else ProjectRef(id, p["name"]?.jsonPrimitive?.content ?: "")
                },
                // Blank or absent means the deployment has no Web Push key.
                webpushVapidPublicKey = obj["webpush"]?.jsonObject
                    ?.get("vapid_public_key")?.jsonPrimitive?.content?.takeIf { k -> k.isNotBlank() },
                fcm = parseFcmAppConfig(obj["fcm"]),
            )
        }
    }

    /**
     * Parses the Firebase config block. A block missing any of its four fields
     * is treated as absent: a FirebaseApp built from half a config mints tokens
     * against the wrong project and fails only at delivery.
     */
    private fun parseFcmAppConfig(element: kotlinx.serialization.json.JsonElement?): FcmAppConfig? {
        val f = (element as? JsonObject) ?: return null
        val projectId = f["project_id"]?.jsonPrimitive?.content
        val appId = f["app_id"]?.jsonPrimitive?.content
        val apiKey = f["api_key"]?.jsonPrimitive?.content
        val senderId = f["sender_id"]?.jsonPrimitive?.content
        if (projectId.isNullOrBlank() || appId.isNullOrBlank() ||
            apiKey.isNullOrBlank() || senderId.isNullOrBlank()
        ) {
            return null
        }
        return FcmAppConfig(projectId = projectId, appId = appId, apiKey = apiKey, senderId = senderId)
    }

    override suspend fun pending(deviceToken: String): ApiResult<List<PendingRequest>> =
        request("/v1/approver/pending", "GET", body = null, bearer = deviceToken).map { raw ->
            // `challenge` is optional on the wire, present on decidable rows
            // only. A row without one is skipped, so it cannot fail the whole
            // page as malformed and hide the other pending approvals.
            (json.parseToJsonElement(raw) as JsonArray).mapNotNull { row ->
                val obj = row.jsonObject
                if (obj["challenge"] == null) null else parsePendingRequest(obj)
            }
        }

    override suspend fun history(deviceToken: String, limit: Int, cursor: String?): ApiResult<ResolvedPage> {
        val path = buildString {
            append("/v1/approver/history?limit=").append(limit)
            // Percent-encoded so base64url padding ('=') in the opaque
            // cursor survives the query string.
            if (!cursor.isNullOrEmpty()) append("&cursor=").append(percentEncode(cursor))
        }
        return request(path, "GET", body = null, bearer = deviceToken).map { raw ->
            val obj = json.parseToJsonElement(raw).jsonObject
            ResolvedPage(
                items = (obj["items"]?.jsonArray ?: JsonArray(emptyList())).map { parseResolvedRequest(it.jsonObject) },
                // Absent reads as empty, which ends the walk.
                nextCursor = obj["next_cursor"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    override suspend fun registerPush(
        deviceToken: String,
        kind: PushKind,
        tokenOrEndpoint: String,
        p256dh: String?,
        auth: String?,
    ): ApiResult<Unit> = pushRequest("PUT", deviceToken, kind, tokenOrEndpoint, p256dh, auth)

    override suspend fun unregisterPush(
        deviceToken: String,
        kind: PushKind,
        tokenOrEndpoint: String,
        p256dh: String?,
        auth: String?,
    ): ApiResult<Unit> = pushRequest("DELETE", deviceToken, kind, tokenOrEndpoint, p256dh, auth)

    override suspend fun unenroll(deviceToken: String): ApiResult<Unit> =
        request("/v1/approver/enrollment", "DELETE", body = null, bearer = deviceToken).map { }

    private suspend fun pushRequest(
        method: String,
        deviceToken: String,
        kind: PushKind,
        tokenOrEndpoint: String,
        p256dh: String?,
        auth: String?,
    ): ApiResult<Unit> {
        val body = buildJsonObject {
            put("kind", kind.wire)
            put("token_or_endpoint", tokenOrEndpoint)
            // Sent as given. Dropping a lone key here would hide the caller
            // bug that the server's both-or-neither 400 exposes.
            p256dh?.let { put("p256dh", it) }
            auth?.let { put("auth", it) }
        }
        // The 200 body is a status object the app does not need.
        return request("/v1/approver/push", method, body.toString(), deviceToken).map { }
    }

    /**
     * Signs and submits a decision. [sign] is awaited on the caller's context
     * before anything reaches the transport, because it shows an
     * authentication prompt, which is main-thread work.
     */
    override suspend fun decide(
        deviceToken: String,
        requestId: String,
        verdict: Verdict,
        challenge: String,
        unixTs: Long,
        reason: String?,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<String> {
        // The message and the body both derive from the one nullable reason:
        // null gives the 4-line message and no `reason` field, non-null gives
        // the 5-line message and the field.
        val message = DecisionSigning.canonicalMessage(requestId, verdict, challenge, unixTs, reason)
        val signature = Base64.Default.encode(sign(message))

        val body = buildJsonObject {
            put("request_id", requestId)
            put("verdict", verdict.wire)
            put("challenge", challenge)
            put("signature", signature)
            put("ts", unixTs)
            reason?.let { put("reason", it) }
        }

        return request("/v1/approver/decide", "POST", body.toString(), deviceToken).map {
            json.parseToJsonElement(it).jsonObject["state"]!!.jsonPrimitive.content
        }
    }

    override suspend fun renewToken(
        approverDeviceId: String,
        sign: suspend (ByteArray) -> ByteArray,
    ): ApiResult<RenewedToken> {
        // Step 1: a single-use challenge, valid for at most 5 minutes. Neither
        // step carries a bearer; the enrolled key is the credential. A 404 here
        // is not a revocation signal, because a server before openapi 0.23.0
        // answers 404 for the unknown path. Only the refresh POST's own 401
        // `device_revoked` destroys the key.
        val challengeBody = buildJsonObject { put("approver_device_id", approverDeviceId) }
        val challengeResult =
            request("/v1/approver/refresh/challenge", "POST", challengeBody.toString(), bearer = null)
        if (challengeResult !is ApiResult.Ok) return failureOf(challengeResult)
        val challenge = try {
            json.parseToJsonElement(challengeResult.value).jsonObject["challenge"]!!.jsonPrimitive.content
        } catch (_: Exception) {
            // An unparseable response cannot be acted on. Nothing has been
            // signed yet.
            return ApiResult.Unreachable("malformed server response")
        }

        // If sign throws (a cancelled prompt), nothing further is sent.
        val message = RefreshSigning.canonicalMessage(approverDeviceId, challenge)
        val signature = Base64.Default.encode(sign(message))

        val body = buildJsonObject {
            put("approver_device_id", approverDeviceId)
            put("challenge", challenge)
            put("signature", signature)
        }
        return request("/v1/approver/refresh", "POST", body.toString(), bearer = null).map {
            val obj = json.parseToJsonElement(it).jsonObject
            RenewedToken(
                deviceToken = obj["device_token"]!!.jsonPrimitive.content,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    /**
     * Re-types a non-Ok result for a different payload type. The refresh flow
     * has no 409, so a stray [ApiResult.AlreadyResolved] becomes a server error.
     */
    private fun failureOf(result: ApiResult<String>): ApiResult<Nothing> = when (result) {
        is ApiResult.Unauthorized -> result
        is ApiResult.Unreachable -> result
        is ApiResult.Untrusted -> result
        is ApiResult.ServerError -> result
        is ApiResult.AlreadyResolved -> ApiResult.ServerError(409)
        is ApiResult.Ok -> throw IllegalStateException("not a failure")
    }

    // Failover and status mapping

    private suspend fun request(
        path: String,
        method: String,
        body: String?,
        bearer: String?,
    ): ApiResult<String> {
        val ordered = buildList {
            preferred?.let(::add)
            servers.forEach { if (it != preferred) add(it) }
        }

        var lastFailure = "no servers configured"
        // Both kinds of failure are tried past, so one bad entry cannot strand
        // a phone that can still reach a good server. If no server answers, a
        // trust failure is reported in preference to a reachability failure,
        // because its exit is to re-pair and waiting will not fix it.
        var untrusted: String? = null

        for (server in ordered) {
            when (val outcome = transport.exchange(server, path, method, body, bearer)) {
                is HttpOutcome.Unreachable -> lastFailure = outcome.reason
                is HttpOutcome.Untrusted -> untrusted = outcome.reason
                is HttpOutcome.Answer -> {
                    preferred = server
                    return interpret(outcome)
                }
            }
        }
        return untrusted?.let { ApiResult.Untrusted(it) } ?: ApiResult.Unreachable(lastFailure)
    }

    /** Maps an HTTP status to an [ApiResult]. */
    private fun interpret(answer: HttpOutcome.Answer): ApiResult<String> = when {
        answer.status == 401 -> ApiResult.Unauthorized(authRejectionOf(answer.body))
        // Mapped from the status alone: older servers send no code on a 403.
        answer.status == 403 -> ApiResult.Unauthorized(AuthRejection.FORBIDDEN)
        answer.status == 409 -> ApiResult.AlreadyResolved(stateOf(answer.body))
        answer.status >= 400 -> ApiResult.ServerError(answer.status, errorTextOf(answer.body), answer.retryAfterSeconds)
        else -> ApiResult.Ok(answer.body)
    }

    private fun stateOf(payload: String): String = try {
        json.parseToJsonElement(payload).jsonObject["state"]?.jsonPrimitive?.content ?: "resolved"
    } catch (_: Exception) {
        "resolved"
    }

    /** The `error` text of an error envelope, or null if it is absent or the body does not parse. */
    private fun errorTextOf(payload: String): String? = try {
        json.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /**
     * The `code` of a 401 envelope (`{"error":…,"code":…}`). A missing code, an
     * unparseable body or an unknown code reads as [AuthRejection.UNKNOWN],
     * which keeps the key.
     */
    private fun authRejectionOf(payload: String): AuthRejection = try {
        AuthRejection.fromWire(
            json.parseToJsonElement(payload).jsonObject["code"]?.jsonPrimitive?.content,
        )
    } catch (_: Exception) {
        AuthRejection.UNKNOWN
    }

    private inline fun <T> ApiResult<String>.map(transform: (String) -> T): ApiResult<T> = when (this) {
        is ApiResult.Ok -> try {
            ApiResult.Ok(transform(value))
        } catch (_: Exception) {
            // An unparseable response cannot be acted on.
            ApiResult.Unreachable("malformed server response")
        }
        is ApiResult.AlreadyResolved -> this
        is ApiResult.Unauthorized -> this
        is ApiResult.Unreachable -> this
        is ApiResult.Untrusted -> this
        is ApiResult.ServerError -> this
    }

    // Response parsing

    /**
     * Three renderings of one `summary`: the `app:toolName` label kept for the
     * CSV export, the humanized title, and the machine identity.
     */
    private class ToolNames(val label: String, val title: String, val wire: String)

    private fun toolNamesOf(obj: JsonObject): ToolNames {
        val summary = obj["summary"]?.jsonObject
        val tool = summary?.get("tool")?.jsonPrimitive?.content.orEmpty()
        val app = summary?.get("app")?.jsonPrimitive?.content.orEmpty()
        val toolName = summary?.get("tool_name")?.jsonPrimitive?.content.orEmpty()
        return ToolNames(
            label = SummaryLabel.of(tool, app, toolName),
            title = SummaryText.title(tool, app, toolName),
            wire = SummaryText.wire(tool, app, toolName),
        )
    }

    private fun parsePendingRequest(obj: JsonObject): PendingRequest {
        val names = toolNamesOf(obj)
        return PendingRequest(
            id = obj["id"]!!.jsonPrimitive.content,
            requester = obj["requester"]?.jsonObject?.get("username")?.jsonPrimitive?.content.orEmpty(),
            requesterKind = obj["requester"]?.jsonObject?.get("kind")?.jsonPrimitive?.content.orEmpty(),
            originActor = obj["origin"]?.jsonObject?.get("actor")?.jsonPrimitive?.content.orEmpty(),
            toolLabel = names.label,
            toolTitle = names.title,
            toolWire = names.wire,
            sessionId = obj["session_id"]?.jsonPrimitive?.content.orEmpty(),
            harness = obj["harness"]?.jsonPrimitive?.content.orEmpty(),
            ruleId = obj["rule_id"]?.jsonPrimitive?.content.orEmpty(),
            justification = obj["justification"]?.jsonPrimitive?.content.orEmpty(),
            challenge = obj["challenge"]!!.jsonPrimitive.content,
            expiresAtEpochSeconds = parseRfc3339EpochSeconds(obj["expires_at"]?.jsonPrimitive?.content.orEmpty()),
            isTicket = obj["class"]?.jsonPrimitive?.content == "ticket",
            argsPreview = parseArgsPreview(obj),
        )
    }

    /** The args preview, or null when the row has none (older server, preview off, or an older row). */
    private fun parseArgsPreview(obj: JsonObject): ArgsPreview? {
        val preview = obj["args_preview"]?.jsonPrimitive?.content ?: return null
        return ArgsPreview(
            preview = preview,
            truncated = obj["args_truncated"]?.jsonPrimitive?.content == "true",
            bytes = obj["args_bytes"]?.jsonPrimitive?.content?.toLongOrNull(),
            hashPrefix = obj["argv_hash_prefix"]?.jsonPrimitive?.content.orEmpty(),
            bindingScope = obj["binding_scope"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private fun parseResolvedRequest(obj: JsonObject): ResolvedRequest {
        val names = toolNamesOf(obj)
        return ResolvedRequest(
            id = obj["id"]!!.jsonPrimitive.content,
            requester = obj["requester"]?.jsonObject?.get("username")?.jsonPrimitive?.content.orEmpty(),
            requesterKind = obj["requester"]?.jsonObject?.get("kind")?.jsonPrimitive?.content.orEmpty(),
            originActor = obj["origin"]?.jsonObject?.get("actor")?.jsonPrimitive?.content.orEmpty(),
            toolLabel = names.label,
            toolTitle = names.title,
            toolWire = names.wire,
            sessionId = obj["session_id"]?.jsonPrimitive?.content.orEmpty(),
            harness = obj["harness"]?.jsonPrimitive?.content.orEmpty(),
            ruleId = obj["rule_id"]?.jsonPrimitive?.content.orEmpty(),
            state = ResolvedState.fromWire(obj["state"]?.jsonPrimitive?.content),
            decidedBy = obj["decided_by"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            decidedAtEpochSeconds = parseRfc3339EpochSeconds(obj["decided_at"]?.jsonPrimitive?.content.orEmpty()),
            justification = obj["justification"]?.jsonPrimitive?.content.orEmpty(),
            isTicket = obj["class"]?.jsonPrimitive?.content == "ticket",
            grantExpiresAtEpochSeconds =
                parseRfc3339EpochSeconds(obj["grant_expires_at"]?.jsonPrimitive?.content.orEmpty()),
            consumedAtEpochSeconds = parseRfc3339EpochSeconds(obj["consumed_at"]?.jsonPrimitive?.content.orEmpty()),
            consumedBy = obj["consumed_by"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            argsPreview = parseArgsPreview(obj),
            decidedViaSurface = obj["decided_via"]?.jsonObject?.get("surface")
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            decidedViaDeviceId = obj["decided_via"]?.jsonObject?.get("device_id")
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            decidedReason = obj["decided_reason"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private companion object {

        /** RFC 3986 unreserved characters. Everything else is %XX-escaped. Used only for the history cursor. */
        private val UNRESERVED =
            ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~").toSet()

        fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt().toChar()
                if (c in UNRESERVED) {
                    append(c)
                } else {
                    append('%')
                    val hex = (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    append(hex)
                }
            }
        }
    }
}
