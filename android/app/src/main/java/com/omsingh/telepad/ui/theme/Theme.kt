package com.omsingh.telepad.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.ThemeMode

/** The seed colour each accent is generated from (see [ThemeEngine]). */
internal fun AccentColor.seed(): Int = when (this) {
    AccentColor.CYAN -> 0xFF0EA5E9.toInt()
    AccentColor.PURPLE -> 0xFF8B5CF6.toInt()
    AccentColor.GREEN -> 0xFF10B981.toInt()
    AccentColor.ORANGE -> 0xFFF97316.toInt()
    AccentColor.RED -> 0xFFEF4444.toInt()
}

/**
 * Colours Material has no role for: status feedback, and the touch surface.
 *
 * Success and warning keep their meaning (green, amber) whatever the accent is.
 */
@Immutable
class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Fill of the touch surface. */
    val pad: Color,
    /** Resting border of the touch surface. */
    val padOutline: Color,
    /** The faint dot grid drawn on the touch surface. */
    val padGrid: Color,
)

internal fun ExtendedRoles.toColors() = ExtendedColors(
    success = Color(success), onSuccess = Color(onSuccess),
    successContainer = Color(successContainer), onSuccessContainer = Color(onSuccessContainer),
    warning = Color(warning), onWarning = Color(onWarning),
    warningContainer = Color(warningContainer), onWarningContainer = Color(onWarningContainer),
    pad = Color(pad), padOutline = Color(padOutline), padGrid = Color(padGrid),
)

/**
 * A complete Material colour scheme for [accent]. Every role is set (see [SchemeRoles]),
 * so no component falls back to Material's baseline purple.
 */
internal fun colorSchemeFor(accent: AccentColor, dark: Boolean): ColorScheme = schemeToColors(ThemeEngine.scheme(accent.seed(), dark))

internal fun schemeToColors(r: SchemeRoles): ColorScheme = ColorScheme(
    primary = Color(r.primary), onPrimary = Color(r.onPrimary),
    primaryContainer = Color(r.primaryContainer), onPrimaryContainer = Color(r.onPrimaryContainer),
    inversePrimary = Color(r.inversePrimary),
    secondary = Color(r.secondary), onSecondary = Color(r.onSecondary),
    secondaryContainer = Color(r.secondaryContainer), onSecondaryContainer = Color(r.onSecondaryContainer),
    tertiary = Color(r.tertiary), onTertiary = Color(r.onTertiary),
    tertiaryContainer = Color(r.tertiaryContainer), onTertiaryContainer = Color(r.onTertiaryContainer),
    background = Color(r.background), onBackground = Color(r.onBackground),
    surface = Color(r.surface), onSurface = Color(r.onSurface),
    surfaceVariant = Color(r.surfaceVariant), onSurfaceVariant = Color(r.onSurfaceVariant),
    surfaceTint = Color(r.surfaceTint),
    inverseSurface = Color(r.inverseSurface), inverseOnSurface = Color(r.inverseOnSurface),
    error = Color(r.error), onError = Color(r.onError),
    errorContainer = Color(r.errorContainer), onErrorContainer = Color(r.onErrorContainer),
    outline = Color(r.outline), outlineVariant = Color(r.outlineVariant),
    scrim = Color(r.scrim),
    surfaceBright = Color(r.surfaceBright), surfaceDim = Color(r.surfaceDim),
    surfaceContainer = Color(r.surfaceContainer),
    surfaceContainerHigh = Color(r.surfaceContainerHigh),
    surfaceContainerHighest = Color(r.surfaceContainerHighest),
    surfaceContainerLow = Color(r.surfaceContainerLow),
    surfaceContainerLowest = Color(r.surfaceContainerLowest),
)

private val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> {
    error("TelepadTheme is missing: wrap the content in TelepadTheme { }")
}

/** Telepad's own design tokens, reachable like `MaterialTheme.colorScheme`. */
object TelepadTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}

/**
 * The app's theme: Material 3, with colours generated from the chosen accent (or taken
 * from the wallpaper with Material You on Android 12+), and the app's type, shapes and
 * spacing.
 */
@Composable
fun TelepadTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.CYAN,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = remember(accent, dark, useDynamic) {
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> colorSchemeFor(accent, dark)
        }
    }
    val extended = remember(colorScheme.primary, accent, dark, useDynamic) {
        val seed = if (useDynamic) colorScheme.primary.toArgb() else accent.seed()
        ThemeEngine.extended(seed, dark).toColors()
    }

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TelepadTypography,
            shapes = TelepadShapes,
            content = content,
        )
    }
}
