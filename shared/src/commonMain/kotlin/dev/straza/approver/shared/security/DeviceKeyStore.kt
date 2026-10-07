package dev.straza.approver.shared.security

/**
 * Where the private key lives, reported to the server at enrollment as
 * `key_security_level`. A key that landed in the TEE because StrongBox was
 * unavailable reports [TEE]; server policy decides what each level is worth.
 */
enum class KeySecurityLevel(val wire: String) {
    /** Android StrongBox, a dedicated tamper-resistant secure element. */
    STRONGBOX("strongbox"),

    /** Trusted Execution Environment: hardware-isolated, not a separate chip. */
    TEE("tee"),

    /** Apple Secure Enclave. */
    SECURE_ENCLAVE("secure-enclave"),

    /** No hardware binding: the server must assume the key could have been copied. */
    SOFTWARE("software"),
}

/**
 * The public half of the device identity. [spkiDer] is X.509
 * SubjectPublicKeyInfo DER: it carries the curve OID, so the server reads the
 * algorithm from the key itself.
 */
class DevicePublicKey(
    val spkiDer: ByteArray,
    val securityLevel: KeySecurityLevel,
) {
    override fun toString(): String =
        "DevicePublicKey(${spkiDer.size} bytes SPKI, level=${securityLevel.wire})"
}

sealed interface KeyState {
    /** No key yet: first run, or the enrollment was wiped. */
    data object Absent : KeyState

    data class Present(val publicKey: DevicePublicKey) : KeyState

    /**
     * The platform invalidated the key, for example because the user enrolled
     * a new fingerprint on Android. The app must re-enroll. Reported only on
     * the explicit invalidation signal; a failed lookup is [Indeterminate].
     */
    data class Unusable(val reason: String) : KeyState

    /**
     * The keystore could not answer (a daemon fault, a momentary
     * unavailability). Nothing is known about the key, so nothing may be
     * destroyed over it.
     */
    data class Indeterminate(val reason: String) : KeyState
}

/** How the OS gates each use of the private key. */
enum class UserAuthPolicy {
    /**
     * Every signature needs a fresh biometric or device-credential
     * authentication, enforced by the keystore and not by the UI.
     */
    REQUIRED_PER_USE,

    /**
     * No per-use authentication. For the enrollment heartbeat, which proves
     * key possession but authorises nothing, and for instrumented tests.
     */
    NONE,
}

class DeviceKeyStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The device identity: an ECDSA P-256 keypair generated in the platform's
 * strongest available hardware. The private key is non-exportable; common
 * code sees public keys and signatures only.
 */
expect class DeviceKeyStore(keyRef: String) {

    /** Cheap enough to call on every screen. Does not throw. */
    fun keyState(): KeyState

    /**
     * Generates the device keypair in hardware, replacing any existing one.
     *
     * @throws DeviceKeyStoreException if no key could be created at all.
     */
    fun createKey(policy: UserAuthPolicy): DevicePublicKey

    /**
     * Signs [message] with the device key: `SHA256withECDSA`, ASN.1 DER output.
     *
     * @param reason text for the OS authentication prompt, so an approval and
     *   a pairing renewal are distinguishable (MASVS-AUTH-2). Used on iOS;
     *   Android's BiometricPrompt carries its own title and subtitle.
     * @throws DeviceKeyStoreException if there is no usable key, or under
     *   [UserAuthPolicy.REQUIRED_PER_USE] if the platform has not just
     *   authenticated the user.
     */
    fun sign(message: ByteArray, reason: String? = null): ByteArray

    /** Destroys the device key. Called on a revoking 401 from the server. */
    fun deleteKey()
}
