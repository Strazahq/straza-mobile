package dev.straza.approver.shared.security

import dev.straza.approver.shared.protocol.SpkiPin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fake [EncryptedBlobStore] that keeps the bytes in the clear. The real
 * encryption is covered by SecureStoreInstrumentedTest on a device.
 */
private class FakeBlobStore : EncryptedBlobStore {
    var bytes: ByteArray? = null
    var clears = 0
    var writes = 0
    /** When set, read() reports a store holding something this app cannot read. */
    var unreadable: String? = null
    override fun read(): BlobRead =
        unreadable?.let { BlobRead.Unreadable(it) } ?: bytes?.let { BlobRead.Bytes(it) } ?: BlobRead.Absent
    override fun write(bytes: ByteArray) { this.bytes = bytes; writes++ }
    override fun clear() { bytes = null; clears++ }
}

private fun enrollment(
    id: String = "apd_1",
    servers: List<String> = listOf("https://a.example:8443"),
    pin: SpkiPin? = null,
    projectId: String = "",
    token: String = "tok",
) = Enrollment(id, token, servers, pin, "pixel", projectId)

class SecureStoreTest {

    @Test
    fun `starts empty`() {
        val s = SecureStore(FakeBlobStore())
        assertNull(s.load())
        assertTrue(s.loadAll().isEmpty())
    }

    @Test
    fun `save then load round-trips the active enrollment with its pin`() {
        val s = SecureStore(FakeBlobStore())
        s.save(enrollment(pin = SpkiPin.parse("sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")))
        val loaded = s.load()!!
        assertEquals("apd_1", loaded.approverDeviceId)
        assertEquals("sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", loaded.pin?.encoded)
    }

    @Test
    fun `save replaces rather than accumulating the N=1 path`() {
        val s = SecureStore(FakeBlobStore())
        s.save(enrollment(id = "apd_1", servers = listOf("https://a.example")))
        s.save(enrollment(id = "apd_2", servers = listOf("https://b.example")))
        assertEquals(1, s.loadAll().size)
        assertEquals("apd_2", s.load()?.approverDeviceId)
    }

    @Test
    fun `upsert accumulates deployments and switches active`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1"))
        s.upsert(enrollment(projectId = "p2"))
        assertEquals(2, s.loadAll().size)
        assertEquals("p2", projectKey(s.active()!!))
        s.setActive("p1")
        assertEquals("p1", projectKey(s.active()!!))
    }

    @Test
    fun `find returns the deployment under a key and null otherwise`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1"))
        assertEquals("apd_1", s.find("p1")?.approverDeviceId)
        assertNull(s.find("p2"))
    }

    @Test
    fun `update rewrites a record without stealing active`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1", token = "old"))
        s.upsert(enrollment(id = "apd_2", projectId = "p2"))
        s.update(enrollment(projectId = "p1", token = "new"))
        assertEquals("p2", projectKey(s.active()!!))
        assertEquals("new", s.find("p1")?.deviceToken)
    }

    @Test
    fun `update never resurrects a removed deployment`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1"))
        s.remove("p1")
        s.update(enrollment(projectId = "p1", token = "late-renewal"))
        assertTrue(s.loadAll().isEmpty())
    }

    @Test
    fun `remove drops one deployment and re-homes active`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1"))
        s.upsert(enrollment(projectId = "p2"))
        s.remove("p2")
        assertEquals(1, s.loadAll().size)
        assertEquals("p1", projectKey(s.active()!!))
    }

    @Test
    fun `removing the last deployment clears the blob`() {
        val fake = FakeBlobStore()
        val s = SecureStore(fake)
        s.upsert(enrollment(projectId = "p1"))
        s.remove("p1")
        assertTrue(s.loadAll().isEmpty())
        assertNull(fake.bytes)
        assertTrue(fake.clears >= 1)
    }

    @Test
    fun `clear wipes everything`() {
        val s = SecureStore(FakeBlobStore())
        s.upsert(enrollment(projectId = "p1"))
        s.clear()
        assertNull(s.load())
    }

    @Test
    fun `migrates a pre-multi single record already on disk`() {
        val fake = FakeBlobStore()
        // What the older single-record store left behind, decrypted.
        fake.bytes =
            """{"approverDeviceId":"apd_old","deviceToken":"t","servers":["https://acme.example:8443"],"pin":null,"deviceName":"pix"}"""
                .encodeToByteArray()
        val s = SecureStore(fake)
        assertEquals("apd_old", s.load()?.approverDeviceId)
        assertEquals("host:acme.example", s.active()?.let(::projectKey))
    }

    @Test
    fun `unreadable bytes decode to no enrollment never a guess`() {
        val fake = FakeBlobStore()
        fake.bytes = "not json {{{".encodeToByteArray()
        val s = SecureStore(fake)
        assertNull(s.load())
        // A non-empty record that decodes to nothing is not an empty vault. It is
        // one this build cannot read, for example after a downgrade.
        assertIs<VaultSnapshot.Unreadable>(s.snapshot())
    }

    // "Cannot read" is not "empty"

    @Test
    fun `an absent blob is a readable empty vault`() {
        val snap = SecureStore(FakeBlobStore()).snapshot()
        assertIs<VaultSnapshot.Readable>(snap)
        assertTrue(snap.all.isEmpty())
        assertNull(snap.active)
    }

    @Test
    fun `an unreadable blob reports unreadable and the getters answer nothing`() {
        val fake = FakeBlobStore().apply { unreadable = "KeyStoreException" }
        val s = SecureStore(fake)
        val snap = s.snapshot()
        assertIs<VaultSnapshot.Unreadable>(snap)
        assertEquals("KeyStoreException", snap.reason)
        assertNull(s.load())
        assertTrue(s.loadAll().isEmpty())
        assertNull(s.find("p1"))
    }

    /**
     * `remove` of what looks like the last record would clear the blob and its
     * wrapping key, destroying the record that could not be read.
     */
    @Test
    fun `mutations on an unreadable vault persist nothing`() {
        val fake = FakeBlobStore()
        SecureStore(fake).upsert(enrollment(projectId = "p1"))
        val stored = fake.bytes!!.copyOf()
        fake.unreadable = "GCM tag mismatch"
        val s = SecureStore(fake)
        s.update(enrollment(projectId = "p1", token = "renewed"))
        s.remove("p1")
        s.setActive("p1")
        assertEquals(1, fake.writes, "no write may happen over an unreadable read")
        assertEquals(0, fake.clears, "remove over an unreadable read must not clear the store")
        assertContentEquals(stored, fake.bytes)
    }

    /** Pairing again is the user's way out: it replaces the unreadable record. */
    @Test
    fun `upsert on an unreadable vault writes a fresh vault with only the new record`() {
        val fake = FakeBlobStore()
        SecureStore(fake).upsert(enrollment(projectId = "p1"))
        fake.unreadable = "storage key missing"
        SecureStore(fake).upsert(enrollment(id = "apd_2", projectId = "p2"))
        fake.unreadable = null
        val after = SecureStore(fake).loadAll()
        assertEquals(listOf("apd_2"), after.map { it.approverDeviceId })
    }
}
