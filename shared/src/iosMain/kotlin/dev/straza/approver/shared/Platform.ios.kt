package dev.straza.approver.shared

import platform.UIKit.UIDevice

actual fun platformLabel(): String =
    "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"
