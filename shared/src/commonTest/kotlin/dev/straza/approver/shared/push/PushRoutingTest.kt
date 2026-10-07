package dev.straza.approver.shared.push

import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.PushKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRoutingTest {

    private val up = PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/abc")
    private val up2 = PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/def")
    private val fcm = PushRoute(PushKind.FCM, "fcm-token-1")

    private companion object {
        // Placeholder values. The server enforces the real key format.
        const val P256DH = "BP256dhExampleSubscriptionPublicKey"
        const val AUTH = "authSecretExample"
        const val AUTH_2 = "authSecretRotated"
    }

    // PushRoutePlanner

    @Test
    fun freshRegistrationRegistersAndDeletesNothing() {
        val plan = PushRoutePlanner.plan(stored = null, desired = up)
        assertEquals(up, plan.register)
        assertEquals(emptyList(), plan.unregister)
    }

    @Test
    fun unchangedRouteReRegistersIdempotently() {
        // Token rotation redelivers the same endpoint. The repeated PUT is the keep-alive.
        val plan = PushRoutePlanner.plan(stored = up, desired = up)
        assertEquals(up, plan.register)
        assertEquals(emptyList(), plan.unregister)
    }

    @Test
    fun endpointRotationRegistersNewThenDeletesOld() {
        val plan = PushRoutePlanner.plan(stored = up, desired = up2)
        assertEquals(up2, plan.register)
        assertEquals(listOf(up), plan.unregister)
    }

    @Test
    fun transportSwitchDeletesTheOtherLane() {
        val plan = PushRoutePlanner.plan(stored = fcm, desired = up)
        assertEquals(up, plan.register)
        assertEquals(listOf(fcm), plan.unregister)
    }

    @Test
    fun signOutDeletesStoredRouteAndRegistersNothing() {
        val plan = PushRoutePlanner.plan(stored = up, desired = null)
        assertEquals(null, plan.register)
        assertEquals(listOf(up), plan.unregister)
    }

    @Test
    fun nothingStoredNothingDesiredIsANoOp() {
        val plan = PushRoutePlanner.plan(stored = null, desired = null)
        assertEquals(null, plan.register)
        assertEquals(emptyList(), plan.unregister)
    }

    // Web Push subscription keys on the route

    private val keyedUp = PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/abc", P256DH, AUTH)

    @Test
    fun subscriptionKeysTravelTogetherByConstruction() {
        // The server answers 400 to one key without the other, so a half-keyed
        // route cannot be constructed.
        assertFailsWith<IllegalArgumentException> {
            PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/abc", p256dh = P256DH, auth = null)
        }
        assertFailsWith<IllegalArgumentException> {
            PushRoute(PushKind.UNIFIEDPUSH, "https://ntfy.example/abc", p256dh = null, auth = AUTH)
        }
        assertTrue(keyedUp.hasKeys)
        assertFalse(up.hasKeys)
        assertEquals(up, keyedUp.withoutKeys())
    }

    @Test
    fun keyRotationOnAnUnchangedUrlIsARouteRotation() {
        // The keys are part of the route's server-side identity: a keyed row is
        // deleted with the same fields it registered.
        val rotated = keyedUp.copy(auth = AUTH_2, p256dh = P256DH)
        val plan = PushRoutePlanner.plan(stored = keyedUp, desired = rotated)
        assertEquals(rotated, plan.register)
        assertEquals(listOf(keyedUp), plan.unregister)
    }

    // The temporary (fallback distributor) lane

    @Test
    fun primaryRegistrationRetiresTheTemporaryRoute() {
        // A non-temporary endpoint means the primary distributor is back.
        val temp = PushRoute(PushKind.UNIFIEDPUSH, "https://fallback.example/t")
        val plan = PushRoutePlanner.plan(stored = up, desired = up, storedTemporary = temp)
        assertEquals(up, plan.register)
        assertEquals(listOf(temp), plan.unregister)
    }

    @Test
    fun teardownRemovesPrimaryAndTemporary() {
        val temp = PushRoute(PushKind.UNIFIEDPUSH, "https://fallback.example/t")
        val plan = PushRoutePlanner.plan(stored = up, desired = null, storedTemporary = temp)
        assertEquals(null, plan.register)
        assertEquals(listOf(up, temp), plan.unregister)
    }

    @Test
    fun temporaryPlanNeverTouchesThePrimary() {
        // The primary distributor being offline says nothing about its
        // endpoint's validity.
        val oldTemp = PushRoute(PushKind.UNIFIEDPUSH, "https://fallback.example/old")
        val newTemp = PushRoute(PushKind.UNIFIEDPUSH, "https://fallback.example/new")
        val plan = PushRoutePlanner.planTemporary(storedTemporary = oldTemp, desired = newTemp)
        assertEquals(newTemp, plan.register)
        assertEquals(listOf(oldTemp), plan.unregister)

        // The same temporary endpoint delivered again is a keep-alive.
        val again = PushRoutePlanner.planTemporary(storedTemporary = newTemp, desired = newTemp)
        assertEquals(newTemp, again.register)
        assertEquals(emptyList(), again.unregister)
    }

    // PushPrefs.effective

    @Test
    fun autoPrefersFcmThenUnifiedPushThenPoll() {
        assertEquals(
            PushTransport.FCM,
            PushPrefs.effective(PushTransportPref.AUTO, fcmAvailable = true, hasDistributor = true),
        )
        assertEquals(
            PushTransport.UNIFIEDPUSH,
            PushPrefs.effective(PushTransportPref.AUTO, fcmAvailable = false, hasDistributor = true),
        )
        assertEquals(
            PushTransport.POLL_ONLY,
            PushPrefs.effective(PushTransportPref.AUTO, fcmAvailable = false, hasDistributor = false),
        )
    }

    @Test
    fun explicitChoiceNeverSubstitutesAnotherTransport() {
        // A chosen transport that is gone falls back to polling, not to a push
        // relay the user did not choose.
        assertEquals(
            PushTransport.POLL_ONLY,
            PushPrefs.effective(PushTransportPref.FCM, fcmAvailable = false, hasDistributor = true),
        )
        assertEquals(
            PushTransport.POLL_ONLY,
            PushPrefs.effective(PushTransportPref.UNIFIEDPUSH, fcmAvailable = true, hasDistributor = false),
        )
    }

    @Test
    fun explicitChoicesResolveWhenAvailable() {
        assertEquals(
            PushTransport.FCM,
            PushPrefs.effective(PushTransportPref.FCM, fcmAvailable = true, hasDistributor = false),
        )
        assertEquals(
            PushTransport.UNIFIEDPUSH,
            PushPrefs.effective(PushTransportPref.UNIFIEDPUSH, fcmAvailable = true, hasDistributor = true),
        )
        assertEquals(
            PushTransport.POLL_ONLY,
            PushPrefs.effective(PushTransportPref.POLL_ONLY, fcmAvailable = true, hasDistributor = true),
        )
    }

    // PushRouteStatus mapping

    @Test
    fun okMapsToRegistered() {
        val status = PushRouteStatus.of(up, ApiResult.Ok(Unit))
        assertEquals(PushRouteStatus.Registered(up), status)
    }

    @Test
    fun rejection400CarriesTheServerTextVerbatim() {
        val serverText = "push endpoint host \"evil.example\" is not in approval.push.allowedPushHosts"
        val status = PushRouteStatus.of(up, ApiResult.ServerError(400, serverText))
        assertTrue(status is PushRouteStatus.Rejected)
        assertEquals(serverText, status.serverMessage)
        assertEquals(up, status.route)
    }

    @Test
    fun rejection400WithoutBodyStillSaysWhatHappened() {
        val status = PushRouteStatus.of(up, ApiResult.ServerError(400, null))
        assertTrue(status is PushRouteStatus.Rejected)
        assertTrue("400" in status.serverMessage)
    }

    @Test
    fun transientFailuresMapToUnreached() {
        assertTrue(PushRouteStatus.of(up, ApiResult.ServerError(503)) is PushRouteStatus.Unreached)
        assertTrue(PushRouteStatus.of(up, ApiResult.Unreachable("timeout")) is PushRouteStatus.Unreached)
        assertTrue(PushRouteStatus.of(up, ApiResult.Untrusted("pin mismatch")) is PushRouteStatus.Unreached)
        assertTrue(PushRouteStatus.of(up, ApiResult.Unauthorized()) is PushRouteStatus.Unreached)
    }

    // FCM availability. A play build without google-services.json is supported,
    // and FirebaseMessaging.getInstance() throws in it, so FCM must not resolve
    // when Firebase is not configured.

    @Test
    fun firebaseUnconfiguredIsInTheBuildButNotUsable() {
        // The FCM row still exists in a play build, so it can say why it is unusable.
        val settings = PushSettingsUi(fcm = FcmStatus.NO_FIREBASE_CONFIG, distributors = emptyList())
        assertTrue(settings.fcmInBuild)
        assertFalse(settings.fcmAvailable)
        assertFalse(FcmStatus.NO_FIREBASE_CONFIG.usable)

        // The foss flavor has no FCM row at all.
        val foss = PushSettingsUi(fcm = FcmStatus.NOT_IN_BUILD, distributors = emptyList())
        assertFalse(foss.fcmInBuild)
        assertFalse(foss.fcmAvailable)

        val ready = PushSettingsUi(fcm = FcmStatus.READY, distributors = emptyList())
        assertTrue(ready.fcmInBuild)
        assertTrue(ready.fcmAvailable)
    }

    @Test
    fun noPreferenceCanResolveToFcmWithoutAFirebaseConfig() {
        // Iterates every preference, so one added later is covered too.
        for (pref in PushTransportPref.entries) {
            for (hasDistributor in listOf(true, false)) {
                assertNotEquals(
                    PushTransport.FCM,
                    PushPrefs.effective(pref, FcmStatus.NO_FIREBASE_CONFIG.usable, hasDistributor),
                    "$pref (hasDistributor=$hasDistributor) resolved to FCM with no Firebase config",
                )
            }
        }
    }

    @Test
    fun playBuildWithoutFirebaseConfigReportsPushUnavailable() {
        // AUTO wanted FCM and there is no distributor to fall back to.
        assertEquals(
            "push unavailable: this build has no Firebase configuration",
            PushPrefs.fcmUnavailability(
                PushTransportPref.AUTO,
                FcmStatus.NO_FIREBASE_CONFIG,
                hasDistributor = false,
            ),
        )
        // An explicit FCM choice is unavailable whatever else is installed.
        assertEquals(
            PushPrefs.NO_FIREBASE_CONFIG_TEXT,
            PushPrefs.fcmUnavailability(
                PushTransportPref.FCM,
                FcmStatus.NO_FIREBASE_CONFIG,
                hasDistributor = true,
            ),
        )
    }

    @Test
    fun aWorkingLaneIsNeverCalledUnavailable() {
        // FCM is fine: nothing to report.
        for (pref in PushTransportPref.entries) {
            assertNull(PushPrefs.fcmUnavailability(pref, FcmStatus.READY, hasDistributor = false))
        }
        // Under AUTO, UnifiedPush carries the push when FCM cannot.
        assertNull(
            PushPrefs.fcmUnavailability(
                PushTransportPref.AUTO,
                FcmStatus.NO_FIREBASE_CONFIG,
                hasDistributor = true,
            ),
        )
        // The user chose another lane, so FCM's state is irrelevant.
        assertNull(
            PushPrefs.fcmUnavailability(
                PushTransportPref.UNIFIEDPUSH,
                FcmStatus.NO_FIREBASE_CONFIG,
                hasDistributor = true,
            ),
        )
        assertNull(
            PushPrefs.fcmUnavailability(
                PushTransportPref.POLL_ONLY,
                FcmStatus.NO_FIREBASE_CONFIG,
                hasDistributor = false,
            ),
        )
    }

    @Test
    fun fossBuildNeverBlamesFirebaseUnderAuto() {
        // A lane that was never in the binary is not a fault to report.
        assertNull(
            PushPrefs.fcmUnavailability(
                PushTransportPref.AUTO,
                FcmStatus.NOT_IN_BUILD,
                hasDistributor = false,
            ),
        )
    }

    @Test
    fun missingPlayServicesIsReportedAsItsOwnCauseNotAsFirebase() {
        val why = PushPrefs.fcmUnavailability(
            PushTransportPref.FCM,
            FcmStatus.NO_PLAY_SERVICES,
            hasDistributor = false,
        )
        assertEquals("push unavailable: Google Play services is not available on this phone", why)
        assertNotEquals(PushPrefs.NO_FIREBASE_CONFIG_TEXT, why)
    }
}
