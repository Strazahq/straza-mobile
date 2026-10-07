package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.PendingRequest

/**
 * The decidable queue split by approval class: blocking first, tickets
 * second. A blocking request is a hold: an agent is stopped mid-call and the
 * window closes in seconds (at zero the server denies). A ticket is day-scale
 * and no agent is waiting.
 *
 * Server order is preserved within each lane. Sorting by expiry would rank
 * rows by `expires_at` against the device clock, which is untrusted (see
 * [Expiry]), so a wrong clock would reorder the queue.
 */
data class PendingQueue(
    val blocking: List<PendingRequest>,
    val tickets: List<PendingRequest>,
) {
    val isEmpty: Boolean get() = blocking.isEmpty() && tickets.isEmpty()

    companion object {
        /**
         * Splits a `scope=decidable` list by class. [PendingRequest.isTicket]
         * is false for an absent or unknown wire `class`, so an older server,
         * or a class this build does not recognise, lands in [blocking], where
         * a real block cannot expire unnoticed.
         */
        fun of(requests: List<PendingRequest>): PendingQueue {
            val (tickets, blocking) = requests.partition { it.isTicket }
            return PendingQueue(blocking = blocking, tickets = tickets)
        }
    }
}
