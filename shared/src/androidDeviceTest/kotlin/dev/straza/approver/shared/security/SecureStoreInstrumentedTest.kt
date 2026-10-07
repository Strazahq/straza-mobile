package dev.straza.approver.shared.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.straza.approver.shared.protocol.SpkiPin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Runs on a device because the encryption key lives in the Android Keystore,
 * which does not exist on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SecureStoreInstrumentedTest {

    private val androidContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureStore(PlatformContext(androidContext))

    private val enrollment = Enrollment(
        approverDeviceId = "apd_test",
        deviceToken = "device-token-value",
        servers = listOf("https://straza.corp.internal:8420", "https://straza.example.com"),
        pin = SpkiPin.parse("sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="),
        deviceName = "test-device",
    )

    @AfterTest
    fun cleanUp() = store.clear()

    @Test
    fun startsEmpty() {
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun roundTripsAnEnrollment() {
        store.save(enrollment)
        val loaded = store.load()

        assertEquals(enrollment.approverDeviceId, loaded?.approverDeviceId)
        assertEquals(enrollment.deviceToken, loaded?.deviceToken)
        assertEquals(enrollment.servers, loaded?.servers)
        assertEquals(enrollment.deviceName, loaded?.deviceName)
        assertTrue(loaded?.pin?.matches(enrollment.pin!!.sha256) == true)
    }

    @Test
    fun clearRemovesEverything() {
        store.save(enrollment)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun savingTwiceKeepsTheLatest() {
        store.save(enrollment)
        store.save(enrollment.copy(deviceToken = "rotated-token"))
        assertEquals("rotated-token", store.load()?.deviceToken)
    }

    /** A token readable on disk is a working credential for a rooted device or a lost phone. */
    @Test
    fun tokenIsNotStoredInPlaintext() {
        store.save(enrollment)

        val file = File(androidContext.filesDir, "enrollment.bin")
        assertTrue(file.exists(), "expected an enrollment file")

        val raw = file.readBytes().decodeToString()
        assertTrue(!raw.contains("device-token-value"), "device token found in plaintext on disk")
        assertTrue(!raw.contains("straza.corp.internal"), "server list found in plaintext on disk")
    }

    /**
     * GCM authenticates its ciphertext, so a modified file fails to decrypt.
     * load() then returns null instead of throwing into the UI.
     */
    @Test
    fun tamperedRecordIsRefused() {
        store.save(enrollment)

        val file = File(androidContext.filesDir, "enrollment.bin")
        val bytes = file.readBytes()
        // Flip a bit in the ciphertext body, past the 12-byte IV.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(store.load(), "a tampered record must not load")
    }

    @Test
    fun truncatedRecordIsRefused() {
        store.save(enrollment)
        val file = File(androidContext.filesDir, "enrollment.bin")
        file.writeBytes(file.readBytes().copyOf(6)) // Shorter than the IV.
        assertNull(store.load())
    }

    /**
     * clear() drops the keystore key as well as the file, so a restored copy of
     * the ciphertext stays unreadable.
     */
    @Test
    fun restoredCiphertextIsUselessAfterClear() {
        store.save(enrollment)
        val file = File(androidContext.filesDir, "enrollment.bin")
        val backup = file.readBytes()

        store.clear()
        file.writeBytes(backup)

        assertNull(store.load(), "ciphertext must not survive key deletion")
    }
}
