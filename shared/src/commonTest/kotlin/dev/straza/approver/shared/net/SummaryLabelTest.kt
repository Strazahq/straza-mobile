package dev.straza.approver.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryLabelTest {

    @Test
    fun `mcp call with an app reads app and tool name`() {
        assertEquals("midpoint:disable_user", SummaryLabel.of("mcp.call", "midpoint", "disable_user"))
    }

    @Test
    fun `mcp call without an app is the tool name alone`() {
        assertEquals("disable_user", SummaryLabel.of("mcp.call", "", "disable_user"))
    }

    /** A non-MCP row carries its identity in `tool`, with app and toolName empty. */
    @Test
    fun `a non-mcp row falls back to the tool field`() {
        assertEquals("kubectl", SummaryLabel.of("kubectl", "", ""))
    }

    @Test
    fun `nothing in the summary is an empty label`() {
        assertEquals("", SummaryLabel.of("", "", ""))
    }
}
