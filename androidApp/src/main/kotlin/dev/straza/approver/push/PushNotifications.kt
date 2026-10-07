package dev.straza.approver.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.straza.approver.MainActivity
import dev.straza.approver.R
import dev.straza.approver.shared.push.PushNotification

/**
 * Posts the opaque "a decision is waiting" notification for a background push.
 *
 * The content is a [PushNotification], derived from the push's kind hint and
 * not its payload, so nothing about the request reaches a locked screen.
 * Tapping opens [MainActivity], which fetches the request over the
 * authenticated channel.
 */
object PushNotifications {

    private const val CHANNEL_APPROVALS = "approvals"
    private const val CHANNEL_UPDATES = "updates"
    private const val ID_APPROVAL = 1
    private const val ID_UPDATE = 2

    // Lint cannot trace the MissingPermission guard through hasPermission(),
    // hence the suppression.
    //
    // [quiet] selects the channel: a waiting approval rings on the
    // high-importance channel, a status update lands silently on the
    // low-importance one. The ids differ too, so a status update cannot
    // overwrite a pending approval alert.
    @SuppressLint("MissingPermission")
    fun show(context: Context, notification: PushNotification, quiet: Boolean = false) {
        // API 33+ refuses notifications without the runtime grant. Stay silent
        // then: the poll still works.
        if (!hasPermission(context)) return

        ensureChannels(context)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val built = NotificationCompat.Builder(context, if (quiet) CHANNEL_UPDATES else CHANNEL_APPROVALS)
            // A flat white silhouette, as a status-bar icon must be.
            .setSmallIcon(R.drawable.ic_stat_straza)
            // Beacon amber tints the small icon and app name in the expanded shade.
            .setColor(0xFFF6B63C.toInt())
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(if (quiet) ID_UPDATE else ID_APPROVAL, built)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVALS,
                "Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts that a request is waiting for your decision."
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                "Updates",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Quiet notices that one of your requests was resolved."
                setShowBadge(false)
            },
        )
    }
}
