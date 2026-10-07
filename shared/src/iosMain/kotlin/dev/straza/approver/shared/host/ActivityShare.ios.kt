package dev.straza.approver.shared.host

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * Shares a CSV export of the Activity feed through the iOS share sheet. The
 * file is written to the app's sandboxed temp directory under one fixed name,
 * so each export overwrites the last, and is passed as a file URL so receivers
 * get a `.csv` attachment. It carries only what ActivityCsv put in it: no
 * token, key or challenge.
 */
internal object ActivityShare {

    /** Writes the CSV and presents the sheet over the top-most view
     *  controller. Returns false when nothing was shared: the write failed,
     *  or there was no window to present from. */
    @OptIn(ExperimentalForeignApi::class)
    fun present(csv: String): Boolean {
        val path = NSTemporaryDirectory() + "straza-activity.csv"
        val bytes = csv.encodeToByteArray()
        if (bytes.isEmpty()) return false
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
        }
        if (!data.writeToFile(path, atomically = true)) return false
        val host = topViewController() ?: return false
        val sheet = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path)),
            applicationActivities = null,
        )
        // iPad presents this as a popover and traps without an anchor, so it
        // is anchored to a centred zero-size rect.
        sheet.popoverPresentationController?.let { pop ->
            val anchor = host.view ?: return@let
            pop.sourceView = anchor
            pop.sourceRect = anchor.bounds.useContents {
                CGRectMake(size.width / 2.0, size.height / 2.0, 0.0, 0.0)
            }
        }
        host.presentViewController(sheet, animated = true, completion = null)
        return true
    }

    /** The key window's root, or the last controller it has presented. UIKit
     *  refuses to present a sheet from any other one. */
    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) top = top.presentedViewController ?: return top
    }
}
