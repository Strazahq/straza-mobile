package dev.straza.approver.shared.flow

/**
 * The device's civil-time offset from UTC at [epochSeconds], in seconds.
 * Resolved by the platform (java.util.TimeZone / NSTimeZone), which owns the
 * zone database.
 */
internal expect fun localUtcOffsetSeconds(epochSeconds: Long): Int

/**
 * Formats an instant as "2025-08-18 16:32:07 +02:00": local wall time plus the
 * UTC offset, as on the server's approvals page. The offset is resolved at the
 * stamped instant, not now, so a decision from before a DST change shows the
 * wall time it happened at.
 */
object AuditStamp {

    /** Null [epochSeconds] yields null: the caller renders nothing, not "now". */
    fun local(epochSeconds: Long?): String? =
        epochSeconds?.let { format(it, localUtcOffsetSeconds(it)) }

    fun format(epochSeconds: Long, offsetSeconds: Int): String {
        val shifted = epochSeconds + offsetSeconds
        val days = shifted.floorDiv(86_400L)
        val secondOfDay = shifted.mod(86_400L).toInt()

        // Civil date from day count (Howard Hinnant's civil_from_days),
        // integer arithmetic only.
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0

        val offsetMinutes = kotlin.math.abs(offsetSeconds) / 60
        return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) + " " +
            pad(secondOfDay / 3_600L, 2) + ":" + pad((secondOfDay % 3_600L) / 60, 2) + ":" + pad(secondOfDay % 60L, 2) + " " +
            (if (offsetSeconds < 0) "-" else "+") + pad(offsetMinutes / 60L, 2) + ":" + pad(offsetMinutes % 60L, 2)
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private fun pad(value: Int, width: Int): String = pad(value.toLong(), width)
}
