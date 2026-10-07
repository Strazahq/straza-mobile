package dev.straza.approver.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** The expected strings are what the server's approvals page renders for the same wire values. */
class SummaryTextTest {

    @Test
    fun `mcp call with app titles as words in app`() {
        assertEquals("request role in midpoint", SummaryText.title("mcp.call", "midpoint", "request_role"))
    }

    @Test
    fun `mcp call without app titles as bare words`() {
        assertEquals("request role", SummaryText.title("mcp.call", "", "request_role"))
    }

    @Test
    fun `every underscore becomes a space`() {
        assertEquals("add org member role in gh", SummaryText.title("mcp.call", "gh", "add_org_member_role"))
    }

    @Test
    fun `bare kinds read as the browser verb phrases`() {
        assertEquals("run a shell command", SummaryText.title("shell.exec", "", ""))
        assertEquals("read files", SummaryText.title("file.read", "", ""))
        assertEquals("write files", SummaryText.title("file.write", "", ""))
        assertEquals("edit files", SummaryText.title("file.edit", "", ""))
        assertEquals("fetch a URL", SummaryText.title("net.fetch", "", ""))
        assertEquals("start a background task", SummaryText.title("task.spawn", "", ""))
        assertEquals("a tool call", SummaryText.title("other", "", ""))
    }

    @Test
    fun `mcp call without a tool name falls back to the kind phrase`() {
        assertEquals("an MCP tool call", SummaryText.title("mcp.call", "", ""))
    }

    @Test
    fun `unknown kind renders raw not guessed`() {
        assertEquals("registry.push", SummaryText.title("registry.push", "", ""))
    }

    @Test
    fun `blank identity degrades to the generic phrase`() {
        assertEquals("a tool call", SummaryText.title("", "", ""))
        assertEquals("a tool call", SummaryText.title("", "midpoint", "request_role"))
    }

    @Test
    fun `wire keeps the exact machine identity`() {
        assertEquals("mcp.call midpoint:request_role", SummaryText.wire("mcp.call", "midpoint", "request_role"))
        assertEquals("mcp.call request_role", SummaryText.wire("mcp.call", "", "request_role"))
        assertEquals("shell.exec", SummaryText.wire("shell.exec", "", ""))
        assertEquals("", SummaryText.wire("", "", ""))
    }
}
