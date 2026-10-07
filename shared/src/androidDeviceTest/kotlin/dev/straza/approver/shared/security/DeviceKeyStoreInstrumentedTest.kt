package dev.straza.approver.shared.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.straza.approver.shared.protocol.DecisionSigning
import dev.straza.approver.shared.protocol.Verdict
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Runs against the real Android Keystore. Uses [UserAuthPolicy.NONE] because an
 * automated test cannot present a fingerprint, so the per-use-auth path that
 * the approve action uses is not covered here: it needs a device with
 * credentials enrolled and a driven BiometricPrompt.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreInstrumentedTest {

    private val store = DeviceKeyStore("test-deployment")

    @AfterTest
    fun cleanUp() = store.deleteKey()

    @Test
    fun startsAbsentAfterDelete() {
        store.deleteKey()
        assertEquals(KeyState.Absent, store.keyState())
    }

    @Test
    fun generatesAKeyAndReportsItPresent() {
        val pub = store.createKey(UserAuthPolicy.NONE)
        assertTrue(pub.spkiDer.isNotEmpty())

        val state = store.keyState()
        assertTrue(state is KeyState.Present, "expected Present, got $state")
        assertTrue(state.publicKey.spkiDer.contentEquals(pub.spkiDer))
    }

    /** The server expects X.509 SubjectPublicKeyInfo DER for a P-256 key. */
    @Test
    fun publicKeyIsParseableP256Spki() {
        val pub = store.createKey(UserAuthPolicy.NONE)

        val parsed = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub.spkiDer))
        assertEquals("EC", parsed.algorithm)
        // SPKI DER for P-256 is 91 bytes. Another size means another curve or
        // a raw point instead of the wrapped form.
        assertEquals(91, pub.spkiDer.size, "not a P-256 SPKI encoding")
    }

    @Test
    fun signatureVerifiesAgainstTheEnrolledPublicKey() {
        val pub = store.createKey(UserAuthPolicy.NONE)
        val message = DecisionSigning.canonicalMessage(
            requestId = "apr_test",
            verdict = Verdict.APPROVE,
            challenge = "dGVzdC1jaGFsbGVuZ2U=",
            unixTs = 1789000000L,
        )

        val signature = store.sign(message)

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub.spkiDer)))
            update(message)
        }
        assertTrue(verifier.verify(signature), "signature did not verify")
    }

    @Test
    fun signatureDoesNotVerifyForATamperedMessage() {
        val pub = store.createKey(UserAuthPolicy.NONE)
        val approve = DecisionSigning.canonicalMessage("apr_1", Verdict.APPROVE, "n", 1L)
        val deny = DecisionSigning.canonicalMessage("apr_1", Verdict.DENY, "n", 1L)

        val signature = store.sign(approve)

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub.spkiDer)))
            update(deny) // An attacker swapping the verdict.
        }
        assertTrue(!verifier.verify(signature), "a deny must not verify under an approve signature")
    }

    /** An emulator reports SOFTWARE, so any level Android can produce is accepted. */
    @Test
    fun reportsItsActualSecurityLevel() {
        val pub = store.createKey(UserAuthPolicy.NONE)

        // Logged to logcat and the test report: the level a device reaches is a
        // fact about its hardware, and it is what server policy weighs.
        val report = "STRAZA device key: security_level=${pub.securityLevel.wire}, " +
            "spki=${pub.spkiDer.size} bytes, api=${android.os.Build.VERSION.SDK_INT}, " +
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        println(report)
        android.util.Log.i("Straza", report)

        assertTrue(
            pub.securityLevel in setOf(
                KeySecurityLevel.STRONGBOX,
                KeySecurityLevel.TEE,
                KeySecurityLevel.SOFTWARE,
            ),
            "Android must never report SECURE_ENCLAVE",
        )
    }

    /**
     * A key in the TEE on a device that advertises StrongBox means the fallback
     * fired wrongly and the device enrolled at a weaker level than it offers.
     */
    @Test
    fun strongBoxIsUsedWhenTheDeviceAdvertisesIt() {
        val advertised = InstrumentationRegistry.getInstrumentation().targetContext
            .packageManager
            .hasSystemFeature("android.hardware.strongbox_keystore")

        val pub = store.createKey(UserAuthPolicy.NONE)
        android.util.Log.i(
            "Straza",
            "STRAZA strongbox: advertised=$advertised actual=${pub.securityLevel.wire}",
        )
        println("STRAZA strongbox: advertised=$advertised actual=${pub.securityLevel.wire}")

        if (advertised) {
            assertEquals(
                KeySecurityLevel.STRONGBOX,
                pub.securityLevel,
                "device advertises StrongBox but the key landed in ${pub.securityLevel.wire}",
            )
        }
    }

    @Test
    fun createKeyReplacesTheOldKey() {
        val first = store.createKey(UserAuthPolicy.NONE)
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
        store.createKey(UserAuthPolicy.NONE)
        store.deleteKey()
        store.deleteKey()
        assertEquals(KeyState.Absent, store.keyState())
    }
}
