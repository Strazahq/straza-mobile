package dev.straza.approver.shared

import android.os.Build

actual fun platformLabel(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
