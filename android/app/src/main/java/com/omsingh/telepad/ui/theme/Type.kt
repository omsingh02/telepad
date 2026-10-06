package com.omsingh.telepad.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/**
 * Material's type scale with the weights tuned so that titles read as titles and
 * small labels stay legible on a phone held at arm's length. The system font is
 * used: it is what the person reading chose, and it has every script.
 */
val TelepadTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(lineHeight = 16.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

/** A monospaced face with fixed-width digits, for fingerprints and addresses. */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.5.sp,
)

/** The fingerprint shown large during verification. */
val FingerprintStyle = MonoStyle.copy(fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 1.sp)
