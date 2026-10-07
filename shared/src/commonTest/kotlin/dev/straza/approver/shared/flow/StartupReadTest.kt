package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.DevicePublicKey
import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeySecurityLevel
import dev.straza.approver.shared.security.KeyState
import dev.straza.approver.shared.security.VaultSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupReadTest {

    private val record = Enrollment("apd_1", "tok", listOf("https://a.example"), null, "pixel", projectId = "p1", keyRef = "p1")
    private val present = KeyState.Present(DevicePublicKey(ByteArray(91), KeySecurityLevel.STRONGBOX))

    @Test
    fun `unreadable with a held session runs on the held record and sweeps nothing`() {
        var swept: List<Enrollment>? = null
        var askedKeyRef: String? = null
        val read = StartupReads.resolve(
            VaultSnapshot.Unreadable("KeyStoreException"),
            held = record,
            onReadable = { swept = it },
            keyStateOf = { askedKeyRef = it; present },
        )
        val ready = assertIs<StartupRead.Ready>(read)
        assertTrue(ready.fromHeldSession)
        assertEquals(listOf(record), ready.all)
        assertEquals(record, ready.active)
        assertIs<KeyState.Indeterminate>(ready.keyState)
        assertNull(swept, "the orphan sweep must not run over an unreadable vault")
        assertNull(askedKeyRef, "no key-state lookup over an unreadable vault")
        // Convergence proceeds on it. A Converge would delete.
        assertIs<StartupDecision.Proceed>(Convergence.onStartup(ready.active, ready.keyState))
    }

    @Test
    fun `unreadable on a cold start tells and touches nothing`() {
        var swept = false
        val read = StartupReads.resolve(
            VaultSnapshot.Unreadable("GCM tag mismatch"),
            held = null,
            onReadable = { swept = true },
            keyStateOf = { present },
        )
        val unreadable = assertIs<StartupRead.Unreadable>(read)
        assertEquals("GCM tag mismatch", unreadable.reason)
        assertFalse(swept)
        assertTrue(StartupReads.unreadableMessage(unreadable.reason).contains("GCM tag mismatch"))
    }

    @Test
    fun `readable sweeps with the full record list and asks for the active key`() {
        var swept: List<Enrollment>? = null
        var askedKeyRef: String? = null
        val read = StartupReads.resolve(
            VaultSnapshot.Readable(listOf(record), record),
            held = null,
            onReadable = { swept = it },
            keyStateOf = { askedKeyRef = it; present },
        )
        val ready = assertIs<StartupRead.Ready>(read)
        assertFalse(ready.fromHeldSession)
        assertEquals(listOf(record), swept)
        assertEquals("p1", askedKeyRef)
        assertEquals(present, ready.keyState)
    }

    @Test
    fun `readable and empty asks for the legacy alias so an orphan is still found`() {
        var askedKeyRef: String? = null
        val read = StartupReads.resolve(
            VaultSnapshot.Readable(emptyList(), null),
            held = null,
            keyStateOf = { askedKeyRef = it; KeyState.Absent },
        )
        assertIs<StartupRead.Ready>(read)
        assertEquals("", askedKeyRef)
    }
}
