package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The expected values come from GNU coreutils:
 * `TZ=<zone> date -d @<epoch> '+%Y-%m-%d %H:%M:%S %:z'`.
 */
class AuditStampTest {

    @Test
    fun `epoch zero at utc`() {
        assertEquals("1970-01-01 00:00:00 +00:00", AuditStamp.format(0, 0))
    }

    @Test
    fun `utc renders with the plus sign like the browser`() {
        assertEquals("2025-12-25 00:00:00 +00:00", AuditStamp.format(1_766_620_800, 0))
        assertEquals("2000-01-01 00:00:00 +00:00", AuditStamp.format(946_684_800, 0))
    }

    @Test
    fun `positive whole hour offset`() {
        assertEquals("2025-08-18 16:32:07 +02:00", AuditStamp.format(1_755_527_527, 7_200))
    }

    @Test
    fun `half hour offsets carry their minutes`() {
        assertEquals("2025-08-18 20:02:07 +05:30", AuditStamp.format(1_755_527_527, 19_800))
        assertEquals("2025-08-18 05:02:07 -09:30", AuditStamp.format(1_755_527_527, -34_200))
    }

    @Test
    fun `negative offset crosses back over a day`() {
        assertEquals("1969-12-31 18:00:00 -07:00", AuditStamp.format(3_600, -25_200))
    }

    @Test
    fun `leap day renders`() {
        assertEquals("2024-02-28 23:59:59 +00:00", AuditStamp.format(1_709_164_799, 0))
        assertEquals("2024-02-29 07:00:00 -05:00", AuditStamp.format(1_709_208_000, -18_000))
    }

    @Test
    fun `year boundary crosses forward`() {
        assertEquals("2025-01-01 12:59:59 +13:00", AuditStamp.format(1_735_689_599, 46_800))
    }

    @Test
    fun `null epoch yields null so the caller renders nothing`() {
        assertNull(AuditStamp.local(null))
    }
}
