package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.components.ChoiceRow
import com.omsingh.telepad.ui.components.SettingsGroup
import com.omsingh.telepad.ui.components.SliderRow
import com.omsingh.telepad.ui.components.SwitchRow
import com.omsingh.telepad.ui.components.TouchSurface
import com.omsingh.telepad.ui.screens.remote.toGestureConfig
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.rememberHaptics

/**
 * Everything about how the pad feels. At the top is a pad you can try the settings on right
 * away, with a pointer that moves as the real one would: changing a slider is no use if you
 * have to connect to a PC to find out what it did.
 */
@Composable
fun TouchpadSettingsScreen(
    preferences: UserPreferences,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics(preferences.hapticFeedback)
    SettingsScaffold(title = stringResource(R.string.settings_touchpad), onBack = onBack, modifier = modifier) {
        Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.touchpad_try_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.touchpad_try_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TestPad(
                preferences = preferences,
                haptics = haptics,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }

        SettingsGroup(stringResource(R.string.touchpad_section_motion)) {
            SliderRow(
                title = stringResource(R.string.touchpad_sensitivity),
                value = preferences.sensitivity,
                valueText = "%.1f×".format(preferences.sensitivity),
                onValueChange = { v -> actions.update { it.copy(sensitivity = v) } },
                valueRange = 0.5f..4f,
            )
            ChoiceRow(
                title = stringResource(R.string.touchpad_acceleration),
                options = AccelerationCurve.values().toList(),
                selected = preferences.accelerationCurve,
                onSelect = { c -> actions.update { it.copy(accelerationCurve = c) } },
                label = { curve ->
                    stringResource(
                        when (curve) {
                            AccelerationCurve.LINEAR -> R.string.touchpad_acceleration_linear
                            AccelerationCurve.MACOS -> R.string.touchpad_acceleration_macos
                            AccelerationCurve.WINDOWS -> R.string.touchpad_acceleration_windows
                            AccelerationCurve.FLAT -> R.string.touchpad_acceleration_flat
                        },
                    )
                },
            )
        }

        SettingsGroup(stringResource(R.string.touchpad_section_scroll)) {
            SliderRow(
                title = stringResource(R.string.touchpad_scroll_speed),
                value = preferences.scrollSpeed,
                valueText = "%.1f×".format(preferences.scrollSpeed),
                onValueChange = { v -> actions.update { it.copy(scrollSpeed = v) } },
                valueRange = 1f..5f,
            )
            SwitchRow(
                stringResource(R.string.touchpad_natural), preferences.naturalScrolling,
                { v -> actions.update { it.copy(naturalScrolling = v) } },
                subtitle = stringResource(R.string.touchpad_natural_subtitle),
            )
            SwitchRow(
                stringResource(R.string.touchpad_momentum), preferences.momentumScrolling,
                { v -> actions.update { it.copy(momentumScrolling = v) } },
                subtitle = stringResource(R.string.touchpad_momentum_subtitle),
            )
            SwitchRow(
                stringResource(R.string.touchpad_strip), preferences.showScrollStrip,
                { v -> actions.update { it.copy(showScrollStrip = v) } },
                subtitle = stringResource(R.string.touchpad_strip_subtitle),
            )
        }

        SettingsGroup(stringResource(R.string.touchpad_section_gestures)) {
            SwitchRow(stringResource(R.string.touchpad_tap), preferences.tapToClick, { v -> actions.update { it.copy(tapToClick = v) } })
            SwitchRow(
                stringResource(R.string.touchpad_tap_drag), preferences.doubleTapDrag,
                { v -> actions.update { it.copy(doubleTapDrag = v) } },
                subtitle = stringResource(R.string.touchpad_tap_drag_subtitle),
            )
            SwitchRow(stringResource(R.string.touchpad_two_finger), preferences.twoFingerRightClick, { v -> actions.update { it.copy(twoFingerRightClick = v) } })
            SwitchRow(stringResource(R.string.touchpad_three_finger), preferences.threeFingerMiddleClick, { v -> actions.update { it.copy(threeFingerMiddleClick = v) } })
            SwitchRow(stringResource(R.string.touchpad_long_press), preferences.longPressRightClick, { v -> actions.update { it.copy(longPressRightClick = v) } })
        }

        SettingsGroup(stringResource(R.string.touchpad_section_buttons)) {
            SwitchRow(
                stringResource(R.string.touchpad_buttons), preferences.showTouchpadButtons,
                { v -> actions.update { it.copy(showTouchpadButtons = v) } },
                subtitle = stringResource(R.string.touchpad_buttons_subtitle),
            )
            SwitchRow(
                stringResource(R.string.touchpad_haptics), preferences.hapticFeedback,
                { v -> actions.update { it.copy(hapticFeedback = v) } },
                subtitle = stringResource(R.string.touchpad_haptics_subtitle),
            )
        }
    }
}

/** The real pad, wired to a pointer drawn on top of it instead of to a PC. */
@Composable
private fun TestPad(preferences: UserPreferences, haptics: com.omsingh.telepad.ui.theme.Haptics, modifier: Modifier = Modifier) {
    // Where the pretend pointer is, as a fraction of the pad.
    var pointer by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val color = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.surface

    Box(modifier) {
        TouchSurface(
            onEvent = { event ->
                if (event is InputEvent.MouseMove) {
                    // A swipe across the pad should carry the pointer about as far as it would
                    // across a typical screen, so 1500 units is a full width.
                    pointer = Offset(
                        (pointer.x + event.dx / PREVIEW_SCREEN_UNITS).coerceIn(0f, 1f),
                        (pointer.y + event.dy / PREVIEW_SCREEN_UNITS * 1.6f).coerceIn(0f, 1f),
                    )
                }
            },
            config = preferences.toGestureConfig(),
            haptics = haptics,
            scrollStrip = preferences.showScrollStrip,
            hints = false,
            modifier = Modifier.fillMaxSize(),
        )
        // Drawn above the pad but not touchable, so touches still reach the pad beneath.
        Canvas(Modifier.matchParentSize()) {
            val w = size.width - if (preferences.showScrollStrip) 44.dp.toPx() else 0f
            val tip = Offset(pointer.x * w, pointer.y * size.height)
            val s = 14.dp.toPx()
            val arrow = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x, tip.y + s * 1.5f)
                lineTo(tip.x + s * 0.42f, tip.y + s * 1.15f)
                lineTo(tip.x + s * 0.72f, tip.y + s * 1.7f)
                lineTo(tip.x + s * 0.95f, tip.y + s * 1.58f)
                lineTo(tip.x + s * 0.66f, tip.y + s * 1.05f)
                lineTo(tip.x + s * 1.1f, tip.y + s * 1.05f)
                close()
            }
            drawPath(arrow, outline)
            drawPath(arrow, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private const val PREVIEW_SCREEN_UNITS = 1500f
