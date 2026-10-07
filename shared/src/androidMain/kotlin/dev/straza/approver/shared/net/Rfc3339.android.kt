package dev.straza.approver.shared.net

import java.time.Instant
import java.time.format.DateTimeParseException

/** `java.time` needs no desugaring here: it is API 26+ and minSdk is 28. */
internal actual fun parseRfc3339EpochSeconds(raw: String): Long? {
    if (raw.isBlank()) return null
    return try {
        Instant.parse(raw).epochSecond
    } catch (_: DateTimeParseException) {
        null
    }
}
