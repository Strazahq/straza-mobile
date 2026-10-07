package dev.straza.approver.shared.security

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs against the real Keychain, so it needs a simulator or device on a Mac.
 * A bare simctl-spawned process has no Keychain (see
 * [keychainAvailableInThisProcess]), and the tests skip there.
 *
 * There is no plaintext-on-disk check as on Android: a Keychain item exposes
 * no file, and this-device-only protection is an attribute set in
 * [platformEncryptedBlobStore] that cannot be observed here.
 */
class EncryptedBlobStoreIosTest {

    private val store = platformEncryptedBlobStore(PlatformContext())

    @AfterTest
    fun cleanUp() = store.clear()

    /** The bytes of a successful read. Any other outcome fails the test. */
    private fun bytes(): ByteArray = assertIs<BlobRead.Bytes>(store.read()).bytes

    @Test
    fun startsEmpty() {
        if (!requireKeychainOrSkip("startsEmpty")) return
        store.clear()
        // Nothing stored reads as Absent, an empty vault the caller may act on,
        // and not as Unreadable.
        assertIs<BlobRead.Absent>(store.read())
    }

    @Test
    fun roundTripsABlob() {
        if (!requireKeychainOrSkip("roundTripsABlob")) return
        val blob = "enrollment-vault-bytes".encodeToByteArray()
        store.write(blob)
        assertTrue(bytes().contentEquals(blob))
    }

    @Test
    fun clearRemovesEverything() {
        if (!requireKeychainOrSkip("clearRemovesEverything")) return
        store.write("x".encodeToByteArray())
        store.clear()
        assertIs<BlobRead.Absent>(store.read())
    }

    @Test
    fun writingTwiceKeepsTheLatest() {
        if (!requireKeychainOrSkip("writingTwiceKeepsTheLatest")) return
        store.write("first".encodeToByteArray())
        store.write("second".encodeToByteArray())
        assertEquals("second", bytes().decodeToString())
    }

    @Test
    fun roundTripsArbitraryBinaryContent() {
        if (!requireKeychainOrSkip("roundTripsArbitraryBinaryContent")) return
        // The codec output is opaque bytes, including zero and high bytes, so
        // the store must not assume text.
        val blob = byteArrayOf(0, 1, 2, 127, -1, -128, 65, 0, 66)
        store.write(blob)
        assertTrue(bytes().contentEquals(blob))
    }
}
