package dev.straza.approver.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceKeyAliasTest {

    @Test
    fun `a blank keyRef maps to the exact legacy alias`() {
        // This string shipped. Changing it strands the keys created under it.
        assertEquals("dev.straza.approver.device.v1", deviceKeyAlias(""))
        assertEquals(LEGACY_DEVICE_KEY_ALIAS, deviceKeyAlias(""))
        assertEquals(LEGACY_DEVICE_KEY_ALIAS, deviceKeyAlias("   "))
    }

    @Test
    fun `a non-blank keyRef is a versioned prefixed sha256 alias`() {
        val alias = deviceKeyAlias("prj_0192f3a4")
        assertTrue(alias.startsWith("$DEVICE_KEY_ALIAS_PREFIX."), "prefixed: $alias")
        assertTrue(alias.endsWith(".v1"), "versioned: $alias")
        val hash = alias.removePrefix("$DEVICE_KEY_ALIAS_PREFIX.").removeSuffix(".v1")
        // SHA-256 rendered as lowercase hex: 64 chars, [0-9a-f] only.
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" }, "lowercase hex: $hash")
    }

    @Test
    fun `the same keyRef always derives the same alias`() {
        assertEquals(deviceKeyAlias("host:acme.example"), deviceKeyAlias("host:acme.example"))
    }

    @Test
    fun `distinct keyRefs derive distinct aliases`() {
        assertNotEquals(deviceKeyAlias("prj_a"), deviceKeyAlias("prj_b"))
        assertNotEquals(deviceKeyAlias("host:a.example"), deviceKeyAlias("host:b.example"))
        // A non-blank keyRef does not collide with the legacy (blank) alias.
        assertNotEquals(deviceKeyAlias(""), deviceKeyAlias("prj_a"))
    }

    @Test
    fun `keyRefs that char-substitution would have merged now stay distinct`() {
        // Substituting unsafe characters would map '/' and '_' both to '_' and
        // give these two deployments one key.
        assertNotEquals(deviceKeyAlias("acme/prod"), deviceKeyAlias("acme_prod"))
        assertNotEquals(deviceKeyAlias("host:acme.example"), deviceKeyAlias("host_acme.example"))
        assertNotEquals(deviceKeyAlias("a b"), deviceKeyAlias("a_b"))
        assertNotEquals(deviceKeyAlias("a:b"), deviceKeyAlias("a-b"))
    }

    @Test
    fun `realistic project ids and hosts all produce well-formed distinct aliases`() {
        val keyRefs = listOf(
            "prj_0192f3a4b5c6",
            "prj_0192f3a4b5c7",
            "host:straza.corp.internal",
            "host:acme.example",
            "host:acme.example:8443", // serverHost strips the port upstream, but the raw value is still injective here
        )
        val aliases = keyRefs.map(::deviceKeyAlias)
        assertEquals(keyRefs.size, aliases.toSet().size)
        assertTrue(aliases.all { it.startsWith("$DEVICE_KEY_ALIAS_PREFIX.") && it.endsWith(".v1") })
    }
}
