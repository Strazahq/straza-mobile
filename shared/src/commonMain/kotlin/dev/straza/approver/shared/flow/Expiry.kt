package dev.straza.approver.shared.flow

/**
 * How long a pending request has left. The server denies on timeout, so the
 * UI shows the window closing.
 *
 * `expiresAt` is the server's clock and `now` the device's. A wrong device
 * clock renders a wrong countdown but cannot grant anything: a slow clock
 * shows time remaining on an expired request (decide then answers 409), and a
 * fast clock hides the actions early. The server remains the only authority.
 */
object Expiry {

    /**
     * Seconds until [expiresAtEpochSeconds], negative once past, or null when
     * the server sent no expiry. Null means unknown: the caller renders nothing.
     */
    fun secondsRemaining(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): Long? =
        expiresAtEpochSeconds?.minus(nowEpochSeconds)

    /**
     * Whether the window has closed. An unknown expiry is not expired:
     * otherwise a missing field would hide every actionable request. The
     * server denies on timeout whatever this app renders.
     */
    fun isExpired(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val remaining = secondsRemaining(expiresAtEpochSeconds, nowEpochSeconds) ?: return false
        return remaining <= 0
    }

    /**
     * Short label such as `"4m 32s left"`, `"18s left"` or `"expired"`, or null
     * when there is no expiry to show. Seconds stay visible below a minute
     * because the server's default timeout is short.
     */
    fun label(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): String? {
        val remaining = secondsRemaining(expiresAtEpochSeconds, nowEpochSeconds) ?: return null
        if (remaining <= 0) return "expired"

        val hours = remaining / 3600
        val minutes = (remaining % 3600) / 60
        val seconds = remaining % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m left"
            minutes > 0 -> "${minutes}m ${seconds}s left"
            else -> "${seconds}s left"
        }
    }

    /** Whether the countdown should read as urgent. Only colours the label; it gates no action. */
    fun isUrgent(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val remaining = secondsRemaining(expiresAtEpochSeconds, nowEpochSeconds) ?: return false
        return remaining in 1..URGENT_THRESHOLD_SECONDS
    }

    /**
     * Coarse label for a ticket's decision window: `"6d left"`, `"23h left"`,
     * `"45m left"`, `"expired"`. Unlike [label] it shows no seconds, because a
     * ticket window lasts hours to days (`ticketTTL` up to 30d).
     */
    fun coarseLabel(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): String? {
        val remaining = secondsRemaining(expiresAtEpochSeconds, nowEpochSeconds) ?: return null
        if (remaining <= 0) return "expired"
        val days = remaining / 86_400
        val hours = remaining / 3600
        val minutes = remaining / 60
        return when {
            days > 0 -> "${days}d left"
            hours > 0 -> "${hours}h left"
            else -> "${minutes.coerceAtLeast(1)}m left"
        }
    }

    /** Ticket urgency: amber inside the final 24 hours. Not red, which is reserved for a denial. */
    fun isTicketUrgent(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val remaining = secondsRemaining(expiresAtEpochSeconds, nowEpochSeconds) ?: return false
        return remaining in 1..TICKET_URGENT_THRESHOLD_SECONDS
    }

    private const val URGENT_THRESHOLD_SECONDS = 30L
    private const val TICKET_URGENT_THRESHOLD_SECONDS = 86_400L // 24h
}
