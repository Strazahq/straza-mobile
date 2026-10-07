package dev.straza.approver.shared.security

import dev.straza.approver.shared.protocol.SpkiPin

/**
 * The enrollment record: everything needed to talk to this device's Straza
 * server except the private key, which stays in hardware. This is what a lost
 * device leaks: a purpose-scoped bearer token the administrator can revoke
 * server-side, plus non-secret connection details. It cannot sign a decision.
 */
data class Enrollment(
    val approverDeviceId: String,
    /** Purpose-scoped `use=approver` bearer token. The only secret here. */
    val deviceToken: String,
    val servers: List<String>,
    val pin: SpkiPin?,
    val deviceName: String,
    /**
     * Stable per-deployment key: the server's `project.id`, or blank, in which
     * case the vault derives one from [servers]. Not a secret.
     */
    val projectId: String = "",
    /** Human deployment label from the server (`project.name`). Not a secret. */
    val projectName: String? = null,
    /** On-device rename; wins over [projectName]. Not sent to the server. */
    val localLabel: String? = null,
    /**
     * The Keystore/Keychain alias suffix of this deployment's signing key. Each
     * deployment has its own key, so enrolling a second backend does not delete
     * the first's. Blank means the single key under the legacy alias.
     */
    val keyRef: String = "",
    /**
     * When [deviceToken] expires, in epoch seconds, or null when unknown (a
     * server before 0.23, or a record written before this field existed). It
     * only lets the UI warn ahead of time: the server's 401 `token_expired` is
     * the authoritative signal.
     */
    val tokenExpiresAtEpochSeconds: Long? = null,
    /**
     * This deployment's VAPID public key from the enroll 201 (openapi 0.40.0).
     * Null for an older pairing or a deployment with the WebPush lane off,
     * which keeps the pairing on the keyless push path. Not a secret, but it
     * decides whose pushes the push service accepts for this device, so it
     * must come over the pinned channel. Refreshed only by re-enrollment.
     */
    val vapidPublicKey: String? = null,
    /**
     * BYO-Firebase public app config from the enroll 201 (openapi 0.39.0), or
     * null when this deployment has no FCM lane. Stored per pairing so that a
     * switch to a deployment on a different Firebase project is detected and
     * forces a full reset (delete token, delete FirebaseApp, re-init). Public
     * values only; the sending credential does not reach the app.
     */
    val fcm: dev.straza.approver.shared.net.FcmAppConfig? = null,
) {
    override fun toString(): String =
        "Enrollment($approverDeviceId, project=${projectId.ifBlank { "?" }}, " +
            "servers=$servers, token=<redacted>)"
}

/**
 * Encrypted-at-rest storage for the enrollment set, one record per deployment.
 * The serialized [EnrollmentVault] leaves here only through
 * [EncryptedBlobStore], not through SharedPreferences, UserDefaults or a
 * plaintext file.
 */
class SecureStore(private val blob: EncryptedBlobStore) {

    constructor(context: PlatformContext) : this(platformEncryptedBlobStore(context))

    /**
     * Tells "empty" from "cannot tell". Absent bytes are an empty vault. Bytes
     * that decrypt but decode to nothing (a format this build does not
     * understand, a half-written record) are unreadable too, because [persist]
     * stores no empty vault. Destructive callers read through [snapshot]. The
     * plain getters answer "nothing" on an unreadable store, which is harmless
     * for rendering a list.
     */
    private fun readVault(): VaultRead = when (val read = blob.read()) {
        is BlobRead.Absent -> VaultRead.Readable(EnrollmentVault())
        is BlobRead.Unreadable -> VaultRead.Unreadable(read.reason)
        is BlobRead.Bytes -> {
            val text = read.bytes.decodeToString()
            val vault = EnrollmentVaultCodec.decode(text)
            if (vault.isEmpty && text.isNotBlank()) {
                VaultRead.Unreadable("stored record not understood")
            } else {
                VaultRead.Readable(vault)
            }
        }
    }

    private fun vault(): EnrollmentVault =
        (readVault() as? VaultRead.Readable)?.vault ?: EnrollmentVault()

    private fun persist(updated: EnrollmentVault) {
        if (updated.isEmpty) blob.clear() else blob.write(EnrollmentVaultCodec.encode(updated).encodeToByteArray())
    }

    /**
     * Applies [change] and persists the result, only when the vault could be
     * read. On an unreadable store this is a no-op: a mutation over an empty
     * guess would persist the guess, and removing the last record would clear
     * the blob and its wrapping key. A record that outlives its retired key
     * is cleaned up by startup convergence on the next readable start.
     */
    private fun mutate(change: (EnrollmentVault) -> EnrollmentVault) {
        when (val read = readVault()) {
            is VaultRead.Readable -> persist(change(read.vault))
            is VaultRead.Unreadable -> Unit
        }
    }

    /**
     * What startup may act on. On [VaultSnapshot.Unreadable] the caller skips
     * the orphan-key sweep and convergence, and tells the user.
     */
    fun snapshot(): VaultSnapshot = when (val read = readVault()) {
        is VaultRead.Readable -> VaultSnapshot.Readable(read.vault.deployments, read.vault.active())
        is VaultRead.Unreadable -> VaultSnapshot.Unreadable(read.reason)
    }

    // Multi-deployment API.

    fun loadAll(): List<Enrollment> = vault().deployments

    fun find(projectId: String): Enrollment? = vault().find(projectId)

    fun active(): Enrollment? = vault().active()

    /**
     * Adds a deployment, or replaces the one with the same key, and makes it
     * active. On an unreadable store this writes a fresh vault holding only
     * [enrollment]. The platform write refuses to create a new wrapping key
     * while the keystore cannot be asked, so a transient fault fails the
     * pairing and does not overwrite a healthy record.
     */
    fun upsert(enrollment: Enrollment) = persist(vault().upsert(enrollment))

    /**
     * Replaces an existing record in place (the renewal write-back). Unlike
     * [upsert] it does not change the active deployment and does not re-add a
     * record that was retired in the meantime.
     */
    fun update(enrollment: Enrollment) = mutate { it.update(enrollment) }

    fun remove(projectId: String) = mutate { it.remove(projectId) }

    fun setActive(projectId: String) = mutate { it.withActive(projectId) }

    // Single-deployment API.

    fun load(): Enrollment? = active()

    /** Replaces the whole vault with this single deployment. */
    fun save(enrollment: Enrollment) = persist(EnrollmentVault().upsert(enrollment))

    /**
     * Destroys all stored enrollments. Only for cleaning up an orphaned key
     * that has no enrollment; a 401 on one deployment uses [remove].
     */
    fun clear() = blob.clear()
}

private sealed interface VaultRead {
    data class Readable(val vault: EnrollmentVault) : VaultRead
    data class Unreadable(val reason: String) : VaultRead
}

/** See [SecureStore.snapshot]. */
sealed interface VaultSnapshot {
    data class Readable(val all: List<Enrollment>, val active: Enrollment?) : VaultSnapshot

    /** The store holds something this app could not read. Act on nothing. */
    data class Unreadable(val reason: String) : VaultSnapshot
}
