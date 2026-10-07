package dev.straza.approver.shared.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PushEnvelopeTest {

    @Test
    fun `a decide push parses to a fetch with the decide hint`() {
        val e = PushPayload.parse("""{"v":1,"ref":"apr_1","kind":"decide"}""")
        assertEquals(PushEnvelope.Fetch("apr_1", PushEnvelope.Hint.Decide), e)
    }

    @Test
    fun `a status push parses to a fetch with the status hint`() {
        val e = PushPayload.parse("""{"v":1,"ref":"apr_2","kind":"status"}""")
        assertEquals(PushEnvelope.Fetch("apr_2", PushEnvelope.Hint.Status), e)
    }

    /**
     * FCM HTTP v1 delivers the envelope in `message.data`, where every value is
     * a string, so `v` arrives as `"1"` and not the integer UnifiedPush sends.
     */
    @Test
    fun `the FCM string-valued envelope parses the same as the int one`() {
        val e = PushPayload.parse("""{"v":"1","ref":"apr_9","kind":"decide"}""")
        assertEquals(PushEnvelope.Fetch("apr_9", PushEnvelope.Hint.Decide), e)
    }

    /** Dropping the push would let a server that adds a kind stop older apps from refreshing. */
    @Test
    fun `an unknown kind still fetches`() {
        val e = PushPayload.parse("""{"v":1,"ref":"apr_3","kind":"whatever"}""")
        assertEquals(PushEnvelope.Fetch("apr_3", PushEnvelope.Hint.Unknown), e)
    }

    @Test
    fun `a missing kind still fetches`() {
        val e = PushPayload.parse("""{"v":1,"ref":"apr_4"}""")
        assertEquals(PushEnvelope.Fetch("apr_4", PushEnvelope.Hint.Unknown), e)
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val e = PushPayload.parse("""{"v":1,"ref":"apr_5","kind":"decide","extra":"x"}""")
        assertIs<PushEnvelope.Fetch>(e)
    }

    @Test
    fun `fromData builds the same fetch as parse`() {
        assertEquals(
            PushEnvelope.Fetch("apr_7", PushEnvelope.Hint.Decide),
            PushPayload.fromData(v = "1", ref = "apr_7", kind = "decide"),
        )
    }

    @Test
    fun `fromData ignores a wrong version or missing ref or non-numeric version`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.fromData(v = "2", ref = "apr_1", kind = "decide"))
        assertEquals(PushEnvelope.Ignore, PushPayload.fromData(v = "1", ref = null, kind = "decide"))
        assertEquals(PushEnvelope.Ignore, PushPayload.fromData(v = "x", ref = "apr_1", kind = "decide"))
    }

    @Test
    fun `fromData with an unknown kind still fetches`() {
        assertEquals(
            PushEnvelope.Fetch("apr_8", PushEnvelope.Hint.Unknown),
            PushPayload.fromData(v = "1", ref = "apr_8", kind = null),
        )
    }

    @Test
    fun `a wrong version is ignored`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"v":2,"ref":"apr_1","kind":"decide"}"""))
    }

    @Test
    fun `a missing version is ignored`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"ref":"apr_1","kind":"decide"}"""))
    }

    @Test
    fun `a missing ref is ignored`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"v":1,"kind":"decide"}"""))
    }

    @Test
    fun `a blank ref is ignored`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"v":1,"ref":"","kind":"decide"}"""))
    }

    @Test
    fun `non-json is ignored and never thrown`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("not json at all"))
        assertEquals(PushEnvelope.Ignore, PushPayload.parse(""))
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("[1,2,3]"))
    }

    /** The relay could deliver anything, so a payload that does not parse is dropped. */
    @Test
    fun `hostile garbage is ignored`() {
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"v":1,"ref":{"nested":"object"}}"""))
        assertEquals(PushEnvelope.Ignore, PushPayload.parse("""{"v":"not-a-number","ref":"x"}"""))
    }
}
