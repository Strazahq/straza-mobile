package dev.straza.approver.shared.ui

import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedState
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.push.FcmStatus
import dev.straza.approver.shared.push.PushSettingsUi
import dev.straza.approver.shared.push.PushTransportPref
import kotlin.test.Test
import kotlin.test.assertEquals

/** A null target leaves the back gesture to the system, which exits the app. */
class BackNavigationTest {

    private val request = PendingRequest("u1", "nova", "aws:x", "rule:y", "", "c")
    private val push = PushSettingsUi(fcm = FcmStatus.NOT_IN_BUILD, distributors = emptyList())

    private fun approvals(
        busy: Boolean = false,
        selected: PendingRequest? = null,
        pushSettings: PushSettingsUi? = null,
        askPushOptIn: PushSettingsUi? = null,
        confirmRemove: String? = null,
    ) = UiState.Approvals(
        screen = ApprovalScreen.Loading,
        busy = busy,
        selected = selected,
        pushSettings = pushSettings,
        askPushOptIn = askPushOptIn,
        confirmRemove = confirmRemove,
    )

    @Test
    fun rootListIsTheSystemsTurn() = assertEquals(null, backTarget(approvals()))

    @Test
    fun decisionScreenUnwindsToTheList() =
        assertEquals(BackTarget.ClearSelectedRequest, backTarget(approvals(selected = request)))

    @Test
    fun pushSettingsCloseBeforeTheSelectedRequest() =
        assertEquals(
            BackTarget.ClosePushSettings,
            backTarget(approvals(selected = request, pushSettings = push)),
        )

    @Test
    fun removeConfirmationOutranksEverything() =
        assertEquals(
            BackTarget.CancelRemoveDeployment,
            backTarget(approvals(selected = request, pushSettings = push, confirmRemove = "Mock")),
        )

    @Test
    fun busySigningBlocksTheGesture() =
        assertEquals(BackTarget.Block, backTarget(approvals(busy = true, selected = request)))

    @Test
    fun busyRemoveBlocksTheGesture() =
        assertEquals(BackTarget.Block, backTarget(approvals(busy = true, confirmRemove = "Mock")))

    @Test
    fun pushOptInAskIsNotDismissable() =
        assertEquals(null, backTarget(approvals(askPushOptIn = push)))

    @Test
    fun firstRunPairingScreenIsTheSystemsTurn() =
        assertEquals(null, backTarget(UiState.NeedsEnrollment()))

    @Test
    fun scannerUnwindsToThePairingScreen() =
        assertEquals(
            BackTarget.StopScan,
            backTarget(UiState.NeedsEnrollment(scanning = true, hasDeployments = true)),
        )

    @Test
    fun replacePromptOutranksTheScanner() =
        assertEquals(
            BackTarget.CancelReplace,
            backTarget(
                UiState.NeedsEnrollment(
                    scanning = true,
                    confirmReplace = ReplacePrompt("Old", "New"),
                ),
            ),
        )

    @Test
    fun addDeploymentScreenUnwindsToTheEnrolledList() =
        assertEquals(
            BackTarget.BackToDeployments,
            backTarget(UiState.NeedsEnrollment(hasDeployments = true)),
        )

    @Test
    fun busyEnrollmentBlocksTheGesture() =
        assertEquals(
            BackTarget.Block,
            backTarget(UiState.NeedsEnrollment(busy = true, hasDeployments = true)),
        )

    @Test
    fun navigateBackCallsTheSameActionTheScreenOffers() {
        val calls = mutableListOf<String>()
        val actions = RecordingActions(calls)
        assertEquals(true, actions.navigateBack(approvals(selected = request)))
        assertEquals(true, actions.navigateBack(approvals(pushSettings = push)))
        assertEquals(true, actions.navigateBack(approvals(confirmRemove = "Mock")))
        assertEquals(true, actions.navigateBack(UiState.NeedsEnrollment(scanning = true)))
        assertEquals(
            true,
            actions.navigateBack(
                UiState.NeedsEnrollment(confirmReplace = ReplacePrompt("Old", "New")),
            ),
        )
        assertEquals(false, actions.navigateBack(approvals()))
        assertEquals(
            listOf("select:null", "closeNotificationSettings", "cancelRemoveDeployment", "stopScan", "cancelReplace"),
            calls,
        )
    }

    @Test
    fun blockedGestureIsConsumedWithoutAnyAction() {
        val calls = mutableListOf<String>()
        val actions = RecordingActions(calls)
        assertEquals(true, actions.navigateBack(approvals(busy = true, selected = request)))
        assertEquals(emptyList(), calls)
    }
}

/** Records only the calls back navigation may make. Everything else is a no-op. */
private class RecordingActions(private val calls: MutableList<String>) : AppActions {
    override fun select(request: PendingRequest?) { calls += "select:${request?.id ?: "null"}" }
    override fun closeNotificationSettings() { calls += "closeNotificationSettings" }
    override fun cancelRemoveDeployment() { calls += "cancelRemoveDeployment" }
    override fun stopScan() { calls += "stopScan" }
    override fun cancelReplace() { calls += "cancelReplace" }

    override fun enroll(qrPayload: String) = Unit
    override fun startScan() = Unit
    override fun enterCodeManually() = Unit
    override fun confirmReplace() = Unit
    override fun refresh() = Unit
    override fun selectTab(tab: ApprovalTab) = Unit
    override fun filterActivity(state: ResolvedState?) = Unit
    override fun exportActivity() = Unit
    override fun decide(request: PendingRequest, verdict: Verdict, reason: String?) = Unit
    override fun dismissNotice() = Unit
    override fun renewPairing() = Unit
    override fun setPushEnabled(enabled: Boolean) = Unit
    override fun answerPushOptIn(choice: PushTransportPref) = Unit
    override fun openNotificationSettings() = Unit
    override fun setPushTransport(pref: PushTransportPref) = Unit
    override fun setPushDistributor(id: String) = Unit
    override fun getDistributorApp() = Unit
    override fun switchDeployment(projectId: String) = Unit
    override fun addDeployment() = Unit
    override fun removeDeployment() = Unit
    override fun confirmRemoveDeployment() = Unit
    override fun reEnroll() = Unit
}
