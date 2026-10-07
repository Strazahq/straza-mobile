package dev.straza.approver.shared.net

/**
 * The human-readable title for the server's redacted tool identity, shared by
 * the pending card, the decision screen, the activity feed and the biometric
 * prompt. It has to produce the same strings as the server's browser page.
 *
 * A blank identity gives the generic phrase. An MCP call with a `tool_name`
 * reads as its words with underscores turned into spaces ("request role in
 * midpoint"). A bare kind maps through the fixed table, and an unknown kind
 * is shown raw. [wire] is the machine identity ("mcp.call
 * midpoint:request_role"), the form the console, Slack and the audit record
 * use.
 */
object SummaryText {

    private val KIND = mapOf(
        "shell.exec" to "run a shell command",
        "file.read" to "read files",
        "file.write" to "write files",
        "file.edit" to "edit files",
        "net.fetch" to "fetch a URL",
        "task.spawn" to "start a background task",
        "mcp.call" to "an MCP tool call",
        "other" to "a tool call",
    )

    fun title(tool: String, app: String, toolName: String): String {
        if (tool.isBlank()) return "a tool call"
        if (tool == "mcp.call" && toolName.isNotBlank()) {
            val words = toolName.replace('_', ' ')
            return if (app.isNotBlank()) "$words in $app" else words
        }
        return KIND[tool] ?: tool
    }

    fun wire(tool: String, app: String, toolName: String): String = when {
        tool.isBlank() -> ""
        app.isNotBlank() && toolName.isNotBlank() -> "$tool $app:$toolName"
        toolName.isNotBlank() -> "$tool $toolName"
        else -> tool
    }
}
