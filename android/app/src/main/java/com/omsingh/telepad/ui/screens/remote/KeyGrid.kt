package com.omsingh.telepad.ui.screens.remote

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.ModifierKey
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.ui.theme.Haptics

/** The letters of a US keyboard, a row at a time, with the usage code of each key. */
private val LetterRows = listOf(
    "qwertyuiop" to intArrayOf(
        HidKeyCodes.Q, HidKeyCodes.W, HidKeyCodes.E, HidKeyCodes.R, HidKeyCodes.T,
        HidKeyCodes.Y, HidKeyCodes.U, HidKeyCodes.I, HidKeyCodes.O, HidKeyCodes.P,
    ),
    "asdfghjkl" to intArrayOf(
        HidKeyCodes.A, HidKeyCodes.S, HidKeyCodes.D, HidKeyCodes.F, HidKeyCodes.G,
        HidKeyCodes.H, HidKeyCodes.J, HidKeyCodes.K, HidKeyCodes.L,
    ),
    "zxcvbnm" to intArrayOf(
        HidKeyCodes.Z, HidKeyCodes.X, HidKeyCodes.C, HidKeyCodes.V, HidKeyCodes.B,
        HidKeyCodes.N, HidKeyCodes.M,
    ),
)

private val DigitUsages = intArrayOf(
    HidKeyCodes.NUM_1, HidKeyCodes.NUM_2, HidKeyCodes.NUM_3, HidKeyCodes.NUM_4, HidKeyCodes.NUM_5,
    HidKeyCodes.NUM_6, HidKeyCodes.NUM_7, HidKeyCodes.NUM_8, HidKeyCodes.NUM_9, HidKeyCodes.NUM_0,
)
private const val DigitLabels = "1234567890"
private const val DigitShifted = "!@#$%^&*()"

/** Punctuation keys: what each shows normally and with Shift, and its usage code. */
private data class Symbol(val plain: String, val shifted: String, val usage: Int)

private val SymbolRow = listOf(
    Symbol("`", "~", HidKeyCodes.GRAVE), Symbol("-", "_", HidKeyCodes.MINUS), Symbol("=", "+", HidKeyCodes.EQUAL),
    Symbol("[", "{", HidKeyCodes.LEFT_BRACE), Symbol("]", "}", HidKeyCodes.RIGHT_BRACE),
    Symbol("\\", "|", HidKeyCodes.BACKSLASH), Symbol(";", ":", HidKeyCodes.SEMICOLON),
    Symbol("'", "\"", HidKeyCodes.APOSTROPHE), Symbol("/", "?", HidKeyCodes.SLASH),
)
private val CommaDot = listOf(Symbol(",", "<", HidKeyCodes.COMMA), Symbol(".", ">", HidKeyCodes.DOT))

/**
 * A full keyboard for the PC: letters, digits, punctuation, function keys, the editing keys
 * and arrows, with Ctrl, Alt, Shift and the Super (Windows, Command) key.
 *
 * It works like a real one. Every key goes down when touched and up when released, so
 * holding Backspace or an arrow repeats on the PC. A modifier can be held with one finger while
 * another taps keys (Super and 2); tapped once it applies to the next key, tapped twice it
 * stays on, and held on its own it is pressed by itself, which is what opens a launcher.
 *
 * Each key's touch area reaches the middle of the gap around it, so nothing between two keys is a
 * dead spot. With [compact] the keys are shorter, to fit a phone held sideways.
 */
@Composable
fun KeyGrid(
    host: HostProfile,
    keyboard: KeyboardSession,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // The session says when a modifier changes through this state. Reading it here, in the body,
    // is what makes the grid redraw: Compose ignores a parameter that the body never reads, so
    // passing the version in from outside never worked.
    val version by keyboard.version.collectAsState()
    // The state is then handed down to the keys as arguments that change. A key given only the
    // session would see the same arguments after every change, and would not be redrawn.
    val shifted = remember(version) { keyboard.modifiers().leftShift }
    val states = remember(version) { ModifierKey.values().associateWith { keyboard.visualState(it) } }

    val keyHeight = if (compact) 34.dp else 46.dp
    val functionHeight = if (compact) 30.dp else 38.dp

    Column(modifier.fillMaxWidth()) {
        KeyRow { for (n in 1..12) TypeKey("F$n", HidKeyCodes.F1 + n - 1, keyboard, haptics, height = functionHeight) }
        KeyRow {
            TypeKey("Esc", HidKeyCodes.ESC, keyboard, haptics, height = keyHeight)
            TypeKey("Tab", HidKeyCodes.TAB, keyboard, haptics, height = keyHeight)
            TypeKey("Ins", HidKeyCodes.INSERT, keyboard, haptics, height = keyHeight)
            TypeKey("Del", HidKeyCodes.DELETE, keyboard, haptics, height = keyHeight)
            TypeKey("Home", HidKeyCodes.HOME, keyboard, haptics, height = keyHeight)
            TypeKey("End", HidKeyCodes.END, keyboard, haptics, height = keyHeight)
            TypeKey("PgUp", HidKeyCodes.PAGE_UP, keyboard, haptics, height = keyHeight)
            TypeKey("PgDn", HidKeyCodes.PAGE_DOWN, keyboard, haptics, height = keyHeight)
            TypeKey("PrtSc", HidKeyCodes.PRINT_SCREEN, keyboard, haptics, height = keyHeight)
        }
        KeyRow {
            for (i in DigitLabels.indices) {
                val label = if (shifted) DigitShifted[i].toString() else DigitLabels[i].toString()
                TypeKey(label, DigitUsages[i], keyboard, haptics, height = keyHeight, description = DigitLabels[i].toString())
            }
            TypeKey(null, HidKeyCodes.BACKSPACE, keyboard, haptics, height = keyHeight, weight = 1.5f, icon = Icons.AutoMirrored.Rounded.Backspace, description = "Backspace")
        }
        for ((index, row) in LetterRows.withIndex()) {
            KeyRow {
                if (index == 2) ModKey(ModifierKey.SHIFT, host.shift.symbol, host.shift.spokenName, states.getValue(ModifierKey.SHIFT), keyboard, haptics, keyHeight, weight = 1.5f)
                for ((i, letter) in row.first.withIndex()) {
                    val label = if (shifted) letter.uppercaseChar().toString() else letter.toString()
                    TypeKey(label, row.second[i], keyboard, haptics, height = keyHeight, description = letter.toString())
                }
                if (index == 1) TypeKey("Enter", HidKeyCodes.ENTER, keyboard, haptics, height = keyHeight, weight = 1.7f)
                if (index == 2) for (symbol in CommaDot) SymbolKey(symbol, shifted, keyboard, haptics, keyHeight)
            }
        }
        KeyRow { for (symbol in SymbolRow) SymbolKey(symbol, shifted, keyboard, haptics, keyHeight) }
        KeyRow {
            ModKey(ModifierKey.CTRL, host.ctrl.symbol, host.ctrl.spokenName, states.getValue(ModifierKey.CTRL), keyboard, haptics, keyHeight, weight = 1.45f)
            ModKey(ModifierKey.META, host.meta.symbol, host.meta.spokenName, states.getValue(ModifierKey.META), keyboard, haptics, keyHeight, weight = 1.45f)
            ModKey(ModifierKey.ALT, host.alt.symbol, host.alt.spokenName, states.getValue(ModifierKey.ALT), keyboard, haptics, keyHeight, weight = 1.45f)
            TypeKey("Space", HidKeyCodes.SPACE, keyboard, haptics, height = keyHeight, weight = 3.0f)
            TypeKey(null, HidKeyCodes.LEFT, keyboard, haptics, height = keyHeight, icon = Icons.AutoMirrored.Rounded.ArrowBack, description = stringResource(R.string.key_left))
            TypeKey(null, HidKeyCodes.DOWN, keyboard, haptics, height = keyHeight, icon = Icons.Rounded.ArrowDownward, description = stringResource(R.string.key_down))
            TypeKey(null, HidKeyCodes.UP, keyboard, haptics, height = keyHeight, icon = Icons.Rounded.ArrowUpward, description = stringResource(R.string.key_up))
            TypeKey(null, HidKeyCodes.RIGHT, keyboard, haptics, height = keyHeight, icon = Icons.AutoMirrored.Rounded.ArrowForward, description = stringResource(R.string.key_right))
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), content = content)
}

/** A key that goes down when touched and up when released. */
@Composable
private fun RowScope.TypeKey(
    label: String?,
    usage: Int,
    keyboard: KeyboardSession,
    haptics: Haptics,
    height: Dp,
    weight: Float = 1f,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
) {
    KeyCell(
        label = label,
        icon = icon,
        description = description,
        height = height,
        weight = weight,
        onDown = { keyboard.press(usage) },
        onUp = { keyboard.release(usage) },
        haptics = haptics,
    )
}

@Composable
private fun RowScope.SymbolKey(symbol: Symbol, shifted: Boolean, keyboard: KeyboardSession, haptics: Haptics, height: Dp) {
    TypeKey(if (shifted) symbol.shifted else symbol.plain, symbol.usage, keyboard, haptics, height = height, description = symbol.plain)
}

/** A modifier key: held while touched, or latched by tapping (see [KeyboardSession]). */
@Composable
private fun RowScope.ModKey(
    key: ModifierKey,
    symbol: String,
    spokenName: String,
    state: ModifierState,
    keyboard: KeyboardSession,
    haptics: Haptics,
    height: Dp,
    weight: Float,
) {
    val stateText = when (state) {
        ModifierState.OFF -> stringResource(R.string.keys_modifier_off, spokenName)
        ModifierState.ARMED -> stringResource(R.string.keys_modifier_armed, spokenName)
        ModifierState.LOCKED -> stringResource(R.string.keys_modifier_locked, spokenName)
    }
    KeyCell(
        label = symbol,
        description = spokenName,
        height = height,
        weight = weight,
        state = state,
        isModifier = true,
        stateText = stateText,
        onDown = { keyboard.holdModifier(key, SystemClock.uptimeMillis()) },
        onUp = { keyboard.releaseModifier(key, SystemClock.uptimeMillis()) },
        haptics = haptics,
    )
}
