package dev.straza.approver.shared.flow

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

/** The offset is resolved at the stamped instant, not now, so a row from the
 *  other side of a DST change shows the wall time it happened at. */
internal actual fun localUtcOffsetSeconds(epochSeconds: Long): Int =
    NSTimeZone.localTimeZone
        .secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))
        .toInt()
