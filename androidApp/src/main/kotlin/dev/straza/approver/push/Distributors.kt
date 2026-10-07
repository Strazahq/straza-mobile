package dev.straza.approver.push

import android.content.Context
import android.content.pm.PackageManager
import dev.straza.approver.shared.push.PushDistributor
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Lists installed UnifiedPush distributor apps for the settings picker, with
 * labels from PackageManager (the package id when no label can be read).
 *
 * The embedded distributor (play flavor) appears in the connector's scan as
 * this app's own package. It is listed only when Play services exist to serve
 * it, and labeled as this app relaying through Google.
 */
object Distributors {

    fun installed(context: Context): List<PushDistributor> =
        UnifiedPush.getDistributors(context).mapNotNull { pkg ->
            if (pkg == context.packageName) {
                // The embedded fallback registers straight against Google push.
                // Without Play services it can only fail, so it is not offered.
                if (!gmsPresent(context)) return@mapNotNull null
                PushDistributor(id = pkg, label = "Built-in (via Google push)")
            } else {
                val label = try {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                PushDistributor(id = pkg, label = label)
            }
        }

    fun saved(context: Context): String? = UnifiedPush.getSavedDistributor(context)

    /**
     * The distributor to use when the user has not chosen one: the single
     * user-installed distributor if there is only one, else the embedded one
     * if nothing else is installed, else null. Several installed distributors
     * are left for the user to choose between in the picker.
     */
    fun autoChoice(context: Context): String? {
        val installed = installed(context).map { it.id }
        val real = installed.filterNot { it == context.packageName }
        return real.singleOrNull()
            ?: installed.singleOrNull().takeIf { real.isEmpty() }
    }

    /** Package-presence probe. It uses no Google API, so it works in the foss flavor. */
    private fun gmsPresent(context: Context): Boolean = try {
        context.packageManager.getPackageInfo("com.google.android.gms", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

/**
 * The REGISTER call for both flavors. A VAPID key the connector rejects as
 * malformed falls back to a keyless registration instead of crashing the sync:
 * server-supplied input must not take push down. A distributor that requires
 * a key then answers VAPID_REQUIRED, handled in
 * [StrazaPushService.onRegistrationFailed].
 */
internal fun registerWithDistributor(context: Context, vapid: String?) {
    try {
        UnifiedPush.register(context, vapid = vapid)
    } catch (_: UnifiedPush.VapidNotValidException) {
        UnifiedPush.register(context)
    }
}
