package dev.straza.approver.shared.net

/**
 * Builds the tool label from the server's redacted `summary` object (wire
 * keys `tool`, `app`, `tool_name`). The server sends three shapes:
 *  - MCP call with an app: `tool="mcp.call"`, with `app` and `tool_name` set
 *  - MCP call without one: `tool="mcp.call"` and `tool_name`
 *  - anything else: only `tool` carries the identity
 * so the label is `app:toolName`, else `toolName`, else `tool`.
 */
object SummaryLabel {
    fun of(tool: String, app: String, toolName: String): String = when {
        app.isNotBlank() && toolName.isNotBlank() -> "$app:$toolName"
        toolName.isNotBlank() -> toolName
        else -> tool
    }
}
