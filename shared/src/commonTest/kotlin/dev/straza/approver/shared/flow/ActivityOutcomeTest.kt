package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1789000000L

class ActivityOutcomeTest {

    // The default row carries no class, which is what an older server sends
    // for a hold; a ticket always names its class on the wire.
    private fun row(
        state: ResolvedState = ResolvedState.APPROVED,
        isTicket: Boolean = false,
        grantExpiresAt: Long? = null,
        consumedAt: Long? = null,
    ) = ResolvedRequest(
        id = "apr_1", requester = "nova", toolLabel = "aws:op", ruleId = "r",
        state = state, decidedBy = "you", decidedAtEpochSeconds = NOW - 60,
        isTicket = isTicket,
        grantExpiresAtEpochSeconds = grantExpiresAt, consumedAtEpochSeconds = consumedAt,
    )

    @Test
    fun `an approval without a use window stays plain approved for both classes`() {
        assertEquals(ActivityOutcome.APPROVED, ActivityOutcome.of(row(), NOW))
        assertEquals(ActivityOutcome.APPROVED, ActivityOutcome.of(row(isTicket = true), NOW))
    }

    @Test
    fun `a used ticket is used whatever the grant clock says`() {
        assertEquals(ActivityOutcome.USED, ActivityOutcome.of(row(isTicket = true, grantExpiresAt = NOW + 600, consumedAt = NOW - 30), NOW))
        assertEquals(ActivityOutcome.USED, ActivityOutcome.of(row(isTicket = true, grantExpiresAt = NOW - 600, consumedAt = NOW - 30), NOW))
    }

    @Test
    fun `an open grant window not yet used is grant-active`() {
        assertEquals(ActivityOutcome.GRANT_ACTIVE, ActivityOutcome.of(row(isTicket = true, grantExpiresAt = NOW + 2520), NOW))
    }

    @Test
    fun `a lapsed grant window never used is unused`() {
        assertEquals(ActivityOutcome.UNUSED, ActivityOutcome.of(row(isTicket = true, grantExpiresAt = NOW - 3600), NOW))
    }

    @Test
    fun `a hold whose call ran is ran-once whatever the window says`() {
        assertEquals(ActivityOutcome.RAN_ONCE, ActivityOutcome.of(row(grantExpiresAt = NOW + 40, consumedAt = NOW - 5), NOW))
        assertEquals(ActivityOutcome.RAN_ONCE, ActivityOutcome.of(row(grantExpiresAt = NOW - 40, consumedAt = NOW - 50), NOW))
        assertEquals(ActivityOutcome.RAN_ONCE, ActivityOutcome.of(row(consumedAt = NOW - 5), NOW))
    }

    @Test
    fun `a hold inside its retry window not yet run is run-pending`() {
        assertEquals(ActivityOutcome.RUN_PENDING, ActivityOutcome.of(row(grantExpiresAt = NOW + 40), NOW))
    }

    @Test
    fun `a hold whose retry window closed with no run is not-run`() {
        assertEquals(ActivityOutcome.NOT_RUN, ActivityOutcome.of(row(grantExpiresAt = NOW - 1), NOW))
        assertEquals(ActivityOutcome.NOT_RUN, ActivityOutcome.of(row(grantExpiresAt = NOW), NOW))
    }

    @Test
    fun `denied and expired and unknown map straight through for both classes`() {
        for (ticket in listOf(false, true)) {
            assertEquals(ActivityOutcome.DENIED, ActivityOutcome.of(row(state = ResolvedState.DENIED, isTicket = ticket), NOW))
            assertEquals(ActivityOutcome.EXPIRED, ActivityOutcome.of(row(state = ResolvedState.EXPIRED, isTicket = ticket), NOW))
            assertEquals(ActivityOutcome.UNKNOWN, ActivityOutcome.of(row(state = ResolvedState.UNKNOWN, isTicket = ticket), NOW))
        }
    }
}
