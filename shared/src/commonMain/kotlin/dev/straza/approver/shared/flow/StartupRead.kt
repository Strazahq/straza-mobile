package dev.straza.approver.shared.flow

import dev.straza.approver.shared.security.Enrollment
import dev.straza.approver.shared.security.KeyState
import dev.straza.approver.shared.security.VaultSnapshot

/**
 * What the foreground startup read hands the controller. An unreadable vault
 * is no information, so nothing destructive runs on it (no orphan-key sweep,
 * no key-state convergence): a session already in memory keeps running on the
 * record it holds, and a cold start tells the user.
 */
sealed interface StartupRead {

    /** The store could not be read and no session is held. Tell the user and touch nothing. */
    data class Unreadable(val reason: String) : StartupRead

    /**
     * What [Convergence.onStartup] decides on. [fromHeldSession] is true when
     * the store was unreadable but a session was in memory: [all] is then just
     * that record, [keyState] is [KeyState.Indeterminate] and nothing was swept.
     */
    data class Ready(
        val all: List<Enrollment>,
        val active: Enrollment?,
        val keyState: KeyState,
        val fromHeldSession: Boolean,
    ) : StartupRead
}

object StartupReads {

    /**
     * @param held the record the in-memory session holds, if any (captured on
     *   the main thread before the off-main read).
     * @param onReadable runs with the full record list, only when the vault was
     *   readable. Android's orphan-key sweep runs here: the keys it keeps must
     *   come from a real read.
     * @param keyStateOf the hardware key state for a keyRef ("" = legacy alias).
     */
    fun resolve(
        snapshot: VaultSnapshot,
        held: Enrollment?,
        onReadable: (List<Enrollment>) -> Unit = {},
        keyStateOf: (keyRef: String) -> KeyState,
    ): StartupRead = when (snapshot) {
        is VaultSnapshot.Unreadable ->
            held?.let { StartupRead.Ready(listOf(it), it, KeyState.Indeterminate(snapshot.reason), fromHeldSession = true) }
                ?: StartupRead.Unreadable(snapshot.reason)

        is VaultSnapshot.Readable -> {
            onReadable(snapshot.all)
            // The active deployment's key, or the legacy alias when nothing is
            // enrolled, so a leftover key with no enrollment is still detected.
            StartupRead.Ready(snapshot.all, snapshot.active, keyStateOf(snapshot.active?.keyRef ?: ""), fromHeldSession = false)
        }
    }

    /** The pairing-screen text for [StartupRead.Unreadable]. */
    fun unreadableMessage(reason: String): String =
        "Stored pairings could not be read on this device ($reason). " +
            "Restart the app to try again. Pairing again replaces them."
}
