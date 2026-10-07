package dev.straza.approver.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Straza colour system. Dark-first: a cold steel-ink ground with a single
 * amber accent, used only on interactive elements. Green, amber and red are
 * reserved for trust states (approved, expired, denied). The core tokens map
 * onto the Material 3 scheme so stock components inherit them; the status
 * colours, which M3 has no slot for, ride a `CompositionLocal`.
 */

/**
 * Contrast on the card: text 15.9:1, muted 9.4:1, faint 7.7:1, all AAA for
 * small text. `surface2` is the plate, a local background that small metadata
 * text sits on.
 */
private object DarkTokens {
    val bg = Color(0xFF070A11)
    val surface = Color(0xFF131A28)
    val surface2 = Color(0xFF1F2939) // the plate
    val border = Color(0xFF263144)
    val borderStrong = Color(0xFF33405A)
    val text = Color(0xFFE9EFF9)
    val muted = Color(0xFFAEBACD)
    val accent = Color(0xFFF6B63C)
    val accentHover = Color(0xFFFFC860)
    val onAccent = Color(0xFF0B0D12)
    val status = StrazaStatusColors(
        approvedFg = Color(0xFF5CD97A), approvedBg = Color(0x215CD97A),
        deniedFg = Color(0xFFFF7A73), deniedBg = Color(0x21FF7A73),
        expiredFg = Color(0xFFE0A544), expiredBg = Color(0x21E0A544),
    )
}

private object LightTokens {
    val bg = Color(0xFFEEF1F6)
    val surface = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFE7ECF4) // the plate
    val border = Color(0xFFCDD5E3)
    val borderStrong = Color(0xFFAEB9CC)
    val text = Color(0xFF141A24)
    val muted = Color(0xFF47525F)
    val accent = Color(0xFF8A5A0F) // darker gold: 5.9:1 as text on white
    val accentHover = Color(0xFF7E5210)
    val onAccent = Color(0xFFFFFFFF)
    val status = StrazaStatusColors(
        approvedFg = Color(0xFF1A7F37), approvedBg = Color(0x1F1A7F37),
        deniedFg = Color(0xFFC62A25), deniedBg = Color(0x1CC62A25),
        expiredFg = Color(0xFF8A5A08), expiredBg = Color(0x1F8A5A08),
    )
}

/**
 * The trust-state palette M3 has no slot for. Each state is a coloured
 * foreground on a roughly 13%-alpha fill of the same hue.
 */
data class StrazaStatusColors(
    val approvedFg: Color, val approvedBg: Color,
    val deniedFg: Color, val deniedBg: Color,
    val expiredFg: Color, val expiredBg: Color,
)

/**
 * The dimmest text role, for timestamps and rule ids. `borderStrong` is a
 * divider colour and too low-contrast for text.
 */
private val DarkFaintText = Color(0xFFA2AEC1)
private val LightFaintText = Color(0xFF525E6B)
val LocalStrazaFaint = staticCompositionLocalOf { LightFaintText }
val LocalStrazaStatus = staticCompositionLocalOf { LightTokens.status }

/** The pressed-state amber. */
val LocalStrazaAccentHover = staticCompositionLocalOf { LightTokens.accentHover }

/** Accessors for the Straza colours, in the style of the `MaterialTheme` object. */
object StrazaTheme {
    val status: StrazaStatusColors
        @Composable @ReadOnlyComposable get() = LocalStrazaStatus.current
    val faint: Color
        @Composable @ReadOnlyComposable get() = LocalStrazaFaint.current
    val accentHover: Color
        @Composable @ReadOnlyComposable get() = LocalStrazaAccentHover.current
}

@Composable
fun StrazaTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (useDarkTheme) {
        darkColorScheme(
            primary = DarkTokens.accent,
            onPrimary = DarkTokens.onAccent,
            background = DarkTokens.bg,
            onBackground = DarkTokens.text,
            surface = DarkTokens.surface,
            onSurface = DarkTokens.text,
            surfaceVariant = DarkTokens.surface2,
            onSurfaceVariant = DarkTokens.muted,
            outline = DarkTokens.border,
            outlineVariant = DarkTokens.borderStrong,
            error = DarkTokens.status.deniedFg,
            onError = DarkTokens.onAccent,
            errorContainer = DarkTokens.status.deniedBg,
            onErrorContainer = DarkTokens.status.deniedFg,
            secondaryContainer = DarkTokens.surface2,
            onSecondaryContainer = DarkTokens.text,
        )
    } else {
        lightColorScheme(
            primary = LightTokens.accent,
            onPrimary = LightTokens.onAccent,
            background = LightTokens.bg,
            onBackground = LightTokens.text,
            surface = LightTokens.surface,
            onSurface = LightTokens.text,
            surfaceVariant = LightTokens.surface2,
            onSurfaceVariant = LightTokens.muted,
            outline = LightTokens.border,
            outlineVariant = LightTokens.borderStrong,
            error = LightTokens.status.deniedFg,
            onError = LightTokens.onAccent,
            errorContainer = LightTokens.status.deniedBg,
            onErrorContainer = LightTokens.status.deniedFg,
            secondaryContainer = LightTokens.surface2,
            onSecondaryContainer = LightTokens.text,
        )
    }
    CompositionLocalProvider(
        LocalStrazaStatus provides if (useDarkTheme) DarkTokens.status else LightTokens.status,
        LocalStrazaFaint provides if (useDarkTheme) DarkFaintText else LightFaintText,
        LocalStrazaAccentHover provides if (useDarkTheme) DarkTokens.accentHover else LightTokens.accentHover,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
