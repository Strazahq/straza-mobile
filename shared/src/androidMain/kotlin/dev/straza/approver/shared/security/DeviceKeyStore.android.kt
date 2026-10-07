package dev.straza.approver.shared.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore implementation. The private key is generated inside the
 * keystore and never exists as bytes in app memory: `KeyPairGenerator` with
 * the `AndroidKeyStore` provider returns a handle, not key material.
 */
actual class DeviceKeyStore actual constructor(private val keyRef: String) {

    // One alias per deployment, so enrolling one does not delete another's key.
    private val alias = deviceKeyAlias(keyRef)

    actual fun keyState(): KeyState = try {
        val entry = loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry == null) {
            KeyState.Absent
        } else {
            val level = securityLevelOf(entry.privateKey)
            KeyState.Present(DevicePublicKey(entry.certificate.publicKey.encoded, level))
        }
    } catch (e: UnrecoverableKeyException) {
        // The entry exists but cannot be loaded, typically because a biometric
        // enrolment change invalidated the key.
        KeyState.Unusable(e.message ?: "device key is unusable")
    } catch (e: KeyPermanentlyInvalidatedException) {
        KeyState.Unusable(e.message ?: "device key was invalidated")
    } catch (e: Exception) {
        // Anything else (a KeyStoreException from the daemon, a
        // ProviderException, an IO fault) is the keystore failing to answer,
        // not proof that the key is dead.
        KeyState.Indeterminate(e::class.simpleName ?: "keystore unavailable")
    }

    actual fun createKey(policy: UserAuthPolicy): DevicePublicKey {
        // Replace, not reuse: an old key's policy and security level are unknown.
        deleteKey()

        // StrongBox is a separate tamper-resistant chip that many devices lack.
        // Ask for it, fall back to the TEE, and report the level obtained.
        return try {
            generate(policy, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(policy, strongBox = false)
        } catch (e: Exception) {
            // Some OEMs throw ProviderException instead of the documented
            // StrongBoxUnavailableException when StrongBox is missing or its
            // key slots are exhausted. Retry once without StrongBox.
            try {
                generate(policy, strongBox = false)
            } catch (inner: Exception) {
                throw DeviceKeyStoreException("could not generate a device key", inner).also {
                    it.addSuppressed(e)
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER") // the gate's BiometricPrompt shows the text, see beginSign()
    actual fun sign(message: ByteArray, reason: String?): ByteArray {
        val entry = try {
            loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            throw DeviceKeyStoreException("device key is unusable", e)
        } ?: throw DeviceKeyStoreException("no device key - the device is not enrolled")

        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(entry.privateKey)
                update(message)
                sign()
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometrics changed since enrolment; the device must re-enroll.
            throw DeviceKeyStoreException("device key was invalidated; re-enroll this device", e)
        } catch (e: Exception) {
            // Includes UserNotAuthenticatedException under REQUIRED_PER_USE.
            throw DeviceKeyStoreException("could not sign with the device key", e)
        }
    }

    /**
     * Returns a [Signature] initialised with the device key, for
     * `BiometricPrompt.CryptoObject`. Under [UserAuthPolicy.REQUIRED_PER_USE]
     * the keystore refuses to complete the signature until the OS reports a
     * successful authentication for this operation. Android-only: iOS binds
     * authentication through the key's access control instead.
     *
     * @throws DeviceKeyStoreException if there is no usable key.
     */
    fun beginSign(): Signature {
        val entry = try {
            loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            throw DeviceKeyStoreException("device key is unusable", e)
        } ?: throw DeviceKeyStoreException("no device key - the device is not enrolled")

        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(entry.privateKey) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw DeviceKeyStoreException("device key was invalidated; re-enroll this device", e)
        } catch (e: Exception) {
            throw DeviceKeyStoreException("could not prepare the signing operation", e)
        }
    }

    actual fun deleteKey() {
        try {
            loadKeyStore().deleteEntry(alias)
        } catch (_: Exception) {
            // Nothing to delete, or the keystore is unreadable.
        }
    }

    private fun generate(policy: UserAuthPolicy, strongBox: Boolean): DevicePublicKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).apply {
            setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            setDigests(KeyProperties.DIGEST_SHA256)

            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }

            if (policy == UserAuthPolicy.REQUIRED_PER_USE) {
                setUserAuthenticationRequired(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Timeout 0 requires authentication for every use, through
                    // a CryptoObject-bound prompt. A time window would let one
                    // authentication authorise several approvals.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1) // -1 = per-use, pre-API 30
                }

                // Takes effect for the biometric-only key below API 30. A key
                // that also accepts the device credential is bound to the
                // lock screen, and a new biometric does not invalidate it.
                setInvalidatedByBiometricEnrollment(true)
            }
        }.build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        return DevicePublicKey(
            // X.509 SubjectPublicKeyInfo DER.
            spkiDer = keyPair.public.encoded,
            securityLevel = securityLevelOf(keyPair.private),
        )
    }

    /** Asks the keystore where the key ended up, which may not be what was requested. */
    private fun securityLevelOf(privateKey: PrivateKey): KeySecurityLevel = try {
        val keyInfo = KeyFactory.getInstance(privateKey.algorithm, PROVIDER)
            .getKeySpec(privateKey, KeyInfo::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TEE
                else -> KeySecurityLevel.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (keyInfo.isInsideSecureHardware) KeySecurityLevel.TEE else KeySecurityLevel.SOFTWARE
        }
    } catch (_: Exception) {
        // Hardware backing that cannot be proven is not claimed.
        KeySecurityLevel.SOFTWARE
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** P-256, secp256r1 and prime256v1 are three names for the same curve. */
        const val CURVE = "secp256r1"

        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

// The alias from before multi-deployment support. A blank keyRef maps here, so
// an enrollment from that time still finds its key. Changing this string
// strands those keys.
internal const val LEGACY_DEVICE_KEY_ALIAS = "dev.straza.approver.device.v1"
internal const val DEVICE_KEY_ALIAS_PREFIX = "dev.straza.approver.device"

/**
 * The Keystore alias for a deployment. Versioned (`.v1`) so a future
 * key-policy change migrates to a new alias instead of mutating a key.
 *
 * keyRef is hashed, not character-substituted: substitution would map
 * `acme/prod` and `acme_prod` to one alias, and two separate deployments
 * would then share a hardware key.
 */
internal fun deviceKeyAlias(keyRef: String): String =
    if (keyRef.isBlank()) {
        LEGACY_DEVICE_KEY_ALIAS
    } else {
        "$DEVICE_KEY_ALIAS_PREFIX.${sha256Hex(keyRef)}.v1"
    }

/**
 * Device-key aliases that no stored enrollment accounts for. The key is
 * generated before the server round-trip, so a crash in between, or an upsert
 * that changes a record's keyRef, leaves an orphan. StrongBox holds few keys,
 * so each orphan can push a later deployment's signing key down to the TEE.
 *
 * Only aliases under [DEVICE_KEY_ALIAS_PREFIX] are candidates, so the blob
 * store's wrapping key is never touched. The legacy alias is excluded: an
 * orphan there is the half-failed enrollment that Convergence handles at
 * startup.
 */
internal fun orphanedDeviceKeyAliases(
    residentAliases: Collection<String>,
    keptKeyRefs: Collection<String>,
): List<String> {
    val kept = keptKeyRefs.map(::deviceKeyAlias).toSet()
    return residentAliases.filter { alias ->
        alias.startsWith("$DEVICE_KEY_ALIAS_PREFIX.") &&
            alias != LEGACY_DEVICE_KEY_ALIAS &&
            alias !in kept
    }
}

/**
 * Deletes every orphaned device key (see [orphanedDeviceKeyAliases]).
 * [keptKeyRefs] must cover every stored enrollment and any enrollment in
 * flight whose record is not stored yet. Best effort: an unreadable keystore
 * changes nothing, and the next foreground sweeps again.
 */
fun deleteOrphanedDeviceKeys(keptKeyRefs: Collection<String>) {
    try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in orphanedDeviceKeyAliases(keyStore.aliases().toList(), keptKeyRefs)) {
            try {
                keyStore.deleteEntry(alias)
            } catch (_: Exception) {
                // This one stays resident until the next sweep.
            }
        }
    } catch (_: Exception) {
        // Keystore unreadable; the next sweep retries.
    }
}

private fun sha256Hex(value: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
