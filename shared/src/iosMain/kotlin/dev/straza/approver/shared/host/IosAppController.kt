@file:OptIn(ExperimentalForeignApi::class)

package dev.straza.approver.shared.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.straza.approver.shared.flow.ActivityCsv
import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalFlow
import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.flow.Convergence
import dev.straza.approver.shared.flow.RenewalWriteBack
import dev.straza.approver.shared.flow.RenewalWriteBacks
import dev.straza.approver.shared.flow.StartupRead
import dev.straza.approver.shared.flow.StartupReads
import dev.straza.approver.shared.flow.DecisionOutcome
import dev.straza.approver.shared.flow.StartupDecision
import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.AuthRejection
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import dev.straza.approver.shared.net.StrazaClient
import dev.straza.approver.shared.protocol.EnrollmentPayload
import dev.straza.approver.shared.protocol.EnrollmentQr
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.push.BareUuid
import dev.straza.approver.shared.push.FcmStatus
import dev.straza.approver.shared.push.PushBridge
import dev.straza.approver.shared.push.PushConsent
import dev.straza.approver.shared.push.PushEnvelope
import dev.straza.approver.shared.push.PushPrefs
import dev.straza.approver.shared.push.PushRegistrar
import dev.straza.approver.shared.push.PushRoute
import dev.straza.approver.shared.push.PushRouteStatus
import dev.straza.approver.shared.push.PushSettingsUi
import dev.straza.approver.shared.push.PushTransportPref
import dev.straza.approver.shared.net.PushKind
import dev.straza.approver.shared.security.DeviceKeyStore
import dev.straza.approver.shared.security.DeviceKeyStoreException
import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeyState
import dev.straza.approver.shared.security.PlatformContext
import dev.straza.approver.shared.security.SecureStore
import dev.straza.approver.shared.security.UserAuthPolicy
import dev.straza.approver.shared.security.displayName
import dev.straza.approver.shared.security.projectKey
import dev.straza.approver.shared.security.projectKeyFor
import dev.straza.approver.shared.ui.AppActions
import dev.straza.approver.shared.ui.ApprovalTab
import dev.straza.approver.shared.ui.DeploymentSummary
import dev.straza.approver.shared.ui.ReplacePrompt
import dev.straza.approver.shared.ui.UiState
import dev.straza.approver.shared.ui.enrollmentInFlight
import dev.straza.approver.shared.ui.ticksEverySecond
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIDevice
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationOptionTimeSensitive
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSettingDisabled
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** NSUserDefaults key for the push opt-in answer (a [PushConsent] name). */
private const val PUSH_CONSENT_KEY = "straza.push.consent.v1"

/**
 * The iOS app controller. State is main-confined and async results are
 * guarded by an epoch. A push only triggers a fetch over the authenticated
 * channel and does not replace the poll: APNs keeps one undelivered
 * notification.
 *
 * Signing must run off main: the key's `.biometryCurrentSet` access control
 * runs Face ID inside `SecKeyCreateSignature`, and on main the prompt
 * deadlocks. `keyState()` reading Present does not prove the key signs, so
 * only an absent key converges at startup.
 */
class IosAppController : AppActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = SecureStore(PlatformContext())

    private var enrollment: Enrollment? = null
    private var flow: ApprovalFlow? = null
    private var api: ApprovalApi? = null
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    private var startJob: Job? = null

    /** The parsed payload awaiting the user's replace-pairing decision. */
    private var pendingReplace: EnrollmentPayload? = null

    /** Bumped on every active-deployment change. An async result captured
     *  under an older epoch is dropped. Main-confined, so no lock is needed. */
    private var epoch = 0

    /** The server's answer to the last APNs route registration. */
    private var pushStatus: PushRouteStatus = PushRouteStatus.None

    /** The last [PushBridge] sequence numbers acted on, so a replayed event
     *  is handled once. */
    private var lastReceivedSeq = 0L
    private var lastOpenedSeq = 0L

    /** Limits the notification permission request to once per process. */
    private var notificationAskFired = false

    /**
     * The push opt-in answer, kept in NSUserDefaults because it is a UI
     * preference, not a credential. It gates both the OS permission prompt
     * and the server route registration.
     */
    private var pushConsent: PushConsent
        get() = NSUserDefaults.standardUserDefaults.stringForKey(PUSH_CONSENT_KEY)
            ?.let { name -> PushConsent.entries.firstOrNull { it.name == name } }
            ?: PushConsent.UNDECIDED
        set(value) {
            NSUserDefaults.standardUserDefaults.setObject(value.name, forKey = PUSH_CONSENT_KEY)
        }

    var state by mutableStateOf<UiState>(UiState.NeedsEnrollment())
        private set

    init {
        // Every foreground re-reads the enrollment, which may have been revoked
        // while away. Every background stops the poll and the tick.
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> start() }
        center.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> stop() }

        // PushBridge collectors live as long as the controller and are not
        // tied to start()/stop(): a token that arrives in the background must
        // still be registered, and the cached cold-start tap replays here.
        scope.launch {
            PushBridge.apnsToken.collect { token ->
                if (token != null) registerApnsRoute()
            }
        }
        scope.launch {
            PushBridge.received.collect { event ->
                if (event == null || event.seq <= lastReceivedSeq) return@collect
                lastReceivedSeq = event.seq
                onPushEnvelope(event.envelope, fromTap = false)
            }
        }
        scope.launch {
            PushBridge.opened.collect { event ->
                if (event == null || event.seq <= lastOpenedSeq) return@collect
                lastOpenedSeq = event.seq
                onPushEnvelope(event.envelope, fromTap = true)
            }
        }

        start()
    }

    // Lifecycle

    fun start() {
        pendingReplace = null
        // Read off main; startJob and the epoch guard drop a stale result.
        // Concurrent Keychain reads are safe (SecItem calls are atomic per item).
        startJob?.cancel()
        val startedEpoch = epoch
        val held = enrollment
        startJob = scope.launch {
            val read = withContext(Dispatchers.Default) {
                // An unreadable Keychain item (locked device, unexpected
                // status) destroys nothing: a held session keeps running and
                // a cold start shows the reason.
                StartupReads.resolve(
                    snapshot = store.snapshot(),
                    held = held,
                    keyStateOf = { keyRef -> DeviceKeyStore(keyRef).keyState() },
                )
            }
            if (epoch != startedEpoch) return@launch
            val snapshot = when (read) {
                is StartupRead.Unreadable -> {
                    state = UiState.NeedsEnrollment(error = StartupReads.unreadableMessage(read.reason))
                    return@launch
                }
                is StartupRead.Ready -> read
            }
            val all = snapshot.all
            val active = snapshot.active
            // A key lookup failure reads Indeterminate, which Convergence
            // proceeds on. Only its Absent and orphan cases act.
            val decision = Convergence.onStartup(active, snapshot.keyState)
            // A system alert during pairing (camera permission sheet, call
            // banner, Control Center) sends the app through resign and
            // become-active. An enrollment in flight is left alone unless the
            // decision is to converge. The Keychain outlives an uninstall, so
            // a fresh install can already hold a deployment at this point.
            if (enrollmentInFlight(state) && decision !is StartupDecision.Converge) return@launch
            when (decision) {
                is StartupDecision.Enroll -> state = UiState.NeedsEnrollment()

                is StartupDecision.Converge -> {
                    if (active != null) {
                        DeviceKeyStore(active.keyRef).deleteKey()
                        store.remove(projectKey(active))
                        convergeAfterActiveRemoved(reason = decision.reason, notice = decision.reason)
                    } else {
                        wipe()
                        state = UiState.NeedsEnrollment(error = decision.reason)
                    }
                }

                is StartupDecision.Proceed -> {
                    adopt(decision.enrollment)
                    state = approvalsLoading(all.map { DeploymentSummary(projectKey(it), it.displayName()) })
                    startPolling()
                    startTicking()
                    ensureNotificationAuthorization()
                    registerApnsRoute()
                }
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        pollJob?.cancel()
        pollJob = null
        tickJob?.cancel()
        tickJob = null
    }

    private fun adopt(record: Enrollment) {
        epoch++
        // A new active deployment starts at None until its registration answers.
        pushStatus = PushRouteStatus.None
        enrollment = record
        val client = StrazaClient(record.servers, record.pin)
        api = client
        flow = ApprovalFlow(
            api = client,
            // The 401 retires the deployment this flow belongs to, not whatever
            // store.active() returns by then.
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

    /** Removes one deployment (push route, device key, stored record), then
     *  moves to the next deployment or back to enrollment. */
    private fun retire(record: Enrollment, whenGone: String, whenSwitched: String) {
        val key = projectKey(record)
        val wasActive = enrollment?.let(::projectKey) == key
        // Best-effort DELETE of the push route. After a revocation it gets a
        // 401, and without an APNs token it cannot be sent; in both cases the
        // server prunes the route itself.
        if (wasActive) {
            val dyingApi = api
            val apnsToken = PushBridge.apnsToken.value
            if (dyingApi != null && apnsToken != null) {
                scope.launch {
                    dyingApi.unregisterPush(record.deviceToken, PushKind.APNS, apnsToken)
                }
            }
            pushStatus = PushRouteStatus.None
        }
        // Best-effort server-side unenroll with the record's own client.
        scope.launch { StrazaClient(record.servers, record.pin).unenroll(record.deviceToken) }
        DeviceKeyStore(record.keyRef).deleteKey()
        store.remove(key)
        if (!wasActive) return
        convergeAfterActiveRemoved(reason = whenGone, notice = whenSwitched)
    }

    private fun convergeAfterActiveRemoved(reason: String, notice: String) {
        val next = store.active()
        if (next != null) {
            adopt(next)
            state = approvalsLoading().copy(notice = notice)
            startPolling()
            startTicking()
            registerApnsRoute()
        } else {
            clearSession()
            state = UiState.NeedsEnrollment(error = reason)
        }
    }

    private fun clearSession() {
        epoch++
        enrollment = null
        flow = null
        api = null
        pollJob?.cancel()
        pollJob = null
        tickJob?.cancel()
        tickJob = null
    }

    /** Orphan cleanup for the startup case with no enrollment. */
    private fun wipe() {
        store.clear()
        DeviceKeyStore("").deleteKey()
        // The opt-in answer is cleared too, so the next pairing asks again.
        NSUserDefaults.standardUserDefaults.removeObjectForKey(PUSH_CONSENT_KEY)
        clearSession()
    }

    private fun deploymentSummaries(): List<DeploymentSummary> =
        store.loadAll().map { DeploymentSummary(projectKey(it), it.displayName()) }

    private fun approvalsLoading(deployments: List<DeploymentSummary> = deploymentSummaries()): UiState.Approvals =
        UiState.Approvals(
            screen = ApprovalScreen.Loading,
            deploymentName = enrollment?.displayName(),
            deployments = deployments,
            activeProjectId = enrollment?.let(::projectKey),
            thisDeviceId = enrollment?.approverDeviceId,
            tokenExpiresAtEpochSeconds = enrollment?.tokenExpiresAtEpochSeconds,
            nowEpochSeconds = nowEpochSeconds(),
            // The opt-in ask shows on every entry to the enrolled state until answered.
            askPushOptIn = if (PushPrefs.shouldAsk(pushConsent, enrolled = true)) askFacts() else null,
        )

    /** What the opt-in ask shows on iOS: no transport picker (Apple push is
     *  the only provider) and no posture note. */
    private fun askFacts(): PushSettingsUi = PushSettingsUi(
        fcm = FcmStatus.NOT_IN_BUILD,
        distributors = emptyList(),
        status = pushStatus,
        consent = pushConsent,
        transportPickerAvailable = false,
    )

    /** Epoch second until which the poll holds (server 429/503). */
    private var pollBackoffUntilEpochSeconds = 0L

    private fun hasDeployments(): Boolean = store.loadAll().isNotEmpty()

    // Enrollment

    override fun startScan() {
        val current = state as? UiState.NeedsEnrollment ?: return
        if (current.busy) return
        scope.launch {
            if (!requestCameraPermission()) {
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

    override fun enterCodeManually() {
        val current = state as? UiState.NeedsEnrollment ?: return
        if (current.busy || !current.scanning) return
        state = UiState.NeedsEnrollment(hasDeployments = hasDeployments())
    }

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

    override fun enroll(qrPayload: String) {
        // Only a NeedsEnrollment state may enroll, and busy drops a second or
        // late decode from the scanner.
        val entry = state as? UiState.NeedsEnrollment ?: return
        if (entry.busy) return

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

            if (!canAuthenticate()) {
                // The key demands biometry for every signature, so a device
                // that cannot authenticate would enroll and then never approve.
                state = UiState.NeedsEnrollment(
                    error = "Set up Face ID or Touch ID before enrolling. " +
                        "Approvals must be confirmed with device authentication.",
                    hasDeployments = hasDeployments(),
                )
                return@launch
            }

            // A scanned code naming a deployment this phone already holds
            // stops here, before createKey() can destroy the existing
            // pairing's Enclave key.
            val qrKey = projectKeyFor(parsed.project?.id, parsed.servers)
            val existing = store.find(qrKey)
            if (existing != null) {
                pendingReplace = parsed
                state = UiState.NeedsEnrollment(
                    confirmReplace = ReplacePrompt(
                        existingName = existing.displayName(),
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

    private suspend fun completeEnrollment(parsed: EnrollmentPayload) {
        val keyRef = projectKeyFor(parsed.project?.id, parsed.servers)
        val replacing = store.find(keyRef)

        val publicKey = try {
            withContext(Dispatchers.Default) {
                DeviceKeyStore(keyRef).createKey(UserAuthPolicy.REQUIRED_PER_USE)
            }
        } catch (e: Exception) {
            state = UiState.NeedsEnrollment(
                error = e.message ?: "could not create a device key",
                hasDeployments = hasDeployments(),
            )
            return
        }

        val client = StrazaClient(parsed.servers, parsed.pin)
        val deviceName = UIDevice.currentDevice.model

        when (val result = client.enroll(parsed.enrollToken, deviceName, publicKey)) {
            is ApiResult.Ok -> {
                val record = Enrollment(
                    approverDeviceId = result.value.approverDeviceId,
                    deviceToken = result.value.deviceToken,
                    servers = parsed.servers,
                    pin = parsed.pin,
                    deviceName = deviceName,
                    projectId = (result.value.project ?: parsed.project)?.id ?: "",
                    projectName = (result.value.project ?: parsed.project)?.name,
                    keyRef = keyRef,
                    tokenExpiresAtEpochSeconds = result.value.expiresInSeconds
                        .takeIf { it > 0 }?.let { nowEpochSeconds() + it },
                    vapidPublicKey = result.value.webpushVapidPublicKey,
                    fcm = result.value.fcm,
                )

                // The server's answer can name a different project than the
                // code did; it must not evict a deployment the user never saw named.
                val finalKey = projectKey(record)
                if (finalKey != keyRef) {
                    val collided = store.find(finalKey)
                    if (collided != null) {
                        DeviceKeyStore(keyRef).deleteKey()
                        state = UiState.NeedsEnrollment(
                            error = "The server identified itself as \"${collided.displayName()}\", " +
                                "a deployment this phone already holds under a different code. " +
                                "Nothing was changed. Ask for a freshly generated code.",
                            hasDeployments = hasDeployments(),
                        )
                        return
                    }
                }
                val superseded = store.find(finalKey)
                if (superseded != null && superseded.keyRef != record.keyRef) {
                    DeviceKeyStore(superseded.keyRef).deleteKey()
                }

                store.upsert(record)
                adopt(record)
                state = approvalsLoading()
                startPolling()
                startTicking()
                ensureNotificationAuthorization()
                registerApnsRoute()
            }

            else -> {
                DeviceKeyStore(keyRef).deleteKey()
                if (replacing != null && replacing.keyRef == keyRef) {
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
    }

    // Deployments

    override fun switchDeployment(projectId: String) {
        if (state !is UiState.Approvals) return
        if (enrollment?.let(::projectKey) == projectId) return
        store.setActive(projectId)
        val active = store.active() ?: return
        adopt(active)
        state = approvalsLoading()
        startPolling()
        startTicking()
        // The push route follows the active deployment. The previous
        // deployment keeps its route until retire() or its own prune removes it.
        registerApnsRoute()
    }

    override fun addDeployment() {
        if (state !is UiState.Approvals) return
        scope.launch {
            if (!requestCameraPermission()) {
                state = UiState.NeedsEnrollment(
                    error = "Camera access is needed to scan the enrollment code. " +
                        "You can enter the code manually instead.",
                    hasDeployments = true,
                )
                return@launch
            }
            state = UiState.NeedsEnrollment(scanning = true, hasDeployments = true)
        }
    }

    override fun removeDeployment() {
        val current = state as? UiState.Approvals ?: return
        if (current.busy) return
        val name = enrollment?.displayName() ?: return
        state = current.copy(confirmRemove = name)
    }

    override fun confirmRemoveDeployment() {
        val current = state as? UiState.Approvals ?: return
        val name = current.confirmRemove ?: return
        val record = enrollment ?: return
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

    override fun reEnroll() {
        val active = store.active()
        if (active == null) {
            clearSession()
            state = UiState.NeedsEnrollment(
                error = "Pair this device again to trust the server's new certificate.",
            )
            return
        }
        retire(
            active,
            whenGone = "Pair this device again to trust the server's new certificate.",
            whenSwitched = "Removed the deployment whose certificate changed. Scan its code to pair it again.",
        )
    }

    // Polling and tick

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val pause = pollBackoffUntilEpochSeconds - nowEpochSeconds()
                if (pause > 0) {
                    delay(minOf(pause * 1000, POLL_INTERVAL_MILLIS))
                    continue
                }
                refresh()
                if ((state as? UiState.Approvals)?.tab == ApprovalTab.Activity) refreshActivity()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** Wakes every second but publishes state only while [ticksEverySecond]
     *  says something on screen counts seconds, otherwise once a minute. */
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

    override fun refresh() {
        if (state !is UiState.Approvals) return
        val token = enrollment?.deviceToken ?: return
        val activeFlow = flow ?: return
        val boundEpoch = epoch

        scope.launch {
            val screen = activeFlow.refresh(token)
            if (epoch != boundEpoch) return@launch
            if (screen is ApprovalScreen.Revoked) {
                state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                return@launch
            }
            if (screen is ApprovalScreen.CannotReach) {
                screen.retryAfterSeconds?.let { pollBackoffUntilEpochSeconds = nowEpochSeconds() + it }
            }
            val latest = state as? UiState.Approvals ?: return@launch
            state = latest.copy(
                screen = screen,
                selected = latest.selected?.takeIf { open ->
                    screen is ApprovalScreen.Pending && screen.requests.any { it.id == open.id }
                },
            )
        }
    }

    private fun refreshActivity() {
        val token = enrollment?.deviceToken ?: return
        val activeFlow = flow ?: return
        val boundEpoch = epoch

        scope.launch {
            val screen = activeFlow.activity(token)
            if (epoch != boundEpoch) return@launch
            if (screen is ActivityScreen.Revoked) {
                state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                return@launch
            }
            val latest = state as? UiState.Approvals ?: return@launch
            state = latest.copy(activity = screen)
        }
    }

    // Deciding

    override fun select(request: PendingRequest?) {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(selected = request, notice = null)
    }

    override fun decide(request: PendingRequest, verdict: Verdict, reason: String?) {
        val current = state as? UiState.Approvals ?: return
        // Snapshot key, token and flow for the life of this decision, so a
        // deployment switch cannot make one deployment's key sign for another.
        val boundEnrollment = enrollment ?: return
        val activeFlow = flow ?: return
        val boundKeyStore = DeviceKeyStore(boundEnrollment.keyRef)
        val token = boundEnrollment.deviceToken
        val boundEpoch = epoch

        state = current.copy(busy = true, notice = null)

        scope.launch {
            val outcome = activeFlow.decide(token, request, verdict, reason) { message ->
                // SecKeyCreateSignature presents the Face ID / Touch ID prompt
                // and blocks its thread while the system UI runs, so it must
                // not run on main. A refused or failed authentication throws;
                // null submits nothing, because a cancelled prompt is not a deny.
                withContext(Dispatchers.Default) {
                    try {
                        boundKeyStore.sign(
                            message,
                            reason = (if (verdict == Verdict.APPROVE) "Approve this request: " else "Deny this request: ") +
                                request.toolTitle,
                        )
                    } catch (_: DeviceKeyStoreException) {
                        null
                    }
                }
            }

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

                // No key retirement here: a signing failure cannot be told from
                // a cancelled prompt, and keyState() cannot settle it. The way
                // out of a dead key is to remove the deployment and pair again.
                is DecisionOutcome.NotSigned ->
                    latest.copy(busy = false, notice = "Nothing was submitted (${outcome.reason}).")

                is DecisionOutcome.Failed ->
                    latest.copy(busy = false, notice = "Could not submit: ${outcome.reason}")

                is DecisionOutcome.Untrusted ->
                    latest.copy(
                        busy = false,
                        selected = null,
                        screen = ApprovalScreen.Untrusted(outcome.reason),
                    )

                DecisionOutcome.RenewNeeded ->
                    latest.copy(
                        busy = false,
                        selected = null,
                        screen = ApprovalScreen.RenewNeeded,
                    )

                DecisionOutcome.Revoked -> {
                    state = UiState.NeedsEnrollment(error = "This device's enrollment was revoked.")
                    return@launch
                }
            }
            refresh()
        }
    }

    override fun renewPairing() {
        val current = state as? UiState.Approvals ?: return
        if (current.busy) return
        val bound = enrollment ?: return
        val activeApi = api ?: return
        val boundKeyStore = DeviceKeyStore(bound.keyRef)
        val boundEpoch = epoch

        state = current.copy(busy = true, notice = null)

        scope.launch {
            val result = try {
                activeApi.renewToken(bound.approverDeviceId) { message ->
                    withContext(Dispatchers.Default) {
                        try {
                            boundKeyStore.sign(message, reason = "Renew the pairing with ${bound.displayName()}")
                        } catch (_: DeviceKeyStoreException) {
                            throw RenewCancelled
                        }
                    }
                }
            } catch (_: RenewCancelled) {
                if (epoch != boundEpoch) return@launch
                (state as? UiState.Approvals)?.let {
                    state = it.copy(busy = false, notice = "Nothing was renewed (not authenticated).")
                }
                return@launch
            }

            when (result) {
                is ApiResult.Ok -> {
                    // Write back against the currently stored record, and apply
                    // to the live session only if it still holds this
                    // deployment. adopt() bumps the epoch so polls with the old
                    // token are dropped; the push route is registered again.
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
                    registerApnsRoute()
                    refresh()
                }

                is ApiResult.Unauthorized -> {
                    // Only an explicit device_revoked destroys the key.
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

    // Tabs and feed

    override fun selectTab(tab: ApprovalTab) {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(tab = tab, notice = null)
        if (tab == ApprovalTab.Activity) refreshActivity()
    }

    override fun filterActivity(state: ResolvedState?) {
        val current = this.state as? UiState.Approvals ?: return
        this.state = current.copy(activityFilter = state)
    }

    override fun dismissNotice() {
        val current = state as? UiState.Approvals ?: return
        state = current.copy(notice = null)
    }

    // Push

    /**
     * Handles a push, which is untrusted third-party input: it may only
     * trigger a fetch of server state over the pinned channel, and nothing
     * from the payload is rendered. A tap also selects the tab, but only when
     * its `ref` passes the [BareUuid] check.
     */
    private fun onPushEnvelope(envelope: PushEnvelope, fromTap: Boolean) {
        if (state !is UiState.Approvals) return
        val fetch = envelope as? PushEnvelope.Fetch ?: return
        if (fromTap && BareUuid.isValid(fetch.ref)) {
            val tab = if (fetch.hint == PushEnvelope.Hint.Status) ApprovalTab.Activity else ApprovalTab.Decide
            (state as? UiState.Approvals)?.let { state = it.copy(tab = tab, notice = null) }
        }
        refresh()
        // A status push means a request resolved, so the Activity feed refreshes too.
        if (fetch.hint == PushEnvelope.Hint.Status) refreshActivity()
    }

    /**
     * Registers the APNs route with the active deployment: an idempotent
     * `PUT /v1/approver/push` with `kind: apns`. The token is a delivery
     * address, not an identity, and is registered whether or not
     * notifications are authorized.
     *
     * Do not remove or debounce the PUT on every launch. Each PUT refreshes
     * the server-side registration timestamp, which the sender's 410 prune
     * compares against Apple's invalidation time; a phone that re-registered
     * after an invalidation keeps its route only because of it.
     */
    private fun registerApnsRoute() {
        // The server learns the push token only after the user's in-app yes.
        // Every caller goes through this check.
        if (pushConsent != PushConsent.GRANTED) return
        val record = enrollment ?: return
        val activeApi = api ?: return
        val token = PushBridge.apnsToken.value ?: return
        val boundEpoch = epoch
        scope.launch {
            val outcome = PushRegistrar.register(
                activeApi, record.deviceToken, PushRoute(PushKind.APNS, token),
            )
            if (epoch != boundEpoch) return@launch
            pushStatus = PushRouteStatus.of(outcome.registered, outcome.result)
            refreshPushSettingsIfOpen()
        }
    }

    /**
     * Shows the system notification-permission prompt if it has never been
     * answered on this install. `.timeSensitive` is requested in the same
     * ask (iOS 15+). A declined prompt can only be reversed in Settings.
     */
    private fun ensureNotificationAuthorization() {
        // The OS permission prompt appears only after the user's in-app yes.
        if (pushConsent != PushConsent.GRANTED) return
        if (notificationAskFired) return
        notificationAskFired = true
        scope.launch {
            val settings = notificationSettings()
            if (settings?.authorizationStatus != UNAuthorizationStatusNotDetermined) return@launch
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or
                UNAuthorizationOptionBadge or UNAuthorizationOptionTimeSensitive
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(options) { _, _ ->
                    // The OS records the answer; it is read back on demand.
                }
        }
    }

    private suspend fun notificationSettings(): UNNotificationSettings? =
        suspendCancellableCoroutine { cont ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings ->
                    cont.resume(settings)
                }
        }

    override fun openNotificationSettings() {
        if (state !is UiState.Approvals) return
        scope.launch {
            val settings = buildPushSettings()
            (state as? UiState.Approvals)?.let {
                state = it.copy(pushSettings = settings, notice = null)
            }
        }
    }

    private suspend fun buildPushSettings(): PushSettingsUi = PushSettingsUi(
        fcm = FcmStatus.NOT_IN_BUILD,
        distributors = emptyList(),
        status = pushStatus,
        consent = pushConsent,
        transportPickerAvailable = false,
        // The off state has its own card on the shared screen.
        postureNote = if (pushConsent == PushConsent.GRANTED) {
            pushPosture(notificationSettings())
        } else {
            null
        },
    )

    private fun refreshPushSettingsIfOpen() {
        val current = state as? UiState.Approvals ?: return
        if (current.pushSettings == null) return
        scope.launch {
            val settings = buildPushSettings()
            (state as? UiState.Approvals)?.let {
                if (it.pushSettings != null) state = it.copy(pushSettings = settings)
            }
        }
    }

    override fun answerPushOptIn(choice: PushTransportPref) {
        // iOS stores no transport preference (Apple push is the only provider),
        // so the choice reduces to the consent it expresses.
        setPushEnabled(PushPrefs.consentFor(choice) == PushConsent.GRANTED)
    }

    override fun setPushEnabled(enabled: Boolean) {
        pushConsent = if (enabled) PushConsent.GRANTED else PushConsent.DECLINED
        (state as? UiState.Approvals)?.let { state = it.copy(askPushOptIn = null) }
        if (enabled) {
            // Registration is a no-op while the token is not there yet; the
            // token collector registers when it arrives.
            ensureNotificationAuthorization()
            registerApnsRoute()
        } else {
            // Best-effort DELETE of the server route; the sender's 404/410
            // prune covers a missed one.
            val record = enrollment
            val activeApi = api
            val token = PushBridge.apnsToken.value
            if (record != null && activeApi != null && token != null) {
                scope.launch {
                    activeApi.unregisterPush(record.deviceToken, PushKind.APNS, token)
                }
            }
            pushStatus = PushRouteStatus.None
        }
        refreshPushSettingsIfOpen()
    }

    private fun pushPosture(settings: UNNotificationSettings?): String {
        val status = settings?.authorizationStatus
        val authorized = status == UNAuthorizationStatusAuthorized ||
            status == UNAuthorizationStatusProvisional ||
            status == UNAuthorizationStatusEphemeral
        val base = when {
            status == UNAuthorizationStatusNotDetermined ->
                "The app will ask to send alerts. Until then it checks for new " +
                    "requests every few seconds while it is open."
            !authorized ->
                "Notifications are off for Straza in iOS Settings. Turn them on " +
                    "there to be alerted about new requests. While the app is " +
                    "open it still checks every few seconds."
            else -> when (val s = pushStatus) {
                is PushRouteStatus.Registered ->
                    "Push is set up. This phone is registered with the active " +
                        "deployment for alerts, and the app still checks every " +
                        "few seconds while it is open."
                is PushRouteStatus.Rejected ->
                    "The server refused this phone's push registration: " +
                        "\"${s.serverMessage}\". The app checks for new requests " +
                        "every few seconds while it is open."
                is PushRouteStatus.Unreached ->
                    "Push is not registered yet (${s.why}). It retries the next " +
                        "time the app opens; until then the app checks every few " +
                        "seconds while it is open."
                else -> {
                    val failure = PushBridge.tokenFailure.value
                    if (failure != null) {
                        "iOS did not issue a push token ($failure). The app " +
                            "retries automatically and checks every few seconds " +
                            "while it is open."
                    } else {
                        "Push is being set up. The app checks for new requests " +
                            "every few seconds while it is open."
                    }
                }
            }
        }
        val focus = if (authorized && settings?.timeSensitiveSetting == UNNotificationSettingDisabled) {
            " Time-sensitive alerts are off, so a Focus mode may delay approvals."
        } else {
            ""
        }
        return base + focus
    }

    // Activity export

    override fun exportActivity() {
        val records = store.loadAll()
        if (records.isEmpty()) return
        (state as? UiState.Approvals)?.let { state = it.copy(notice = "Exporting…") }
        // The export spans every deployment, each fetched over its own pinned
        // client. A deployment that fails is skipped and reported; it does not
        // go through the revoke path, which would destroy a signing key.
        scope.launch {
            val sections = mutableListOf<Pair<String, List<ResolvedRequest>>>()
            val failed = mutableListOf<String>()
            withContext(Dispatchers.Default) {
                for (record in records) {
                    // Walk the cursor to the end and stop only on an empty
                    // next_cursor: a filtered page can be short or empty and
                    // still carry one. 200 is the server's page-size ceiling;
                    // the page cap stops a runaway loop.
                    val client = StrazaClient(record.servers, record.pin)
                    val rows = mutableListOf<ResolvedRequest>()
                    var cursor: String? = null
                    var ok = true
                    var pages = 0
                    walk@ while (pages++ < 1000) {
                        when (val page = client.history(record.deviceToken, limit = 200, cursor = cursor)) {
                            is ApiResult.Ok -> {
                                rows += page.value.items
                                if (page.value.nextCursor.isEmpty()) break@walk
                                cursor = page.value.nextCursor
                            }
                            else -> { ok = false; break@walk }
                        }
                    }
                    if (ok) sections.add(record.displayName() to rows)
                    else failed.add(record.displayName())
                }
            }
            val latest = state as? UiState.Approvals
            if (sections.isEmpty()) {
                if (latest != null) state = latest.copy(notice = "Couldn't reach any deployment to export.")
                return@launch
            }
            val shared = ActivityShare.present(ActivityCsv.exportAll(sections))
            val note = when {
                !shared -> "Couldn't open the share sheet."
                failed.isEmpty() -> null
                else -> "Exported ${sections.size} of ${records.size} - couldn't reach: ${failed.joinToString(", ")}."
            }
            if (latest != null) state = latest.copy(notice = note)
        }
    }

    override fun closeNotificationSettings() {
        (state as? UiState.Approvals)?.let { state = it.copy(pushSettings = null) }
    }

    // No-ops: APNs is the only push system on iOS, so there is no picker.
    override fun setPushTransport(pref: PushTransportPref) = Unit
    override fun setPushDistributor(id: String) = Unit
    override fun getDistributorApp() = Unit

    // Platform helpers

    /** Checks for biometrics specifically: the key demands
     *  `.biometryCurrentSet`, so a passcode-only device would create a key
     *  that can never sign. */
    private fun canAuthenticate(): Boolean =
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)

    private suspend fun requestCameraPermission(): Boolean =
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { cont ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    cont.resume(granted)
                }
            }
            else -> false
        }

    private fun nowEpochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Unauthorized -> "The enrollment payload was rejected. Ask for a fresh one."
        is ApiResult.Unreachable ->
            "Could not reach Straza (${result.reason}). Check the address and the network."
        is ApiResult.Untrusted ->
            "Straza answered but presented a different certificate than this enrollment " +
                "code expects (${result.reason}). Ask for a freshly generated code."
        is ApiResult.ServerError -> "The server returned an error (${result.status})."
        is ApiResult.AlreadyResolved -> "Unexpected server response."
        is ApiResult.Ok -> "OK"
    }

    private object RenewCancelled : Exception() {
        private fun readResolve(): Any = RenewCancelled
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 15_000L
        const val TICK_INTERVAL_MILLIS = 1_000L
        const val QUIET_TICKS_PER_PUBLISH = 60
    }
}
