package dev.straza.approver.shared.protocol

/**
 * The intake rule for a decider's reason, the same rule the server applies:
 * UTF-8, at most 500 bytes measured after trimming, no control characters
 * except `\n` and `\t` (DEL is refused too), and none of the code points in
 * [FORMATTING_REJECTS]. Input is rejected, not transformed, because the stored
 * words must be the words whose hash the device key signed. Change the rule
 * only when the server's rule changes.
 */
object ReasonValidation {

    const val MAX_BYTES = 500

    /**
     * The 14 rejected invisible formatting code points. This is a fixed list,
     * not Unicode category Cf: bidi embeddings, overrides and PDF
     * U+202A..U+202E, bidi isolates U+2066..U+2069, direction marks U+200E,
     * U+200F and U+061C, and the invisibles U+200B and U+FEFF. The bidi
     * characters can reorder displayed text on an audit record; the invisibles
     * make identical-looking reasons differ byte for byte. U+2060 WORD JOINER,
     * ZWJ U+200D and ZWNJ U+200C stay legal: emoji sequences and Persian and
     * Indic orthography need the joiners.
     */
    private val FORMATTING_REJECTS = charArrayOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
        '\u200E', '\u200F', '\u061C',
        '\u200B', '\uFEFF',
    )

    /**
     * Normalizes a UI draft to its wire form: trimmed, or null when nothing is
     * left. Null is the only encoding of "no reason"; the wire does not carry
     * an empty string.
     *
     * @throws IllegalArgumentException when a non-empty draft breaks the rule.
     */
    fun forWire(draft: String?): String? {
        val trimmed = draft?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        requireValid(trimmed)
        return trimmed
    }

    /**
     * What [DecisionSigning] requires of a reason before binding it: non-empty,
     * already trimmed (the signed bytes must be the bytes the body carries),
     * within the byte cap, well-formed UTF-16 (a lone surrogate cannot be
     * encoded without substitution), and free of the rejected characters.
     */
    fun requireValid(reason: String) {
        require(reason.isNotEmpty()) { "reason must not be empty (send no reason instead)" }
        require(reason.trim() == reason) { "reason must be trimmed before signing" }
        for (c in reason) {
            require(c.code >= 0x20 || c == '\n' || c == '\t') { "reason must not contain control characters" }
            require(c.code != 0x7F) { "reason must not contain control characters" }
            require(c !in FORMATTING_REJECTS) { "reason must not contain invisible formatting characters" }
        }
        val bytes = try {
            reason.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("reason is not valid UTF-16 text")
        }
        require(bytes.size <= MAX_BYTES) { "reason must be at most $MAX_BYTES bytes (was ${bytes.size})" }
    }

    /**
     * UTF-8 byte length for the UI counter, because the cap is in bytes.
     * Lenient: text being composed may briefly hold a lone surrogate, and the
     * counter must not throw.
     */
    fun utf8ByteLength(text: String): Int = text.encodeToByteArray().size
}
