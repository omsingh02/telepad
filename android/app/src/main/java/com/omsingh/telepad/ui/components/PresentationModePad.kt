package com.omsingh.telepad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.theme.Dimens

/**
 * Presentation mode: turns the screen into two giant slide tap zones.
 *
 * The left two-thirds advances the slide (Right Arrow). The right one-third
 * goes back (Left Arrow). A long-press anywhere blanks the slide (B key —
 * the universal "blank screen" key in PowerPoint / Keynote / Google Slides).
 *
 * Why the asymmetric split? Most use cases advance forward 90% of the time;
 * the larger zone catches stray taps that would otherwise rewind the slide
 * mid-thought. The visual mark in the centre tells the user which side does
 * what.
 *
 * Sends standard arrow-key HID events, so it works on every slideshow app
 * that responds to keyboard navigation — which is all of them.
 */
@Composable
fun PresentationModePad(
    onInputEvent: (InputEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    fun pressKey(code: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onInputEvent(InputEvent.KeyPress(code))
        onInputEvent(InputEvent.KeyRelease(code))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Dimens.CardCornerRadius)),
    ) {
        // Forward zone (left ~67% of width).
        TapZone(
            label = "Advance",
            icon = Icons.Filled.ChevronRight,
            color = MaterialTheme.colorScheme.primary,
            onTap = { pressKey(HidKeyCodes.RIGHT) },
            onLongPress = { pressKey(HidKeyCodes.B) },
            modifier = Modifier.weight(2f),
        )
        // Back zone (right ~33%).
        TapZone(
            label = "Back",
            icon = Icons.Filled.ChevronLeft,
            color = MaterialTheme.colorScheme.secondary,
            onTap = { pressKey(HidKeyCodes.LEFT) },
            onLongPress = { pressKey(HidKeyCodes.B) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TapZone(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(Dimens.CardCornerRadius))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                text = "Long-press to blank",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f),
            )
        }
    }
}

