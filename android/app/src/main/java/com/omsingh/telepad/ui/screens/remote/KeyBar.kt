package com.omsingh.telepad.ui.screens.remote

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.host.ModifierLabel
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.ModifierKey
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.ui.theme.Haptics

private val BarKeyHeight = 52.dp
private val FunctionKeyWidth = 54.dp

/**
 * The keys a phone's keyboard lacks, docked above it: Esc and Tab, the modifiers, the arrows and
 * the editing keys, with the function keys one tap away on Fn.
 *
 * Text comes from the phone's own keyboard, so swipe typing, autocorrect and every language keep
 * working. The modifiers meet it halfway: tap Ctrl and then "c" on the phone's keyboard, and the
 * PC receives Ctrl+C ([com.omsingh.telepad.core.input.TypingBridge]).
 *
 * A modifier works like a real one. Tap it for the next key, tap it twice to lock it, or hold it
 * with one finger while another taps a key. Held on its own for a moment it is pressed by itself,
 * which is what opens a launcher on Super.
 *
 * [beforeKey] runs before any touch on the bar, so text typed so far reaches the PC ahead of the key.
 */
@Composable
fun KeyBar(
    host: HostProfile,
    keyboard: KeyboardSession,
    haptics: Haptics,
    beforeKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The session says when a modifier changes through this state. Reading it here, in the body, is
    // what makes the bar redraw: Compose ignores a parameter that the body never reads.
    val version by keyboard.version.collectAsState()
    val states = remember(version) { ModifierKey.values().associateWith { keyboard.visualState(it) } }
    var functionKeys by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = functionKeys,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (n in 1..12) {
                    ScrollKey("F$n", HidKeyCodes.F1 + n - 1, keyboard, haptics, beforeKey)
                }
                ScrollKey(stringResource(R.string.key_page_up), HidKeyCodes.PAGE_UP, keyboard, haptics, beforeKey)
                ScrollKey(stringResource(R.string.key_page_down), HidKeyCodes.PAGE_DOWN, keyboard, haptics, beforeKey)
                ScrollKey(stringResource(R.string.key_insert), HidKeyCodes.INSERT, keyboard, haptics, beforeKey)
                ScrollKey(stringResource(R.string.key_print_screen), HidKeyCodes.PRINT_SCREEN, keyboard, haptics, beforeKey)
            }
        }

        Row(Modifier.fillMaxWidth()) {
            BarKey(stringResource(R.string.key_esc), HidKeyCodes.ESC, keyboard, haptics, beforeKey)
            BarKey(stringResource(R.string.key_tab), HidKeyCodes.TAB, keyboard, haptics, beforeKey)
            BarModifier(ModifierKey.CTRL, host.ctrl, states.getValue(ModifierKey.CTRL), keyboard, haptics, beforeKey)
            BarModifier(ModifierKey.ALT, host.alt, states.getValue(ModifierKey.ALT), keyboard, haptics, beforeKey)
            BarModifier(ModifierKey.SHIFT, host.shift, states.getValue(ModifierKey.SHIFT), keyboard, haptics, beforeKey)
            BarModifier(ModifierKey.META, host.meta, states.getValue(ModifierKey.META), keyboard, haptics, beforeKey)
            KeyCell(
                label = stringResource(R.string.key_fn),
                description = stringResource(R.string.keys_function_keys),
                height = BarKeyHeight,
                isToggle = true,
                toggledOn = functionKeys,
                stateText = stringResource(if (functionKeys) R.string.keys_function_keys_shown else R.string.keys_function_keys_hidden),
                holdable = false,
                onDown = { functionKeys = !functionKeys },
                onUp = {},
                haptics = haptics,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            BarKey(null, HidKeyCodes.LEFT, keyboard, haptics, beforeKey, Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.key_left))
            BarKey(null, HidKeyCodes.UP, keyboard, haptics, beforeKey, Icons.Rounded.ArrowUpward, stringResource(R.string.key_up))
            BarKey(null, HidKeyCodes.DOWN, keyboard, haptics, beforeKey, Icons.Rounded.ArrowDownward, stringResource(R.string.key_down))
            BarKey(null, HidKeyCodes.RIGHT, keyboard, haptics, beforeKey, Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.key_right))
            BarKey(stringResource(R.string.key_home), HidKeyCodes.HOME, keyboard, haptics, beforeKey)
            BarKey(stringResource(R.string.key_end), HidKeyCodes.END, keyboard, haptics, beforeKey)
            BarKey(stringResource(R.string.key_delete), HidKeyCodes.DELETE, keyboard, haptics, beforeKey)
        }
    }
}

/** A key that goes down when touched and up when released, so holding an arrow repeats on the PC. */
@Composable
private fun RowScope.BarKey(
    label: String?,
    usage: Int,
    keyboard: KeyboardSession,
    haptics: Haptics,
    beforeKey: () -> Unit,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
) {
    KeyCell(
        label = label,
        icon = icon,
        description = description,
        height = BarKeyHeight,
        onDown = {
            beforeKey()
            keyboard.press(usage)
        },
        onUp = { keyboard.release(usage) },
        haptics = haptics,
    )
}

/** A key in the row that scrolls: pressed when the finger lifts, so scrolling the row presses nothing. */
@Composable
private fun RowScope.ScrollKey(
    label: String,
    usage: Int,
    keyboard: KeyboardSession,
    haptics: Haptics,
    beforeKey: () -> Unit,
    width: Dp = FunctionKeyWidth,
) {
    KeyCell(
        label = label,
        description = label,
        height = BarKeyHeight,
        width = width,
        holdable = false,
        onDown = {
            beforeKey()
            keyboard.press(usage)
        },
        onUp = { keyboard.release(usage) },
        haptics = haptics,
    )
}

/** A modifier key: held while touched, or latched by tapping (see [KeyboardSession]). */
@Composable
private fun RowScope.BarModifier(
    key: ModifierKey,
    label: ModifierLabel,
    state: ModifierState,
    keyboard: KeyboardSession,
    haptics: Haptics,
    beforeKey: () -> Unit,
) {
    val stateText = when (state) {
        ModifierState.OFF -> stringResource(R.string.keys_modifier_off, label.spokenName)
        ModifierState.ARMED -> stringResource(R.string.keys_modifier_armed, label.spokenName)
        ModifierState.LOCKED -> stringResource(R.string.keys_modifier_locked, label.spokenName)
    }
    KeyCell(
        label = label.symbol,
        description = label.spokenName,
        height = BarKeyHeight,
        state = state,
        isModifier = true,
        stateText = stateText,
        onDown = {
            // Text typed so far must not be caught up in the chord this modifier starts.
            beforeKey()
            keyboard.holdModifier(key, SystemClock.uptimeMillis())
        },
        onUp = { keyboard.releaseModifier(key, SystemClock.uptimeMillis()) },
        haptics = haptics,
    )
}
