package dev.straza.approver.shared.push

/**
 * The user-facing text of a push notification, derived from the
 * [PushEnvelope.Hint] alone and not from the payload. A notification is
 * visible on a locked screen to anyone holding the phone, so it says only that
 * a decision is waiting, not which one.
 */
data class PushNotification(val title: String, val body: String) {

    companion object {
        fun forHint(hint: PushEnvelope.Hint): PushNotification = when (hint) {
            PushEnvelope.Hint.Decide -> PushNotification(
                title = "Approval needed",
                body = "A request is waiting for your decision. Open Straza to review it.",
            )
            PushEnvelope.Hint.Status -> PushNotification(
                title = "Request updated",
                body = "One of your requests was resolved. Open Straza to see the outcome.",
            )
            // An unrecognized kind still asks the user to open the app, without
            // naming a tab.
            PushEnvelope.Hint.Unknown -> PushNotification(
                title = "Straza",
                body = "Open Straza to check for updates.",
            )
        }
    }
}
