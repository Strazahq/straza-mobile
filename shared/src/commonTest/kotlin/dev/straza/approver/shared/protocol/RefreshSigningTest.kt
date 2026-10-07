package dev.straza.approver.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RefreshSigningTest {

    @Test
    fun `canonical message is the exact domain-tagged byte string`() {
        val message = RefreshSigning.canonicalMessage("apd_12345678", "bm9uY2U")
        assertEquals("refresh\napd_12345678\nbm9uY2U", message.decodeToString())
    }

    @Test
    fun `refresh and decide messages are disjoint by construction`() {
        // With the same challenge in both domains the second field still
        // differs: a verdict literal in decide, an apd_ device id here. So one
        // signature cannot verify in the other domain.
        val refresh = RefreshSigning.canonicalMessage("apd_12345678", "nonce").decodeToString()
        for (verdict in Verdict.entries) {
            val decide = DecisionSigning
                .canonicalMessage("refresh", verdict, "nonce", 1_753_000_000L)
                .decodeToString()
            assertFalse(refresh == decide, "collision: $refresh")
            // A request id of "refresh" cannot align either: decide's second
            // line is the verdict, not an apd_ id.
            assertEquals(verdict.wire, decide.split("\n")[1])
        }
    }

    @Test
    fun `fields with line breaks are refused not escaped`() {
        assertFailsWith<IllegalArgumentException> {
            RefreshSigning.canonicalMessage("apd_1\n2", "nonce")
        }
        assertFailsWith<IllegalArgumentException> {
            RefreshSigning.canonicalMessage("apd_12", "non\rce")
        }
    }

    @Test
    fun `empty fields are refused`() {
        assertFailsWith<IllegalArgumentException> {
            RefreshSigning.canonicalMessage("", "nonce")
        }
        assertFailsWith<IllegalArgumentException> {
            RefreshSigning.canonicalMessage("apd_12", "")
        }
    }
}
