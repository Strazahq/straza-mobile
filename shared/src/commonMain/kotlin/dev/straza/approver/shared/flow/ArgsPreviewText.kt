package dev.straza.approver.shared.flow

/**
 * Rendering helpers for the server's args preview. The app renders the preview
 * verbatim, because the server makes it display-safe: it redacts, turns every
 * C0/C1 control (except `\n` and `\t`), bidi control and zero-width or
 * invisible character into a visible `\uXXXX` escape, then measures and caps.
 * A second neutralising pass here would be a weaker validator over content
 * the server already made safe. Redacted values arrive as a visible
 * `[REDACTED]` marker and a no-arg call as `"{}"`.
 */
object ArgsPreviewText {

    /**
     * The line that says what the approval's hash binds. Only `call` gets the
     * "exact call" wording. `tool_identity` and any unrecognised value get the
     * weaker "tool identity" wording, so a future scope is not over-claimed.
     */
    fun honestyLine(bindingScope: String, hashPrefix: String): String {
        val fp = if (hashPrefix.isNotBlank()) " (sha256:$hashPrefix…)" else ""
        return if (bindingScope == "call") {
            "preview only; approval binds the exact call$fp"
        } else {
            "preview only; approval binds tool identity$fp"
        }
    }

    /**
     * The footnote shown when the preview was cut. [bytes] is the redacted and
     * neutralised length before truncation.
     */
    fun truncationFootnote(bytes: Long?): String =
        if (bytes != null) "preview truncated: $bytes bytes total (full args in the server audit log)"
        else "preview truncated (full args in the server audit log)"
}
