package dev.straza.approver.shared.ui

import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1789000000L

class ClockCadenceTest {

    private val hold = PendingRequest("h1", "nova", "aws:x", "rule:y", "", "c")
    private val ticket = PendingRequest("t1", "nova", "gh:y", "rule:z", "", "c", isTicket = true)

    private fun resolved(isTicket: Boolean = false, grantExpiresAt: Long? = null, consumedAt: Long? = null) =
        ResolvedRequest(
            id = "apr_1", requester = "nova", toolLabel = "aws:op", ruleId = "r",
            state = ResolvedState.APPROVED, decidedBy = "you", decidedAtEpochSeconds = NOW - 20,
            isTicket = isTicket,
            grantExpiresAtEpochSeconds = grantExpiresAt, consumedAtEpochSeconds = consumedAt,
        )

    private fun approvals(
        pending: List<PendingRequest>? = null,
        selected: PendingRequest? = null,
        tab: ApprovalTab = ApprovalTab.Decide,
        activity: ActivityScreen = ActivityScreen.Loading,
    ) = UiState.Approvals(
        screen = pending?.let { ApprovalScreen.Pending(it) } ?: ApprovalScreen.Loading,
        selected = selected,
        tab = tab,
        activity = activity,
        nowEpochSeconds = NOW,
    )

    @Test
    fun `a pending hold ticks every second`() =
        assertTrue(ticksEverySecond(approvals(pending = listOf(ticket, hold))))

    @Test
    fun `a queue of tickets alone is minute-grained`() =
        assertFalse(ticksEverySecond(approvals(pending = listOf(ticket))))

    @Test
    fun `a hold open on the decision screen ticks every second`() =
        assertTrue(ticksEverySecond(approvals(selected = hold)))

    @Test
    fun `a ticket open on the decision screen is minute-grained`() =
        assertFalse(ticksEverySecond(approvals(selected = ticket)))

    @Test
    fun `an approved hold inside its retry window ticks while Activity shows it`() =
        assertTrue(
            ticksEverySecond(
                approvals(
                    pending = emptyList(),
                    tab = ApprovalTab.Activity,
                    activity = ActivityScreen.Resolved(listOf(resolved(grantExpiresAt = NOW + 40))),
                ),
            ),
        )

    @Test
    fun `the same row leaves the clock minute-grained while the Decide tab is up`() =
        assertFalse(
            ticksEverySecond(
                approvals(
                    pending = emptyList(),
                    activity = ActivityScreen.Resolved(listOf(resolved(grantExpiresAt = NOW + 40))),
                ),
            ),
        )

    @Test
    fun `a window already closed at the published time no longer needs seconds`() =
        assertFalse(
            ticksEverySecond(
                approvals(
                    tab = ApprovalTab.Activity,
                    activity = ActivityScreen.Resolved(listOf(resolved(grantExpiresAt = NOW))),
                ),
            ),
        )

    @Test
    fun `a hold that ran and an open ticket grant are minute-grained`() =
        assertFalse(
            ticksEverySecond(
                approvals(
                    tab = ApprovalTab.Activity,
                    activity = ActivityScreen.Resolved(
                        listOf(
                            resolved(grantExpiresAt = NOW + 40, consumedAt = NOW - 5),
                            resolved(isTicket = true, grantExpiresAt = NOW + 2520),
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `an Activity feed still loading is minute-grained`() =
        assertFalse(ticksEverySecond(approvals(tab = ApprovalTab.Activity)))
}
