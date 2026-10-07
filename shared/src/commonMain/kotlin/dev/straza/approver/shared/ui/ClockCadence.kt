package dev.straza.approver.shared.ui

import dev.straza.approver.shared.flow.ActivityOutcome
import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalScreen

/**
 * Whether the clock the platform publishes into
 * [UiState.Approvals.nowEpochSeconds] must move every second. Otherwise one
 * publish a minute is enough.
 *
 * Seconds matter while a hold is pending or open on the decision screen, and
 * while the Activity tab shows an approved hold still inside its retry window.
 * That window is judged at the published time, so the row gets the publish
 * that flips it. Tickets render minute-grained. An unknown class counts as a
 * hold, as in PendingQueue.
 */
fun ticksEverySecond(state: UiState.Approvals): Boolean {
    if ((state.screen as? ApprovalScreen.Pending)?.requests?.any { !it.isTicket } == true) return true
    if (state.selected?.isTicket == false) return true
    if (state.tab != ApprovalTab.Activity) return false
    val rows = (state.activity as? ActivityScreen.Resolved)?.items ?: return false
    return rows.any { ActivityOutcome.of(it, state.nowEpochSeconds) == ActivityOutcome.RUN_PENDING }
}
