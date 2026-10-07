package dev.straza.approver.mock

import java.io.File

/**
 * Runs the mock strazad for hands-on development:
 *
 *     ./gradlew :mock-strazad:run --args="8443"
 *
 * It prints the enrollment QR payload and writes it as a PNG. The certificate
 * covers only localhost and 127.0.0.1, so a phone reaches the mock through
 * `adb reverse`.
 */
/** Turns a project id into a display name: "fin-sandbox" becomes "Fin Sandbox". */
private fun prettify(id: String): String =
    id.split('-', '_', ' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

fun main(args: Array<String>) {
    if ("--review" in args) {
        runReview(args)
        return
    }
    // `--token-ttl <seconds>` sets the lifetime of the tokens minted here. The
    // default is the real server's 30 days.
    val tokenTtl = flagValue(args, "--token-ttl")?.toLongOrNull()
    if ("--token-ttl" in args && (tokenTtl == null || tokenTtl <= 0)) {
        System.err.println("--token-ttl needs a positive number of seconds, e.g. --token-ttl 120")
        kotlin.system.exitProcess(2)
    }
    val positional = args.filterIndexed { i, a ->
        !a.startsWith("--") && !(i > 0 && args[i - 1].startsWith("--"))
    }
    val port = positional.getOrNull(0)?.toIntOrNull() ?: 8443
    // The optional second and third arguments are a project id and name, so a
    // second mock on another port shows up as a separate deployment:
    //   gradlew :mock-strazad:run --args="8444 fin-sandbox"        -> "Fin Sandbox"
    //   gradlew :mock-strazad:run --args="8444 fin-sandbox 'Fin EU'"
    val projectId = positional.getOrNull(1)?.takeIf { it.isNotBlank() }
    val projectName = positional.getOrNull(2)?.takeIf { it.isNotBlank() }
        ?: projectId?.let(::prettify) ?: "Mock Straza"
    // Each project id gets its own keystore, so two instances present
    // different certificates and pins. The file is reused across restarts,
    // which keeps an existing enrollment working.
    val keystoreName = projectId?.let { "straza-mock-$it.p12" } ?: "straza-mock.p12"
    val keystore = File(System.getProperty("java.io.tmpdir"), keystoreName)

    val mock = try {
        MockStrazad(
            keystoreFile = keystore,
            projectId = projectId ?: "prj_mock",
            projectName = projectName,
            initialTokenTtlSeconds = tokenTtl ?: 30L * 24 * 3600,
            devControls = true,
        ).start(port)
    } catch (e: IllegalStateException) {
        System.err.println()
        System.err.println("mock strazad could not start: ${e.message}")
        System.err.println()
        kotlin.system.exitProcess(1)
    }
    // A long-lived request to approve, with an args preview and a session.
    val approveReq = mock.seedRequest(
        tool = "midpoint:disable_user", requester = "nova", ttlSeconds = 3600,
        sessionId = "01a00145b2c93f70", harness = "claude-code",
        argsPreview = """{
  "user_id": "alice@corp.example",
  "reason": "offboarding",
  "cascade": true,
  "session_token": "[REDACTED]"
}""",
    )
    // A short-lived request without an args preview, to watch expire.
    val expiringReq = mock.seedRequest(
        tool = "aws:delete_bucket", requester = "atlas", ttlSeconds = 120,
        ruleId = "destructive-aws-needs-human",
        justification = "agent says: cleaning up stale staging buckets",
    )
    // A bare kind (no colon), the server's shape for shell, file and net calls.
    val bareReq = mock.seedRequest(
        tool = "shell.exec", requester = "scout", ttlSeconds = 1800,
        ruleId = "shell-on-prod-needs-human",
        justification = "agent says: restart the stuck deploy worker on web-1",
    )
    // A ticket with a 23-hour window and an args preview.
    val ticketReq = mock.seedRequest(
        tool = "gh:add_org_member", requester = "nova", ttlSeconds = 82_800 /* 23h */, isTicket = true,
        ruleId = "org-membership-needs-human",
        justification = "agent says: add contractor-jo to the org for the audit sprint",
        argsPreview = """{
  "org": "nightjar",
  "username": "contractor-jo",
  "role": "member"
}""",
    )

    seedActivity(mock)

    println("mock strazad listening on ${mock.baseUrl()}")
    println("deployment: ${projectName} (${projectId ?: "prj_mock"})")
    println("SPKI pin : ${mock.spkiPin}")
    println("seeded pending: $approveReq (approve this - 1h window)")
    println("seeded pending: $expiringReq (watch this expire - 2m window)")
    println("seeded pending: $bareReq (bare kind - titles as a verb phrase)")
    println("seeded pending: $ticketReq (TICKET - 23h day-scale window)")
    println("seeded Activity: 5 resolved, 3 ticket outcomes (used / grant-active / unused), 3 hold outcomes (ran once / not run yet / not run)")
    println()
    // One QR PNG per project id, so a second instance does not overwrite the first's.
    val qrName = projectId?.let { "straza-enroll-qr-$it.png" } ?: "straza-enroll-qr.png"
    val qrFile = File(System.getProperty("java.io.tmpdir"), qrName)
    QrPng.write(mock.qrPayload(), qrFile)
    println("Scan this QR to enroll (open it on screen, point the phone at it):")
    println("  ${qrFile.absolutePath}")
    println()

    println("QR payload (or paste it manually):")
    println(mock.qrPayload())
    println()
    // Base64, because cmd.exe and PowerShell strip the double quotes from raw
    // JSON before adb sees them. The app decodes it and validates it the same
    // way. `adb shell input text` types into whichever field has focus.
    val packaged = java.util.Base64.getEncoder()
        .encodeToString(mock.qrPayload().toByteArray(Charsets.UTF_8))
    println("Or, with the app's payload field focused, push it from the PC:")
    println("  adb shell input text $packaged")
    println()
    println("Reachable from a USB-connected phone via:  adb reverse tcp:$port tcp:$port")
    println()
    println(
        "token ttl : ${tokenTtl ?: 30L * 24 * 3600} s" +
            if (tokenTtl == null) " (the real server's 30 d; --token-ttl 120 to watch a renewal, --token-ttl 600000 (~7 d) for the renews-soon banner)"
            else " (expired tokens answer 401 token_expired -> RenewNeeded; under 7 d the app shows its renews-soon banner)",
    )
    println("Dev controls (from this PC, any time; never on the review deployment):")
    println("  curl -k -X POST   \"https://127.0.0.1:$port/mock/expire-tokens\"                 -> every phone's next poll: 401 token_expired (RenewNeeded)")
    println("  curl -k -X POST   \"https://127.0.0.1:$port/mock/outage?status=503&seconds=60\"  -> 503 + Retry-After: 20 on every API call for 60 s")
    println("                    (status=429 for slow-down, retry_after=0 to omit the header, seconds=0 to clear)")
    println("  curl -k -X DELETE \"https://127.0.0.1:$port/mock/outage\"                        -> back to normal")
    println("  curl -k -X POST   \"https://127.0.0.1:$port/mock/token-ttl?seconds=2592000\"     -> tokens minted from now on live this long (pair short, switch long, Renew now -> banner must go)")
    println("Every request is traced below as  METHOD path -> status  (no tokens or bodies).")
    println()
    if (projectId == null) {
        println("To test the deployment switcher, start a SECOND deployment in another terminal:")
        println("  gradlew :mock-strazad:run --args=\"8444 fin-sandbox\"")
        println("  adb reverse tcp:8444 tcp:8444")
        println("then in the app tap  + Add  and scan its QR.")
        println()
    }
    println("Ctrl-C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread { mock.close() })
    Thread.currentThread().join()
}

/** Seeds the Activity feed with rows resolved on other channels, for dev and review mode. */
private fun seedActivity(mock: MockStrazad) {
    // One row per decision surface: Slack, browser (with a long decider
    // reason), console, and the legacy `api` value written before 0.63.0.
    mock.seedResolved("okta:suspend_user", "atlas", state = "approved", decidedBy = "kim", decidedSecondsAgo = 90, justification = "agent says: offboarding ticket INC-42, disable within 1h", decidedViaSurface = "slack", sessionId = "3f8ee2a1907cc4d2", harness = "claude-code", ruleId = "identity-changes-need-human")
    mock.seedResolved(
        "aws:delete_bucket", "scout", state = "denied", decidedBy = "dana", decidedSecondsAgo = 600,
        justification = "agent says: cleaning up stale staging buckets",
        decidedViaSurface = "browser", decidedViaDeviceId = "apd_browser_dana",
        decidedReason = "That bucket still backs the eu-west failover replica. Confirm the replication cutover finished and re-raise this with the cutover ticket linked, then I will approve it.",
        ruleId = "destructive-aws-needs-human",
    )
    mock.seedResolved("github:remove_member", "nova", state = "expired", decidedBy = null, decidedSecondsAgo = 3600, justification = "agent says: revoke access for departed contractor", ruleId = "org-membership-needs-human")
    mock.seedResolved("pagerduty:override_oncall", "atlas", state = "approved", decidedBy = "dana", decidedSecondsAgo = 5400, justification = "agent says: cover the gap left by the schedule change", decidedViaSurface = "console", decidedReason = "Approved for this rotation only.", ruleId = "oncall-overrides-need-human")
    mock.seedResolved("okta:deactivate_app", "scout", state = "denied", decidedBy = "kim", decidedSecondsAgo = 86400, justification = "agent says: app unused for 90 days", decidedViaSurface = "api", ruleId = "identity-changes-need-human")
    // The three ticket outcomes: used, grant active, unused.
    mock.seedResolved("aws:rotate_key", "nova", state = "approved", decidedBy = "you", decidedSecondsAgo = 7200, justification = "agent says: quarterly key rotation", isTicket = true, grantExpiresSecondsFromNow = -3600, consumedSecondsAgo = 6900, consumedBy = "agent-7", argsPreview = """{"key_id": "AKIA…7Q", "force": true}""", ruleId = "aws-credentials-need-human")
    mock.seedResolved("gh:add_org_member", "nova", state = "approved", decidedBy = "you", decidedSecondsAgo = 1080, justification = "agent says: onboard new contractor within the day", isTicket = true, grantExpiresSecondsFromNow = 2520 /* 42m left */, ruleId = "org-membership-needs-human")
    mock.seedResolved("okta:reset_mfa", "nova", state = "approved", decidedBy = "kim", decidedSecondsAgo = 18000, justification = "agent says: user locked out, reset when convenient", isTicket = true, grantExpiresSecondsFromNow = -14400 /* lapsed 4h ago */, ruleId = "identity-changes-need-human")
    // The three hold outcomes: ran once, not run yet (its window closes about
    // 45 s after the mock starts), and not run.
    mock.seedResolved("midpoint:disable_user", "atlas", state = "approved", decidedBy = "you", decidedSecondsAgo = 300, justification = "agent says: offboarding ticket INC-42", grantExpiresSecondsFromNow = -240, consumedSecondsAgo = 299, consumedBy = "3f8ee2a1907cc4d2", sessionId = "3f8ee2a1907cc4d2", harness = "claude-code", ruleId = "disable-user-needs-human")
    mock.seedResolved("shell.exec", "scout", state = "approved", decidedBy = "you", decidedSecondsAgo = 15, justification = "agent says: restart the stuck deploy worker on web-1", grantExpiresSecondsFromNow = 45, ruleId = "shell-on-prod-needs-human")
    mock.seedResolved("okta:suspend_user", "atlas", state = "approved", decidedBy = "kim", decidedSecondsAgo = 900, justification = "agent says: contractor offboarding", grantExpiresSecondsFromNow = -840, decidedViaSurface = "console", ruleId = "identity-changes-need-human")
}

/** Returns the value after [name], or null when the flag is absent or is the last argument. */
private fun flagValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/**
 * Review mode, the demo deployment for app-store reviewers: plain HTTP on
 * loopback behind a TLS proxy, a fixed reusable enrollment code, the /review
 * page, and a reseed every minute so there is always something to decide.
 */
private fun runReview(args: Array<String>) {
    val port = flagValue(args, "--port")?.toIntOrNull() ?: 8080
    val publicUrl = flagValue(args, "--public-url")?.trimEnd('/')
    val enrollToken = flagValue(args, "--enroll-token")
    if (publicUrl.isNullOrBlank() || enrollToken.isNullOrBlank()) {
        System.err.println("review mode needs --public-url and --enroll-token, e.g.")
        System.err.println(
            "  mock-strazad --review --port 8080 --public-url https://demo.example " +
                "--enroll-token REVIEW-CODE",
        )
        kotlin.system.exitProcess(2)
    }

    val mock = try {
        MockStrazad(
            enrollToken = enrollToken,
            selfUsername = "reviewer",
            projectId = "prj_straza_demo",
            projectName = "Straza Demo",
            publicUrl = publicUrl,
            plainHttp = true,
            reviewPage = true,
        ).start(port)
    } catch (e: IllegalStateException) {
        System.err.println()
        System.err.println("mock strazad could not start: ${e.message}")
        System.err.println()
        kotlin.system.exitProcess(1)
    }

    seedActivity(mock)
    mock.reviewTick()
    Thread {
        while (true) {
            Thread.sleep(60_000)
            try {
                mock.reviewTick()
            } catch (t: Throwable) {
                // Keep the seeder running after a failed tick.
                System.err.println("review reseed failed: ${t.message}")
            }
        }
    }.apply { isDaemon = true; name = "review-seeder"; start() }

    println("mock strazad REVIEW mode")
    println("listening   : http://127.0.0.1:$port (loopback only - front it with a TLS proxy)")
    println("public URL  : $publicUrl")
    println("review page : $publicUrl/review")
    println("enroll code : $enrollToken (reusable, never expires)")
    println("seeding     : one blocking hold + one ticket kept live, reseeded every minute")
    println("Ctrl-C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread { mock.close() })
    Thread.currentThread().join()
}
