package dev.straza.approver.shared.flow

/**
 * The execution-context line: which agent session and harness raised the
 * request, for example "01a00145… · claude-code". The session id is cut to 8
 * characters with an ellipsis (the audit record holds the full id), and the
 * harness is appended when the server resolved one. No session id, as from a
 * server before openapi 0.69.0, means no line, even with a harness.
 */
object ExecutionContext {

    fun line(sessionId: String, harness: String): String? {
        if (sessionId.isBlank()) return null
        val cut = sessionId.take(8) + "…"
        return if (harness.isBlank()) cut else "$cut · $harness"
    }
}
