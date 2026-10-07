package dev.straza.approver.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecAccessControlCreateFlags
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecReturnRef
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlPrivateKeyUsage

/**
 * iOS device key store: an ECDSA P-256 keypair generated inside the Secure
 * Enclave, which holds no other key type. The private half is non-exportable
 * and there is no software fallback.
 *
 * The key is `WhenUnlockedThisDeviceOnly` with `.privateKeyUsage`. Per-use
 * authentication adds `.biometryCurrentSet`: a new biometric invalidates the
 * key, and [SecKeyCreateSignature] itself presents the Face ID prompt.
 * Signatures (ECDSA over SHA-256, ASN.1 DER) and the public key (X.509 SPKI)
 * use the same encodings as Android.
 */
@OptIn(ExperimentalForeignApi::class)
actual class DeviceKeyStore actual constructor(private val keyRef: String) {

    // One tag per deployment, so enrolling one does not delete another's key.
    private val tag = deviceKeyTag(keyRef)

    /**
     * Reports what the Keychain reference reveals about the Enclave key.
     * [KeyState.Present] means a key reference exists, not that the key can
     * sign: under `.biometryCurrentSet` the reference still resolves after a
     * biometric change, and only [SecKeyCreateSignature] fails. iOS never
     * reports [KeyState.Unusable], so callers must not use this to detect a
     * dead key. A failed lookup is [KeyState.Indeterminate].
     */
    actual fun keyState(): KeyState = try {
        val key = copyPrivateKey()
        if (key == null) {
            KeyState.Absent
        } else {
            try {
                // Deriving the public key does not use the private key material,
                // so no biometric prompt appears.
                KeyState.Present(publicKeyOf(key))
            } finally {
                CFRelease(key)
            }
        }
    } catch (e: Throwable) {
        // A lookup failure is not proof of a dead key.
        KeyState.Indeterminate(e.message ?: "device key could not be read")
    }

    actual fun createKey(policy: UserAuthPolicy): DevicePublicKey {
        // Replace, not reuse: an old key's auth policy is not known.
        deleteKey()

        return cfScope {
            memScoped {
                val error = alloc<CFErrorRefVar>()

                val flags: SecAccessControlCreateFlags =
                    if (policy == UserAuthPolicy.REQUIRED_PER_USE) {
                        kSecAccessControlPrivateKeyUsage or kSecAccessControlBiometryCurrentSet
                    } else {
                        kSecAccessControlPrivateKeyUsage
                    }

                val access = own(
                    SecAccessControlCreateWithFlags(
                        null, // null allocator = kCFAllocatorDefault
                        kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                        flags,
                        error.ptr,
                    ),
                ) ?: throw DeviceKeyStoreException("could not build the key access control${error.describe()}")

                val privateKeyAttrs = cfDictionary(
                    kSecAttrIsPermanent to kCFBooleanTrue,
                    kSecAttrApplicationTag to cfData(tag.encodeToByteArray()),
                    kSecAttrAccessControl to access,
                )

                val attributes = cfDictionary(
                    kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                    kSecAttrKeySizeInBits to cfNumber(256),
                    kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                    kSecPrivateKeyAttrs to privateKeyAttrs,
                )

                val privateKey = own(SecKeyCreateRandomKey(attributes, error.ptr))
                    ?: throw DeviceKeyStoreException(
                        "Secure Enclave key generation failed - refusing to fall back to a " +
                            "software key${error.describe()}",
                    )

                publicKeyOf(privateKey)
            }
        }
    }

    actual fun sign(message: ByteArray, reason: String?): ByteArray {
        // The reason is set on the key lookup (kSecUseOperationPrompt). The
        // Security framework shows it on the Face ID / Touch ID sheet that the
        // key's access control raises inside SecKeyCreateSignature.
        val key = copyPrivateKey(reason)
            ?: throw DeviceKeyStoreException("no device key - the device is not enrolled")
        try {
            return cfScope {
                memScoped {
                    val error = alloc<CFErrorRefVar>()
                    val signature = own(
                        SecKeyCreateSignature(
                            key,
                            kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                            cfData(message),
                            error.ptr,
                        ),
                    ) ?: throw DeviceKeyStoreException("could not sign with the device key${error.describe()}")

                    signature.toByteArray()
                }
            }
        } finally {
            CFRelease(key)
        }
    }

    actual fun deleteKey() {
        cfScope {
            val query = cfDictionary(
                kSecClass to kSecClassKey,
                kSecAttrApplicationTag to cfData(tag.encodeToByteArray()),
            )
            // errSecItemNotFound is fine: there is no key either way.
            SecItemDelete(query)
        }
    }

    /**
     * Looks up the Enclave key by its application tag. Returns a `+1` reference
     * that the caller must [CFRelease]; the scope does not own it. The lookup
     * does not authenticate the user; only signing with the key does.
     */
    private fun copyPrivateKey(operationPrompt: String? = null): SecKeyRef? = cfScope {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val query = cfDictionary(
                kSecClass to kSecClassKey,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrApplicationTag to cfData(tag.encodeToByteArray()),
                kSecReturnRef to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
                // Left out when null. Deprecated since iOS 14 in favour of
                // kSecUseAuthenticationContext with LAContext.localizedReason,
                // but still honoured; the LAContext form needs an ObjC bridge.
                kSecUseOperationPrompt to operationPrompt?.let { cfString(it) },
            )
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> result.value?.reinterpret()
                errSecItemNotFound -> null
                else -> throw DeviceKeyStoreException("device key lookup failed (OSStatus $status)")
            }
        }
    }

    /** Derives the X.509 SPKI public key from an Enclave private-key reference. */
    private fun publicKeyOf(privateKey: SecKeyRef): DevicePublicKey = cfScope {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val publicKey = own(SecKeyCopyPublicKey(privateKey))
                ?: throw DeviceKeyStoreException("could not derive the public key")
            val exported = own(SecKeyCopyExternalRepresentation(publicKey, error.ptr))
                ?: throw DeviceKeyStoreException("could not export the public key${error.describe()}")

            val point = exported.toByteArray()
            // The SPKI header only fits the 65-byte X9.63 point. Any other
            // length would produce malformed DER, so this fails closed.
            if (point.size != P256_UNCOMPRESSED_POINT_SIZE) {
                throw DeviceKeyStoreException(
                    "unexpected P-256 public-key export size ${point.size}, " +
                        "expected $P256_UNCOMPRESSED_POINT_SIZE (04 || X || Y)",
                )
            }
            DevicePublicKey(
                spkiDer = P256_SPKI_HEADER + point,
                securityLevel = KeySecurityLevel.SECURE_ENCLAVE,
            )
        }
    }
}

/**
 * The Keychain application tag for a deployment's Enclave key. The raw keyRef
 * sits between a fixed prefix and a `.v1` suffix, which is injective, so two
 * deployments never share a key; a key-policy change would migrate to a new
 * suffix. A blank keyRef maps to the legacy tag, so an enrollment from before
 * multi-deployment support keeps its key.
 */
internal const val LEGACY_DEVICE_KEY_TAG = "dev.straza.approver.device.v1"
internal const val DEVICE_KEY_TAG_PREFIX = "dev.straza.approver.device"

internal fun deviceKeyTag(keyRef: String): String =
    if (keyRef.isBlank()) {
        LEGACY_DEVICE_KEY_TAG
    } else {
        "$DEVICE_KEY_TAG_PREFIX.$keyRef.v1"
    }

/** Size of a P-256 public key in X9.63 uncompressed-point form (`04 || X || Y`). */
private const val P256_UNCOMPRESSED_POINT_SIZE = 65

/**
 * The fixed X.509 SubjectPublicKeyInfo header for a P-256 key: a SEQUENCE
 * wrapping the ecPublicKey and prime256v1 OIDs and a 66-byte BIT STRING.
 * Prepended to the 65-byte point, it gives the 91-byte SPKI DER the server
 * parses.
 */
private val P256_SPKI_HEADER: ByteArray = intArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
).map { it.toByte() }.toByteArray()
