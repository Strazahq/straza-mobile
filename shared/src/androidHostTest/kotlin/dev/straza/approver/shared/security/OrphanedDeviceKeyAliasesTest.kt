package dev.straza.approver.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrphanedDeviceKeyAliasesTest {

    @Test
    fun `selects only unreferenced device-key aliases`() {
        val kept = listOf("prj_a", "host:acme.example")
        val resident = listOf(
            deviceKeyAlias("prj_a"), // referenced, kept
            deviceKeyAlias("host:acme.example"), // referenced, kept
            deviceKeyAlias("prj_gone"), // orphan, selected
        )
        assertEquals(listOf(deviceKeyAlias("prj_gone")), orphanedDeviceKeyAliases(resident, kept))
    }

    @Test
    fun `never selects the legacy alias even when unreferenced`() {
        // Convergence detects an orphaned legacy key and explains it to the
        // user, so the sweep leaves it alone.
        assertTrue(orphanedDeviceKeyAliases(listOf(LEGACY_DEVICE_KEY_ALIAS), emptyList()).isEmpty())
    }

    @Test
    fun `never selects aliases outside the device-key prefix`() {
        val resident = listOf(
            "dev.straza.approver.store.v1", // the blob store's wrapping key
            "some.other.app.key",
        )
        assertTrue(orphanedDeviceKeyAliases(resident, emptyList()).isEmpty())
    }

    @Test
    fun `an in-flight enrollment's alias survives the sweep`() {
        // Permission dialogs re-run onStart during an enroll, so the sweep can
        // run before the new record is stored. Its keyRef is in the kept set.
        val resident = listOf(deviceKeyAlias("prj_enrolling"))
        assertTrue(orphanedDeviceKeyAliases(resident, listOf("prj_enrolling")).isEmpty())
    }

    @Test
    fun `blank keyRefs contribute nothing and cannot expose the legacy alias`() {
        // A blank keyRef maps to the legacy alias, which the sweep excludes anyway.
        val resident = listOf(LEGACY_DEVICE_KEY_ALIAS, deviceKeyAlias("prj_gone"))
        assertEquals(
            listOf(deviceKeyAlias("prj_gone")),
            orphanedDeviceKeyAliases(resident, listOf("")),
        )
    }
}
