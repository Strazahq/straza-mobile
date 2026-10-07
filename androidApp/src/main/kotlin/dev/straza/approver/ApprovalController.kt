package dev.straza.approver

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.straza.approver.push.AppPush
import dev.straza.approver.push.Distributors
import dev.straza.approver.push.PushRouteStore
import dev.straza.approver.shared.push.PushConsent
import dev.straza.approver.shared.push.PushPrefs
import dev.straza.approver.shared.push.PushRegistrar
import dev.straza.approver.shared.push.PushRoute
import dev.straza.approver.shared.push.PushRoutePlanner
import dev.straza.approver.shared.push.PushRouteStatus
import dev.straza.approver.shared.push.PushSettingsUi
import dev.straza.approver.shared.push.PushTransport
import dev.straza.approver.shared.push.PushTransportPref
import dev.straza.approver.shared.flow.ActivityCsv
import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalFlow
import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.flow.Convergence
import dev.straza.approver.shared.flow.RenewalWriteBack
import dev.straza.approver.shared.flow.RenewalWriteBacks
import dev.straza.approver.shared.flow.StartupRead
import dev.straza.approver.shared.flow.StartupReads
import dev.straza.approver.shared.flow.StartupDecision
import dev.straza.approver.shared.flow.DecisionOutcome
import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.AuthRejection
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.PushKind
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import dev.straza.approver.shared.net.StrazaClient
import dev.straza.approver.shared.push.PushEnvelope
import dev.straza.approver.shared.protocol.EnrollmentPayload
import dev.straza.approver.shared.protocol.EnrollmentQr
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.security.DeviceKeyStore
import dev.straza.approver.shared.security.deleteOrphanedDeviceKeys
import dev.straza.approver.shared.security.displayName
import dev.straza.approver.shared.security.projectKey
import dev.straza.approver.shared.security.projectKeyFor
import dev.straza.approver.shared.ui.DeploymentSummary
import dev.straza.approver.shared.ui.ReplacePrompt
import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeyState
import dev.straza.approver.shared.security.PlatformContext
import dev.straza.approver.shared.security.SecureStore
import dev.straza.approver.shared.security.UserAuthPolicy
import dev.straza.approver.shared.ui.AppActions
import dev.straza.approver.shared.ui.ApprovalTab
import dev.straza.approver.shared.ui.UiState
import dev.straza.approver.shared.ui.enrollmentInFlight
import dev.straza.approver.shared.ui.ticksEverySecond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the device key store, the biometric gate and the server client to the
 * shared UI, which sees only [UiState] and [AppActions].
 *
 * [onEnrolledForeground] fires on main when a foreground [start], which is
 * asynchronous, resolves onto an enrolled session.
 * [requestNotificationPermission] is called only after the in-app opt-in.
 */
class ApprovalController(
    context: Context,
    private val gate: BiometricGate,
    private val scope: CoroutineScope,
    private val requestCameraPermission: suspend () -> Boolean = { false },
    private val onEnrolledForeground: () -> Unit = {},
    private val requestNotificationPermission: () -> Unit = {},
) : AppActions {

    // Each deployment's signing key has its own alias (keyRef), so a
    // DeviceKeyStore is built per deployment at the point of use. A blank
    // keyRef resolves to the legacy alias.

    private fun deploymentSummaries(records: List<Enrollment> = store.loadAll()): List<DeploymentSummary> =
        records.map { DeploymentSummary(projectKey(it), it.displayName()) }

    /**
     * The Loading state on entering Approvals. Callers that hold a vault read
     * pass its summaries in to avoid another decrypt on the main thread.
     */
    private fun approvalsLoading(deployments: List<DeploymentSummary> = deploymentSummaries()): UiState.Approvals =
        UiState.Approvals(
            screen = ApprovalScreen.Loading,
            deploymentName = enrollment?.displayName(),
            deployments = deployments,
            activeProjectId = enrollment?.let(::projectKey),
            thisDeviceId = enrollment?.approverDeviceId,
            tokenExpiresAtEpochSeconds = enrollment?.tokenExpiresAtEpochSeconds,
            nowEpochSeconds = nowEpochSeconds(),
            // Asked on every entry into the enrolled state until answered.
            askPushOptIn = if (PushPrefs.shouldAsk(pushStore.read().consent, enrolled = true)) {
                buildPushSettings()
            } else {
                null
            },
        )
    private val appContext = context.applicationContext
    private val store = SecureStore(PlatformContext(appContext))
    private val pushStore = PushRouteStore(appContext)

    private var enrollment: Enrollment? = null
    private var flow: ApprovalFlow? = null
    /** The client the flow wraps, also used for push registration. */
    private var api: ApprovalApi? = null
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    /** The in-flight foreground [start]. A newer start or [stop] cancels it. */
    private var startJob: Job? = null

    /**
     * Parsed enrollment payload awaiting the replace-pairing decision. Held
     * here, not in [UiState], so its enroll token stays in the controller.
     */
    private var pendingReplace: EnrollmentPayload? = null

    /**
     * The keyRef of an enrollment in flight. The startup orphan-key cleanup
     * spares it: a foreground can happen before its vault row lands.
     */
    private var enrollingKeyRef: String? = null

    /**
     * Bumped on every change of the active deployment. Each async operation
     * captures the epoch it started under and drops its result if the epoch
     * has moved, so a fetch or a signature in flight across a switch or a
     * revocation is not applied to another deployment. Main dispatcher only.
     */
    private var epoch = 0

    var state by mutableStateOf<UiState>(UiState.NeedsEnrollment())
        private set

    /**
     * Restores a previous enrollment, or converges out of a state that would
     * deny everything forever (see [Convergence]). Runs on every foreground:
     * the key can be destroyed while the app is in the background.
     */
    fun start() {
        // A replace decision does not survive backgrounding: the vault may have
        // changed under the stale payload.
        pendingReplace = null
        // The Keystore and vault reads run off the main thread. A stale result
        // is dropped: a newer start() or stop() cancels startJob, and the epoch
        // check catches a session that changed meanwhile. A concurrent
        // main-thread vault write is safe, because the blob store writes a temp
        // file and renames it.
        startJob?.cancel()
        val startedEpoch = epoch
        val enrolling = enrollingKeyRef
        val held = enrollment
        startJob = scope.launch {
            val read = withContext(Dispatchers.IO) {
                StartupReads.resolve(
                    snapshot = store.snapshot(),
                    held = held,
                    // Deletes device keys no enrollment references. Orphans
                    // occupy scarce StrongBox slots. Runs only on a readable
                    // vault: an empty "kept" set would delete every signing key.
                    onReadable = { all -> deleteOrphanedDeviceKeys(all.map { it.keyRef } + listOfNotNull(enrolling)) },
                    keyStateOf = { keyRef -> DeviceKeyStore(keyRef).keyState() },
                )
            }
            if (epoch != startedEpoch) return@launch
            val snapshot = when (read) {
                is StartupRead.Unreadable -> {
                    // Cold start over a store that cannot be read, which is
                    // no information. Show the pairing screen and leave every
                    // key and the record intact.
                    state = UiState.NeedsEnrollment(error = StartupReads.unreadableMessage(read.reason))
                    return@launch
                }
                is StartupRead.Ready -> read
            }
            val decision = Convergence.onStartup(snapshot.active, snapshot.keyState)
            // A pairing in progress survives the foreground re-read, so a system
            // sheet mid-scan does not tear the viewfinder down. Converging still
            // wins: a dead key is retired whatever is on screen.
            if (enrollmentInFlight(state) && decision !is StartupDecision.Converge) return@launch
            when (decision) {
                is StartupDecision.Enroll -> state = UiState.NeedsEnrollment()

                is StartupDecision.Converge -> {
                    // This deployment's key is gone. Other deployments are
                    // untouched, so retire only this one.
                    val active = snapshot.active
                    if (active != null) {
                        DeviceKeyStore(active.keyRef).deleteKey()
                        store.remove(projectKey(active))
                        convergeAfterActiveRemoved(reason = decision.reason, notice = decision.reason)
                    } else {
                        // Leftover key, empty vault: clean the orphan and enroll.
                        wipe()
                        state = UiState.NeedsEnrollment(error = decision.reason)
                    }
                }

                is StartupDecision.Proceed -> {
                    adopt(decision.enrollment)
                    // Reuses the snapshot's vault read: no main-thread decrypt.
                    state = approvalsLoading(deploymentSummaries(snapshot.all))
                    startPolling()
                    startTicking()
                    onEnrolledForeground()
                }
            }
        }
    }

    fun stop() {
        // Cancel the startup read too, so it cannot repaint state or restart
        // the poll after the app has backgrounded.
        startJob?.cancel()
        startJob = null
        pollJob?.cancel()
        pollJob = null
        tickJob?.cancel()
        tickJob = null
    }

    private fun adopt(record: Enrollment) {
        epoch++
        enrollment = record
        val client = StrazaClient(record.servers, record.pin)
        api = client
        flow = ApprovalFlow(
            api = client,
            // A 401 revokes the deployment this flow belongs to. It is captured
            // here because a poll in flight across a switch can 401 after the
            // active deployment has moved on (see retire()).
            onRevoked = {
                retire(
                    record,
                    whenGone = "This deployment revoked the device. Enroll it again.",
                    whenSwitched = "A deployment revoked this device and was removed.",
                )
            },
            now = ::nowEpochSeconds,
        )
    }

    /**
     * Removes one deployment whose key was revoked or has died, then switches
     * to another enrolled deployment or falls back to the enrollment screen.
     *
     * Acts on the given [record], not `store.active()`: a 401 can arrive on a
     * flow whose deployment is no longer active, and deleting the active
     * deployment's key there would be irreversible.
     */
    private fun retire(record: Enrollment, whenGone: String, whenSwitched: String) {
        val key = projectKey(record)
        val wasActive = enrollment?.let(::projectKey) == key
        // Push teardown for the dying pairing, best-effort. After a revocation
        // the token is dead and this 401s harmlessly. The stored route is
        // cleared either way.
        if (wasActive) {
            val dyingApi = api
            val stored = pushStore.read()
            val dyingRoutes = listOfNotNull(stored.route, stored.temporaryRoute)
            if (dyingApi != null && dyingRoutes.isNotEmpty()) {
                scope.launch {
                    dyingRoutes.forEach {
                        dyingApi.unregisterPush(record.deviceToken, it.kind, it.tokenOrEndpoint, it.p256dh, it.auth)
                    }
                }
            }
            pushStore.clearRoute()
        }
        // Server-side teardown (DELETE /v1/approver/enrollment), best-effort
        // with the record's own client and bearer. After a revocation it 401s,
        // an older server 404s, and the local teardown does not wait for it.
        scope.launch { StrazaClient(record.servers, record.pin).unenroll(record.deviceToken) }
        DeviceKeyStore(record.keyRef).deleteKey()
        store.remove(key)
        // A stale flow retired a deployment that is not on screen: the active
        // session stays as it is.
        if (!wasActive) return
        convergeAfterActiveRemoved(reason = whenGone, notice = whenSwitched)
    }

    /**
     * Runs once the active deployment's row is gone from storage: adopts the
     * next enrolled deployment (showing [notice]) or drops to the enrollment
     * screen with [reason]. Either way the epoch moves.
     */
    private fun convergeAfterActiveRemoved(reason: String, notice: String) {
        val next = store.active()
        if (next != null) {
            adopt(next)
            state = approvalsLoading().copy(notice = notice)
            startPolling()
            startTicking()
            syncPushTransport()
        } else {
            clearSession()
            state = UiState.NeedsEnrollment(error = reason)
        }
    }

    /** Drops the active session and bumps the epoch, discarding in-flight results. */
    private fun clearSession() {
        epoch++
        enrollment = null
        flow = null
        api = null
        pollJob?.cancel()
        pollJob = null
        tickJob?.cancel()
        tickJob = null
        // No active pairing: the play flavor tears down the runtime FirebaseApp
        // and its token.
        AppPush.onActiveDeployment(appContext, null)
    }

    /**
     * Cleans up a leftover key that has no enrollment: an empty vault plus a
     * legacy-alias key from an enroll that half-failed. A live deployment is
     * removed with [retire], which leaves the other deployments intact.
     */
    private fun wipe() {
        store.clear()
        DeviceKeyStore("").deleteKey() // the legacy alias, the only place an orphan key can sit
        pushStore.clear() // no pairing left; the route record and its pref die too
        clearSession()
    }

    // Push

    /**
     * Handles an incoming push. A push is untrusted third-party input: the only
     * thing it may do is trigger a fetch of server state, and its payload is
     * not rendered. No-op unless enrolled.
     */
    fun onPushReceived(envelope: PushEnvelope) {
        // Push callbacks arrive on the transport's thread, and everything this
        // class holds (enrollment, flow, epoch) is confined to main. Hop first,
        // then read: reading those fields off-main during a switch could pair
        // one deployment's token with another's client.
        scope.launch {
            if (state !is UiState.Approvals) return@launch
            when (envelope) {
                is PushEnvelope.Fetch -> {
                    refresh()
                    // A status push: one of the user's own requests resolved.
                    if (envelope.hint == PushEnvelope.Hint.Status) refreshActivity()
                }
                // Unrecognized: drop it. The poll covers the gap.
                PushEnvelope.Ignore -> Unit
            }
        }
    }

    /**
     * Aligns push with the stored preference: the platform side through
     * [AppPush.sync], the server side through the route planner. Runs on every
     * enrolled foreground, after enrollment and after a deployment switch.
     * Registration is idempotent server-side, so this is also the keep-alive.
     *
     * [AppPush.onActiveDeployment] runs first so the FCM probe sees the active
     * pairing's Firebase config. FCM and UnifiedPush finish in [onFcmToken]
     * and [onUnifiedPushEndpoint].
     */
    fun syncPushTransport() {
        scope.launch {
            AppPush.onActiveDeployment(appContext, enrollment?.fcm)
            val record = pushStore.read()
            val transport = effectiveTransport(record)
            AppPush.sync(appContext, transport, record.distributorId, enrollment?.vapidPublicKey)
            if (transport == PushTransport.POLL_ONLY &&
                (record.route != null || record.temporaryRoute != null)
            ) {
                executeRoutePlan(desired = null)
            }
            noteTransportAvailability()
        }
    }

    /**
     * A fresh FCM token (play flavor). Ignored unless FCM is the resolved
     * transport, so a rotation cannot re-register FCM against the user's choice.
     */
    fun onFcmToken(token: String) {
        scope.launch {
            if (effectiveTransport(pushStore.read()) != PushTransport.FCM) return@launch
            executeRoutePlan(PushRoute(PushKind.FCM, token))
        }
    }

    /**
     * A fresh UnifiedPush endpoint from the distributor. [p256dh] and [auth]
     * are registered only when this pairing's enroll carried a VAPID key: a
     * keyed registration against a deployment without WebPush is a 400. A
     * [temporary] endpoint is not stored as the primary route.
     */
    fun onUnifiedPushEndpoint(endpoint: String, p256dh: String?, auth: String?, temporary: Boolean) {
        scope.launch {
            if (effectiveTransport(pushStore.read()) != PushTransport.UNIFIEDPUSH) return@launch
            val keyed = enrollment?.vapidPublicKey != null && p256dh != null && auth != null
            val route = PushRoute(
                kind = PushKind.UNIFIEDPUSH,
                tokenOrEndpoint = endpoint,
                p256dh = p256dh.takeIf { keyed },
                auth = auth.takeIf { keyed },
            )
            if (temporary) executeTemporaryRoute(route) else executeRoutePlan(route)
        }
    }

    /** The distributor revoked the registration: tear the server route down. */
    fun onUnifiedPushUnregistered() {
        scope.launch {
            val record = pushStore.read()
            if (record.route?.kind == PushKind.UNIFIEDPUSH || record.temporaryRoute != null) {
                executeRoutePlan(desired = null)
            }
        }
    }

    private fun effectiveTransport(record: PushRouteStore.Record): PushTransport =
        // Every caller resolves through here, so no route can be registered
        // while consent is missing or withdrawn.
        PushPrefs.gate(
            record.consent,
            PushPrefs.effective(
                record.pref,
                // FCM resolves only when usable, so nothing reaches the
                // Firebase call that throws when no Firebase config exists.
                fcmAvailable = AppPush.fcmStatus(appContext).usable,
                hasDistributor = Distributors.installed(appContext).isNotEmpty(),
            ),
        )

    /**
     * Persists why this phone will not ring when the chosen transport cannot
     * run here, for example a play build with no Firebase config. Runs after
     * any route teardown in the same pass, whose `status = None` would
     * otherwise overwrite it, and clears itself once the transport works.
     */
    private fun noteTransportAvailability() {
        val current = pushStore.read()
        // Push switched off by the user is not a fault.
        val why = if (current.consent != PushConsent.GRANTED) {
            null
        } else {
            PushPrefs.fcmUnavailability(
                pref = current.pref,
                fcm = AppPush.fcmStatus(appContext),
                hasDistributor = Distributors.installed(appContext).isNotEmpty(),
            )
        }
        if (why != null) {
            val unavailable = PushRouteStatus.Unavailable(why)
            if (current.status != unavailable) pushStore.write(current.copy(status = unavailable))
        } else if (current.status is PushRouteStatus.Unavailable) {
            pushStore.write(current.copy(status = PushRouteStatus.None))
        }
        refreshPushSettingsUi()
    }

    /**
     * Executes a route transition against the active deployment: PUT the new
     * route, and DELETE the old one only after it registers, so a failed
     * registration leaves the previous route standing. No-op unless enrolled.
     */
    private suspend fun executeRoutePlan(desired: PushRoute?) {
        val token = enrollment?.deviceToken ?: return
        val activeApi = api ?: return
        val record = pushStore.read()
        val plan = PushRoutePlanner.plan(
            stored = record.route,
            desired = desired,
            storedTemporary = record.temporaryRoute,
        )

        val register = plan.register
        if (register == null) {
            // Teardown is best-effort: the server prunes dead routes on their
            // first failed delivery.
            plan.unregister.forEach {
                activeApi.unregisterPush(token, it.kind, it.tokenOrEndpoint, it.p256dh, it.auth)
            }
            pushStore.write(
                pushStore.read().copy(route = null, temporaryRoute = null, status = PushRouteStatus.None),
            )
            refreshPushSettingsUi()
            return
        }

        // PushRegistrar retries a keyed route without keys when the server
        // answers 400 (a deployment without webpush.vapidKeyFile).
        val outcome = PushRegistrar.register(activeApi, token, register)
        val status = PushRouteStatus.of(outcome.registered, outcome.result)
        if (status is PushRouteStatus.Registered) {
            plan.unregister.forEach {
                activeApi.unregisterPush(token, it.kind, it.tokenOrEndpoint, it.p256dh, it.auth)
            }
            // A registered primary also retires the temporary slot.
            pushStore.write(
                pushStore.read().copy(route = outcome.registered, temporaryRoute = null, status = status),
            )
        } else {
            // The stored route was not deleted and may still deliver.
            pushStore.write(pushStore.read().copy(status = status))
            if (status is PushRouteStatus.Rejected) surfacePushRejection(status)
        }
        refreshPushSettingsUi()
    }

    /**
     * Registers a temporary endpoint, used while the saved distributor's
     * backend is down. It has its own slot, is retired by the next primary
     * registration and is not written to `route`. Best-effort.
     */
    private suspend fun executeTemporaryRoute(desired: PushRoute) {
        val token = enrollment?.deviceToken ?: return
        val activeApi = api ?: return
        val plan = PushRoutePlanner.planTemporary(
            storedTemporary = pushStore.read().temporaryRoute,
            desired = desired,
        )
        val outcome = PushRegistrar.register(activeApi, token, plan.register ?: return)
        if (outcome.result is ApiResult.Ok<*>) {
            plan.unregister.forEach {
                activeApi.unregisterPush(token, it.kind, it.tokenOrEndpoint, it.p256dh, it.auth)
            }
            pushStore.write(pushStore.read().copy(temporaryRoute = outcome.registered))
        }
    }

    /**
     * The server refused the push route (`approval.push.allowedPushHosts`).
     * Shown as a notice, since the settings screen may be closed.
     */
    private fun surfacePushRejection(status: PushRouteStatus.Rejected) {
        val current = state as? UiState.Approvals ?: return
        if (current.pushSettings != null) return // the open settings screen already shows it
        state = current.copy(
            notice = "The server refused this push endpoint: \"${status.serverMessage}\". " +
                "Ask your admin to allowlist this push host (approval.push.allowedPushHosts). " +
                "Checking for approvals still works while the app is open.",
        )
    }

    /** Rebuilds the settings screen's state if it is open. */
    private fun refreshPushSettingsUi() {
        val current = state as? UiState.Approvals ?: return
        if (current.pushSettings == null) return
        state = current.copy(pushSettings = buildPushSettings())
    }

    private fun buildPushSettings(): PushSettingsUi {
        val record = pushStore.read()
        val distributors = Distributors.installed(appContext)
        val fcm = AppPush.fcmStatus(appContext)
        return PushSettingsUi(
            fcm = fcm,
            distributors = distributors,
            chosenDistributorId = record.distributorId
                ?: Distributors.saved(appContext)
                ?: distributors.singleOrNull()?.id,
            pref = record.pref,
            effective = effectiveTransport(record),
            status = record.status,
            lastDeliveryEpochSeconds = record.lastDeliveryEpochSeconds,
            consent = record.consent,
        )
    }

    /** True once the user opted in to push. The OS notification ask waits for it. */
    fun pushConsentGranted(): Boolean = pushStore.read().consent == PushConsent.GRANTED

    override fun setPushEnabled(enabled: Boolean) {
        scope.launch {
            pushStore.write(
                pushStore.read().copy(
                    consent = if (enabled) PushConsent.GRANTED else PushConsent.DECLINED,
                ),
            )
            (state as? UiState.Approvals)?.let { state = it.copy(askPushOptIn = null) }
            if (enabled) requestNotificationPermission()
            refreshPushSettingsUi()
            // On yes the sync registers whichever transport resolves. On no it
            // resolves to polling and deletes the standing route.
            syncPushTransport()
        }
    }

    override fun answerPushOptIn(choice: PushTransportPref) {
        scope.launch {
            val consent = PushPrefs.consentFor(choice)
            // One write for the chosen pref and the consent it expresses. A
            // decline persists POLL_ONLY, so enabling the master switch later
            // stays at polling until a provider is picked.
            pushStore.write(pushStore.read().copy(pref = choice, consent = consent))
            (state as? UiState.Approvals)?.let { state = it.copy(askPushOptIn = null) }
            if (consent == PushConsent.GRANTED) requestNotificationPermission()
            refreshPushSettingsUi()
            syncPushTransport()
        }
    }

    override fun openNotificationSettings() {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(pushSettings = buildPushSettings(), notice = null)
    }

    override fun closeNotificationSettings() {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(pushSettings = null)
    }

    override fun setPushTransport(pref: PushTransportPref) {
        scope.launch {
            pushStore.write(pushStore.read().copy(pref = pref))
            refreshPushSettingsUi()
            val record = pushStore.read()
            val transport = effectiveTransport(record)
            AppPush.sync(appContext, transport, record.distributorId, enrollment?.vapidPublicKey)
            if (transport == PushTransport.POLL_ONLY &&
                (record.route != null || record.temporaryRoute != null)
            ) {
                executeRoutePlan(desired = null)
            }
            noteTransportAvailability()
        }
    }

    override fun setPushDistributor(id: String) {
        scope.launch {
            pushStore.write(pushStore.read().copy(distributorId = id))
            refreshPushSettingsUi()
            val record = pushStore.read()
            if (effectiveTransport(record) == PushTransport.UNIFIEDPUSH) {
                AppPush.sync(appContext, PushTransport.UNIFIEDPUSH, id, enrollment?.vapidPublicKey)
            }
        }
    }

    override fun getDistributorApp() {
        // NEW_TASK because this starts from an application context.
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, NTFY_URL.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // No browser. The settings text already names ntfy.
        }
    }

    // AppActions

    override fun startScan() {
        val current = state as? UiState.NeedsEnrollment ?: return
        if (current.busy) return

        scope.launch {
            if (!requestCameraPermission()) {
                // Refused, or no camera. Manual entry still works.
                state = UiState.NeedsEnrollment(
                    error = "Camera access is needed to scan the enrollment code. " +
                        "You can enter the code manually instead.",
                    hasDeployments = hasDeployments(),
                )
                return@launch
            }
            state = UiState.NeedsEnrollment(scanning = true, hasDeployments = hasDeployments())
        }
    }

    override fun stopScan() {
        if ((state as? UiState.NeedsEnrollment)?.busy == true) return
        returnToDeployments()
    }

    /** Returns to the deployment list if one is enrolled, else to the pairing screen. */
    private fun returnToDeployments() {
        val active = store.active()
        if (active != null) {
            adopt(active)
            state = approvalsLoading()
            startPolling()
            startTicking()
        } else {
            state = UiState.NeedsEnrollment()
        }
    }

    override fun switchDeployment(projectId: String) {
        if (state !is UiState.Approvals) return
        if (enrollment?.let(::projectKey) == projectId) return
        store.setActive(projectId)
        val active = store.active() ?: return
        adopt(active)
        state = approvalsLoading()
        startPolling()
        startTicking()
        // The push route uses the active pairing's bearer, so register it again
        // with the deployment now active. The PUT is idempotent.
        syncPushTransport()
    }

    override fun addDeployment() {
        if (state !is UiState.Approvals) return
        scope.launch {
            if (!requestCameraPermission()) {
                // Same fallback as startScan: the pairing screen offers manual
                // entry and the way back to the existing pairings.
                state = UiState.NeedsEnrollment(
                    error = "Camera access is needed to scan the enrollment code. " +
                        "You can enter the code manually instead.",
                    hasDeployments = true,
                )
                return@launch
            }
            // Reuses the enrollment scanner. stopScan() routes back to the list.
            state = UiState.NeedsEnrollment(scanning = true, hasDeployments = true)
        }
    }

    override fun enterCodeManually() {
        val current = state as? UiState.NeedsEnrollment ?: return
        if (current.busy || !current.scanning) return
        // Closes the viewfinder into the pairing screen, where the manual field is.
        state = UiState.NeedsEnrollment(hasDeployments = hasDeployments())
    }

    override fun removeDeployment() {
        val current = state as? UiState.Approvals ?: return
        if (current.busy) return
        val name = enrollment?.displayName() ?: return
        // Navigation only: nothing is destroyed until confirmRemoveDeployment.
        state = current.copy(confirmRemove = name)
    }

    override fun confirmRemoveDeployment() {
        val current = state as? UiState.Approvals ?: return
        val name = current.confirmRemove ?: return
        // The prompt names the active pairing, and every path that changes the
        // active pairing replaces the whole Approvals state and drops the
        // prompt. So `enrollment` here is the pairing the user saw named.
        val record = enrollment ?: return
        // On an older server without self-unenroll the device row stays until
        // an admin revokes it.
        retire(
            record,
            whenGone = "Removed \"$name\". Scan an enrollment code to pair this phone again.",
            whenSwitched = "Removed \"$name\".",
        )
    }

    override fun cancelRemoveDeployment() {
        val current = state as? UiState.Approvals ?: return
        if (current.confirmRemove == null) return
        state = current.copy(confirmRemove = null)
    }

    override fun enroll(qrPayload: String) {
        // Only NeedsEnrollment may enroll: a decode can arrive just after the
        // scanner was dismissed, when the user is back on a live deployment
        // that must not be re-paired. `busy` drops a second decode.
        val entry = state as? UiState.NeedsEnrollment ?: return
        if (entry.busy) return

        // Keep the viewfinder up while the scanned payload is processed.
        state = UiState.NeedsEnrollment(
            busy = true,
            scanning = entry.scanning,
            hasDeployments = hasDeployments(),
        )

        scope.launch {
            val parsed = when (val result = EnrollmentQr.parse(qrPayload.trim())) {
                is EnrollmentQr.Result.Invalid -> {
                    state = UiState.NeedsEnrollment(error = result.reason, hasDeployments = hasDeployments())
                    return@launch
                }
                is EnrollmentQr.Result.Ok -> result.payload
            }

            if (!gate.canAuthenticate()) {
                // The key requires authentication per use, so a device that
                // cannot authenticate could enroll and then never approve.
                state = UiState.NeedsEnrollment(
                    error = "Set up a screen lock and biometrics before enrolling. " +
                        "Approvals must be confirmed with device authentication.",
                    hasDeployments = hasDeployments(),
                )
                return@launch
            }

            // A payload naming a deployment this phone already holds stops here:
            // createKey() below replaces the alias, which would destroy the
            // existing pairing's key before any server validation. The scanned
            // identity is untrusted, so the user must confirm the replacement.
            val qrKey = projectKeyFor(parsed.project?.id, parsed.servers)
            val existing = store.find(qrKey)
            if (existing != null) {
                pendingReplace = parsed
                state = UiState.NeedsEnrollment(
                    confirmReplace = ReplacePrompt(
                        existingName = existing.displayName(),
                        // The server name if the code carries one, else its URL.
                        claimedName = parsed.project?.name?.takeIf { it.isNotBlank() }
                            ?: parsed.servers.first(),
                    ),
                    hasDeployments = true,
                )
                return@launch
            }

            completeEnrollment(parsed)
        }
    }

    override fun confirmReplace() {
        val parsed = pendingReplace ?: return
        pendingReplace = null
        state = UiState.NeedsEnrollment(busy = true, hasDeployments = true)
        scope.launch { completeEnrollment(parsed) }
    }

    override fun cancelReplace() {
        pendingReplace = null
        returnToDeployments()
    }

    /**
     * The irreversible half of enrollment: creates the hardware key and
     * exchanges it with the server for a device row.
     */
    private suspend fun completeEnrollment(parsed: EnrollmentPayload) {
        val keyRef = projectKeyFor(parsed.project?.id, parsed.servers)
        // The pairing being replaced, if any. When it shares the alias,
        // createKey() below destroys its key, which matters on failure.
        val replacing = store.find(keyRef)

        enrollingKeyRef = keyRef
        try {
            val publicKey = try {
                withContext(Dispatchers.IO) { DeviceKeyStore(keyRef).createKey(UserAuthPolicy.REQUIRED_PER_USE) }
            } catch (e: Exception) {
                state = UiState.NeedsEnrollment(
                    error = e.message ?: "could not create a device key",
                    hasDeployments = hasDeployments(),
                )
                return
            }

            val client = StrazaClient(parsed.servers, parsed.pin)
            val deviceName = android.os.Build.MODEL ?: "android-device"

            when (val result = client.enroll(parsed.enrollToken, deviceName, publicKey)) {
                is ApiResult.Ok -> {
                    val record = Enrollment(
                        approverDeviceId = result.value.approverDeviceId,
                        deviceToken = result.value.deviceToken,
                        servers = parsed.servers,
                        pin = parsed.pin,
                        deviceName = deviceName,
                        // The enroll response is authoritative over the QR
                        // preview. An older server omits it, which leaves
                        // projectId blank and keys the vault row by server host.
                        projectId = (result.value.project ?: parsed.project)?.id ?: "",
                        projectName = (result.value.project ?: parsed.project)?.name,
                        keyRef = keyRef,
                        // For the renew warning. Renewal reacts to the server's 401.
                        tokenExpiresAtEpochSeconds = result.value.expiresInSeconds
                            .takeIf { it > 0 }?.let { nowEpochSeconds() + it },
                        // The WebPush key and the Firebase config. Each is null
                        // when that transport is off server-side.
                        vapidPublicKey = result.value.webpushVapidPublicKey,
                        fcm = result.value.fcm,
                    )

                    // The response identity decides the vault row. If it differs
                    // from the QR's and lands on another deployment this phone
                    // holds, refuse: the user consented at most to replacing the
                    // pairing the QR named, and a server must not evict another
                    // one by answering with its project id.
                    val finalKey = projectKey(record)
                    if (finalKey != keyRef) {
                        val collided = store.find(finalKey)
                        if (collided != null) {
                            DeviceKeyStore(keyRef).deleteKey()
                            state = UiState.NeedsEnrollment(
                                error = "The server identified itself as \"${collided.displayName()}\" - " +
                                    "a deployment this phone already holds under a different code. " +
                                    "Nothing was changed. Ask for a freshly generated code.",
                                hasDeployments = hasDeployments(),
                            )
                            return
                        }
                    }

                    // If the replaced row kept its key under a different alias
                    // (a host-keyed record superseded by a project-keyed one),
                    // delete that key so it does not occupy a StrongBox slot.
                    val superseded = store.find(finalKey)
                    if (superseded != null && superseded.keyRef != record.keyRef) {
                        DeviceKeyStore(superseded.keyRef).deleteKey()
                    }

                    // Add (or replace, as confirmed); it becomes the active one.
                    store.upsert(record)
                    adopt(record)
                    state = approvalsLoading()
                    startPolling()
                    startTicking()
                    // MainActivity's onStart sync ran before a bearer existed,
                    // so run the first push registration for this pairing now.
                    syncPushTransport()
                }

                else -> {
                    // The server has no row for this key: do not leave it behind.
                    DeviceKeyStore(keyRef).deleteKey()
                    if (replacing != null && replacing.keyRef == keyRef) {
                        // createKey() already destroyed the replaced pairing's
                        // key and the new enrollment failed. Retire the dead
                        // record: it could never sign again.
                        store.remove(keyRef)
                        if (enrollment?.let(::projectKey) == keyRef) clearSession()
                        state = UiState.NeedsEnrollment(
                            error = "The pairing for \"${replacing.displayName()}\" was replaced, " +
                                "but the new enrollment failed: ${describe(result)} " +
                                "Scan a fresh code to pair it again.",
                            hasDeployments = hasDeployments(),
                        )
                    } else {
                        state = UiState.NeedsEnrollment(error = describe(result), hasDeployments = hasDeployments())
                    }
                }
            }
        } finally {
            enrollingKeyRef = null
        }
    }

    private fun hasDeployments(): Boolean = store.loadAll().isNotEmpty()

    /** Epoch second until which the poll holds (server 429/503). */
    private var pollBackoffUntilEpochSeconds = 0L

    override fun refresh() {
        val current = state as? UiState.Approvals ?: return
        val token = enrollment?.deviceToken ?: return
        val activeFlow = flow ?: return
        val boundEpoch = epoch

        scope.launch {
            val screen = activeFlow.refresh(token)
            // If the active deployment changed while this fetch was in flight,
            // the result belongs to a deployment that is not on screen: drop it.
            // A 401 inside refresh() retires the deployment, which also moves
            // the epoch.
            if (epoch != boundEpoch) return@launch
            if (screen is ApprovalScreen.Revoked) {
                state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                return@launch
            }
            // A server-requested pause (429/503) holds the poll only. Pushes
            // and manual refreshes are not held.
            if (screen is ApprovalScreen.CannotReach) {
                screen.retryAfterSeconds?.let { pollBackoffUntilEpochSeconds = nowEpochSeconds() + it }
            }
            // Re-read state: a tab switch or a clock tick may have landed while
            // the fetch was in flight, and copying `current` would roll it back.
            val latest = state as? UiState.Approvals ?: return@launch
            state = latest.copy(
                screen = screen,
                // Keep an open detail sheet only while its request is still
                // pending, so a request resolved elsewhere shows no live buttons.
                selected = latest.selected?.takeIf { open ->
                    screen is ApprovalScreen.Pending && screen.requests.any { it.id == open.id }
                },
            )
        }
    }

    /** Fetches the resolved feed, which gates no action. */
    private fun refreshActivity() {
        val token = enrollment?.deviceToken ?: return
        val activeFlow = flow ?: return
        val boundEpoch = epoch

        scope.launch {
            val screen = activeFlow.activity(token)
            // Same epoch check as refresh().
            if (epoch != boundEpoch) return@launch
            if (screen is ActivityScreen.Revoked) {
                state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                return@launch
            }
            val latest = state as? UiState.Approvals ?: return@launch
            state = latest.copy(activity = screen)
        }
    }

    override fun selectTab(tab: ApprovalTab) {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(tab = tab, notice = null)
        if (tab == ApprovalTab.Activity) refreshActivity()
    }

    override fun filterActivity(state: ResolvedState?) {
        val current = this.state as? UiState.Approvals ?: return
        // View-only: no fetch, the list is already in memory.
        this.state = current.copy(activityFilter = state)
    }

    override fun exportActivity() {
        val records = store.loadAll()
        if (records.isEmpty()) return
        (state as? UiState.Approvals)?.let { state = it.copy(notice = "Exporting…") }
        // The export spans every deployment, so each backend's history is
        // fetched live with a client built per record. It is read-only: a 401
        // here is skipped and reported, and does not go through the revoke path
        // that would destroy a signing key.
        scope.launch {
            val sections = mutableListOf<Pair<String, List<ResolvedRequest>>>()
            val failed = mutableListOf<String>()
            for (record in records) {
                // Walk the keyset cursor to the end. limit=200 is the server's
                // ceiling. Stop only on an empty next_cursor: after visibility
                // filtering a page can be short or empty and still carry a
                // cursor. The page cap guards against a runaway loop.
                val client = StrazaClient(record.servers, record.pin)
                val rows = mutableListOf<ResolvedRequest>()
                var cursor: String? = null
                var ok = true
                var pages = 0
                walk@ while (pages++ < 1000) {
                    when (val page = client.history(record.deviceToken, limit = 200, cursor = cursor)) {
                        is ApiResult.Ok -> {
                            rows += page.value.items
                            if (page.value.nextCursor.isEmpty()) break@walk // "" ⇒ done
                            cursor = page.value.nextCursor // opaque; echo verbatim
                        }
                        // Report the deployment as unreachable. A history error,
                        // an invalid_cursor 400 included, is not a revocation.
                        else -> { ok = false; break@walk }
                    }
                }
                if (ok) sections.add(record.displayName() to rows)
                else failed.add(record.displayName())
            }
            val latest = state as? UiState.Approvals
            if (sections.isEmpty()) {
                if (latest != null) state = latest.copy(notice = "Couldn't reach any deployment to export.")
                return@launch
            }
            ActivityExport.share(
                appContext,
                ActivityCsv.exportAll(sections),
                if (records.size == 1) records.first().displayName() else "${sections.size} deployments",
            )
            val note = if (failed.isEmpty()) null
            else "Exported ${sections.size} of ${records.size} - couldn't reach: ${failed.joinToString(", ")}."
            if (latest != null) state = latest.copy(notice = note)
        }
    }

    override fun select(request: PendingRequest?) {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(selected = request, notice = null)
    }

    override fun decide(request: PendingRequest, verdict: Verdict, reason: String?) {
        val current = state as? UiState.Approvals ?: return
        // Snapshot the active deployment for the life of this decision. The
        // sign lambda runs after the biometric prompt, and by then a 401 or a
        // switch may have moved `enrollment` to another backend. Signing the
        // captured challenge with that backend's key would be a
        // cross-deployment signature.
        val boundEnrollment = enrollment ?: return
        val activeFlow = flow ?: return
        val boundKeyStore = DeviceKeyStore(boundEnrollment.keyRef)
        val token = boundEnrollment.deviceToken
        val boundEpoch = epoch

        state = current.copy(busy = true, notice = null)

        scope.launch {
            // No dispatcher switch here: the client dispatches its own network
            // IO and the gate hops to the main thread for the prompt.
            val outcome = activeFlow.decide(token, request, verdict, reason) { message ->
                gate.signWithAuthentication(
                    keyStore = boundKeyStore,
                    message = message,
                    title = if (verdict == Verdict.APPROVE) "Approve this request?" else "Deny this request?",
                    // The tool being authorised is named on the prompt itself,
                    // in the same words as the screen behind it.
                    subtitle = request.toolTitle,
                )
            }

            // The decision went to the captured deployment. If the active one
            // changed meanwhile, do not paint this outcome over it.
            if (epoch != boundEpoch) return@launch

            val latest = state as? UiState.Approvals ?: return@launch
            state = when (outcome) {
                is DecisionOutcome.Recorded ->
                    latest.copy(busy = false, selected = null, notice = "Recorded: ${outcome.state}")

                is DecisionOutcome.AlreadyResolved ->
                    latest.copy(
                        busy = false,
                        selected = null,
                        notice = "Already resolved elsewhere: ${outcome.state}",
                    )

                is DecisionOutcome.NotSigned -> {
                    // A cancelled prompt stays on this screen. A dead key cannot
                    // sign again, so retire this deployment only. Only a proven
                    // dead key retires (absent, or invalidated by the platform):
                    // a keystore that could not answer keeps the pairing.
                    val keyState = boundKeyStore.keyState()
                    if (keyState is KeyState.Absent || keyState is KeyState.Unusable) {
                        retire(
                            boundEnrollment,
                            whenGone = "This device's key is no longer usable, so approvals cannot be " +
                                "signed. Enroll this device again.",
                            whenSwitched = "A deployment's signing key is no longer usable; it was removed.",
                        )
                        return@launch
                    }
                    latest.copy(busy = false, notice = "Nothing was submitted (${outcome.reason}).")
                }

                is DecisionOutcome.Failed ->
                    latest.copy(busy = false, notice = "Could not submit: ${outcome.reason}")

                // Signed, but the server was not the one this device is pinned
                // to. Route to the re-pair screen and wipe nothing, because a
                // hostile network can produce this state at will.
                is DecisionOutcome.Untrusted ->
                    latest.copy(
                        busy = false,
                        selected = null,
                        screen = ApprovalScreen.Untrusted(outcome.reason),
                    )

                // The token expired mid-decision. Nothing was recorded and the
                // key is fine: route to the renew screen.
                DecisionOutcome.RenewNeeded ->
                    latest.copy(
                        busy = false,
                        selected = null,
                        screen = ApprovalScreen.RenewNeeded,
                    )

                DecisionOutcome.Revoked -> {
                    // Fallback: onRevoked and retire() already moved the epoch,
                    // so the check above normally returns first.
                    state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                    return@launch
                }
            }
            refresh()
        }
    }

    override fun dismissNotice() {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(notice = null)
    }

    override fun renewPairing() {
        val current = state as? UiState.Approvals ?: return
        if (current.busy) return
        // Snapshot as decide() does: the signature and the write-back bind to
        // the deployment that asked for renewal.
        val bound = enrollment ?: return
        val activeApi = api ?: return
        val boundKeyStore = DeviceKeyStore(bound.keyRef)
        val boundEpoch = epoch

        state = current.copy(busy = true, notice = null)

        scope.launch {
            val result = try {
                activeApi.renewToken(bound.approverDeviceId) { message ->
                    gate.signWithAuthentication(
                        keyStore = boundKeyStore,
                        message = message,
                        title = "Renew this pairing?",
                        subtitle = bound.displayName(),
                    ) ?: throw RenewCancelled
                }
            } catch (_: RenewCancelled) {
                // A cancelled prompt renews nothing and destroys nothing.
                if (epoch != boundEpoch) return@launch
                (state as? UiState.Approvals)?.let {
                    state = it.copy(busy = false, notice = "Nothing was renewed (not authenticated).")
                }
                return@launch
            }

            when (result) {
                is ApiResult.Ok -> {
                    // Write back against the current stored record, not the
                    // pre-renew snapshot, so a rename or push-config change made
                    // meanwhile survives. A deployment re-paired mid-renewal has
                    // a new device row and token: a token minted for the old row
                    // must not be written onto it.
                    val key = projectKey(bound)
                    val renewed = when (
                        val writeBack = RenewalWriteBacks.persist(
                            store, bound, result.value.deviceToken, result.value.expiresInSeconds, nowEpochSeconds(),
                        )
                    ) {
                        is RenewalWriteBack.Dropped -> {
                            if (epoch != boundEpoch) return@launch
                            (state as? UiState.Approvals)?.let { state = it.copy(busy = false) }
                            return@launch
                        }
                        is RenewalWriteBack.Applied -> writeBack.renewed
                    }
                    // Apply to the live session whenever it still holds this
                    // deployment, whatever the epoch: a foreground re-read may
                    // have re-adopted the pre-write record with the expired
                    // token. adopt() moves the epoch, so a poll issued with the
                    // old token is dropped.
                    if (enrollment?.let(::projectKey) != key) return@launch
                    adopt(renewed)
                    (state as? UiState.Approvals)?.let {
                        state = it.copy(
                            busy = false,
                            screen = ApprovalScreen.Loading,
                            notice = "Pairing renewed.",
                            tokenExpiresAtEpochSeconds = renewed.tokenExpiresAtEpochSeconds,
                        )
                    }
                    // The foreground route registration got a 401 on the expired
                    // token. Sync again with the new one.
                    syncPushTransport()
                    refresh()
                }

                is ApiResult.Unauthorized -> {
                    // Only an explicit device_revoked destroys the key. An
                    // unknown code here is an anomaly (servers from 0.23 code
                    // their refresh 401s, older ones 404 the path) and must not
                    // cost a hardware key.
                    if (result.reason == AuthRejection.DEVICE_REVOKED) {
                        retire(
                            bound,
                            whenGone = "This deployment revoked the device. Enroll it again.",
                            whenSwitched = "A deployment revoked this device and was removed.",
                        )
                        return@launch
                    }
                    if (epoch != boundEpoch) return@launch
                    (state as? UiState.Approvals)?.let {
                        val why = when (result.reason) {
                            AuthRejection.USER_INACTIVE -> "your account is inactive on this deployment."
                            else -> "the server rejected the renewal. Try again, or re-pair with a fresh code."
                        }
                        state = it.copy(busy = false, notice = "Could not renew: $why")
                    }
                }

                // The pin check failed mid-renewal: the re-pair screen, wiping
                // nothing, as for a decision's Untrusted.
                is ApiResult.Untrusted -> {
                    if (epoch != boundEpoch) return@launch
                    (state as? UiState.Approvals)?.let {
                        state = it.copy(busy = false, screen = ApprovalScreen.Untrusted(result.reason))
                    }
                }

                else -> {
                    if (epoch != boundEpoch) return@launch
                    (state as? UiState.Approvals)?.let {
                        val why = if ((result as? ApiResult.ServerError)?.status == 404) {
                            // Ambiguous: an older server has no refresh path
                            // and an unknown device also 404s. Neither
                            // justifies touching the key.
                            "this server does not support renewal (or the device is unknown to it). " +
                                "Update the server, or re-pair with a fresh code."
                        } else {
                            describe(result)
                        }
                        state = it.copy(busy = false, notice = "Could not renew: $why")
                    }
                }
            }
        }
    }

    /**
     * The way out of a stale certificate pin. The pin lives in the enrollment
     * record, so this discards the enrollment and returns to the pairing
     * screen. It runs only from the button on the untrusted screen, because a
     * network attacker can induce the state it exits.
     */
    override fun reEnroll() {
        val active = store.active()
        if (active == null) {
            clearSession()
            state = UiState.NeedsEnrollment(
                error = "Pair this device again to trust the server's new certificate.",
            )
            return
        }
        // Only the active deployment's certificate rotated. Every other
        // enrolled deployment is left intact.
        retire(
            active,
            whenGone = "Pair this device again to trust the server's new certificate.",
            whenSwitched = "Removed the deployment whose certificate changed. Scan its code to pair it again.",
        )
    }

    // Polling

    /**
     * The poll. It stays even with push: a push is only a hint to fetch, and
     * may be dropped, delayed or not registered at all.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Honour a server-requested pause (Retry-After on 429/503).
                val pause = pollBackoffUntilEpochSeconds - nowEpochSeconds()
                if (pause > 0) {
                    delay(minOf(pause * 1000, POLL_INTERVAL_MILLIS))
                    continue
                }
                refresh()
                // The feed is polled only while its tab is open. Pending is
                // always polled: it drives the badge count and the countdowns.
                if ((state as? UiState.Approvals)?.tab == ApprovalTab.Activity) refreshActivity()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * Advances the clock the expiry countdowns render against, so a request
     * whose window closed between two polls loses its Approve button. Wakes
     * every second but publishes a state copy only while [ticksEverySecond]
     * says something on screen counts seconds, and otherwise once a minute.
     */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var quietTicks = 0
            while (isActive) {
                (state as? UiState.Approvals)?.let { current ->
                    quietTicks++
                    if (ticksEverySecond(current) || quietTicks >= QUIET_TICKS_PER_PUBLISH) {
                        quietTicks = 0
                        state = current.copy(nowEpochSeconds = nowEpochSeconds())
                    }
                }
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Unauthorized -> "The enrollment payload was rejected. Ask for a fresh one."
        is ApiResult.Unreachable ->
            "Could not reach Straza (${result.reason}). Check the address and the network."
        // At enrollment the pin comes from the payload just scanned, so a
        // mismatch means the payload does not describe this server: a stale
        // code, the wrong environment or an intercepted connection.
        is ApiResult.Untrusted ->
            "Straza answered but presented a different certificate than this enrollment " +
                "code expects (${result.reason}). Ask for a freshly generated code."
        is ApiResult.ServerError -> "The server returned an error (${result.status})."
        is ApiResult.AlreadyResolved -> "Unexpected server response."
        is ApiResult.Ok -> "OK"
    }

    /** Thrown by the renew sign lambda when the prompt did not authenticate. */
    private object RenewCancelled : Exception() {
        private fun readResolve(): Any = RenewCancelled
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 15_000L
        const val TICK_INTERVAL_MILLIS = 1_000L

        /** With no hold on screen, publish a tick every this-many wakes. */
        const val QUIET_TICKS_PER_PUBLISH = 60

        /** The suggested UnifiedPush distributor's website. */
        const val NTFY_URL = "https://ntfy.sh"
    }
}
