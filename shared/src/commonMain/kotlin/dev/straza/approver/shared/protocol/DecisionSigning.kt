package dev.straza.approver.shared.protocol

/**
 * A decision, in the spelling the server matches on. The wire values are part
 * of every signed message, so they cannot be renamed.
 */
enum class Verdict(val wire: String) {
    APPROVE("approve"),
    DENY("deny"),
}

/**
 * Builds the byte string a decision is signed over:
 *
 *     request_id \n verdict \n challenge \n unix_ts
 *
 * and, only when the decider gave a reason:
 *
 *     request_id \n verdict \n challenge \n unix_ts \n lowercasehex(sha256(utf8(reason)))
 *
 * A decision without a reason always uses the 4-line form. The reason is
 * signed as a hash because it may contain `\n`, and binding it means the
 * words cannot be attached, stripped or swapped in transit. The signing itself
 * happens in hardware behind [dev.straza.approver.shared.security.DeviceKeyStore].
 */
object DecisionSigning {

    /**
     * @param reason the decider's words in wire form ([ReasonValidation.forWire]),
     *   or null for the 4-line message. Validated here as well, so the device
     *   key does not sign a reason the server would reject.
     * @throws IllegalArgumentException if a field is empty or contains a line
     *   break, the timestamp is not positive, or the reason is invalid.
     */
    fun canonicalMessage(
        requestId: String,
        verdict: Verdict,
        challenge: String,
        unixTs: Long,
        reason: String? = null,
    ): ByteArray {
        requireSafeField(requestId, "request_id")
        requireSafeField(challenge, "challenge")
        require(unixTs > 0) { "timestamp must be positive" }
        reason?.let(ReasonValidation::requireValid)

        return buildString {
            append(requestId).append('\n')
            append(verdict.wire).append('\n')
            append(challenge).append('\n')
            append(unixTs)
            reason?.let { append('\n').append(hexLower(sha256(it.encodeToByteArray()))) }
        }.encodeToByteArray()
    }

    /** Lowercase hex, as the reason line of the signed message requires. */
    private fun hexLower(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"

    /**
     * The message is newline-delimited, so a field containing a line break
     * could move the field boundaries and change what a signature means. Such
     * input is refused, not escaped: escaping only works if the server
     * unescapes identically. `\r` is refused too, because a peer splitting on
     * universal newlines would treat it as a delimiter.
     */
    private fun requireSafeField(value: String, name: String) {
        require(value.isNotEmpty()) { "$name must not be empty" }
        require('\n' !in value && '\r' !in value) { "$name must not contain line breaks" }
    }
}
