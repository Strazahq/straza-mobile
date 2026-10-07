package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1789000000L

class ExpiryTest {

    @Test
    fun `an absent expiry is unknown rather than zero`() {
        assertNull(Expiry.secondsRemaining(null, NOW))
        assertNull(Expiry.label(null, NOW))
    }

    @Test
    fun `an absent expiry is not treated as expired`() {
        assertFalse(
            Expiry.isExpired(null, NOW),
            "a missing field must not hide a request the server still considers live",
        )
    }

    @Test
    fun `an absent expiry is never urgent`() {
        assertFalse(Expiry.isUrgent(null, NOW))
    }

    /** The server denies on timeout, so the tie does not go to showing an Approve button. */
    @Test
    fun `the instant of expiry counts as expired`() {
        assertTrue(Expiry.isExpired(NOW, NOW))
        assertEquals("expired", Expiry.label(NOW, NOW))
    }

    @Test
    fun `one second before expiry is still live`() {
        assertFalse(Expiry.isExpired(NOW + 1, NOW))
        assertEquals("1s left", Expiry.label(NOW + 1, NOW))
    }

    @Test
    fun `a passed deadline is expired`() {
        assertTrue(Expiry.isExpired(NOW - 1, NOW))
        assertEquals("expired", Expiry.label(NOW - 3600, NOW))
    }

    /** The server's default decision window is 90 s, so seconds stay visible under a minute. */
    @Test
    fun `under a minute shows seconds`() {
        assertEquals("45s left", Expiry.label(NOW + 45, NOW))
        assertEquals("59s left", Expiry.label(NOW + 59, NOW))
    }

    @Test
    fun `under an hour shows minutes and seconds`() {
        assertEquals("1m 0s left", Expiry.label(NOW + 60, NOW))
        assertEquals("4m 32s left", Expiry.label(NOW + 272, NOW))
    }

    @Test
    fun `an hour or more shows hours and minutes`() {
        assertEquals("1h 0m left", Expiry.label(NOW + 3600, NOW))
        assertEquals("2h 30m left", Expiry.label(NOW + 9000, NOW))
    }

    @Test
    fun `the last thirty seconds are urgent`() {
        assertTrue(Expiry.isUrgent(NOW + 30, NOW))
        assertTrue(Expiry.isUrgent(NOW + 1, NOW))
    }

    @Test
    fun `a comfortable window is not urgent`() {
        assertFalse(Expiry.isUrgent(NOW + 31, NOW))
    }

    @Test
    fun `an expired request is not urgent`() {
        assertFalse(Expiry.isUrgent(NOW, NOW))
        assertFalse(Expiry.isUrgent(NOW - 10, NOW))
    }

    @Test
    fun `coarse label renders the largest unit and never seconds`() {
        assertEquals("6d left", Expiry.coarseLabel(NOW + 6 * 86_400 + 4000, NOW))
        assertEquals("23h left", Expiry.coarseLabel(NOW + 23 * 3600, NOW))
        assertEquals("45m left", Expiry.coarseLabel(NOW + 45 * 60, NOW))
        assertEquals("1m left", Expiry.coarseLabel(NOW + 20, NOW)) // The final seconds floor to 1m.
        assertEquals("expired", Expiry.coarseLabel(NOW, NOW))
        assertNull(Expiry.coarseLabel(null, NOW), "no expiry ⇒ nothing to show")
    }

    @Test
    fun `a ticket is urgent only inside the final 24h and never red`() {
        assertTrue(Expiry.isTicketUrgent(NOW + 23 * 3600, NOW), "23h out ⇒ amber")
        assertTrue(Expiry.isTicketUrgent(NOW + 86_400, NOW), "exactly 24h ⇒ amber")
        assertFalse(Expiry.isTicketUrgent(NOW + 86_401, NOW), "just over 24h ⇒ calm")
        assertFalse(Expiry.isTicketUrgent(NOW + 6 * 86_400, NOW), "6 days out ⇒ calm")
        assertFalse(Expiry.isTicketUrgent(NOW, NOW), "already gone is not 'urgent'")
    }
}
