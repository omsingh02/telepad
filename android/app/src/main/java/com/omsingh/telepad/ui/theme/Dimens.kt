package com.omsingh.telepad.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralised spacing and corner-radius tokens.
 *
 * Why a token object rather than scattered `8.dp` literals everywhere?
 * Two reasons:
 *  - Audits show a typical Compose app drifts to 4–6 different "padding 16"
 *    values that *look* identical to the eye but Android layout cares about.
 *  - When we ship a "compact density" toggle later, swapping one file gets
 *    us tighter spacing across the entire UI.
 */
object Dimens {

    // ── Padding / gaps ─────────────────────────────────────────────────
    val ScreenHorizontalPadding   = 16.dp
    val ScreenVerticalPadding     = 16.dp
    val ItemSpacing               = 12.dp
    val ItemSpacingLarge          = 16.dp
    val ItemSpacingSmall          = 8.dp

    // ── Cards ─────────────────────────────────────────────────────────
    val CardCornerRadius          = 16.dp
    val CardCornerRadiusLarge     = 24.dp
    val CardInternalPadding       = 16.dp
    val CardInternalPaddingLarge  = 20.dp

    // ── Buttons / chips ───────────────────────────────────────────────
    val ButtonCornerRadius        = 12.dp
    val ButtonMinHeight           = 48.dp
    val ChipCornerRadius          = 8.dp

    // ── Bottom-nav-style status bar at the top of every non-home screen.
    val StatusBarHeight           = 36.dp

    // ── Touchpad specific ─────────────────────────────────────────────
    val TouchpadCornerRadius      = 28.dp
    val TouchpadBorderWidth       = 2.dp

    // ── Onboarding ────────────────────────────────────────────────────
    val OnboardingIllustrationSize = 160.dp
}
