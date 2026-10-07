package dev.straza.approver.shared.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushNotificationTest {

    @Test
    fun `each hint maps to distinct opaque text`() {
        val decide = PushNotification.forHint(PushEnvelope.Hint.Decide)
        val status = PushNotification.forHint(PushEnvelope.Hint.Status)
        val unknown = PushNotification.forHint(PushEnvelope.Hint.Unknown)

        assertEquals("Approval needed", decide.title)
        assertEquals("Request updated", status.title)
        assertTrue(decide.body != status.body)
        assertTrue(unknown.body.isNotBlank())
    }

    /** A notification carries no request detail: the mapper takes a hint and nothing else. */
    @Test
    fun `notification text never carries a reference or tool identity`() {
        for (hint in PushEnvelope.Hint.entries) {
            val n = PushNotification.forHint(hint)
            val text = "${n.title} ${n.body}".lowercase()
            listOf("apr_", "ref", "midpoint", "disable_user", "mcp.call").forEach { leak ->
                assertTrue(leak !in text, "notification for $hint leaked '$leak': $text")
            }
        }
    }
}
