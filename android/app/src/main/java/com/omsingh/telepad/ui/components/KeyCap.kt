package com.omsingh.telepad.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.Spacing

private val KeyShape = RoundedCornerShape(12.dp)

/**
 * A key on the on-screen keyboard. It goes down when touched and comes up when released,
 * like a real key, so holding an arrow or Backspace repeats on the PC; if the touch is
 * taken away mid-press the key still comes up.
 */
@Composable
fun KeyCap(
    label: String?,
    onDown: () -> Unit,
    onUp: () -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)

    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp)
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.key()
                        down()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            up()
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                // A screen reader's activation is a whole tap.
                onClick { down(); up(); true }
            },
        shape = KeyShape,
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (pressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (pressed) 0.dp else 1.dp,
    ) {
        Box(Modifier.padding(horizontal = Spacing.md), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Text(label.orEmpty(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A latching modifier key (Ctrl, Alt, Shift, Windows or Command): tap once for the next key,
 * twice to hold it on, a third time to release. The three states look different, and are
 * announced by name, so nobody is left guessing whether Shift is on.
 */
@Composable
fun ModifierKeyCap(
    symbol: String,
    spokenName: String,
    state: ModifierState,
    stateDescription: String,
    onTap: () -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val container = when (state) {
        ModifierState.OFF -> MaterialTheme.colorScheme.surfaceContainerHigh
        ModifierState.ARMED -> MaterialTheme.colorScheme.secondaryContainer
        ModifierState.LOCKED -> MaterialTheme.colorScheme.primary
    }
    val content = when (state) {
        ModifierState.OFF -> MaterialTheme.colorScheme.onSurface
        ModifierState.ARMED -> MaterialTheme.colorScheme.onSecondaryContainer
        ModifierState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    Surface(
        onClick = {
            haptics.key()
            onTap()
        },
        modifier = modifier
            .defaultMinSize(minWidth = 52.dp)
            .height(48.dp)
            .semantics {
                role = Role.Switch
                contentDescription = spokenName
                this.stateDescription = stateDescription
                selected = state != ModifierState.OFF
            },
        shape = KeyShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(symbol, style = MaterialTheme.typography.labelLarge)
            if (state == ModifierState.LOCKED) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}
