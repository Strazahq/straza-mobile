package dev.straza.approver.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Security.SecItemCopyMatching
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword

/**
 * True when this process can reach a Keychain. A Kotlin/Native test binary on
 * the iOS simulator is a bare process spawned via simctl, not an installed app,
 * and modern simulator runtimes give it no Keychain: every SecItem and SecKey
 * call answers OSStatus -25291 (`errSecNotAvailable`).
 *
 * The probe asks for a generic-password item that does not exist.
 * `errSecItemNotFound` proves the Keychain answered; `errSecNotAvailable` means
 * there is none.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun keychainAvailableInThisProcess(): Boolean = cfScope {
    val status = SecItemCopyMatching(
        cfDictionary(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to cfString("dev.straza.approver.test.keychain-probe"),
        ),
        null,
    )
    status == errSecItemNotFound || status == errSecSuccess
}

/** Returns false, after printing why, when the caller should skip the test. */
internal fun requireKeychainOrSkip(testName: String): Boolean {
    if (keychainAvailableInThisProcess()) return true
    println(
        "SKIPPING $testName - no Keychain in this bare test process " +
            "(errSecNotAvailable; see KeychainTestSupport). " +
            "Runs for real once tests are app-hosted.",
    )
    return false
}
