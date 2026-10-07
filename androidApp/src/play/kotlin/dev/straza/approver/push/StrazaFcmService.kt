package dev.straza.approver.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.straza.approver.shared.push.PushEnvelope
import dev.straza.approver.shared.push.PushNotification
import dev.straza.approver.shared.push.PushPayload

/**
 * Receives FCM pushes in the play flavor, with the same defensive parse and
 * "notify or fetch" rule as [StrazaPushService].
 *
 * The envelope rides FCM's `data` map with string values
 * (`{"v":"1","ref":…,"kind":…}`), which [PushPayload.fromData] parses.
 */
class StrazaFcmService : FirebaseMessagingService() {

    /**
     * A rotated token: re-register the route if the app is up and enrolled.
     * The controller ignores it unless FCM is the user's resolved transport.
     */
    override fun onNewToken(token: String) {
        PushBridge.controller?.onFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val envelope = PushPayload.fromData(
            v = message.data["v"],
            ref = message.data["ref"],
            kind = message.data["kind"],
        )
        if (envelope !is PushEnvelope.Fetch) return

        // "Last push received" for the Notifications screen.
        PushRouteStore(this).stampDelivery(System.currentTimeMillis() / 1000)

        val live = PushBridge.controller
        if (live != null) {
            live.onPushReceived(envelope)
        } else {
            // A status hint uses the quiet channel; a waiting decision rings.
            PushNotifications.show(
                this,
                PushNotification.forHint(envelope.hint),
                quiet = envelope.hint == PushEnvelope.Hint.Status,
            )
        }
    }
}
