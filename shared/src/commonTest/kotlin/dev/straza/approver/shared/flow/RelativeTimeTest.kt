package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val NOW = 1789000000L

class RelativeTimeTest {

    @Test
    fun `a null timestamp renders nothing`() {
        assertNull(RelativeTime.label(null, NOW))
    }

    @Test
    fun `seconds ago reads as just now`() {
        assertEquals("just now", RelativeTime.label(NOW - 5, NOW))
        assertEquals("just now", RelativeTime.label(NOW - 44, NOW))
    }

    /** A server clock slightly ahead of the phone puts `then` in the future. */
    @Test
    fun `a future timestamp from clock skew reads as just now`() {
        assertEquals("just now", RelativeTime.label(NOW + 30, NOW))
    }

    @Test
    fun `minutes ago`() {
        assertEquals("1m ago", RelativeTime.label(NOW - 60, NOW))
        assertEquals("45m ago", RelativeTime.label(NOW - 45 * 60, NOW))
        assertEquals("59m ago", RelativeTime.label(NOW - 59 * 60, NOW))
    }

    @Test
    fun `hours ago`() {
        assertEquals("1h ago", RelativeTime.label(NOW - 3600, NOW))
        assertEquals("23h ago", RelativeTime.label(NOW - 23 * 3600, NOW))
    }

    @Test
    fun `yesterday is spelled out`() {
        assertEquals("yesterday", RelativeTime.label(NOW - 25 * 3600, NOW))
    }

    @Test
    fun `days ago`() {
        assertEquals("2d ago", RelativeTime.label(NOW - 2 * 86_400, NOW))
        assertEquals("30d ago", RelativeTime.label(NOW - 30 * 86_400, NOW))
    }
}
