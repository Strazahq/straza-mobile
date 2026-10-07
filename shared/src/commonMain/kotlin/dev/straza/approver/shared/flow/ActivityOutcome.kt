package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState

/**
 * The outcome a resolved row reached, derived and not read off [ResolvedState]
 * alone: `state` stays `approved` after the approval is used (use is a
 * separate fact, `consumed_at`). A ticket's window is the grant the agent acts
 * within (openapi 0.25.0). A hold's window is the short retry window after the
 * decision in which the held call runs once (openapi 0.126.0). A server that
 * stamps no window leaves the approval plain. Same clock caveat as [Expiry].
 */
enum class ActivityOutcome {
    /** An approval the server stamped no use window on, ticket or hold. */
    APPROVED,

    /** Ticket approved and the agent used the grant. */
    USED,

    /** Ticket approved, grant window still open, not yet used. */
    GRANT_ACTIVE,

    /** Ticket approved but the grant window lapsed unused. */
    UNUSED,

    /** Hold approved and the held call ran, once. */
    RAN_ONCE,

    /** Hold approved, the agent's retry can still run it. */
    RUN_PENDING,

    /** Hold approved but the retry window closed with no run. */
    NOT_RUN,

    DENIED,
    EXPIRED,

    /** A state this build does not recognise. */
    UNKNOWN;

    companion object {
        fun of(row: ResolvedRequest, nowEpochSeconds: Long): ActivityOutcome = when (row.state) {
            ResolvedState.APPROVED -> {
                val used = row.consumedAtEpochSeconds != null
                val window = row.grantExpiresAtEpochSeconds
                when {
                    used -> if (row.isTicket) USED else RAN_ONCE
                    window == null -> APPROVED
                    window > nowEpochSeconds -> if (row.isTicket) GRANT_ACTIVE else RUN_PENDING
                    else -> if (row.isTicket) UNUSED else NOT_RUN
                }
            }
            ResolvedState.DENIED -> DENIED
            ResolvedState.EXPIRED -> EXPIRED
            ResolvedState.UNKNOWN -> UNKNOWN
        }
    }
}
