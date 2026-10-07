package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutionContextTest {

    @Test
    fun `session and harness join with a middot`() {
        assertEquals("01a00145… · claude-code", ExecutionContext.line("01a00145b2c93f70", "claude-code"))
    }

    @Test
    fun `session without harness stands alone`() {
        assertEquals("01a00145…", ExecutionContext.line("01a00145b2c93f70", ""))
    }

    @Test
    fun `short ids keep the browser ellipsis`() {
        // The browser's approvals page appends the ellipsis unconditionally,
        // and this matches it.
        assertEquals("abc…", ExecutionContext.line("abc", ""))
    }

    @Test
    fun `no session means no line even with a harness`() {
        assertNull(ExecutionContext.line("", "claude-code"))
        assertNull(ExecutionContext.line("", ""))
    }
}
