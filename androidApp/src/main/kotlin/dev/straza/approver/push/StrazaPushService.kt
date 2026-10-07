package dev.straza.approver.push

import dev.straza.approver.shared.push.PushEnvelope
import dev.straza.approver.shared.push.PushNotification
import dev.straza.approver.shared.push.PushPayload
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events from the connector (3.x), in both flavors.
 *
 * The distributor can only reach the connector library's exported receiver;
 * this service is internal. Nothing arriving here is trusted: every payload
 * goes through [PushPayload.parse], and the most a forged message achieves is
 * a spurious notification and a fetch. The connector holds the Web Push
 * (RFC 8291) keypair and decrypts before delivery, so [PushMessage.content]
 * is plaintext.
 */
class StrazaPushService : PushService() {

    /** A fresh endpoint from the distributor: register it, rotating out the old one. */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Registration needs the enrolled device token, so it only happens
        // while the app is up. The distributor re-issues the endpoint on the
        // next register(), which runs on each foreground.
        PushBridge.controller?.onUnifiedPushEndpoint(
            endpoint = endpoint.url,
            p256dh = endpoint.pubKeySet?.pubKey,
            auth = endpoint.pubKeySet?.auth,
            temporary = endpoint.temporary,
        )
    }

    /** A push arrived. Parse defensively; the only action is notify or fetch. */
    override fun onMessage(message: PushMessage, instance: String) {
        // decodeToString does not throw: malformed UTF-8 becomes replacement
        // characters, which fail the JSON parse into Ignore. message.decrypted
        // is not checked, because a keyless registration arrives undecrypted
        // but plaintext and the parse rejects garbage either way.
        val envelope = PushPayload.parse(message.content.decodeToString())
        if (envelope !is PushEnvelope.Fetch) return // unrecognized → drop; the poll covers it

        // "Last push received" for the Notifications screen. Not security state.
        PushRouteStore(this).stampDelivery(System.currentTimeMillis() / 1000)

        val live = PushBridge.controller
        if (live != null) {
            // Foreground: refresh in place, no notification.
            live.onPushReceived(envelope)
        } else {
            // Backgrounded or dead: the notification says only that something
            // changed. A status hint uses the quiet channel.
            PushNotifications.show(
                this,
                PushNotification.forHint(envelope.hint),
                quiet = envelope.hint == PushEnvelope.Hint.Status,
            )
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // The poll remains. The reason is recorded for the Notifications
        // screen, but only when no route is standing (see
        // PushRouteStore.noteRegistrationFailed).
        val why = when (reason) {
            FailedReason.VAPID_REQUIRED ->
                "the distributor requires a WebPush (VAPID) key and this deployment did not provide one - " +
                    "re-pair after the server enables WebPush, or install another distributor"
            FailedReason.ACTION_REQUIRED ->
                "the distributor needs attention - open its app and finish its setup"
            FailedReason.NETWORK ->
                "the distributor could not reach its push server; it will be retried"
            FailedReason.INTERNAL_ERROR ->
                "the distributor failed to register; it will be retried"
        }
        PushRouteStore(this).noteRegistrationFailed(why)
    }

    override fun onUnregistered(instance: String) {
        // The distributor revoked the registration. With the app up, DELETE the
        // route server-side; otherwise drop the stored route, and the server
        // prunes the dead endpoint on its first failed delivery.
        PushBridge.controller?.onUnifiedPushUnregistered()
            ?: PushRouteStore(this).noteUnifiedPushGone()
    }

    // onTempUnavailable keeps its default no-op: the 15 s foreground poll
    // covers the gap, and a failover arrives in onNewEndpoint with
    // temporary=true.
}
