package dev.straza.approver.shared.flow

import java.time.Instant
import java.time.ZoneId

/** The offset is resolved at the stamped instant, not now, so a row from the
 *  other side of a DST change shows the wall time it happened at. */
internal actual fun localUtcOffsetSeconds(epochSeconds: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(epochSeconds)).totalSeconds
