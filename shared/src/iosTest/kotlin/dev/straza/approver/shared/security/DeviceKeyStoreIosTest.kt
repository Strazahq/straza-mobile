package dev.straza.approver.shared.security

import dev.straza.approver.shared.protocol.DecisionSigning
import dev.straza.approver.shared.protocol.Verdict
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFErrorRefVar
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs on a simulator or device on a Mac. Uses [UserAuthPolicy.NONE] because an
 * automated test cannot present a biometric, so the per-use-auth path that
 * approve uses is not covered here.
 *
 * A simulator or host without a Secure Enclave cannot mint the key, because the
 * store does not fall back to software. The key assertions are skipped there.
 */
class DeviceKeyStoreIosTest {

    private val store = DeviceKeyStore("test-deployment")

    @AfterTest
    fun cleanUp() = store.deleteKey()

    /** Generates a key, or returns null when there is no Secure Enclave. */
    private fun createKeyOrSkip(): DevicePublicKey? = try {
        store.createKey(UserAuthPolicy.NONE)
    } catch (e: DeviceKeyStoreException) {
        println("SKIPPING iOS Enclave test - no Secure Enclave on this host: ${e.message}")
        null
    }

    @Test
    fun startsAbsentAfterDelete() {
        // Without a Keychain the lookup fails and keyState() answers Unusable,
        // not Absent, so this needs a Keychain.
        if (!requireKeychainOrSkip("startsAbsentAfterDelete")) return
        store.deleteKey()
        assertEquals(KeyState.Absent, store.keyState())
    }

    @Test
    fun generatesAKeyAndReportsItPresent() {
        val pub = createKeyOrSkip() ?: return
        assertTrue(pub.spkiDer.isNotEmpty())
        assertEquals(KeySecurityLevel.SECURE_ENCLAVE, pub.securityLevel)

        val state = store.keyState()
        assertTrue(state is KeyState.Present, "expected Present, got $state")
        assertTrue(state.publicKey.spkiDer.contentEquals(pub.spkiDer))
    }

    /**
     * A P-256 SubjectPublicKeyInfo is 91 bytes: a fixed 26-byte header, then the
     * uncompressed-point marker and the point.
     */
    @Test
    fun publicKeyIsP256Spki() {
        val pub = createKeyOrSkip() ?: return
        assertEquals(91, pub.spkiDer.size, "not a P-256 SPKI encoding")
        assertTrue(
            pub.spkiDer.copyOfRange(0, 26).contentEquals(P256_SPKI_HEADER_EXPECTED),
            "SPKI header does not match the P-256 constant",
        )
        assertEquals(0x04.toByte(), pub.spkiDer[26], "expected an uncompressed EC point (0x04)")
    }

    @Test
    fun signatureVerifiesAgainstTheEnrolledPublicKey() {
        val pub = createKeyOrSkip() ?: return
        val message = DecisionSigning.canonicalMessage(
            requestId = "apr_test",
            verdict = Verdict.APPROVE,
            challenge = "dGVzdC1jaGFsbGVuZ2U=",
            unixTs = 1789000000L,
        )
        val signature = store.sign(message)
        assertTrue(verifyP256(pub.spkiDer, message, signature), "signature did not verify")
    }

    @Test
    fun signatureDoesNotVerifyForATamperedMessage() {
        val pub = createKeyOrSkip() ?: return
        val approve = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L)
        val deny = DecisionSigning.canonicalMessage("apr_1", Verdict.DENY, "n", 1L)

        val signature = store.sign(approve)
        assertTrue(
            !verifyP256(pub.spkiDer, deny, signature),
            "a deny must not verify under an approve signature",
        )
    }

    @Test
    fun createKeyReplacesTheOldKey() {
        val first = createKeyOrSkip() ?: return
        val second = store.createKey(UserAuthPolicy.NONE)
        assertTrue(!first.spkiDer.contentEquals(second.spkiDer), "re-enrollment must mint a fresh key")
    }

    @Test
    fun signingWithoutAKeyFailsClosed() {
        store.deleteKey()
        assertFailsWith<DeviceKeyStoreException> { store.sign("anything".encodeToByteArray()) }
    }

    @Test
    fun deleteIsIdempotent() {
        createKeyOrSkip() ?: return
        store.deleteKey()
        store.deleteKey()
        assertEquals(KeyState.Absent, store.keyState())
    }
}

/** The 26-byte P-256 SPKI header, duplicated here so the test does not depend
 * on the production constant. */
private val P256_SPKI_HEADER_EXPECTED: ByteArray = intArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
).map { it.toByte() }.toByteArray()

/**
 * Verifies an ECDSA P-256 SHA-256 signature in ASN.1 DER against the public key
 * rebuilt from its SPKI bytes, as the server does.
 */
@OptIn(ExperimentalForeignApi::class)
private fun verifyP256(spkiDer: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    val point = spkiDer.copyOfRange(26, spkiDer.size) // 04 || X || Y
    return cfScope {
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val publicKey = own(
                SecKeyCreateWithData(
                    cfData(point),
                    cfDictionary(
                        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                        kSecAttrKeyClass to kSecAttrKeyClassPublic,
                        kSecAttrKeySizeInBits to cfNumber(256),
                    ),
                    error.ptr,
                ),
            ) ?: return@memScoped false

            SecKeyVerifySignature(
                publicKey,
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                cfData(message),
                cfData(signature),
                error.ptr,
            )
        }
    }
}
