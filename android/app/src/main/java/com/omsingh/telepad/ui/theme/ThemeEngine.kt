package com.omsingh.telepad.ui.theme

/**
 * Every colour role Material 3 components read, as `0xAARRGGBB` ints.
 *
 * The app used to set only about half of the roles and leave the rest at
 * Material's baseline defaults, which are purple. Components that read the
 * unset roles (segmented buttons, filter chips, navigation indicators, switch
 * tracks) therefore showed stray purple tints. Here every role is generated, so
 * the whole UI stays in one family.
 */
internal data class SchemeRoles(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val background: Int, val onBackground: Int,
    val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceTint: Int,
    val surfaceDim: Int, val surfaceBright: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
    val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
    val inverseSurface: Int, val inverseOnSurface: Int, val inversePrimary: Int,
) {
    /** Every foreground/background pair that carries text or icons, with the contrast it must reach. */
    fun textPairs(): List<ContrastRequirement> = listOf(
        ContrastRequirement("onPrimary/primary", onPrimary, primary, TEXT),
        ContrastRequirement("onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer, TEXT),
        ContrastRequirement("onSecondary/secondary", onSecondary, secondary, TEXT),
        ContrastRequirement("onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer, TEXT),
        ContrastRequirement("onTertiary/tertiary", onTertiary, tertiary, TEXT),
        ContrastRequirement("onTertiaryContainer/tertiaryContainer", onTertiaryContainer, tertiaryContainer, TEXT),
        ContrastRequirement("onError/error", onError, error, TEXT),
        ContrastRequirement("onErrorContainer/errorContainer", onErrorContainer, errorContainer, TEXT),
        ContrastRequirement("onBackground/background", onBackground, background, TEXT),
        ContrastRequirement("onSurface/surface", onSurface, surface, TEXT),
        ContrastRequirement("onSurface/surfaceDim", onSurface, surfaceDim, TEXT),
        ContrastRequirement("onSurface/surfaceBright", onSurface, surfaceBright, TEXT),
        ContrastRequirement("onSurface/surfaceContainerLowest", onSurface, surfaceContainerLowest, TEXT),
        ContrastRequirement("onSurface/surfaceContainerLow", onSurface, surfaceContainerLow, TEXT),
        ContrastRequirement("onSurface/surfaceContainer", onSurface, surfaceContainer, TEXT),
        ContrastRequirement("onSurface/surfaceContainerHigh", onSurface, surfaceContainerHigh, TEXT),
        ContrastRequirement("onSurface/surfaceContainerHighest", onSurface, surfaceContainerHighest, TEXT),
        ContrastRequirement("onSurfaceVariant/surface", onSurfaceVariant, surface, TEXT),
        ContrastRequirement("onSurfaceVariant/surfaceContainerHigh", onSurfaceVariant, surfaceContainerHigh, TEXT),
        ContrastRequirement("onSurfaceVariant/surfaceVariant", onSurfaceVariant, surfaceVariant, TEXT),
        ContrastRequirement("inverseOnSurface/inverseSurface", inverseOnSurface, inverseSurface, TEXT),
        // Accent-coloured text and icons sitting directly on surfaces.
        ContrastRequirement("primary/surface", primary, surface, TEXT),
        ContrastRequirement("primary/surfaceContainerHigh", primary, surfaceContainerHigh, UI_COMPONENT),
        ContrastRequirement("error/surface", error, surface, TEXT),
        // Borders and control outlines must be visible.
        ContrastRequirement("outline/surface", outline, surface, UI_COMPONENT),
        ContrastRequirement("outline/surfaceContainerHigh", outline, surfaceContainerHigh, UI_COMPONENT_MIN),
    )

    companion object {
        /** WCAG AA for normal text. */
        const val TEXT = 4.5
        /** WCAG AA for icons and meaningful graphics. */
        const val UI_COMPONENT = 3.0
        /** Outlines on elevated containers: still visible, slightly relaxed. */
        const val UI_COMPONENT_MIN = 2.6
    }
}

internal data class ContrastRequirement(
    val name: String,
    val foreground: Int,
    val background: Int,
    val minimum: Double,
) {
    val actual: Double get() = ColorMath.contrast(foreground, background)
}

/** Semantic colours Material has no role for: status feedback and the touchpad surface. */
internal data class ExtendedRoles(
    val success: Int, val onSuccess: Int, val successContainer: Int, val onSuccessContainer: Int,
    val warning: Int, val onWarning: Int, val warningContainer: Int, val onWarningContainer: Int,
    /** Fill of the touch surface: a touch quieter than the surrounding screen. */
    val pad: Int,
    /** Resting border of the touch surface; meets the 3:1 non-text contrast guideline. */
    val padOutline: Int,
    /** The faint dot grid drawn on the touch surface. */
    val padGrid: Int,
) {
    fun textPairs(surface: Int): List<ContrastRequirement> = listOf(
        ContrastRequirement("onSuccess/success", onSuccess, success, SchemeRoles.TEXT),
        ContrastRequirement("onSuccessContainer/successContainer", onSuccessContainer, successContainer, SchemeRoles.TEXT),
        ContrastRequirement("onWarning/warning", onWarning, warning, SchemeRoles.TEXT),
        ContrastRequirement("onWarningContainer/warningContainer", onWarningContainer, warningContainer, SchemeRoles.TEXT),
        ContrastRequirement("success/surface", success, surface, SchemeRoles.UI_COMPONENT),
        ContrastRequirement("warning/surface", warning, surface, SchemeRoles.UI_COMPONENT),
        // The pad's border is what tells a thumb where the surface ends.
        ContrastRequirement("padOutline/surface", padOutline, surface, SchemeRoles.UI_COMPONENT),
    )
}

/**
 * Builds complete Material 3 colour schemes from a single seed colour.
 *
 * The approach follows Material's own: derive tonal palettes (a hue plus a chroma,
 * sampled at lightness "tones" 0 to 100), then assign tones to roles with the
 * fixed table from the M3 specification. Tones are chosen by *luminance*, so the
 * contrast between two roles depends only on their tone numbers and never on the
 * hue. That is why every pair below is guaranteed readable, for any seed.
 */
internal object ThemeEngine {

    /** Hue of Material's error red in OKLCH. */
    private const val ERROR_HUE = 29.0
    private const val SUCCESS_HUE = 150.0
    private const val WARNING_HUE = 85.0

    private const val MIN_ACCENT_CHROMA = 0.085
    private const val MAX_ACCENT_CHROMA = 0.20

    /** A palette generates colours of one hue at any tone, caching the results. */
    internal class Palette(private val hue: Double, private val chroma: Double) {
        private val cache = HashMap<Int, Int>()
        fun tone(t: Int): Int = cache.getOrPut(t) { ColorMath.colorOfTone(hue, chroma, t.toDouble()) }
    }

    fun scheme(seed: Int, dark: Boolean): SchemeRoles {
        val accent = ColorMath.toOklch(seed)
        val hue = accent.h
        val chroma = accent.c.coerceIn(MIN_ACCENT_CHROMA, MAX_ACCENT_CHROMA)

        val primary = Palette(hue, chroma)
        val secondary = Palette(hue, chroma * 0.30)
        val tertiary = Palette((hue + 60.0) % 360.0, chroma * 0.50)
        val neutral = Palette(hue, 0.012)
        val neutralVariant = Palette(hue, 0.026)
        val error = Palette(ERROR_HUE, 0.20)

        return if (dark) {
            SchemeRoles(
                primary = primary.tone(80), onPrimary = primary.tone(20),
                primaryContainer = primary.tone(30), onPrimaryContainer = primary.tone(90),
                secondary = secondary.tone(80), onSecondary = secondary.tone(20),
                secondaryContainer = secondary.tone(30), onSecondaryContainer = secondary.tone(90),
                tertiary = tertiary.tone(80), onTertiary = tertiary.tone(20),
                tertiaryContainer = tertiary.tone(30), onTertiaryContainer = tertiary.tone(90),
                error = error.tone(80), onError = error.tone(20),
                errorContainer = error.tone(30), onErrorContainer = error.tone(90),
                background = neutral.tone(6), onBackground = neutral.tone(90),
                surface = neutral.tone(6), onSurface = neutral.tone(90),
                surfaceVariant = neutralVariant.tone(30), onSurfaceVariant = neutralVariant.tone(80),
                surfaceTint = primary.tone(80),
                surfaceDim = neutral.tone(6), surfaceBright = neutral.tone(24),
                surfaceContainerLowest = neutral.tone(4), surfaceContainerLow = neutral.tone(10),
                surfaceContainer = neutral.tone(12), surfaceContainerHigh = neutral.tone(17),
                surfaceContainerHighest = neutral.tone(22),
                outline = neutralVariant.tone(60), outlineVariant = neutralVariant.tone(30),
                scrim = neutral.tone(0),
                inverseSurface = neutral.tone(90), inverseOnSurface = neutral.tone(20),
                inversePrimary = primary.tone(40),
            )
        } else {
            SchemeRoles(
                primary = primary.tone(40), onPrimary = primary.tone(100),
                primaryContainer = primary.tone(90), onPrimaryContainer = primary.tone(10),
                secondary = secondary.tone(40), onSecondary = secondary.tone(100),
                secondaryContainer = secondary.tone(90), onSecondaryContainer = secondary.tone(10),
                tertiary = tertiary.tone(40), onTertiary = tertiary.tone(100),
                tertiaryContainer = tertiary.tone(90), onTertiaryContainer = tertiary.tone(10),
                error = error.tone(40), onError = error.tone(100),
                errorContainer = error.tone(90), onErrorContainer = error.tone(10),
                background = neutral.tone(98), onBackground = neutral.tone(10),
                surface = neutral.tone(98), onSurface = neutral.tone(10),
                surfaceVariant = neutralVariant.tone(90), onSurfaceVariant = neutralVariant.tone(30),
                surfaceTint = primary.tone(40),
                surfaceDim = neutral.tone(87), surfaceBright = neutral.tone(98),
                surfaceContainerLowest = neutral.tone(100), surfaceContainerLow = neutral.tone(96),
                surfaceContainer = neutral.tone(94), surfaceContainerHigh = neutral.tone(92),
                surfaceContainerHighest = neutral.tone(90),
                outline = neutralVariant.tone(50), outlineVariant = neutralVariant.tone(80),
                scrim = neutral.tone(0),
                inverseSurface = neutral.tone(20), inverseOnSurface = neutral.tone(95),
                inversePrimary = primary.tone(80),
            )
        }
    }

    /**
     * Status colours and the touch-pad surface. They use fixed hues (green for good
     * news, amber for caution) so their meaning never depends on the chosen accent,
     * but share the scheme's neutral so they sit comfortably in it.
     */
    fun extended(seed: Int, dark: Boolean): ExtendedRoles {
        val accent = ColorMath.toOklch(seed)
        val success = Palette(SUCCESS_HUE, 0.14)
        val warning = Palette(WARNING_HUE, 0.14)
        val neutral = Palette(accent.h, 0.012)
        val neutralVariant = Palette(accent.h, 0.026)
        return if (dark) {
            ExtendedRoles(
                success = success.tone(80), onSuccess = success.tone(20),
                successContainer = success.tone(30), onSuccessContainer = success.tone(90),
                warning = warning.tone(80), onWarning = warning.tone(20),
                warningContainer = warning.tone(30), onWarningContainer = warning.tone(90),
                pad = neutral.tone(8), padOutline = neutralVariant.tone(46), padGrid = neutralVariant.tone(28),
            )
        } else {
            ExtendedRoles(
                success = success.tone(40), onSuccess = success.tone(100),
                successContainer = success.tone(90), onSuccessContainer = success.tone(10),
                warning = warning.tone(40), onWarning = warning.tone(100),
                warningContainer = warning.tone(90), onWarningContainer = warning.tone(10),
                pad = neutral.tone(93), padOutline = neutralVariant.tone(56), padGrid = neutralVariant.tone(80),
            )
        }
    }
}
