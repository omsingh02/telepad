package com.omsingh.telepad.ui.screens.remote

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
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
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.ModifierKey
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.ui.theme.Haptics

private val KeyHeight = 44.dp
private val KeyGap = 4.dp
private val KeyShape = RoundedCornerShape(8.dp)

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
 */
@Composable
fun KeyGrid(
    host: HostProfile,
    keyboard: KeyboardSession,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    // The session says when a modifier changes through this state. Reading it here, in the body,
    // is what makes the grid redraw: Compose ignores a parameter that the body never reads, so
    // passing the version in from outside never worked.
    val version by keyboard.version.collectAsState()
    // The state is then handed down to the keys as arguments that change. A key given only the
    // session would see the same arguments after every change, and would not be redrawn.
    val mods = remember(version) { keyboard.modifiers() }
    val shifted = mods.leftShift
    val states = remember(version) { ModifierKey.values().associateWith { keyboard.visualState(it) } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        ChordLine(host, mods)

        KeyRow { for (n in 1..12) TypeKey("F$n", HidKeyCodes.F1 + n - 1, keyboard, haptics, height = 36.dp) }
        KeyRow {
            TypeKey("Esc", HidKeyCodes.ESC, keyboard, haptics)
            TypeKey("Tab", HidKeyCodes.TAB, keyboard, haptics)
            TypeKey("Ins", HidKeyCodes.INSERT, keyboard, haptics)
            TypeKey("Del", HidKeyCodes.DELETE, keyboard, haptics)
            TypeKey("Home", HidKeyCodes.HOME, keyboard, haptics)
            TypeKey("End", HidKeyCodes.END, keyboard, haptics)
            TypeKey("PgUp", HidKeyCodes.PAGE_UP, keyboard, haptics)
            TypeKey("PgDn", HidKeyCodes.PAGE_DOWN, keyboard, haptics)
            TypeKey("PrtSc", HidKeyCodes.PRINT_SCREEN, keyboard, haptics)
        }
        KeyRow {
            for (i in DigitLabels.indices) {
                val label = if (shifted) DigitShifted[i].toString() else DigitLabels[i].toString()
                TypeKey(label, DigitUsages[i], keyboard, haptics, description = DigitLabels[i].toString())
            }
            TypeKey(null, HidKeyCodes.BACKSPACE, keyboard, haptics, weight = 1.5f, icon = Icons.AutoMirrored.Rounded.Backspace, description = "Backspace")
        }
        for ((index, row) in LetterRows.withIndex()) {
            KeyRow {
                if (index == 2) ModKey(ModifierKey.SHIFT, host.shift.symbol, host.shift.spokenName, states.getValue(ModifierKey.SHIFT), keyboard, haptics, weight = 1.5f)
                for ((i, letter) in row.first.withIndex()) {
                    val label = if (shifted) letter.uppercaseChar().toString() else letter.toString()
                    TypeKey(label, row.second[i], keyboard, haptics, description = letter.toString())
                }
                if (index == 1) TypeKey("Enter", HidKeyCodes.ENTER, keyboard, haptics, weight = 1.7f)
                if (index == 2) for (symbol in CommaDot) SymbolKey(symbol, shifted, keyboard, haptics)
            }
        }
        KeyRow { for (symbol in SymbolRow) SymbolKey(symbol, shifted, keyboard, haptics) }
        KeyRow {
            ModKey(ModifierKey.CTRL, host.ctrl.symbol, host.ctrl.spokenName, states.getValue(ModifierKey.CTRL), keyboard, haptics, weight = 1.45f)
            ModKey(ModifierKey.META, host.meta.symbol, host.meta.spokenName, states.getValue(ModifierKey.META), keyboard, haptics, weight = 1.45f)
            ModKey(ModifierKey.ALT, host.alt.symbol, host.alt.spokenName, states.getValue(ModifierKey.ALT), keyboard, haptics, weight = 1.45f)
            TypeKey("Space", HidKeyCodes.SPACE, keyboard, haptics, weight = 3.0f)
            TypeKey(null, HidKeyCodes.LEFT, keyboard, haptics, icon = Icons.AutoMirrored.Rounded.ArrowBack, description = stringResource(R.string.key_left))
            TypeKey(null, HidKeyCodes.DOWN, keyboard, haptics, icon = Icons.Rounded.ArrowDownward, description = stringResource(R.string.key_down))
            TypeKey(null, HidKeyCodes.UP, keyboard, haptics, icon = Icons.Rounded.ArrowUpward, description = stringResource(R.string.key_up))
            TypeKey(null, HidKeyCodes.RIGHT, keyboard, haptics, icon = Icons.AutoMirrored.Rounded.ArrowForward, description = stringResource(R.string.key_right))
        }
    }
}

/** Says what is held, as a chord ("Super + Shift + …"), or how the modifiers work. */
@Composable
private fun ChordLine(host: HostProfile, mods: InputEvent.Modifiers) {
    val names = buildList {
        if (mods.leftCtrl) add(host.ctrl.symbol)
        if (mods.leftAlt) add(host.alt.symbol)
        if (mods.leftShift) add(host.shift.symbol)
        if (mods.leftMeta) add(host.meta.symbol)
    }
    Text(
        text = if (names.isEmpty()) stringResource(R.string.keys_chord_hint) else stringResource(R.string.keys_chord_active, names.joinToString(" + ")),
        style = MaterialTheme.typography.bodySmall,
        color = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyGap), content = content)
}

/** A key that goes down when touched and up when released. */
@Composable
private fun RowScope.TypeKey(
    label: String?,
    usage: Int,
    keyboard: KeyboardSession,
    haptics: Haptics,
    weight: Float = 1f,
    height: Dp = KeyHeight,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
) {
    GridKey(
        label = label,
        icon = icon,
        description = description,
        weight = weight,
        height = height,
        state = ModifierState.OFF,
        isModifier = false,
        onDown = { keyboard.press(usage) },
        onUp = { keyboard.release(usage) },
        haptics = haptics,
    )
}

@Composable
private fun RowScope.SymbolKey(symbol: Symbol, shifted: Boolean, keyboard: KeyboardSession, haptics: Haptics) {
    TypeKey(if (shifted) symbol.shifted else symbol.plain, symbol.usage, keyboard, haptics, description = symbol.plain)
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
    weight: Float,
) {
    val stateText = when (state) {
        ModifierState.OFF -> stringResource(R.string.keys_modifier_off, spokenName)
        ModifierState.ARMED -> stringResource(R.string.keys_modifier_armed, spokenName)
        ModifierState.LOCKED -> stringResource(R.string.keys_modifier_locked, spokenName)
    }
    GridKey(
        label = symbol,
        icon = null,
        description = spokenName,
        weight = weight,
        height = KeyHeight,
        state = state,
        isModifier = true,
        stateText = stateText,
        onDown = { keyboard.holdModifier(key, SystemClock.uptimeMillis()) },
        onUp = { keyboard.releaseModifier(key, SystemClock.uptimeMillis()) },
        haptics = haptics,
    )
}

@Composable
private fun RowScope.GridKey(
    label: String?,
    icon: ImageVector?,
    description: String,
    weight: Float,
    height: Dp,
    state: ModifierState,
    isModifier: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    haptics: Haptics,
    stateText: String? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val colors = MaterialTheme.colorScheme
    // A modifier that is on stays lit: tinted with an outline for the next key (or while a finger
    // holds it), filled for locked. A plain key is only lit while it is touched.
    val container = when {
        state == ModifierState.LOCKED -> colors.primary
        state == ModifierState.ARMED -> colors.primaryContainer
        pressed -> colors.primaryContainer
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        state == ModifierState.LOCKED -> colors.onPrimary
        state == ModifierState.ARMED -> colors.onPrimaryContainer
        pressed -> colors.onPrimaryContainer
        else -> colors.onSurface
    }

    Surface(
        modifier = Modifier
            .weight(weight)
            .height(height)
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
                role = if (isModifier) Role.Switch else Role.Button
                contentDescription = description
                if (stateText != null) stateDescription = stateText
                if (isModifier) selected = state != ModifierState.OFF
                // A screen reader's activation is a whole tap.
                onClick { down(); up(); true }
            },
        shape = KeyShape,
        color = container,
        contentColor = content,
        border = if (state == ModifierState.OFF) null else BorderStroke(2.dp, colors.primary),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            } else {
                // Long legends (Home, PgUp, Super) get a smaller size, so that none is cut off on a narrow phone.
                val text = label.orEmpty()
                val size = when {
                    text.length <= 2 -> 14.sp
                    text.length == 3 -> 12.sp
                    else -> 10.5.sp
                }
                Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = size), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
            if (state == ModifierState.LOCKED) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).size(10.dp))
            }
        }
    }
}
