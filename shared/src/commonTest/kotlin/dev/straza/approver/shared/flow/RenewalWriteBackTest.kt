package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.BlobRead
import dev.straza.approver.shared.security.EncryptedBlobStore
import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class WriteBackFakeBlob : EncryptedBlobStore {
    var bytes: ByteArray? = null
    var unreadable: String? = null
    var writes = 0
    override fun read(): BlobRead =
        unreadable?.let { BlobRead.Unreadable(it) } ?: bytes?.let { BlobRead.Bytes(it) } ?: BlobRead.Absent
    override fun write(bytes: ByteArray) { this.bytes = bytes; writes++ }
    override fun clear() { bytes = null }
}

class RenewalWriteBackTest {

    private val now = 1_789_000_000L
    private val bound = Enrollment("apd_1", "old", listOf("https://a.example"), null, "pixel", projectId = "p1", keyRef = "p1")

    private fun storeWith(vararg records: Enrollment): Pair<SecureStore, WriteBackFakeBlob> {
        val blob = WriteBackFakeBlob()
        val store = SecureStore(blob)
        records.forEach(store::upsert)
        return store to blob
    }

    @Test
    fun `writes the new token and deadline onto the stored record`() {
        val (store, _) = storeWith(bound)
        val result = RenewalWriteBacks.persist(store, bound, "fresh", 2_592_000, now)
        val applied = assertIs<RenewalWriteBack.Applied>(result)
        assertEquals("fresh", applied.renewed.deviceToken)
        assertEquals(now + 2_592_000, applied.renewed.tokenExpiresAtEpochSeconds)
        assertEquals("fresh", store.find("p1")?.deviceToken)
    }

    @Test
    fun `a rename that landed during the signature survives - the token is all renewal changes`() {
        val (store, _) = storeWith(bound)
        store.update(bound.copy(localLabel = "Prod (EU)"))
        val applied = assertIs<RenewalWriteBack.Applied>(RenewalWriteBacks.persist(store, bound, "fresh", 10, now))
        assertEquals("Prod (EU)", applied.renewed.localLabel)
        assertEquals("Prod (EU)", store.find("p1")?.localLabel)
    }

    @Test
    fun `a deployment re-paired mid-renewal keeps its own token - the old row's token is dropped`() {
        val (store, blob) = storeWith(bound.copy(approverDeviceId = "apd_2", deviceToken = "theirs", keyRef = "p1"))
        val before = blob.writes
        assertIs<RenewalWriteBack.Dropped>(RenewalWriteBacks.persist(store, bound, "fresh-for-apd_1", 10, now))
        assertEquals(before, blob.writes, "nothing may be written onto the re-paired record")
        assertEquals("theirs", store.find("p1")?.deviceToken)
    }

    @Test
    fun `a record retired meanwhile is not resurrected`() {
        val (store, blob) = storeWith(bound)
        store.remove("p1")
        val before = blob.writes
        assertIs<RenewalWriteBack.Dropped>(RenewalWriteBacks.persist(store, bound, "fresh", 10, now))
        assertEquals(before, blob.writes)
        assertNull(store.find("p1"))
    }

    @Test
    fun `an unreadable store gets nothing written`() {
        val (store, blob) = storeWith(bound)
        blob.unreadable = "KeyStoreException"
        val before = blob.writes
        assertIs<RenewalWriteBack.Dropped>(RenewalWriteBacks.persist(store, bound, "fresh", 10, now))
        assertEquals(before, blob.writes)
    }

    @Test
    fun `an absent expires_in leaves the deadline unknown rather than inventing one`() {
        val (store, _) = storeWith(bound.copy(tokenExpiresAtEpochSeconds = 123L))
        val applied = assertIs<RenewalWriteBack.Applied>(RenewalWriteBacks.persist(store, bound, "fresh", 0, now))
        assertNull(applied.renewed.tokenExpiresAtEpochSeconds)
    }
}
