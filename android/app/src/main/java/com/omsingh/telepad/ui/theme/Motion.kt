package com.omsingh.telepad.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * How things move. Springs for things that have a place (so they settle rather than
 * stop), short tweens for things that merely change. When the person has switched
 * animations off in the system settings, nothing animates.
 */
object Motion {
    const val FAST = 150
    const val MEDIUM = 250
    const val SLOW = 400

    /** Standard "emphasized" easing from the Material motion spec. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For movement and size. */
    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 380f)

    /** For colour and opacity. */
    fun <T> effects(reduced: Boolean, durationMs: Int = FAST): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs)
}

/** True when the person has turned system animations off (or asked for none). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}
