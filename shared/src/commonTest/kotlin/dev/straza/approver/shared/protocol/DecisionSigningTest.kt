package dev.straza.approver.shared.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecisionSigningTest {

    @Test
    fun `canonical message matches the contract byte for byte`() {
        val msg = DecisionSigning.canonicalMessage(
            requestId = "apr_123",
            verdict = Verdict.APPROVE,
            challenge = "Y2hhbGxlbmdl",
            unixTs = 1789000000L,
        )
        assertContentEquals("apr_123\napprove\nY2hhbGxlbmdl\n1789000000".encodeToByteArray(), msg)
    }

    @Test
    fun `verdict wire values are exactly approve and deny`() {
        // The server matches these literals, so a rename would invalidate every
        // signature.
        assertEquals("approve", Verdict.APPROVE.wire)
        assertEquals("deny", Verdict.DENY.wire)
    }

    @Test
    fun `no trailing newline`() {
        val msg = DecisionSigning.canonicalMessage("apr_1", Verdict.DENY, "n", 1L)
        assertTrue(!msg.decodeToString().endsWith("\n"))
    }

    /**
     * The message is newline-delimited, so a field containing a newline could
     * move the field boundaries and let a signature be replayed for a different
     * request or verdict. Such input is refused, not escaped.
     */
    @Test
    fun `rejects newline in request id`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1\napprove\nX\n1", Verdict.DENY, "n", 1L)
        }
    }

    @Test
    fun `rejects newline in challenge`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "nonce\nextra", 1L)
        }
    }

    @Test
    fun `rejects carriage return too`() {
        // \r is not a delimiter here, but a server splitting on universal
        // newlines would see one.
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1\r", Verdict.APPROVE, "n", 1L)
        }
    }

    @Test
    fun `rejects empty request id and challenge`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("", Verdict.APPROVE, "n", 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "", 1L)
        }
    }

    @Test
    fun `rejects non-positive timestamp`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", -1L)
        }
    }

    /** Identical bytes would let a captured deny signature authorise an approve. */
    @Test
    fun `approve and deny produce different messages`() {
        val approve = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L)
        val deny = DecisionSigning.canonicalMessage("apr_1", Verdict.DENY, "n", 1L)
        assertTrue(!approve.contentEquals(deny))
    }

    @Test
    fun `distinct challenges produce distinct messages`() {
        val a = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "nonce-a", 1L)
        val b = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "nonce-b", 1L)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `message is utf8 encoded`() {
        // Ids are ASCII today, but the encoding must not come from a platform default.
        val msg = DecisionSigning.canonicalMessage("apr_é", Verdict.APPROVE, "n", 1L)
        assertContentEquals("apr_é\napprove\nn\n1".encodeToByteArray(), msg)
    }

    // The five-line form: the reason is carried as its hash

    /** The last line is sha256("abc"), the FIPS 180 test vector. */
    @Test
    fun `five line message matches the contract byte for byte`() {
        val msg = DecisionSigning.canonicalMessage(
            requestId = "apr_123",
            verdict = Verdict.DENY,
            challenge = "Y2hhbGxlbmdl",
            unixTs = 1789000000L,
            reason = "abc",
        )
        assertContentEquals(
            ("apr_123\ndeny\nY2hhbGxlbmdl\n1789000000\n" +
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad").encodeToByteArray(),
            msg,
        )
    }

    @Test
    fun `reason hash is over utf8 bytes - multibyte pinned vector`() {
        val msg = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "Presuň účty najprv.")
        assertTrue(
            msg.decodeToString()
                .endsWith("\n8a36fa7c410a8c724785cf177aac7350633f5f5ac3f76a5e0b191a6953fd1155"),
        )
    }

    /** Hashing lets a reason contain the newline that the format cannot carry raw. */
    @Test
    fun `reason containing newline and tab signs fine - pinned vector`() {
        val msg = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "line one\nline two\ttabbed")
        val text = msg.decodeToString()
        assertEquals(5, text.split('\n').size, "reason newlines must not add message lines")
        assertTrue(text.endsWith("\n6a4c31a869473942ebedf974646f7824ba1852449c205a8b13800d7d4cab7299"))
    }

    @Test
    fun `null reason produces exactly the four line message`() {
        val fourLine = DecisionSigning.canonicalMessage("apr_9", Verdict.APPROVE, "c", 42L)
        val explicitNull = DecisionSigning.canonicalMessage("apr_9", Verdict.APPROVE, "c", 42L, reason = null)
        assertContentEquals(fourLine, explicitNull)
        assertEquals(4, fourLine.decodeToString().split('\n').size)
    }

    @Test
    fun `distinct reasons produce distinct messages and differ from reasonless`() {
        val none = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L)
        val a = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "reason a")
        val b = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "reason b")
        assertTrue(!a.contentEquals(b))
        assertTrue(!a.contentEquals(none))
    }

    /** The key must not sign a reason the server would reject with a 400. */
    @Test
    fun `invalid reasons are refused before signing`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "")
        }
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, " untrimmed")
        }
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "x".repeat(501))
        }
        assertFailsWith<IllegalArgumentException> {
            DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L, "bad\u0000char")
        }
    }
}
