package dev.straza.approver.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun platformEncryptedBlobStore(context: PlatformContext): EncryptedBlobStore =
    IosKeychainBlobStore()

/**
 * One Keychain generic-password item holding the whole enrollment vault. The
 * Keychain item is the protection, so there is no cipher on top of it. The
 * item is `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: it does not sync to
 * iCloud and does not restore onto another device.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosKeychainBlobStore : EncryptedBlobStore {

    override fun read(): BlobRead = cfScope {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val query = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to cfString(SERVICE),
                kSecAttrAccount to cfString(ACCOUNT),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> Unit
                errSecItemNotFound -> return@memScoped BlobRead.Absent
                // errSecInteractionNotAllowed (device locked) or any other
                // failure: the record may still exist, so this is not Absent.
                else -> return@memScoped BlobRead.Unreadable("keychain status $status")
            }
            // Returned with a +1 reference (kSecReturnData) the scope does not own.
            val data: CFDataRef? = result.value?.reinterpret()
            if (data == null) {
                BlobRead.Unreadable("keychain item without data")
            } else {
                try {
                    BlobRead.Bytes(data.toByteArray())
                } finally {
                    CFRelease(data)
                }
            }
        }
    }

    override fun write(bytes: ByteArray) {
        // SecItemAdd fails with errSecDuplicateItem if a record exists, so any
        // prior one is dropped first. There is a single writer, so delete and
        // add do not race.
        clear()
        cfScope {
            val attributes = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to cfString(SERVICE),
                kSecAttrAccount to cfString(ACCOUNT),
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecValueData to cfData(bytes),
            )
            val status = SecItemAdd(attributes, null)
            check(status == errSecSuccess) { "could not persist the enrollment record (OSStatus $status)" }
        }
    }

    override fun clear() {
        cfScope {
            val query = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to cfString(SERVICE),
                kSecAttrAccount to cfString(ACCOUNT),
            )
            // errSecItemNotFound is fine: the item is gone either way.
            SecItemDelete(query)
        }
    }

    private companion object {
        const val SERVICE = "dev.straza.approver.enrollment"
        const val ACCOUNT = "enrollment"
    }
}
