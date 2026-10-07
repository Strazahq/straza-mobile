package dev.straza.approver.shared.ui

import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedState
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.push.PushSettingsUi
import dev.straza.approver.shared.push.PushTransportPref

/** A deployment in the switcher. */
data class DeploymentSummary(val projectId: String, val name: String)

/**
 * A scanned code claims the identity of a deployment this phone already holds.
 * [existingName] is the pairing that would be destroyed. [claimedName] is the
 * new code's project name, or the URL it points at when it carries none.
 */
data class ReplacePrompt(val existingName: String, val claimedName: String)

/** The two tabs of the enrolled app. */
enum class ApprovalTab {
    /** Requests this device may decide. */
    Decide,

    /** Read-only feed of resolved requests. */
    Activity,
}

/** Everything the UI renders. Platform-free so the screens stay shared. */
sealed interface UiState {

    data class NeedsEnrollment(
        val busy: Boolean = false,
        val error: String? = null,
        /** The camera viewfinder is open. */
        val scanning: Boolean = false,
        /**
         * Set when the scanned code names a deployment this phone already holds.
         * Enrolling would destroy that pairing's key, so the user must confirm.
         */
        val confirmReplace: ReplacePrompt? = null,
        /** Other deployments remain enrolled, so the screen offers a route back to them. */
        val hasDeployments: Boolean = false,
    ) : UiState

    data class Approvals(
        val screen: ApprovalScreen,
        val deploymentName: String? = null,
        /** All enrolled deployments, for the switcher; the active one is [activeProjectId]. */
        val deployments: List<DeploymentSummary> = emptyList(),
        val activeProjectId: String? = null,
        val tab: ApprovalTab = ApprovalTab.Decide,
        val activity: ActivityScreen = ActivityScreen.Loading,
        /** Client-side outcome filter on the Activity feed. Null shows everything. */
        val activityFilter: ResolvedState? = null,
        /** Non-null when the detail sheet is open. */
        val selected: PendingRequest? = null,
        /** Non-null when the Push service settings screen is open. */
        val pushSettings: PushSettingsUi? = null,
        /**
         * Non-null while the first-start push provider choice is unanswered. The
         * screen's one exit is [AppActions.answerPushOptIn]. Until then the
         * transports resolve to polling and no OS permission prompt fires.
         */
        val askPushOptIn: PushSettingsUi? = null,
        /**
         * The display name for the remove-pairing confirmation of the active
         * deployment, or null when it is closed. Removing a pairing destroys its
         * signing key on this phone, so the user must confirm.
         */
        val confirmRemove: String? = null,
        /** A decision is being signed or submitted. Every action is disabled. */
        val busy: Boolean = false,
        /** Transient message shown as the result of an action. */
        val notice: String? = null,
        /**
         * The active enrollment's approver_device_id. The Activity feed shows
         * "this phone" on a row whose `decided_via.device_id` equals it.
         */
        val thisDeviceId: String? = null,
        /** When the active deployment's token expires, or null. Drives the pre-expiry nudge. */
        val tokenExpiresAtEpochSeconds: Long? = null,
        /** The platform's clock, pushed in so countdowns tick. Shared UI has no clock of its own. */
        val nowEpochSeconds: Long = 0L,
    ) : UiState
}

/** What the UI can ask the platform layer to do. */
interface AppActions {
    fun enroll(qrPayload: String)

    /**
     * Opens the QR viewfinder, asking for camera permission if it is not held.
     * The platform scanner hands a decoded code to [enroll] as untrusted input.
     */
    fun startScan()

    /** Closes the viewfinder and releases the camera. */
    fun stopScan()

    /**
     * Leaves the viewfinder for the pairing screen's manual entry. A typed code
     * meets the same parser and the same replace confirmation as a scanned one.
     */
    fun enterCodeManually()

    /**
     * Proceeds with the enrollment that replaces the pairing named in
     * [UiState.NeedsEnrollment.confirmReplace]. Called only from that prompt.
     */
    fun confirmReplace()

    /** Abandons a pending replace and returns to the deployment list. */
    fun cancelReplace()

    fun refresh()

    /** Switches tabs. Selecting Activity triggers an immediate fetch. */
    fun selectTab(tab: ApprovalTab)

    /** Filters the Activity feed by outcome; null shows all. */
    fun filterActivity(state: ResolvedState?)

    /**
     * Exports a CSV snapshot of the resolved feed through the platform share
     * sheet: all rows, ignoring the active filter. The copy carries only what
     * is already on screen, and the agent's reason stays labelled unverified.
     */
    fun exportActivity()

    fun select(request: PendingRequest?)

    /**
     * @param reason the decider's own words from the confirm step, or null.
     *   Passed raw: the flow normalizes it once (trim, empty means none), so
     *   the signed message and the stored record carry the same words.
     */
    fun decide(request: PendingRequest, verdict: Verdict, reason: String? = null)
    fun dismissNotice()

    /** Renews the active pairing's token with a device-key-signed refresh, without a re-scan. */
    fun renewPairing()

    /**
     * The push master switch. Enabling asks for the OS notification permission
     * and registers the route with the active deployment. Disabling deletes the
     * server route and falls back to polling. The answer persists per device.
     */
    fun setPushEnabled(enabled: Boolean)

    /**
     * Answers the first-start provider choice and clears
     * [UiState.Approvals.askPushOptIn]. Any provider grants push consent: the
     * OS notification ask fires and the chosen lane registers. Polling declines
     * and tears down any standing route. A declined choice persists as
     * poll-only, so a later master-switch enable cannot register a provider the
     * user did not pick.
     */
    fun answerPushOptIn(choice: PushTransportPref)

    /** Opens the Push service settings screen. */
    fun openNotificationSettings()

    fun closeNotificationSettings()

    /**
     * Applies a push transport choice. The platform registers the new lane,
     * then deletes the old one. A choice that is unavailable on this device
     * resolves to polling, not to a transport the user did not pick.
     */
    fun setPushTransport(pref: PushTransportPref)

    /** Picks which installed UnifiedPush distributor app delivers the push. */
    fun setPushDistributor(id: String)

    /**
     * Opens the suggested distributor's page (ntfy) in the browser. Shown when
     * UnifiedPush is wanted but no distributor app is installed.
     */
    fun getDistributorApp()

    fun switchDeployment(projectId: String)

    /** Opens the scanner from the enrolled state to pair another deployment. */
    fun addDeployment()

    /** Opens the remove-pairing confirmation for the active deployment. */
    fun removeDeployment()

    /**
     * Removes the active pairing. The push route and the server enrollment row
     * are torn down best-effort (`DELETE /v1/approver/enrollment`, openapi
     * 0.66.0; an older server keeps the row, inert without the key). Then the
     * hardware signing key and the stored enrollment are destroyed locally.
     * This fails closed: requests the phone cannot sign time out into denial.
     * Called only from the user's choice on the confirmation screen.
     */
    fun confirmRemoveDeployment()

    /** Abandons the remove prompt and returns to the approvals list. */
    fun cancelRemoveDeployment()

    /**
     * Discards this enrollment and returns to the enrollment screen, so a
     * device whose pinned certificate has stopped matching can be paired again.
     * This must stay a user action. A network attacker can force
     * [dev.straza.approver.shared.flow.ApprovalScreen.Untrusted] at will, so
     * wiping automatically would let them destroy enrollments. Polling
     * continues under that screen, so a transient interception clears itself.
     */
    fun reEnroll()
}
