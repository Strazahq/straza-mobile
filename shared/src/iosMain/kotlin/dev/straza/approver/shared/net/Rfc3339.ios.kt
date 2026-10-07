package dev.straza.approver.shared.net

import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.timeIntervalSince1970

/**
 * `NSISO8601DateFormatter` does not auto-detect fractional seconds; they are a
 * format option. The server emits RFC3339Nano, which may or may not carry
 * them, so two formatters are tried. Both are thread-safe and created once.
 */
private val plainFormatter = NSISO8601DateFormatter().apply {
    formatOptions = NSISO8601DateFormatWithInternetDateTime
}
private val fractionalFormatter = NSISO8601DateFormatter().apply {
    formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
}

internal actual fun parseRfc3339EpochSeconds(raw: String): Long? {
    if (raw.isBlank()) return null
    val date = plainFormatter.dateFromString(raw) ?: fractionalFormatter.dateFromString(raw) ?: return null
    return date.timeIntervalSince1970.toLong()
}
