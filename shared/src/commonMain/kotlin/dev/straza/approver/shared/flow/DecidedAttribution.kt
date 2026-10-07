package dev.straza.approver.shared.flow

/**
 * Renders the decided-attribution wire values into the app's copy. The wording
 * follows the server's approvals page.
 */
object DecidedAttribution {

    /**
     * The surface clause of the attribution line: where the decision was made.
     * "this phone" appears only when the row's signing device is this
     * enrollment. Legacy `api` rows and any surface this build does not
     * recognise render as a generic enrolled device. Null means the server did
     * not say (the field arrived in openapi 0.63.0).
     */
    fun surfaceClause(surface: String?, deviceId: String?, thisDeviceId: String?): String? = when (surface) {
        null -> null
        "phone" -> if (deviceId != null && deviceId == thisDeviceId) "this phone" else "from their phone"
        "browser" -> "from an enrolled browser"
        "console" -> "in the console"
        "slack" -> "via Slack"
        else -> "from an enrolled device"
    }

    /** The uppercase tag on the decider-reason block ("THIS PHONE", "SLACK"). */
    fun surfaceTag(surface: String?, deviceId: String?, thisDeviceId: String?): String? = when (surface) {
        null -> null
        "phone" -> if (deviceId != null && deviceId == thisDeviceId) "THIS PHONE" else "THEIR PHONE"
        "browser" -> "BROWSER"
        "console" -> "CONSOLE"
        "slack" -> "SLACK"
        else -> "DEVICE"
    }

    /**
     * The requester value: a human subject reads "bob's agent, acting for
     * bob". An NHI subject is the agent itself and keeps the plain name, as
     * does an absent kind (an older server).
     */
    fun requesterValue(requester: String, kind: String): String =
        if (kind == "human" && requester.isNotBlank()) "$requester's agent, acting for $requester" else requester

    fun isNhi(kind: String): Boolean = kind == "nhi"

    /** The caption for an NHI subject, verbatim from the server's approvals page. */
    fun nhiCaption(requester: String): String =
        "$requester is a non-human identity. No other human reviews this request."
}
