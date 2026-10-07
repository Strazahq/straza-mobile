package dev.straza.approver.shared.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The Swift shell's entry into Kotlin for APNs: the AppDelegate forwards its
 * four callbacks here and does nothing else. The APNs token and a cold-start
 * tap arrive before the controller exists, so every event is cached in a
 * [StateFlow] that the controller collects when ready. Entry points are plain
 * functions that only write flows: suspend functions called from Swift are
 * restricted to main, and the delegate queue is not guaranteed to be main.
 *
 * Payload fields are untrusted strings and go through [PushPayload.fromData].
 */
object PushBridge {

    /**
     * An envelope with a monotonic sequence number. A [StateFlow] conflates
     * equal values, and two taps for the same request must both act, so the
     * sequence is the identity. Collectors act only on a sequence newer than
     * the last one handled.
     */
    data class Event(val seq: Long, val envelope: PushEnvelope)

    private val _apnsToken = MutableStateFlow<String?>(null)
    private val _tokenFailure = MutableStateFlow<String?>(null)
    private val _received = MutableStateFlow<Event?>(null)
    private val _opened = MutableStateFlow<Event?>(null)

    /** The current device token, lowercase hex, or null until registration
     *  answers. Not persisted, because tokens change on restore and
     *  reinstall; the server registration is redone on every launch. */
    val apnsToken: StateFlow<String?> = _apnsToken

    /** Why registration last failed, or null. Shown on the notifications
     *  screen; polling is unaffected. */
    val tokenFailure: StateFlow<String?> = _tokenFailure

    /** Pushes that arrived while the app was frontmost (willPresent). */
    val received: StateFlow<Event?> = _received

    /** Notification taps (didReceive), including the cold-start one. */
    val opened: StateFlow<Event?> = _opened

    fun onApnsToken(hexToken: String) {
        _tokenFailure.value = null
        _apnsToken.value = hexToken
    }

    fun onApnsTokenFailure(message: String) {
        _tokenFailure.value = message
    }

    fun onPushReceived(v: String?, ref: String?, kind: String?) {
        emit(_received, v, ref, kind)
    }

    fun onNotificationOpened(v: String?, ref: String?, kind: String?) {
        emit(_opened, v, ref, kind)
    }

    private fun emit(flow: MutableStateFlow<Event?>, v: String?, ref: String?, kind: String?) {
        val envelope = PushPayload.fromData(v, ref, kind)
        // Ignore is dropped here so nothing downstream sees malformed input.
        // The poll covers the gap of at most one interval.
        if (envelope is PushEnvelope.Ignore) return
        flow.update { prev -> Event((prev?.seq ?: 0L) + 1L, envelope) }
    }
}
