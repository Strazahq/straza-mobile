package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.DevicePublicKey
import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeySecurityLevel
import dev.straza.approver.shared.security.KeyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConvergenceTest {

    private val enrollment = Enrollment(
        approverDeviceId = "apd_1",
        deviceToken = "token",
        servers = listOf("https://straza.example.com"),
        pin = null,
        deviceName = "test-device",
    )

    private val usableKey = KeyState.Present(
        DevicePublicKey(ByteArray(91), KeySecurityLevel.STRONGBOX),
    )

    @Test
    fun `healthy state proceeds`() {
        val decision = Convergence.onStartup(enrollment, usableKey)
        assertIs<StartupDecision.Proceed>(decision)
        assertEquals(enrollment, decision.enrollment)
    }

    @Test
    fun `no enrollment and no key means enroll`() {
        assertEquals(StartupDecision.Enroll, Convergence.onStartup(null, KeyState.Absent))
    }

    /** A new fingerprint invalidates the device key, but the enrollment record survives. */
    @Test
    fun `invalidated key converges instead of proceeding`() {
        val decision = Convergence.onStartup(enrollment, KeyState.Unusable("key permanently invalidated"))
        assertIs<StartupDecision.Converge>(decision)
        assertTrue(decision.reason.isNotBlank())
    }

    @Test
    fun `enrollment with a missing key converges`() {
        val decision = Convergence.onStartup(enrollment, KeyState.Absent)
        assertIs<StartupDecision.Converge>(decision)
    }

    /** A half-finished enroll leaves a key without a record. */
    @Test
    fun `key without enrollment converges`() {
        val decision = Convergence.onStartup(null, usableKey)
        assertIs<StartupDecision.Converge>(decision)
    }

    @Test
    fun `orphan unusable key also converges`() {
        assertIs<StartupDecision.Converge>(
            Convergence.onStartup(null, KeyState.Unusable("dead")),
        )
    }

    /**
     * A keystore that could not answer is not a dead key. Converge deletes, so
     * it is not chosen here. Signing fails closed if the key really is gone.
     */
    @Test
    fun `an indeterminate key with an enrollment proceeds`() {
        val decision = Convergence.onStartup(enrollment, KeyState.Indeterminate("KeyStoreException"))
        assertIs<StartupDecision.Proceed>(decision)
        assertEquals(enrollment, decision.enrollment)
    }

    @Test
    fun `an indeterminate key without an enrollment enrolls and destroys nothing`() {
        assertEquals(
            StartupDecision.Enroll,
            Convergence.onStartup(null, KeyState.Indeterminate("keystore unavailable")),
        )
    }

    @Test
    fun `converge reasons say what happened and what to do`() {
        val invalidated = Convergence.onStartup(enrollment, KeyState.Unusable("x"))
        val reason = (invalidated as StartupDecision.Converge).reason
        assertTrue(reason.contains("fingerprint", ignoreCase = true), "should name the likely cause")
        assertTrue(reason.contains("again", ignoreCase = true), "should say re-enrollment is needed")
    }

    @Test
    fun `no state produces a dead end`() {
        val states = listOf(
            KeyState.Absent,
            KeyState.Unusable("dead"),
            KeyState.Indeterminate("could not ask"),
            usableKey,
        )
        listOf(enrollment, null).forEach { record ->
            states.forEach { key ->
                val decision = Convergence.onStartup(record, key)
                val ok = when (decision) {
                    is StartupDecision.Proceed -> true
                    is StartupDecision.Converge -> decision.reason.isNotBlank()
                    StartupDecision.Enroll -> true
                }
                assertTrue(ok, "dead end for record=$record key=$key")
            }
        }
    }
}
