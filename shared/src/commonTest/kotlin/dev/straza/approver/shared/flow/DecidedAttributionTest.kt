package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecidedAttributionTest {

    @Test
    fun `this phone only when the signing device is this enrollment`() {
        assertEquals("this phone", DecidedAttribution.surfaceClause("phone", "apd_1", "apd_1"))
        assertEquals("from their phone", DecidedAttribution.surfaceClause("phone", "apd_2", "apd_1"))
        // A phone row with no device id must not claim to be this phone, and
        // neither may any row when this enrollment's id is unknown.
        assertEquals("from their phone", DecidedAttribution.surfaceClause("phone", null, "apd_1"))
        assertEquals("from their phone", DecidedAttribution.surfaceClause("phone", null, null))
    }

    @Test
    fun `the surface table`() {
        assertEquals("from an enrolled browser", DecidedAttribution.surfaceClause("browser", null, null))
        assertEquals("in the console", DecidedAttribution.surfaceClause("console", null, null))
        assertEquals("via Slack", DecidedAttribution.surfaceClause("slack", null, null))
    }

    @Test
    fun `legacy api and unknown surfaces render as a generic enrolled device`() {
        assertEquals("from an enrolled device", DecidedAttribution.surfaceClause("api", null, null))
        assertEquals("from an enrolled device", DecidedAttribution.surfaceClause("hologram", "apd_1", "apd_1"))
    }

    @Test
    fun `no surface means no clause - the pre-0_63 line unchanged`() {
        assertNull(DecidedAttribution.surfaceClause(null, "apd_1", "apd_1"))
        assertNull(DecidedAttribution.surfaceTag(null, null, null))
    }

    @Test
    fun `the tag table`() {
        assertEquals("THIS PHONE", DecidedAttribution.surfaceTag("phone", "apd_1", "apd_1"))
        assertEquals("THEIR PHONE", DecidedAttribution.surfaceTag("phone", "apd_2", "apd_1"))
        assertEquals("SLACK", DecidedAttribution.surfaceTag("slack", null, null))
        assertEquals("DEVICE", DecidedAttribution.surfaceTag("api", null, null))
    }

    @Test
    fun `two voices for a human subject and plain name otherwise`() {
        assertEquals("bob's agent, acting for bob", DecidedAttribution.requesterValue("bob", "human"))
        assertEquals("svc-batch", DecidedAttribution.requesterValue("svc-batch", "nhi"))
        // An older server sends no kind.
        assertEquals("nova", DecidedAttribution.requesterValue("nova", ""))
        // A human kind with no name must not render "'s agent, acting for ".
        assertEquals("", DecidedAttribution.requesterValue("", "human"))
    }

    @Test
    fun `nhi caption mirrors the approvals page verbatim`() {
        assertEquals(
            "svc-batch is a non-human identity. No other human reviews this request.",
            DecidedAttribution.nhiCaption("svc-batch"),
        )
    }
}
