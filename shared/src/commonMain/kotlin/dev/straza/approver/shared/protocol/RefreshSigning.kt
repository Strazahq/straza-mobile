package dev.straza.approver.shared.protocol

/**
 * Builds the byte string a token refresh is signed over:
 *
 *     refresh \n approver_device_id \n challenge
 *
 * The leading literal `refresh` is the domain tag. A decide message
 * ([DecisionSigning]) starts with a request id and has the verdict as its
 * second field, where this message has an `apd_…` device id, so a decide
 * signature cannot verify as a refresh or the reverse.
 */
object RefreshSigning {

    /** The domain tag the server prepends when rebuilding the message. */
    const val DOMAIN = "refresh"

    /**
     * @throws IllegalArgumentException if a field is empty or contains a line
     *   break, the same rule as in [DecisionSigning].
     */
    fun canonicalMessage(approverDeviceId: String, challenge: String): ByteArray {
        requireSafeField(approverDeviceId, "approver_device_id")
        requireSafeField(challenge, "challenge")

        return buildString {
            append(DOMAIN).append('\n')
            append(approverDeviceId).append('\n')
            append(challenge)
        }.encodeToByteArray()
    }

    private fun requireSafeField(value: String, name: String) {
        require(value.isNotEmpty()) { "$name must not be empty" }
        require('\n' !in value && '\r' !in value) { "$name must not contain line breaks" }
    }
}
