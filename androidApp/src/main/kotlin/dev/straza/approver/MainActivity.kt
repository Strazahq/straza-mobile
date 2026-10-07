package dev.straza.approver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.straza.approver.push.PushBridge
import dev.straza.approver.shared.ui.App
import dev.straza.approver.shared.ui.backTarget
import dev.straza.approver.shared.ui.navigateBack
import kotlinx.coroutines.CompletableDeferred

/**
 * Single activity host. A [FragmentActivity] because `BiometricPrompt` is
 * implemented as a fragment and requires one.
 */
class MainActivity : FragmentActivity() {

    private lateinit var controller: ApprovalController

    /**
     * Pending camera-permission request, if one is in flight. The launcher
     * must be registered before the activity resumes, so it is registered once
     * and the answer is routed back through this deferred.
     */
    private var cameraPermission: CompletableDeferred<Boolean>? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermission?.complete(granted)
            cameraPermission = null
        }

    /**
     * Notification permission (API 33+), asked once enrolled. A denial is not
     * fatal: the app cannot post background notifications and keeps polling.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks for the camera only when it is about to be used, not at launch. */
    private suspend fun ensureCameraPermission(): Boolean {
        if (hasCameraPermission()) return true
        // Complete any abandoned request so its caller is not left suspended.
        cameraPermission?.complete(false)
        val pending = CompletableDeferred<Boolean>()
        cameraPermission = pending
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        return pending.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECURE before anything is drawn: excludes this app's windows
        // from screenshots, recording and the recent-apps thumbnail. It covers
        // the whole app: the pending list alone names the tools an
        // organisation gates and who requests them.
        //
        // The one exemption is for demo capture, on a debuggable build (which
        // a release build is not) launched with an explicit adb extra:
        //   adb shell am start -n ai.straza.approver.foss.debug/dev.straza.approver.MainActivity \
        //     --ez straza.allow_capture true
        val allowCapture = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("straza.allow_capture", false) == true
        if (!allowCapture) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = ApprovalController(
            context = applicationContext,
            gate = BiometricGate(this),
            scope = lifecycleScope,
            requestCameraPermission = ::ensureCameraPermission,
            // Asks for the notification grant if the user opted in to push, and
            // re-syncs the push transport on every foreground as the
            // keep-alive. A callback because start() resolves off the main
            // thread, so state is stale right after it returns.
            onEnrolledForeground = {
                if (controller.pushConsentGranted()) ensureNotificationPermission()
                controller.syncPushTransport()
            },
            requestNotificationPermission = ::ensureNotificationPermission,
        )

        setContent {
            // System Back steps one screen up through the same actions as the
            // on-screen controls. With nothing to unwind the handler disables
            // itself and the system finishes the activity. See BackNavigation.kt.
            val backTarget = backTarget(controller.state)
            BackHandler(enabled = backTarget != null) {
                controller.navigateBack(controller.state)
            }
            App(
                state = controller.state,
                actions = controller,
                // The scanner is a slot: shared code decides when it is shown.
                scanner = { QrScanner(onDecoded = controller::enroll) },
                // Assembled here because shared code cannot read BuildConfig.
                versionLabel = "Straza ${BuildConfig.VERSION_NAME} " +
                    "(${BuildConfig.VERSION_CODE}) · ${BuildConfig.FLAVOR}",
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-read enrollment and resume polling on every foreground: the
        // enrollment may have been revoked while the app was away.
        controller.start()

        // Publish the live controller so a foreground push refreshes in place
        // instead of posting a notification.
        PushBridge.controller = controller
    }

    override fun onStop() {
        // Drop the live reference first: once backgrounded, a push must take
        // the notification path.
        PushBridge.controller = null
        controller.stop()
        super.onStop()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
