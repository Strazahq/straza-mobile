package dev.straza.approver.shared.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushConsentTest {

    @Test
    fun grantedPassesEveryResolvedTransportThrough() {
        for (transport in PushTransport.entries) {
            assertEquals(transport, PushPrefs.gate(PushConsent.GRANTED, transport))
        }
    }

    @Test
    fun undecidedCollapsesEveryTransportToPollOnly() {
        for (transport in PushTransport.entries) {
            assertEquals(
                PushTransport.POLL_ONLY,
                PushPrefs.gate(PushConsent.UNDECIDED, transport),
                "an unanswered ask must never register a route ($transport)",
            )
        }
    }

    @Test
    fun declinedCollapsesEveryTransportToPollOnly() {
        for (transport in PushTransport.entries) {
            assertEquals(
                PushTransport.POLL_ONLY,
                PushPrefs.gate(PushConsent.DECLINED, transport),
                "a withdrawn consent must never register a route ($transport)",
            )
        }
    }

    @Test
    fun askIsOwedOnlyWhenEnrolledAndUndecided() {
        assertTrue(PushPrefs.shouldAsk(PushConsent.UNDECIDED, enrolled = true))
        assertFalse(PushPrefs.shouldAsk(PushConsent.UNDECIDED, enrolled = false))
        assertFalse(PushPrefs.shouldAsk(PushConsent.GRANTED, enrolled = true))
        assertFalse(PushPrefs.shouldAsk(PushConsent.DECLINED, enrolled = true))
    }

    @Test
    fun providerChoiceExpressesConsent() {
        // The first-start ask is a provider choice: picking any provider grants consent.
        assertEquals(PushConsent.GRANTED, PushPrefs.consentFor(PushTransportPref.AUTO))
        assertEquals(PushConsent.GRANTED, PushPrefs.consentFor(PushTransportPref.FCM))
        assertEquals(PushConsent.GRANTED, PushPrefs.consentFor(PushTransportPref.UNIFIEDPUSH))
        // Polling declines it and registers nothing.
        assertEquals(PushConsent.DECLINED, PushPrefs.consentFor(PushTransportPref.POLL_ONLY))
    }

    @Test
    fun answeredAskNeverReappears() {
        // Switching or adding a deployment rebuilds the enrolled state, and
        // neither answer may turn back into an ask.
        for (enrolled in listOf(true, false)) {
            assertFalse(PushPrefs.shouldAsk(PushConsent.GRANTED, enrolled))
            assertFalse(PushPrefs.shouldAsk(PushConsent.DECLINED, enrolled))
        }
    }
}
