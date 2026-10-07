package dev.straza.approver.push

import android.content.Context
import dev.straza.approver.shared.net.FcmAppConfig
import dev.straza.approver.shared.push.FcmStatus
import dev.straza.approver.shared.push.PushTransport
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The push transport for the foss flavor: UnifiedPush only, no Google
 * dependencies. The `play` flavor supplies its own [AppPush] with the same
 * shape.
 *
 * [sync] is idempotent and does not block or prompt: it aligns the
 * distributor registration with the resolved transport. Server-side route
 * registration stays in the controller, since endpoints arrive asynchronously
 * in [StrazaPushService.onNewEndpoint].
 */
object AppPush {

    /** Always [FcmStatus.NOT_IN_BUILD]: no Firebase artifact is on this flavor's classpath. */
    @Suppress("UNUSED_PARAMETER")
    fun fcmStatus(context: Context): FcmStatus = FcmStatus.NOT_IN_BUILD

    /** The active pairing changed. Nothing to align: this flavor has no FCM. */
    @Suppress("UNUSED_PARAMETER")
    fun onActiveDeployment(context: Context, fcm: FcmAppConfig?) = Unit

    /**
     * @param vapid the active deployment's VAPID public key, or null for a
     *   keyless pairing. Handed to the distributor at registration so the push
     *   service accepts pushes only from the server holding the private key.
     */
    fun sync(context: Context, transport: PushTransport, distributorId: String?, vapid: String?) {
        when (transport) {
            PushTransport.UNIFIEDPUSH -> {
                val chosen = distributorId
                    ?: UnifiedPush.getSavedDistributor(context)
                    ?: Distributors.autoChoice(context)
                    ?: return // none chosen, none unambiguous → poll only
                UnifiedPush.saveDistributor(context, chosen)
                registerWithDistributor(context, vapid)
            }
            // FCM cannot resolve on this flavor. Both remaining cases mean no
            // distributor registration.
            PushTransport.FCM, PushTransport.POLL_ONLY -> UnifiedPush.unregister(context)
        }
    }
}
