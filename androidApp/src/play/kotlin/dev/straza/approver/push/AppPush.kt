package dev.straza.approver.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.straza.approver.shared.net.FcmAppConfig
import dev.straza.approver.shared.push.FcmStatus
import dev.straza.approver.shared.push.PushTransport
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The push transport for the play flavor: FCM and UnifiedPush, with the same
 * shape as the foss [AppPush]. FCM is the AUTO default when Play services
 * exist.
 *
 * A store build carries the Straza relay's Firebase project from a
 * `google-services.json` present at build time, and a source build without
 * that file still works. A deployment's own public Firebase config arrives in
 * the enroll response, [onActiveDeployment] mirrors it into [FcmRuntime], and
 * the default FirebaseApp is re-initialised at runtime.
 */
object AppPush {

    /**
     * Whether FCM can deliver on this build and phone. Two independent facts:
     * Play services are present, and a default `FirebaseApp` is initialised in
     * this process. Without the second, `FirebaseInitProvider` fails quietly
     * at process start and the next Firebase call throws, so the initialised
     * state is probed here without entering that path.
     */
    fun fcmStatus(context: Context): FcmStatus = when {
        !playServicesPresent(context) -> FcmStatus.NO_PLAY_SERVICES
        !firebaseConfigured(context) -> FcmStatus.NO_FIREBASE_CONFIG
        else -> FcmStatus.READY
    }

    /**
     * The active pairing changed (enroll, switch, retire): align the runtime
     * FirebaseApp with its deployment's Firebase project. See [FcmRuntime].
     */
    fun onActiveDeployment(context: Context, fcm: FcmAppConfig?) = FcmRuntime.apply(context, fcm)

    private fun playServicesPresent(context: Context): Boolean = try {
        GoogleApiAvailabilityLight.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) {
        false
    }

    /**
     * Whether a default FirebaseApp exists. Runtime init runs first. `getApps`
     * reads already-initialised state and cannot throw the way `getInstance`
     * does. `initializeApp(context)` is the null-returning form and covers a
     * baked google-services.json whose FirebaseInitProvider ran too early.
     */
    private fun firebaseConfigured(context: Context): Boolean = try {
        FcmRuntime.ensureInitialized(context)
        FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
    } catch (_: Exception) {
        // Any failure on the initialisation path means FCM is not offered.
        false
    }

    /** [vapid] is the active deployment's VAPID public key, as in the foss flavor. */
    fun sync(context: Context, transport: PushTransport, distributorId: String?, vapid: String?) {
        when (transport) {
            PushTransport.FCM -> {
                // FirebaseMessaging.getInstance() throws when no Firebase
                // configuration exists, and this runs on the UI thread from
                // onStart. The check comes before any teardown, so this branch
                // cannot unregister a working UnifiedPush registration and
                // then bail.
                if (fcmStatus(context) != FcmStatus.READY) return
                // Drop a distributor registration left from a previous choice.
                // The controller deletes the server-side UnifiedPush route when
                // the FCM token registers.
                UnifiedPush.unregister(context)
                try {
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        PushBridge.controller?.onFcmToken(token)
                    }
                } catch (_: Exception) {
                    // Any other Firebase failure is non-fatal: the poll
                    // remains and onNewToken re-registers if FCM recovers.
                }
            }
            PushTransport.UNIFIEDPUSH -> {
                val chosen = distributorId
                    ?: UnifiedPush.getSavedDistributor(context)
                    ?: Distributors.autoChoice(context)
                    ?: return // none chosen, none unambiguous → poll only
                UnifiedPush.saveDistributor(context, chosen)
                registerWithDistributor(context, vapid)
            }
            PushTransport.POLL_ONLY -> UnifiedPush.unregister(context)
        }
    }
}
