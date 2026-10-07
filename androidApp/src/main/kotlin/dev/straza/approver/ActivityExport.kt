package dev.straza.approver

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares a CSV export of the Activity feed through the system share sheet.
 *
 * The file lives in the app's private cache and reaches the one app the user
 * picks through a FileProvider content URI with a read grant for that single
 * intent. It carries only what [dev.straza.approver.shared.flow.ActivityCsv]
 * put in it: no token, key or challenge.
 */
internal object ActivityExport {

    fun share(context: Context, csv: String, subjectLabel: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep only the newest export.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "straza-activity.csv")
        file.writeText(csv)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Straza activity - $subjectLabel")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Export activity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
