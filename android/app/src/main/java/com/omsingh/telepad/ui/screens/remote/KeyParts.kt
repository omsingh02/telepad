package com.omsingh.telepad.ui.screens.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.ui.theme.Haptics

private val KeyShape = RoundedCornerShape(10.dp)

/**
 * The space left around the visible key on each side. The key's touch area is the whole cell,
 * this space included, so a finger that lands between two keys still presses one of them
 * instead of nothing, which is what makes a small keyboard feel unreliable.
 */
private val KeyInset = 2.dp

/**
 * One key of the on-screen keyboard.
 *
 * Held keys ([holdable], the default) go down when touched and up when released, like a real key,
 * so holding an arrow repeats on the PC. A key in a row that scrolls is not held: it fires when the
 * finger lifts, and a touch that turns into a scroll presses nothing.
 *
 * A modifier shows whether it is on: tinted with an outline for the next key (or while a finger
 * holds it), filled with a lock for locked. A toggle key (Fn) shows when it is switched on.
 */
@Composable
internal fun RowScope.KeyCell(
    label: String?,
    description: String,
    height: Dp,
    onDown: () -> Unit,
    onUp: () -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    weight: Float = 1f,
    width: Dp? = null,
    icon: ImageVector? = null,
    state: ModifierState = ModifierState.OFF,
    isModifier: Boolean = false,
    isToggle: Boolean = false,
    toggledOn: Boolean = false,
    stateText: String? = null,
    holdable: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val colors = MaterialTheme.colorScheme

    val container = when {
        state == ModifierState.LOCKED -> colors.primary
        state == ModifierState.ARMED -> colors.primaryContainer
        pressed -> colors.primaryContainer
        toggledOn -> colors.secondaryContainer
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        state == ModifierState.LOCKED -> colors.onPrimary
        state == ModifierState.ARMED -> colors.onPrimaryContainer
        pressed -> colors.onPrimaryContainer
        toggledOn -> colors.onSecondaryContainer
        else -> colors.onSurface
    }

    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.weight(weight))
            .height(height)
            .pointerInput(holdable) {
                if (holdable) {
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
                } else {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = {
                            haptics.key()
                            down()
                            up()
                        },
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                role = if (isModifier || isToggle) Role.Switch else Role.Button
                contentDescription = description
                if (stateText != null) stateDescription = stateText
                if (isModifier) selected = state != ModifierState.OFF
                if (isToggle) selected = toggledOn
                // A screen reader's activation is a whole tap.
                onClick { down(); up(); true }
            },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(KeyInset),
            shape = KeyShape,
            color = container,
            contentColor = content,
            border = if (state == ModifierState.OFF) null else BorderStroke(2.dp, colors.primary),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                } else {
                    // Long legends (Home, PgUp, Super) get a smaller size, so that none is cut off on a narrow phone.
                    val text = label.orEmpty()
                    val size = when {
                        text.length <= 2 -> 15.sp
                        text.length == 3 -> 13.sp
                        else -> 11.5.sp
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = size),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (state == ModifierState.LOCKED) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(10.dp))
                }
            }
        }
    }
}
