package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeyState

/**
 * What the app does with the state it found on disk at startup.
 *
 * Android destroys the device key when biometrics change
 * (`setInvalidatedByBiometricEnrollment`), while the enrollment record and the
 * server row survive. Left alone, the app would show Approve buttons that
 * cannot sign. Such a state is wiped and explained; the key is unrecoverable,
 * so re-enrollment is required.
 */
sealed interface StartupDecision {

    /** Nothing stored. Show enrollment; no cleanup needed. */
    data object Enroll : StartupDecision

    /** Local state is present but unusable. Wipe it, then show enrollment with [reason]. */
    data class Converge(val reason: String) : StartupDecision

    data class Proceed(val enrollment: Enrollment) : StartupDecision
}

object Convergence {

    fun onStartup(enrollment: Enrollment?, keyState: KeyState): StartupDecision = when {
        // The keystore could not answer. Nothing is known, so nothing is
        // destroyed: with a record, proceed and let the signing attempt fail
        // closed on its own; without one, enroll, and a possible orphan key
        // waits for a start that can see it. Only a state proven dead converges.
        keyState is KeyState.Indeterminate ->
            enrollment?.let { StartupDecision.Proceed(it) } ?: StartupDecision.Enroll

        // No enrollment, but a key exists: an enroll that failed partway, or a
        // wipe that only got half done. The key is useless without a
        // server-side device row, so clear it.
        enrollment == null && keyState !is KeyState.Absent ->
            StartupDecision.Converge("Leftover device key with no enrollment.")

        enrollment == null -> StartupDecision.Enroll

        // Enrollment intact, key gone or dead.
        keyState is KeyState.Absent -> StartupDecision.Converge(
            "This device's key is missing, so approvals can no longer be signed. " +
                "Enroll this device again.",
        )

        keyState is KeyState.Unusable -> StartupDecision.Converge(
            "This device's key was invalidated. This normally happens when a " +
                "fingerprint or face is added or removed, which deliberately " +
                "destroys the key. Enroll this device again.",
        )

        else -> StartupDecision.Proceed(enrollment)
    }
}
