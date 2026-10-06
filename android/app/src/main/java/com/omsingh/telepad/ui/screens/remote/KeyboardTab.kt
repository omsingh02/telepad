package com.omsingh.telepad.ui.screens.remote

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.host.ShortcutId
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.ModifierKey
import com.omsingh.telepad.core.input.ModifierState
import com.omsingh.telepad.core.input.TextDiff
import com.omsingh.telepad.ui.components.KeyCap
import com.omsingh.telepad.ui.components.ModifierKeyCap
import com.omsingh.telepad.ui.components.SectionHeader
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.Spacing

/** The most text kept in the field. Older text is dropped from the field (never from the PC). */
private const val MAX_BUFFER = 400
private const val KEEP_AFTER_TRIM = 120

/**
 * A keyboard for the PC: a field whose every edit is sent as it is made, the keys a phone
 * keyboard lacks (Esc, Tab, Ctrl, arrows...), and shortcuts named and shaped for the PC's OS.
 *
 * Whatever the phone's keyboard does to the text (autocorrect, suggestions, deleting a word)
 * is turned into the exact Backspaces and typing that give the PC the same result
 * ([TextDiff]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardTab(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val host = state.host
    val version by keyboard.version.collectAsState()
    var moreKeys by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        state.pcClipboard?.let { text ->
            PcClipboardCard(text, onCopy = actions::copyPcClipboardToPhone, onDismiss = actions::dismissPcClipboard)
        }

        TypingField(keyboard)

        if (!state.overWifi) {
            Text(
                stringResource(R.string.keys_bt_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(stringResource(R.string.keys_section_keys), horizontalPadding = 0.dp)
        // Passing the version makes the modifier keys redraw when their state changes.
        ModifierRow(host, keyboard, haptics, version, functionRow = moreKeys, onToggleFunctionRow = { moreKeys = !moreKeys })
        NavigationRow(keyboard, haptics)
        if (moreKeys) FunctionRow(keyboard, haptics)

        SectionHeader(stringResource(R.string.keys_section_shortcuts), horizontalPadding = 0.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            // Each chip already has a 48dp touch target around its 32dp body.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (state.clipboardAvailable) {
                ShortcutChip(Icons.Rounded.ContentPaste, stringResource(R.string.keys_paste_phone), null, haptics, actions::pasteFromPhone)
                ShortcutChip(Icons.Rounded.ContentCopy, stringResource(R.string.keys_copy_pc), null, haptics, actions::copyFromPc)
            }
            for (id in ShortcutId.values()) {
                val chord = host.chord(id)
                ShortcutChip(iconFor(id), labelFor(id), host.describe(chord), haptics) { keyboard.shortcut(chord) }
            }
        }
    }
}

// ── The field ────────────────────────────────────────────────────────

@Composable
private fun TypingField(keyboard: KeyboardSession) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val description = stringResource(R.string.keys_field_description)

    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(Spacing.lg)) {
            if (value.text.isEmpty()) {
                Text(
                    stringResource(R.string.keys_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { new ->
                    val edit = TextDiff.diff(value.text, new.text)
                    if (!edit.isEmpty) {
                        keyboard.backspace(edit.backspaces)
                        keyboard.type(edit.insert)
                    }
                    value = if (new.text.length > MAX_BUFFER && new.composition == null) {
                        val tail = new.text.takeLast(KEEP_AFTER_TRIM)
                        TextFieldValue(tail, TextRange(tail.length))
                    } else {
                        new
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = !hidden,
                    keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Text,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .padding(end = 72.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = description }
                    // A phone keyboard sends Backspace as a key event when there is nothing to delete,
                    // and the PC may well have something there.
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Backspace && event.type == KeyEventType.KeyDown && value.text.isEmpty()) {
                            keyboard.backspace(1)
                            true
                        } else {
                            false
                        }
                    },
            )
            Row(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { hidden = !hidden }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (hidden) R.string.keys_show_text else R.string.keys_hide_text),
                    )
                }
                if (value.text.isNotEmpty()) {
                    IconButton(onClick = { value = TextFieldValue("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.keys_clear))
                    }
                }
            }
        }
    }
}

// ── Keys ─────────────────────────────────────────────────────────────

@Composable
private fun ModifierRow(
    host: HostProfile,
    keyboard: KeyboardSession,
    haptics: Haptics,
    @Suppress("UNUSED_PARAMETER") version: Int,
    functionRow: Boolean,
    onToggleFunctionRow: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainKey(stringResource(R.string.key_esc), HidKeyCodes.ESC, keyboard, haptics)
        PlainKey(stringResource(R.string.key_tab), HidKeyCodes.TAB, keyboard, haptics)
        LatchKey(ModifierKey.CTRL, host.ctrl.symbol, host.ctrl.spokenName, keyboard, haptics)
        LatchKey(ModifierKey.ALT, host.alt.symbol, host.alt.spokenName, keyboard, haptics)
        LatchKey(ModifierKey.SHIFT, host.shift.symbol, host.shift.spokenName, keyboard, haptics)
        LatchKey(ModifierKey.META, host.meta.symbol, host.meta.spokenName, keyboard, haptics)
        // Shows or hides the row of function keys.
        ModifierKeyCap(
            symbol = "Fn",
            spokenName = stringResource(R.string.keys_function_row),
            state = if (functionRow) ModifierState.ARMED else ModifierState.OFF,
            stateDescription = stringResource(R.string.keys_function_row),
            onTap = onToggleFunctionRow,
            haptics = haptics,
        )
    }
}

@Composable
private fun LatchKey(key: ModifierKey, symbol: String, spokenName: String, keyboard: KeyboardSession, haptics: Haptics) {
    val state = keyboard.state(key)
    val stateText = when (state) {
        ModifierState.OFF -> stringResource(R.string.keys_modifier_off, spokenName)
        ModifierState.ARMED -> stringResource(R.string.keys_modifier_armed, spokenName)
        ModifierState.LOCKED -> stringResource(R.string.keys_modifier_locked, spokenName)
    }
    ModifierKeyCap(
        symbol = symbol,
        spokenName = spokenName,
        state = state,
        stateDescription = stateText,
        onTap = { keyboard.tapModifier(key, SystemClock.uptimeMillis()) },
        haptics = haptics,
    )
}

@Composable
private fun NavigationRow(keyboard: KeyboardSession, haptics: Haptics) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeldKey(HidKeyCodes.LEFT, keyboard, haptics, icon = Icons.AutoMirrored.Rounded.ArrowBack, description = stringResource(R.string.key_left))
        HeldKey(HidKeyCodes.DOWN, keyboard, haptics, icon = Icons.Rounded.ArrowDownward, description = stringResource(R.string.key_down))
        HeldKey(HidKeyCodes.UP, keyboard, haptics, icon = Icons.Rounded.ArrowUpward, description = stringResource(R.string.key_up))
        HeldKey(HidKeyCodes.RIGHT, keyboard, haptics, icon = Icons.AutoMirrored.Rounded.ArrowForward, description = stringResource(R.string.key_right))
        HeldKey(HidKeyCodes.HOME, keyboard, haptics, label = stringResource(R.string.key_home))
        HeldKey(HidKeyCodes.END, keyboard, haptics, label = stringResource(R.string.key_end))
        HeldKey(HidKeyCodes.PAGE_UP, keyboard, haptics, label = stringResource(R.string.key_page_up))
        HeldKey(HidKeyCodes.PAGE_DOWN, keyboard, haptics, label = stringResource(R.string.key_page_down))
        HeldKey(HidKeyCodes.DELETE, keyboard, haptics, label = stringResource(R.string.key_delete))
        HeldKey(HidKeyCodes.ENTER, keyboard, haptics, label = stringResource(R.string.key_enter))
    }
}

@Composable
private fun FunctionRow(keyboard: KeyboardSession, haptics: Haptics) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (n in 1..12) {
            PlainKey("F$n", HidKeyCodes.F1 + n - 1, keyboard, haptics)
        }
    }
}

/** A key that is tapped: down and up at once, however briefly it is touched. */
@Composable
private fun PlainKey(label: String, usage: Int, keyboard: KeyboardSession, haptics: Haptics) {
    KeyCap(
        label = label,
        onDown = {},
        onUp = { keyboard.tapKey(usage) },
        haptics = haptics,
    )
}

/** A key that stays down while it is touched, so the PC repeats it. */
@Composable
private fun HeldKey(
    usage: Int,
    keyboard: KeyboardSession,
    haptics: Haptics,
    label: String? = null,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
) {
    KeyCap(
        label = label,
        icon = icon,
        description = description,
        onDown = { keyboard.press(usage) },
        onUp = { keyboard.release(usage) },
        haptics = haptics,
    )
}

// ── Shortcuts and clipboard ──────────────────────────────────────────

@Composable
private fun ShortcutChip(icon: ImageVector, label: String, hint: String?, haptics: Haptics, onClick: () -> Unit) {
    AssistChip(
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = null,
        onClick = {
            haptics.key()
            onClick()
        },
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
private fun PcClipboardCard(text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(R.string.keys_pc_clipboard),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.keys_dismiss)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.keys_pc_clipboard_copy)) }
            }
        }
    }
}

private fun iconFor(id: ShortcutId): ImageVector = when (id) {
    ShortcutId.COPY -> Icons.Rounded.ContentCopy
    ShortcutId.PASTE -> Icons.Rounded.ContentPaste
    ShortcutId.CUT -> Icons.Rounded.ContentCut
    ShortcutId.UNDO -> Icons.Rounded.Undo
    ShortcutId.REDO -> Icons.Rounded.Redo
    ShortcutId.SELECT_ALL -> Icons.Rounded.SelectAll
    ShortcutId.FIND -> Icons.Rounded.Search
    ShortcutId.SAVE -> Icons.Rounded.Save
    ShortcutId.NEW_TAB -> Icons.Rounded.Tab
    ShortcutId.CLOSE_TAB -> Icons.Rounded.Close
    ShortcutId.REFRESH -> Icons.Rounded.Refresh
    ShortcutId.SWITCH_APP -> Icons.Rounded.SwapHoriz
}

@Composable
private fun labelFor(id: ShortcutId): String = stringResource(
    when (id) {
        ShortcutId.COPY -> R.string.shortcut_copy
        ShortcutId.PASTE -> R.string.shortcut_paste
        ShortcutId.CUT -> R.string.shortcut_cut
        ShortcutId.UNDO -> R.string.shortcut_undo
        ShortcutId.REDO -> R.string.shortcut_redo
        ShortcutId.SELECT_ALL -> R.string.shortcut_select_all
        ShortcutId.FIND -> R.string.shortcut_find
        ShortcutId.SAVE -> R.string.shortcut_save
        ShortcutId.NEW_TAB -> R.string.shortcut_new_tab
        ShortcutId.CLOSE_TAB -> R.string.shortcut_close_tab
        ShortcutId.REFRESH -> R.string.shortcut_refresh
        ShortcutId.SWITCH_APP -> R.string.shortcut_switch_app
    },
)
