package dev.straza.approver.push

import dev.straza.approver.ApprovalController

/**
 * The link between [StrazaPushService], which can fire when the app is
 * backgrounded or dead, and a live [ApprovalController].
 *
 * MainActivity publishes its controller here while in the foreground and
 * clears it on stop. When it is null the service posts a notification instead.
 */
object PushBridge {
    @Volatile
    var controller: ApprovalController? = null
}
