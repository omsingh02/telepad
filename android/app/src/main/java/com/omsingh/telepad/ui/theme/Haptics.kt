package com.omsingh.telepad.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/**
 * Physical feedback for a touch interface: a click should be felt. Each kind maps to the
 * platform constant that fits it, and everything is silent when the person turned haptics
 * off in the app (the system's own touch-feedback setting is respected as well).
 */
@Stable
class Haptics(private val view: View, private val enabled: () -> Boolean) {

    /** A light tick: a scroll notch, a key. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A key being pressed. */
    fun key() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A click on the pad or a button. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Something that took effort: a long press, a drag beginning. */
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun confirm() = perform(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)

    fun reject() = perform(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        if (enabled()) view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val view = LocalView.current
    val current = rememberUpdatedState(enabled)
    return remember(view) { Haptics(view) { current.value } }
}
