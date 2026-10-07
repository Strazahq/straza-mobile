package dev.straza.approver.shared.flow

/**
 * The pre-expiry nudge: the enrollment stores when its token expires, so the
 * app can offer renewal before the server's 401 blocks the Decide tab. The
 * deadline was computed from the phone's clock at renewal time, so a skewed
 * phone shows the nudge early or late; the nudge has no destructive action.
 * Once the deadline has passed nothing is shown, and the server's
 * `token_expired` drives [ApprovalScreen.RenewNeeded].
 */
data class RenewalNotice(val daysLeft: Int, val body: String)

object RenewalNotices {

    /** How long before expiry the nudge appears. */
    const val WINDOW_SECONDS: Long = 7L * 24 * 3600

    fun of(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): RenewalNotice? {
        val expiresAt = expiresAtEpochSeconds ?: return null
        val remaining = expiresAt - nowEpochSeconds
        if (remaining <= 0 || remaining > WINDOW_SECONDS) return null
        val days = ((remaining + 86_399) / 86_400).toInt()
        val body = if (days <= 1) {
            "This phone's pairing expires within a day. Renew it now with one fingerprint or " +
                "screen-lock check. No new code is needed."
        } else {
            "This phone's pairing expires in $days days. Renew it now with one fingerprint or " +
                "screen-lock check. No new code is needed."
        }
        return RenewalNotice(days, body)
    }
}
