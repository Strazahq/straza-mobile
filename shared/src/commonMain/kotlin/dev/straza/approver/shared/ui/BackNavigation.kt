package dev.straza.approver.shared.ui

/**
 * One step of system-back navigation. The app's layers live in [UiState], not
 * in a platform back stack, so [backTarget] decides what one step out means and
 * [navigateBack] maps it onto the [AppActions] the on-screen controls call.
 *
 * [BackTarget.Block] holds the gesture while an operation is in flight, because
 * finishing the Activity would cancel its scope mid-submit. This protects the
 * UX only: the token is idempotent and the server fails closed. The first-start
 * push opt-in has no target: its one exit is answering, so back goes to the
 * system.
 */
enum class BackTarget {
    /** Consume the gesture and change nothing: an operation is in flight. */
    Block,
    CancelRemoveDeployment,
    ClosePushSettings,
    ClearSelectedRequest,
    CancelReplace,
    StopScan,
    BackToDeployments,
}

/** What one system-back step does in [state], or null to leave the gesture to the system. */
fun backTarget(state: UiState): BackTarget? = when (state) {
    is UiState.Approvals -> when {
        state.busy && (state.confirmRemove != null || state.selected != null) -> BackTarget.Block
        state.confirmRemove != null -> BackTarget.CancelRemoveDeployment
        state.pushSettings != null -> BackTarget.ClosePushSettings
        state.selected != null -> BackTarget.ClearSelectedRequest
        else -> null
    }
    is UiState.NeedsEnrollment -> when {
        state.busy -> BackTarget.Block
        state.confirmReplace != null -> BackTarget.CancelReplace
        state.scanning -> BackTarget.StopScan
        // The "add a deployment" landing screen. Its "Back to deployments"
        // button takes the same route (stopScan).
        state.hasDeployments -> BackTarget.BackToDeployments
        else -> null
    }
}

/**
 * Performs one back step for [state]. Returns false when there was nothing to
 * unwind, in which case the caller must let the system handle the gesture.
 */
fun AppActions.navigateBack(state: UiState): Boolean {
    when (backTarget(state) ?: return false) {
        BackTarget.Block -> Unit
        BackTarget.CancelRemoveDeployment -> cancelRemoveDeployment()
        BackTarget.ClosePushSettings -> closeNotificationSettings()
        BackTarget.ClearSelectedRequest -> select(null)
        BackTarget.CancelReplace -> cancelReplace()
        BackTarget.StopScan, BackTarget.BackToDeployments -> stopScan()
    }
    return true
}
