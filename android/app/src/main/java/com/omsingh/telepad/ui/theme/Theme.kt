package com.omsingh.telepad.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.ThemeMode

/**
 * Root theme composable.
 *
 * Decides between:
 *  - Material You **dynamic color** (system wallpaper-derived palette) on
 *    Android 12+ when the user opts in.
 *  - Static palette derived from [accentColor] + [themeMode] otherwise.
 *
 * Also sets edge-to-edge transparent system bars and the correct light/dark
 * status bar icon mode via [WindowCompat].
 */
@Composable
fun TelepadTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.CYAN,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }

    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context)
            else        dynamicLightColorScheme(context)
        }
        isDark -> darkScheme(accentColor)
        else   -> lightScheme(accentColor)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION") window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION") window.navigationBarColor = Color.Transparent.toArgb()
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = TelepadTypography,
        content = content
    )
}

private fun darkScheme(accent: AccentColor) = darkColorScheme(
    primary             = accent.darkPrimary,
    onPrimary           = DarkBackground,
    primaryContainer    = DarkSurfaceVariant,
    onPrimaryContainer  = DarkOnSurface,
    secondary           = accent.darkPrimary,
    onSecondary         = DarkBackground,
    tertiary            = StatusOnline,
    onTertiary          = DarkBackground,
    error               = RedDark,
    onError             = DarkBackground,
    background          = DarkBackground,
    onBackground        = DarkOnSurface,
    surface             = DarkSurface,
    onSurface           = DarkOnSurface,
    surfaceVariant      = DarkSurfaceVariant,
    onSurfaceVariant    = DarkOnSurfaceVar,
    surfaceContainerLow = DarkSurface,
    surfaceContainerHigh= DarkSurfaceVariant,
    outline             = DarkOutline,
)

private fun lightScheme(accent: AccentColor) = lightColorScheme(
    primary             = accent.lightPrimary,
    onPrimary           = LightSurface,
    primaryContainer    = accent.lightContainer,
    onPrimaryContainer  = LightOnSurface,
    secondary           = accent.lightPrimary,
    onSecondary         = LightSurface,
    tertiary            = StatusOnline,
    onTertiary          = LightSurface,
    error               = RedLight,
    onError             = LightSurface,
    background          = LightBackground,
    onBackground        = LightOnSurface,
    surface             = LightSurface,
    onSurface           = LightOnSurface,
    surfaceVariant      = LightSurfaceVariant,
    onSurfaceVariant    = LightOnSurfaceVar,
    surfaceContainerLow = LightSurfaceVariant,
    surfaceContainerHigh= LightSurfaceVariant,
    outline             = LightOutline,
)

private val AccentColor.darkPrimary: Color
    get() = when (this) {
        AccentColor.CYAN   -> CyanDark
        AccentColor.PURPLE -> PurpleDark
        AccentColor.GREEN  -> GreenDark
        AccentColor.ORANGE -> OrangeDark
        AccentColor.RED    -> RedDark
    }

private val AccentColor.lightPrimary: Color
    get() = when (this) {
        AccentColor.CYAN   -> CyanLight
        AccentColor.PURPLE -> PurpleLight
        AccentColor.GREEN  -> GreenLight
        AccentColor.ORANGE -> OrangeLight
        AccentColor.RED    -> RedLight
    }

/** Light container = a translucent tint of the accent atop the surface. */
private val AccentColor.lightContainer: Color
    get() = lightPrimary.copy(alpha = 0.12f).compositeOver(LightSurface)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    val r = red   * a + background.red   * (1f - a)
    val g = green * a + background.green * (1f - a)
    val b = blue  * a + background.blue  * (1f - a)
    return Color(r, g, b, 1f)
}

/** Re-exported convenience for screens that need transport-tinted surfaces. */
fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red   * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue  * 255).toInt().coerceIn(0, 255),
)
