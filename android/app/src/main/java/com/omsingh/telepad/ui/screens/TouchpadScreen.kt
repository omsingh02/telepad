package com.omsingh.telepad.ui.screens

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.SensitivityCurve
import com.omsingh.telepad.core.input.TouchpadProcessor
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.components.StatusBar
import com.omsingh.telepad.ui.components.TouchpadIntroOverlay
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.TrackpadBorderDark
import com.omsingh.telepad.ui.theme.TrackpadSurfaceDark
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Main touchpad surface.
 *
 * Architecture:
 *  - Raw [MotionEvent]s flow through `pointerInteropFilter` (lowest-latency
 *    raw-event API in Compose) directly into the [TouchpadProcessor].
 *  - [TouchpadProcessor] synchronously emits [InputEvent]s to the ViewModel.
 *  - No batching, no main-thread Handler. Touch hardware rate caps dispatch.
 *
 * Visual feedback: border tints to accent color while a finger is down,
 * giving the user immediate confirmation that input is being received.
 *
 * First-launch coach mark is rendered on top via [TouchpadIntroOverlay].
 * It dismisses on tap and persists in [UserPreferences] via a hoist to the
 * caller (we don't persist directly here — separation of concerns).
 *
 * Optional left/right click buttons under the pad — hidden by default,
 * shown via the `showTouchpadButtons` preference for accessibility users.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    viewModel: MainViewModel,
    preferences: UserPreferences,
    introShown: Boolean,
    onIntroDismissed: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val connectionState by viewModel.connectionState.collectAsState()
    var isPressed by remember { mutableStateOf(false) }

    val processor = remember {
        TouchpadProcessor(onEvent = { event ->
            when (event) {
                InputEvent.Click,
                InputEvent.DoubleClick,
                InputEvent.RightClick -> {
                    if (preferences.hapticFeedback) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                else -> Unit
            }
            viewModel.onInputEvent(event)
        })
    }

    // Sync preference changes into the processor exactly once per change.
    LaunchedEffect(preferences) {
        processor.tapToClick           = preferences.tapToClick
        processor.naturalScrolling     = preferences.naturalScrolling
        processor.scrollSpeed          = preferences.scrollSpeed
        processor.doubleTapDrag        = preferences.doubleTapDrag
        processor.twoFingerRightClick  = preferences.twoFingerRightClick
        processor.longPressRightClick  = preferences.longPressRightClick
        processor.sensitivityCurve.baseSensitivity = preferences.sensitivity
        processor.sensitivityCurve.curve = when (preferences.accelerationCurve) {
            AccelerationCurve.LINEAR  -> SensitivityCurve.AccelCurve.LINEAR
            AccelerationCurve.MACOS   -> SensitivityCurve.AccelCurve.MACOS
            AccelerationCurve.WINDOWS -> SensitivityCurve.AccelCurve.WINDOWS
            AccelerationCurve.FLAT    -> SensitivityCurve.AccelCurve.FLAT
        }
    }

    val borderColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.primary else TrackpadBorderDark,
        label = "trackpadBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        StatusBar(state = connectionState, onDisconnect = { viewModel.disconnect() })

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(Dimens.ScreenHorizontalPadding)
                .background(TrackpadSurfaceDark, RoundedCornerShape(Dimens.TouchpadCornerRadius))
                .border(Dimens.TouchpadBorderWidth, borderColor, RoundedCornerShape(Dimens.TouchpadCornerRadius))
                .semantics {
                    contentDescription = "Touchpad surface. Drag to move pointer, tap to click."
                    role = Role.Button
                }
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> isPressed = true
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> isPressed = false
                    }
                    processor.onTouchEvent(event)
                    true
                }
        )

        if (preferences.showTouchpadButtons) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.ScreenHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
            ) {
                Button(
                    onClick = { viewModel.onInputEvent(InputEvent.Click) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                ) { Text("Left click") }
                Button(
                    onClick = { viewModel.onInputEvent(InputEvent.RightClick) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                ) { Text("Right click") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "1 finger: drag to move • Tap: click • 2 fingers: scroll & right-click",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenHorizontalPadding),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.ScreenVerticalPadding))
    }

    TouchpadIntroOverlay(
        visible = !introShown,
        onDismiss = onIntroDismissed,
    )
}
