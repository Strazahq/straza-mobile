package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenewalNoticeTest {

    private val now = 1_789_000_000L
    private val day = 86_400L

    @Test
    fun `unknown deadline shows nothing`() {
        assertNull(RenewalNotices.of(null, now))
    }

    @Test
    fun `far from expiry shows nothing`() {
        assertNull(RenewalNotices.of(now + 30 * day, now))
        assertNull(RenewalNotices.of(now + RenewalNotices.WINDOW_SECONDS + 1, now))
    }

    @Test
    fun `inside the window names the days left`() {
        val three = assertNotNull(RenewalNotices.of(now + 3 * day, now))
        assertEquals(3, three.daysLeft)
        assertTrue(three.body.contains("3 days"), three.body)
        // The notice is still shown at the window edge, seven days out.
        val seven = assertNotNull(RenewalNotices.of(now + RenewalNotices.WINDOW_SECONDS, now))
        assertEquals(7, seven.daysLeft)
    }

    @Test
    fun `under a day says so without a number`() {
        val soon = assertNotNull(RenewalNotices.of(now + 3600, now))
        assertEquals(1, soon.daysLeft)
        assertTrue(soon.body.contains("within a day"), soon.body)
    }

    @Test
    fun `past the deadline shows nothing - the server's 401 owns that state`() {
        assertNull(RenewalNotices.of(now, now))
        assertNull(RenewalNotices.of(now - 1, now))
    }

    @Test
    fun `copy has no ai tells`() {
        val body = assertNotNull(RenewalNotices.of(now + 2 * day, now)).body
        for (ch in "\u2014\u2013\u2018\u2019\u201c\u201d") assertTrue(ch !in body, "banned char in copy")
    }
}
