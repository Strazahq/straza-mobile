package dev.straza.approver

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.straza.approver.shared.security.DeviceKeyStore
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Wraps the hardware signing operation in an OS authentication prompt.
 *
 * The [Signature] handed to the prompt in a [BiometricPrompt.CryptoObject] is
 * one the Keystore will not finish until the OS reports a successful
 * authentication for that operation. Calling `sign()` without the prompt
 * throws `UserNotAuthenticatedException`.
 */
class BiometricGate(private val activity: FragmentActivity) {

    /**
     * Authenticators the prompt may use. Below API 30 `BiometricPrompt` cannot
     * combine a CryptoObject with device credential, so those devices are
     * biometric-only and get a negative button. [DeviceKeyStore] sets the same
     * split on the key.
     */
    private val allowedAuthenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    /** Whether this device can authenticate at all. */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(activity).canAuthenticate(allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompts the user, then signs [message] inside the authenticated
     * operation. [title] and [subtitle] say what is being authorised.
     *
     * @return the ECDSA signature, or null if the user cancelled, failed
     *   authentication, or the platform refused. Null means nothing is
     *   submitted: a cancelled prompt is not a denial.
     */
    suspend fun signWithAuthentication(
        keyStore: DeviceKeyStore,
        message: ByteArray,
        title: String,
        subtitle: String,
    ): ByteArray? {
        val signature = try {
            keyStore.beginSign()
        } catch (_: Exception) {
            // No usable key, for example one invalidated by a new biometric enrolment.
            return null
        }

        // BiometricPrompt is fragment-based: authenticate() must run on the
        // fragment host's main thread and throws otherwise. Callers may be on
        // another thread, so the hop is made here.
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    // Callbacks run on the main thread too.
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {

                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            // Use the Signature the OS released in the result.
                            // It is the same object as the one passed in.
                            val authenticated = result.cryptoObject?.signature
                            val signed = try {
                                authenticated?.run {
                                    update(message)
                                    sign()
                                }
                            } catch (_: Exception) {
                                null
                            }
                            continuation.resume(signed)
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            // Cancellation, lockout, no hardware: none of them
                            // authenticated, and all submit nothing.
                            continuation.resume(null)
                        }

                        override fun onAuthenticationFailed() {
                            // A single bad read. The prompt stays up and may
                            // still succeed, so do not resume here.
                        }
                    },
                )

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(allowedAuthenticators)
                    .apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            setNegativeButtonText("Cancel")
                        }
                    }
                    .setConfirmationRequired(true)
                    .build()

                prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))

                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }
}
