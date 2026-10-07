package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.SecureStore
import dev.straza.approver.shared.security.projectKey

/**
 * The renewal write-back. The fresh token is written onto the current stored
 * record, so a rename or push-config change that landed while the signature
 * was in flight survives. It is written only when that record is still the
 * same pairing: a deployment re-paired mid-renewal has a new device row and a
 * token of its own, and a token minted for the old row must not land on it.
 * Nothing is written over a record that is gone or unreadable
 * ([SecureStore.update] is a no-op there).
 */
sealed interface RenewalWriteBack {
    data class Applied(val renewed: Enrollment) : RenewalWriteBack

    /** Gone, re-paired, or unreadable: nothing written; the caller clears `busy` and stops. */
    data object Dropped : RenewalWriteBack
}

object RenewalWriteBacks {
    fun persist(
        store: SecureStore,
        bound: Enrollment,
        deviceToken: String,
        expiresInSeconds: Long,
        nowEpochSeconds: Long,
    ): RenewalWriteBack {
        val current = store.find(projectKey(bound)) ?: return RenewalWriteBack.Dropped
        if (current.approverDeviceId != bound.approverDeviceId) return RenewalWriteBack.Dropped
        val renewed = current.copy(
            deviceToken = deviceToken,
            tokenExpiresAtEpochSeconds = expiresInSeconds.takeIf { it > 0 }?.let { nowEpochSeconds + it },
        )
        store.update(renewed)
        return RenewalWriteBack.Applied(renewed)
    }
}
