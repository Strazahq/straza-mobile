package dev.straza.approver.shared.flow

/**
 * Approximate age labels for the resolved feed: "2m ago", "yesterday",
 * "3d ago". `then` is the server's timestamp and `now` the device's, so a
 * wrong phone clock skews the label; the feed is read-only, so nothing
 * actionable depends on it. A timestamp in the future collapses to "just now".
 */
object RelativeTime {

    /** Null [thenEpochSeconds] yields null: the caller renders nothing, not "now". */
    fun label(thenEpochSeconds: Long?, nowEpochSeconds: Long): String? {
        if (thenEpochSeconds == null) return null
        val delta = nowEpochSeconds - thenEpochSeconds

        // Server slightly ahead of the phone, or this instant.
        if (delta < JUST_NOW_SECONDS) return "just now"

        val minutes = delta / 60
        if (minutes < 60) return "${minutes}m ago"

        val hours = delta / 3600
        if (hours < 24) return "${hours}h ago"

        val days = delta / 86_400
        if (days == 1L) return "yesterday"
        return "${days}d ago"
    }

    private const val JUST_NOW_SECONDS = 45L
}
