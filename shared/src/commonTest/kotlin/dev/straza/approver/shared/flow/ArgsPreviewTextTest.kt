package dev.straza.approver.shared.flow

import kotlin.test.Test
import kotlin.test.assertEquals

class ArgsPreviewTextTest {

    @Test
    fun `call scope is the only stronger claim`() {
        assertEquals(
            "preview only; approval binds the exact call (sha256:a3f19c4b2e00…)",
            ArgsPreviewText.honestyLine("call", "a3f19c4b2e00"),
        )
    }

    @Test
    fun `tool_identity and any unrecognised scope both render the weaker wording`() {
        val weaker = "preview only; approval binds tool identity (sha256:a3f19c4b2e00…)"
        assertEquals(weaker, ArgsPreviewText.honestyLine("tool_identity", "a3f19c4b2e00"))
        // An unrecognised scope must not be presented as binding the exact call.
        assertEquals(weaker, ArgsPreviewText.honestyLine("some_future_scope", "a3f19c4b2e00"))
        assertEquals(weaker, ArgsPreviewText.honestyLine("", "a3f19c4b2e00"))
    }

    @Test
    fun `a blank hash prefix drops the fingerprint clause`() {
        assertEquals("preview only; approval binds tool identity", ArgsPreviewText.honestyLine("tool_identity", ""))
    }

    @Test
    fun `truncation footnote names the byte count and the audit log`() {
        assertEquals(
            "preview truncated: 1540 bytes total (full args in the server audit log)",
            ArgsPreviewText.truncationFootnote(1540L),
        )
        assertEquals("preview truncated (full args in the server audit log)", ArgsPreviewText.truncationFootnote(null))
    }
}
