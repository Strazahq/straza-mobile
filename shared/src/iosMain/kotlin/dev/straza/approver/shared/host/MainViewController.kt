package dev.straza.approver.shared.host

import androidx.compose.ui.window.ComposeUIViewController
import dev.straza.approver.shared.ui.App
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/**
 * The Swift host's single entry point. The Swift app (StrazaApp.swift) only
 * mounts this controller: state and security wiring live in
 * [IosAppController], the camera in [IosQrScanner].
 */
fun MainViewController(): UIViewController {
    val controller = IosAppController()
    return ComposeUIViewController {
        App(
            state = controller.state,
            actions = controller,
            scanner = { IosQrScanner(onDecoded = controller::enroll) },
            versionLabel = versionLabel(),
        )
    }
}

/** A label such as "Straza 0.1.5 (6) · ios", read from the bundle because
 *  shared code has no BuildConfig. */
private fun versionLabel(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val version = info?.get("CFBundleShortVersionString") as? String ?: return ""
    val build = info["CFBundleVersion"] as? String
    return "Straza $version" + (build?.let { " ($it)" } ?: "") + " · ios"
}
