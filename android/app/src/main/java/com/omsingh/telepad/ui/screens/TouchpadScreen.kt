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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.omsingh.telepad.core.input.ConnectionState
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
import com.omsingh.telepad.viewmodel.SettingsViewModel

/**
 * Route composable: collects state and delegates to stateless [TouchpadScreen].
 */
@Composable
fun TouchpadRoute(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    introShown: Boolean,
    onIntroDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionState by mainViewModel.connectionState.collectAsState()
    val preferences by settingsViewModel.preferences.collectAsState()

    TouchpadScreen(
        connectionState = connectionState,
        preferences = preferences,
        introShown = introShown,
        onIntroDismissed = onIntroDismissed,
        onDisconnect = mainViewModel::disconnect,
        onInputEvent = mainViewModel::onInputEvent,
        modifier = modifier,
    )
}

/**
 * Main touchpad surface — clean, stateless, and responsive.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    connectionState: ConnectionState,
    preferences: UserPreferences,
    introShown: Boolean,
    onIntroDismissed: () -> Unit,
    onDisconnect: () -> Unit,
    onInputEvent: (InputEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val currentHaptic by rememberUpdatedState(preferences.hapticFeedback)

    val processor = remember {
        TouchpadProcessor(onEvent = { event ->
            when (event) {
                InputEvent.Click,
                InputEvent.DoubleClick,
                InputEvent.RightClick -> {
                    if (currentHaptic) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                else -> Unit
            }
            onInputEvent(event)
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
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (connectionState is ConnectionState.Connected) {
            StatusBar(
                state = connectionState,
                onDisconnect = onDisconnect,
                modifier = Modifier.padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = 4.dp)
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
                    .padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
            ) {
                Button(
                    onClick = {
                        if (currentHaptic) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onInputEvent(InputEvent.Click)
                    },
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
                    onClick = {
                        if (currentHaptic) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onInputEvent(InputEvent.RightClick)
                    },
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

        Text(
            text = "1 finger: drag to move • Tap: click • 2 fingers: scroll & right-click",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = 4.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
    }

    TouchpadIntroOverlay(
        visible = !introShown,
        onDismiss = onIntroDismissed,
    )
}
