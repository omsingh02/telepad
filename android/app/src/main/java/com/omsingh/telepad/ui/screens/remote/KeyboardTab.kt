package com.omsingh.telepad.ui.screens.remote

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.ShortcutId
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.TypingBridge
import com.omsingh.telepad.ui.components.SectionHeader
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.Spacing

/**
 * What the person chose about the keyboard, kept above the tab so that switching to the Pad and
 * back does not undo it.
 */
@Stable
class KeyboardUiState(chosenPcLayout: Boolean? = null, live: Boolean = false, hidden: Boolean = false) {
    /** The layout picked from the menu, or null to let the way the phone is held decide. */
    var chosenPcLayout by mutableStateOf(chosenPcLayout)

    /** Send every key at once, with no suggestions from the phone's keyboard. */
    var live by mutableStateOf(live)

    /** Mask what is typed, for a password. */
    var hidden by mutableStateOf(hidden)

    companion object {
        val Saver = listSaver<KeyboardUiState, Any?>(
            save = { listOf(it.chosenPcLayout, it.live, it.hidden) },
            restore = { KeyboardUiState(it[0] as Boolean?, it[1] as Boolean, it[2] as Boolean) },
        )
    }
}

@Composable
fun rememberKeyboardUiState(): KeyboardUiState = rememberSaveable(saver = KeyboardUiState.Saver) { KeyboardUiState() }

/** Below this much room the keys and shortcuts are made smaller to fit. */
private val ShortScreen = 560.dp

/**
 * Typing and keys for the PC.
 *
 * You type with your phone's own keyboard, so autocorrect, swipe typing, voice and every language
 * work, and what you type is sent to the PC ([TypingBridge]). The keys a phone keyboard lacks sit
 * right above it ([KeyBar]): Esc, Tab, the modifiers, arrows. Shortcuts named for the PC's own
 * operating system fill the rest of the room.
 *
 * Keybinds that need every key of a real keyboard are on the PC keyboard ([KeyGrid]), which
 * is what a phone held sideways shows, since its own keyboard would fill the screen.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyboardTab(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    ui: KeyboardUiState = rememberKeyboardUiState(),
) {
    val host = state.host
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // A choice from the menu wins. Without one, the way the phone is held decides (the screen forgets
    // the choice when the phone is turned).
    val pcLayout = ui.chosenPcLayout ?: landscape
    var openPhoneKeyboard by remember { mutableStateOf(false) }

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var live by ui::live
    var hidden by ui::hidden
    var help by remember { mutableStateOf(false) }

    val bridge = remember(keyboard) { TypingBridge(keyboard) }
    val focus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Text typed so far must reach the PC before a key from the bar, or the key arrives ahead of it.
    val beforeKey: () -> Unit = { bridge.flush(field.text) }

    val onFieldChange: (TextFieldValue) -> Unit = { new ->
        val composition = new.composition?.let { TypingBridge.Composition(it.start, it.end) }
        val replacement = bridge.update(new.text, composition)
        field = if (replacement == null) new else TextFieldValue(replacement, TextRange(replacement.length))
    }

    val showPcKeyboard = {
        bridge.flush(field.text)
        keyboardController?.hide()
        focusManager.clearFocus()
        ui.chosenPcLayout = true
    }
    val showPhoneKeyboard = {
        ui.chosenPcLayout = false
        openPhoneKeyboard = true
    }
    LaunchedEffect(pcLayout, openPhoneKeyboard) {
        if (!pcLayout && openPhoneKeyboard) {
            openPhoneKeyboard = false
            runCatching { focus.requestFocus() }
            keyboardController?.show()
        }
    }

    // The shortcuts: the same list wherever there is room for it.
    val shortcuts = buildList {
        if (state.clipboardAvailable) {
            add(ShortcutItem(Icons.Rounded.ContentPaste, stringResource(R.string.keys_paste_phone), null) {
                beforeKey()
                actions.pasteFromPhone()
            })
            add(ShortcutItem(Icons.Rounded.ContentCopy, stringResource(R.string.keys_copy_pc), null) {
                beforeKey()
                actions.copyFromPc()
            })
        }
        for (id in ShortcutId.values()) {
            val chord = host.chord(id)
            add(ShortcutItem(iconFor(id), labelFor(id), host.describe(chord)) {
                beforeKey()
                keyboard.shortcut(chord)
            })
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val short = maxHeight < ShortScreen
        // A phone held sideways has no room for a section of shortcuts beside the whole keyboard:
        // they go in the row above it instead, where the width is.
        val shortcutsAboveKeys = pcLayout && short

        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (shortcutsAboveKeys) {
                state.pcClipboard?.let { text ->
                    PcClipboardCard(text, onCopy = actions::copyPcClipboardToPhone, onDismiss = actions::dismissPcClipboard)
                }
                Spacer(Modifier.weight(1f))
            } else {
                // The shortcuts take whatever room the keyboard leaves.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    state.pcClipboard?.let { text ->
                        PcClipboardCard(text, onCopy = actions::copyPcClipboardToPhone, onDismiss = actions::dismissPcClipboard)
                    }

                    // Two columns that scroll up and down, however much room there is: with the phone's keyboard
                    // open there is little, and with it closed a long list still fits by scrolling.
                    SectionHeader(stringResource(R.string.keys_section_shortcuts), horizontalPadding = 0.dp)
                    FlowRow(
                        maxItemsInEachRow = 2,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        // Each chip already has a 48dp touch target around its 32dp body.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) { for (item in shortcuts) ShortcutChip(item, haptics, Modifier.weight(1f)) }

                    if (!state.overWifi) {
                        Text(
                            stringResource(R.string.keys_bt_limits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (pcLayout) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = showPhoneKeyboard) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.keys_menu_phone_layout), modifier = Modifier.padding(start = Spacing.sm))
                    }
                    if (shortcutsAboveKeys) {
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { for (item in shortcuts) ShortcutChip(item, haptics) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = { help = true }) {
                        Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = stringResource(R.string.keys_menu_help))
                    }
                }
                KeyGrid(host, keyboard, haptics, compact = short || landscape)
            } else {
                TypingField(
                    value = field,
                    onValueChange = onFieldChange,
                    live = live,
                    hidden = hidden,
                    onBackspaceWithNothingToDelete = bridge::backspaceWithNothingToDelete,
                    focusRequester = focus,
                    trailing = {
                        IconButton(onClick = { hidden = !hidden }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(if (hidden) R.string.keys_show_text else R.string.keys_hide_text),
                            )
                        }
                        if (field.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    field = TextFieldValue("")
                                    bridge.reset()
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.keys_clear))
                            }
                        }
                        FieldMenu(
                            live = live,
                            onPcKeyboard = showPcKeyboard,
                            onToggleLive = {
                                bridge.flush(field.text)
                                live = !live
                            },
                            onHelp = { help = true },
                        )
                    },
                )
                KeyBar(host, keyboard, haptics, beforeKey)
            }
        }
    }

    if (help) KeyHelpSheet(onDismiss = { help = false })
}

/** The options for typing, behind one button so that the line has room for what is typed. */
@Composable
private fun FieldMenu(live: Boolean, onPcKeyboard: () -> Unit, onToggleLive: () -> Unit, onHelp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.keys_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_menu_pc_layout)) },
                leadingIcon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                onClick = {
                    open = false
                    onPcKeyboard()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_menu_live)) },
                trailingIcon = { if (live) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = {
                    open = false
                    onToggleLive()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.keys_menu_help)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
                onClick = {
                    open = false
                    onHelp()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.keys_help_title), style = MaterialTheme.typography.headlineSmall)
            for (line in listOf(
                R.string.keys_help_type,
                R.string.keys_help_chord,
                R.string.keys_help_lock,
                R.string.keys_help_hold,
                R.string.keys_help_fn,
                R.string.keys_help_pc,
            )) {
                Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ── Shortcuts and clipboard ──────────────────────────────────────────

/** A shortcut: what to call it, which key combination it is on this PC, and what to do. */
private class ShortcutItem(val icon: ImageVector, val label: String, val hint: String?, val onClick: () -> Unit)

@Composable
private fun ShortcutChip(item: ShortcutItem, haptics: Haptics, modifier: Modifier = Modifier) {
    AssistChip(
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = null,
        modifier = modifier,
        onClick = {
            haptics.key()
            item.onClick()
        },
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label)
                if (item.hint != null) {
                    Text(item.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingIcon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
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
