package dev.straza.approver.shared.security

/**
 * Encrypted-at-rest bytes: the one native seam under [SecureStore]. Android
 * wraps the blob with an AES-256-GCM key held in the Keystore; iOS uses the
 * Keychain.
 *
 * Implementations fail closed and do not throw into the UI. [BlobRead.Absent]
 * (nothing was ever stored, or it was cleared) is an empty vault the caller
 * may act on. [BlobRead.Unreadable] (a tampered or truncated record, a
 * rotated-away or unavailable wrapping key, a locked keychain) is no
 * information: the caller must neither treat it as empty nor destroy anything
 * over it.
 */
interface EncryptedBlobStore {
    fun read(): BlobRead

    fun write(bytes: ByteArray)

    /** Destroys the ciphertext and its key, so a restored copy stays unreadable. */
    fun clear()
}

/** The platform's hardware-backed blob store. */
expect fun platformEncryptedBlobStore(context: PlatformContext): EncryptedBlobStore

sealed interface BlobRead {
    data object Absent : BlobRead

    /**
     * Something is stored but could not be read. [reason] is short and
     * secret-free (an exception class name, a status code), because the
     * pairing screen shows it.
     */
    data class Unreadable(val reason: String) : BlobRead

    data class Bytes(val bytes: ByteArray) : BlobRead
}
