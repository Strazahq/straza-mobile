package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.PendingRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun row(id: String, isTicket: Boolean = false) = PendingRequest(
    id = id,
    requester = "nova",
    toolLabel = "aws:rotate_key",
    ruleId = "rule:iam.write",
    justification = "",
    challenge = "c-$id",
    expiresAtEpochSeconds = null,
    isTicket = isTicket,
)

class PendingQueueTest {

    @Test
    fun `holds and tickets land in their own lanes`() {
        val queue = PendingQueue.of(listOf(row("h1"), row("t1", isTicket = true), row("h2")))

        assertEquals(listOf("h1", "h2"), queue.blocking.map { it.id })
        assertEquals(listOf("t1"), queue.tickets.map { it.id })
    }

    /** Re-sorting here would rank rows by the device clock, which is untrusted. */
    @Test
    fun `server order survives inside each lane`() {
        val queue = PendingQueue.of(
            listOf(
                row("t1", isTicket = true),
                row("h1"),
                row("t2", isTicket = true),
                row("h2"),
                row("t3", isTicket = true),
            ),
        )

        assertEquals(listOf("h1", "h2"), queue.blocking.map { it.id }, "holds keep the order the server sent")
        assertEquals(listOf("t1", "t2", "t3"), queue.tickets.map { it.id }, "tickets keep the order the server sent")
    }

    /** An absent or unrecognised wire `class` parses to `isTicket = false`. */
    @Test
    fun `an unknown class is treated as blocking rather than as a ticket`() {
        val queue = PendingQueue.of(listOf(PendingRequest("u1", "nova", "aws:x", "rule:y", "", "c")))

        assertEquals(listOf("u1"), queue.blocking.map { it.id })
        assertTrue(queue.tickets.isEmpty())
    }

    @Test
    fun `empty means nothing decidable rather than nothing blocking`() {
        assertTrue(PendingQueue.of(emptyList()).isEmpty)

        val ticketsOnly = PendingQueue.of(listOf(row("t1", isTicket = true)))
        assertFalse(ticketsOnly.isEmpty, "a ticket-only queue still has work in it")
        assertTrue(ticketsOnly.blocking.isEmpty())
    }

    @Test
    fun `a queue of only holds leaves the ticket lane empty`() {
        val queue = PendingQueue.of(listOf(row("h1"), row("h2")))

        assertEquals(2, queue.blocking.size)
        assertTrue(queue.tickets.isEmpty())
        assertFalse(queue.isEmpty)
    }
}
