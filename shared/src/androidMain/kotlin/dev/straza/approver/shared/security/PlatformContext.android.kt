package dev.straza.approver.shared.security

import android.content.Context

/**
 * Wraps the Android [Context], because an `expect class` cannot alias an
 * abstract type. Pass the application context: an Activity context would tie
 * the enrollment record's lifetime to a screen.
 */
actual class PlatformContext(internal val android: Context)
