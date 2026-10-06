package com.omsingh.telepad.ui.screens.remote

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.components.TouchSurface
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.Spacing

/** The pad, and under it the mouse buttons. */
@Composable
fun TouchpadTab(
    state: RemoteUiState,
    actions: RemoteActions,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val prefs = state.preferences
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TouchSurface(
            onEvent = actions::send,
            config = prefs.toGestureConfig(),
            haptics = haptics,
            scrollStrip = prefs.showScrollStrip,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        if (prefs.showTouchpadButtons) {
            ClickButtons(onEvent = actions::send, haptics = haptics)
        }
    }
}

/**
 * Left, middle and right mouse buttons. They behave like buttons on a mouse: down when
 * pressed, up when released. Hold left with one thumb and drag with another finger to
 * select text or move things.
 */
@Composable
fun ClickButtons(
    onEvent: (InputEvent) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        MouseButton(
            label = stringResource(R.string.pad_button_left),
            description = stringResource(R.string.pad_action_left_click),
            button = InputEvent.Button.LEFT,
            onEvent = onEvent,
            haptics = haptics,
            modifier = Modifier.weight(1f),
        )
        MouseButton(
            label = null,
            description = stringResource(R.string.pad_button_middle),
            button = InputEvent.Button.MIDDLE,
            onEvent = onEvent,
            haptics = haptics,
            modifier = Modifier.weight(0.4f),
        )
        MouseButton(
            label = stringResource(R.string.pad_button_right),
            description = stringResource(R.string.pad_action_right_click),
            button = InputEvent.Button.RIGHT,
            onEvent = onEvent,
            haptics = haptics,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MouseButton(
    label: String?,
    description: String,
    button: InputEvent.Button,
    onEvent: (InputEvent) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(onEvent)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(button) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.click()
                        send(InputEvent.MouseButton(button, true))
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            send(InputEvent.MouseButton(button, false))
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    send(InputEvent.MouseButton(button, true))
                    send(InputEvent.MouseButton(button, false))
                    true
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (pressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.sm),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.titleSmall)
            } else {
                Icon(Icons.Rounded.Mouse, contentDescription = null)
            }
        }
    }
}
