package dev.straza.approver.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure logic, so it runs on the simulator without a Secure Enclave. */
class DeviceKeyTagIosTest {

    @Test
    fun blankKeyRefMapsToTheLegacyTag() {
        assertEquals("dev.straza.approver.device.v1", deviceKeyTag(""))
        assertEquals(LEGACY_DEVICE_KEY_TAG, deviceKeyTag(""))
        assertEquals(LEGACY_DEVICE_KEY_TAG, deviceKeyTag("   "))
    }

    @Test
    fun nonBlankKeyRefIsPrefixedAndVersioned() {
        val tag = deviceKeyTag("prj_0192f3a4")
        assertTrue(tag.startsWith("$DEVICE_KEY_TAG_PREFIX."), "prefixed: $tag")
        assertTrue(tag.endsWith(".v1"), "versioned: $tag")
    }

    @Test
    fun theSameKeyRefAlwaysDerivesTheSameTag() {
        assertEquals(deviceKeyTag("host:acme.example"), deviceKeyTag("host:acme.example"))
    }

    @Test
    fun distinctKeyRefsDeriveDistinctTags() {
        assertNotEquals(deviceKeyTag("prj_a"), deviceKeyTag("prj_b"))
        assertNotEquals(deviceKeyTag("host:a.example"), deviceKeyTag("host:b.example"))
        // A non-blank keyRef does not collide with the legacy (blank) tag.
        assertNotEquals(deviceKeyTag(""), deviceKeyTag("prj_a"))
    }

    @Test
    fun keyRefsThatCharSubstitutionWouldHaveMergedStayDistinct() {
        // The tag embeds the keyRef unchanged, so distinct keyRefs cannot merge.
        assertNotEquals(deviceKeyTag("acme/prod"), deviceKeyTag("acme_prod"))
        assertNotEquals(deviceKeyTag("host:acme.example"), deviceKeyTag("host_acme.example"))
        assertNotEquals(deviceKeyTag("a b"), deviceKeyTag("a_b"))
        assertNotEquals(deviceKeyTag("a:b"), deviceKeyTag("a-b"))
    }

    @Test
    fun realisticProjectIdsAndHostsAllProduceDistinctWellFormedTags() {
        val keyRefs = listOf(
            "prj_0192f3a4b5c6",
            "prj_0192f3a4b5c7",
            "host:straza.corp.internal",
            "host:acme.example",
            "host:acme.example:8443",
        )
        val tags = keyRefs.map(::deviceKeyTag)
        assertEquals(keyRefs.size, tags.toSet().size, "all distinct")
        assertTrue(tags.all { it.startsWith("$DEVICE_KEY_TAG_PREFIX.") && it.endsWith(".v1") })
    }
}
