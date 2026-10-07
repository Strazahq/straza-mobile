import SwiftUI
import UserNotifications
import StrazaShared

// The whole Swift side of the app. It mounts the shared Kotlin module's
// MainViewController and forwards push callbacks to PushBridge. Logic belongs
// in shared/src/iosMain.

@main
struct StrazaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // All regions, not only .keyboard: the shared UI applies the
                // safe-area insets itself, so SwiftUI must not inset the view
                // a second time.
                .ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// APNs plumbing. Every callback is forwarded unchanged to PushBridge, which
// decides what a payload means.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Set the delegate before launch finishes, or a cold-start
        // notification tap is lost.
        UNUserNotificationCenter.current().delegate = self
        // Register on every launch. It shows no prompt, and the token is not
        // cached because Apple rotates it on restore, on a new device and on
        // OS reinstall.
        application.registerForRemoteNotifications()
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // The token length is not fixed, so hex-encode all of it.
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        PushBridge.shared.onApnsToken(hexToken: hex)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        PushBridge.shared.onApnsTokenFailure(message: error.localizedDescription)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        forward(notification.request.content.userInfo, into: PushBridge.shared.onPushReceived)
        // Returning [] would silence notifications while the app is in the foreground.
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        // A notification tap, including the one that cold-launches the app.
        forward(response.notification.request.content.userInfo, into: PushBridge.shared.onNotificationOpened)
        completionHandler()
    }

    /// Forwards the custom keys `v`, `ref` and `kind` as untrusted strings.
    /// The shared parser validates them. `v` is a JSON number on the APNs
    /// wire and arrives as NSNumber; a string is accepted too.
    private func forward(_ userInfo: [AnyHashable: Any], into entry: (String?, String?, String?) -> Void) {
        let v = (userInfo["v"] as? NSNumber)?.stringValue ?? userInfo["v"] as? String
        entry(v, userInfo["ref"] as? String, userInfo["kind"] as? String)
    }
}
