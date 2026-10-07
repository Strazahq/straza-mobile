package dev.straza.approver.shared.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun platformEncryptedBlobStore(context: PlatformContext): EncryptedBlobStore =
    AndroidEncryptedBlobStore(context)

/**
 * Blob encrypted with an AES-256-GCM key held in the Android Keystore. The
 * ciphertext sits in app-private files, which are readable on rooted devices
 * and through some backup paths, so the protection is the key: the file is
 * useless once copied off the device.
 *
 * The storage key requires no user authentication, because the app must read
 * its own enrollment to poll and render. It does not request StrongBox
 * either: StrongBox slots are scarce and are left for the signing key in
 * [DeviceKeyStore].
 */
private class AndroidEncryptedBlobStore(context: PlatformContext) : EncryptedBlobStore {

    private val file = File(context.android.filesDir, FILE_NAME)

    override fun read(): BlobRead {
        if (!file.exists()) return BlobRead.Absent
        return try {
            val blob = file.readBytes()
            if (blob.size <= IV_BYTES) return BlobRead.Unreadable("truncated record")
            // A record whose wrapping key is gone is unreadable, which is not
            // the same as absent. A keystore that cannot answer throws from
            // existingKey() instead and lands in the catch below.
            val key = existingKey() ?: return BlobRead.Unreadable("storage key missing")
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            }
            BlobRead.Bytes(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
        } catch (e: Exception) {
            // Tampered ciphertext (GCM tag mismatch) or a keystore fault. The
            // class name says which.
            BlobRead.Unreadable(e::class.simpleName ?: "unreadable record")
        }
    }

    override fun write(bytes: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // No IV is supplied: the keystore requires randomized encryption and
            // generates a fresh one per operation.
            // existingKey() throws on a keystore fault instead of returning
            // null, so an outage cannot make this path create a new wrapping
            // key over the old one and leave the stored record unreadable.
            init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        }
        val ciphertext = cipher.doFinal(bytes)

        // Write through a temporary file so an interrupted save cannot leave a
        // half-written record.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(cipher.iv + ciphertext)
        check(temp.renameTo(file)) { "could not persist the enrollment record" }
    }

    override fun clear() {
        file.delete()
        // Drop the key too: without it the ciphertext is unrecoverable even if
        // the file is restored from a backup or forensic image.
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * The wrapping key, or null only when the keystore reports no such entry.
     * Any other failure propagates (see [read] and [write]).
     */
    private fun existingKey(): SecretKey? =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun createKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.straza.approver.store.v1"
        const val FILE_NAME = "enrollment.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
