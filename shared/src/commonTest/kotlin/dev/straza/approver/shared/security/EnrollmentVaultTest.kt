package dev.straza.approver.shared.security

import dev.straza.approver.shared.protocol.SpkiPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// SHA-256 of the empty string: a well-formed 32-byte sha256/ pin.
private const val VALID_PIN = "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

// Valid base64 of the wrong length (3 bytes), so SpkiPin.parse() returns null.
private const val BAD_PIN = "sha256/QUFB"

private fun enrollment(
    id: String = "apd_1",
    token: String = "tok",
    servers: List<String> = listOf("https://a.example:8443"),
    pin: SpkiPin? = null,
    device: String = "pixel",
    projectId: String = "",
    projectName: String? = null,
    localLabel: String? = null,
    keyRef: String = "",
) = Enrollment(id, token, servers, pin, device, projectId, projectName, localLabel, keyRef)

class EnrollmentVaultTest {

    @Test
    fun `serverHost strips scheme port and path`() {
        assertEquals("a.example", serverHost("https://a.example:8443/v1/x"))
        assertEquals("straza.corp.internal", serverHost("https://straza.corp.internal"))
        assertEquals("plainstring", serverHost("plainstring"))
    }

    @Test
    fun `serverHost unwraps a bracketed IPv6 literal`() {
        assertEquals("::1", serverHost("https://[::1]:8443"))
        assertEquals("::1", serverHost("https://[::1]"))
        assertEquals("2001:db8::1", serverHost("https://[2001:db8::1]/v1/x"))
    }

    @Test
    fun `serverHost strips userinfo before the host`() {
        assertEquals("acme.example", serverHost("https://user@acme.example/"))
        assertEquals("acme.example", serverHost("https://user:pass@acme.example:8443"))
        assertEquals("::1", serverHost("https://user@[::1]:8443"))
    }

    @Test
    fun `serverHost falls back sanely on a bracket with no closing bracket`() {
        // A malformed authority must still resolve to something non-blank without
        // throwing: the value selects a signing key downstream.
        assertEquals("::1", serverHost("https://[::1"))
    }

    @Test
    fun `projectKey uses the server project id when present`() {
        assertEquals("prj_9", projectKey(enrollment(projectId = "prj_9")))
    }

    @Test
    fun `projectKey derives from host when blank so re-scanning one backend dedups`() {
        val a = enrollment(servers = listOf("https://acme.example:8443"))
        val b = enrollment(id = "apd_2", servers = listOf("https://acme.example:8443/other"))
        assertEquals(projectKey(a), projectKey(b))
        assertEquals("host:acme.example", projectKey(a))
    }

    @Test
    fun `projectKey differs across backends`() {
        assertTrue(
            projectKey(enrollment(servers = listOf("https://a.example"))) !=
                projectKey(enrollment(servers = listOf("https://b.example"))),
        )
    }

    @Test
    fun `projectKey keeps distinct IPv6 backends distinct`() {
        // A host parsed as a bare '[' would give every project-less IPv6
        // deployment one identity, and one enrollment would replace another.
        val a = enrollment(servers = listOf("https://[::1]:8443"))
        val b = enrollment(id = "apd_2", servers = listOf("https://[2001:db8::1]:8443"))
        assertTrue(projectKey(a) != projectKey(b))
        assertEquals("host:::1", projectKey(a))
    }

    @Test
    fun `displayName prefers local label then server name then host then device`() {
        assertEquals("Home", enrollment(localLabel = "Home", projectName = "Acme").displayName())
        assertEquals("Acme", enrollment(projectName = "Acme").displayName())
        assertEquals("a.example", enrollment(servers = listOf("https://a.example:8443")).displayName())
        assertEquals("pixel", enrollment(servers = emptyList(), device = "pixel").displayName())
    }

    @Test
    fun `upsert adds a deployment and makes it active`() {
        val v = EnrollmentVault().upsert(enrollment(projectId = "p1"))
        assertEquals(1, v.deployments.size)
        assertEquals("p1", v.activeProjectId)
        assertEquals("p1", projectKey(v.active()!!))
    }

    @Test
    fun `upsert with the same key replaces rather than duplicating`() {
        val v = EnrollmentVault()
            .upsert(enrollment(id = "apd_1", projectId = "p1", localLabel = "old"))
            .upsert(enrollment(id = "apd_1b", projectId = "p1", localLabel = "new"))
        assertEquals(1, v.deployments.size)
        assertEquals("new", v.active()!!.localLabel)
    }

    @Test
    fun `upsert with different keys keeps both newest active`() {
        val v = EnrollmentVault().upsert(enrollment(projectId = "p1")).upsert(enrollment(projectId = "p2"))
        assertEquals(2, v.deployments.size)
        assertEquals("p2", v.activeProjectId)
    }

    @Test
    fun `removing the active deployment reassigns active to what remains`() {
        val v = EnrollmentVault()
            .upsert(enrollment(projectId = "p1"))
            .upsert(enrollment(projectId = "p2"))
            .remove("p2")
        assertEquals(1, v.deployments.size)
        assertEquals("p1", v.activeProjectId)
    }

    @Test
    fun `removing the last deployment empties the vault and nulls active`() {
        val v = EnrollmentVault().upsert(enrollment(projectId = "p1")).remove("p1")
        assertTrue(v.isEmpty)
        assertNull(v.activeProjectId)
        assertNull(v.active())
    }

    @Test
    fun `removing a non-active deployment leaves active untouched`() {
        val v = EnrollmentVault()
            .upsert(enrollment(projectId = "p1"))
            .upsert(enrollment(projectId = "p2"))
            .withActive("p1")
            .remove("p2")
        assertEquals("p1", v.activeProjectId)
    }

    @Test
    fun `update replaces in place without changing active`() {
        // A token renewal for a non-active deployment must not switch the user to it.
        val v = EnrollmentVault()
            .upsert(enrollment(projectId = "p1", token = "old"))
            .upsert(enrollment(id = "apd_2", projectId = "p2"))
        val u = v.update(enrollment(projectId = "p1", token = "new"))
        assertEquals("p2", u.activeProjectId)
        assertEquals("new", u.find("p1")!!.deviceToken)
        assertEquals(2, u.deployments.size)
    }

    @Test
    fun `update of a missing key is a no-op never a resurrection`() {
        // A renewal that lands after its deployment was removed must not re-add
        // a record whose key is already gone.
        val v = EnrollmentVault().upsert(enrollment(projectId = "p1"))
        assertEquals(v, v.update(enrollment(projectId = "p_gone")))
    }

    @Test
    fun `codec round-trips the token expiry`() {
        val v = EnrollmentVault().upsert(
            enrollment(projectId = "p1").copy(tokenExpiresAtEpochSeconds = 1_755_600_000L),
        )
        val back = EnrollmentVaultCodec.decode(EnrollmentVaultCodec.encode(v))
        assertEquals(1_755_600_000L, back.find("p1")!!.tokenExpiresAtEpochSeconds)
        // An absent expiry (a pre-0.23 record) decodes to unknown.
        assertNull(
            EnrollmentVaultCodec.decode(
                EnrollmentVaultCodec.encode(EnrollmentVault().upsert(enrollment(projectId = "p2"))),
            ).find("p2")!!.tokenExpiresAtEpochSeconds,
        )
    }

    @Test
    fun `codec round-trips the per-pairing push lanes and treats absence as lane-off`() {
        // A lost VAPID key downgrades push to the keyless lane, and a lost fcm
        // block disables the FCM lane on restart.
        val fcm = dev.straza.approver.shared.net.FcmAppConfig(
            projectId = "acme-prod",
            appId = "1:123456789:android:abcdef012345",
            apiKey = "AIzaSyExample",
            senderId = "123456789",
        )
        val v = EnrollmentVault().upsert(
            enrollment(projectId = "p1").copy(vapidPublicKey = "BExampleVapidKey", fcm = fcm),
        )
        val back = EnrollmentVaultCodec.decode(EnrollmentVaultCodec.encode(v)).find("p1")!!
        assertEquals("BExampleVapidKey", back.vapidPublicKey)
        assertEquals(fcm, back.fcm)

        // A record written before these fields existed decodes with both null.
        val pre040 =
            """{"v":2,"active":"p2","deployments":[
               {"approverDeviceId":"a1","deviceToken":"t","servers":["https://a"],"deviceName":"d","projectId":"p2"}]}"""
        val old = EnrollmentVaultCodec.decode(pre040).find("p2")!!
        assertNull(old.vapidPublicKey)
        assertNull(old.fcm)
    }

    @Test
    fun `withActive switches and ignores an unknown key`() {
        val v = EnrollmentVault().upsert(enrollment(projectId = "p1")).upsert(enrollment(projectId = "p2"))
        assertEquals("p1", v.withActive("p1").activeProjectId)
        assertEquals("p2", v.withActive("nope").activeProjectId)
    }

    @Test
    fun `codec round-trips deployments active and a pin`() {
        val v = EnrollmentVault()
            .upsert(enrollment(id = "apd_1", projectId = "p1", pin = SpkiPin.parse(VALID_PIN), projectName = "Acme", keyRef = "kr1"))
            .upsert(enrollment(id = "apd_2", projectId = "p2"))
            .withActive("p1")
        val back = EnrollmentVaultCodec.decode(EnrollmentVaultCodec.encode(v))
        assertEquals(2, back.deployments.size)
        assertEquals("p1", back.activeProjectId)
        val p1 = back.find("p1")!!
        assertEquals("Acme", p1.projectName)
        // keyRef selects the per-deployment signing key.
        assertEquals("kr1", p1.keyRef)
        // SpkiPin has no value equality, so compare the wire form.
        assertEquals(VALID_PIN, p1.pin?.encoded)
        assertEquals("apd_2", back.find("p2")!!.approverDeviceId)
    }

    @Test
    fun `codec migrates a legacy single record into a one-deployment vault`() {
        val legacy =
            """{"approverDeviceId":"apd_x","deviceToken":"t","servers":["https://acme.example:8443"],"pin":null,"deviceName":"pix"}"""
        val v = EnrollmentVaultCodec.decode(legacy)
        val e = v.deployments.single()
        assertEquals("apd_x", e.approverDeviceId)
        assertEquals("host:acme.example", projectKey(e))
        assertEquals("host:acme.example", v.activeProjectId)
    }

    @Test
    fun `codec migrates a legacy record that carried a pin`() {
        val legacy =
            """{"approverDeviceId":"apd_x","deviceToken":"t","servers":["https://a.example"],"pin":"$VALID_PIN","deviceName":"pix"}"""
        assertEquals(VALID_PIN, EnrollmentVaultCodec.decode(legacy).deployments.single().pin?.encoded)
    }

    @Test
    fun `codec decodes empty garbage and unknown versions to an empty vault`() {
        assertTrue(EnrollmentVaultCodec.decode(null).isEmpty)
        assertTrue(EnrollmentVaultCodec.decode("").isEmpty)
        assertTrue(EnrollmentVaultCodec.decode("not json {{{").isEmpty)
        assertTrue(EnrollmentVaultCodec.decode("""{"v":99,"deployments":[]}""").isEmpty)
    }

    @Test
    fun `codec drops a record with an unparseable pin but keeps the rest`() {
        val doc = """{"v":2,"active":"p2","deployments":[
            {"approverDeviceId":"a1","deviceToken":"t","servers":["https://a"],"pin":"$BAD_PIN","deviceName":"d","projectId":"p1"},
            {"approverDeviceId":"a2","deviceToken":"t","servers":["https://b"],"deviceName":"d","projectId":"p2"}
        ]}"""
        val v = EnrollmentVaultCodec.decode(doc)
        assertEquals(1, v.deployments.size)
        assertEquals("p2", v.deployments.single().projectId)
        assertEquals("p2", v.activeProjectId)
    }

    @Test
    fun `codec nulls active when it names no deployment`() {
        val doc = """{"v":2,"active":"ghost","deployments":[
            {"approverDeviceId":"a2","deviceToken":"t","servers":["https://b"],"deviceName":"d","projectId":"p2"}
        ]}"""
        assertNull(EnrollmentVaultCodec.decode(doc).activeProjectId)
    }

    @Test
    fun `a legacy record whose pin no longer parses decodes to empty never unpinned`() {
        val legacy =
            """{"approverDeviceId":"a","deviceToken":"t","servers":["https://a"],"pin":"$BAD_PIN","deviceName":"d"}"""
        assertTrue(EnrollmentVaultCodec.decode(legacy).isEmpty)
    }
}
