package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.InputEvent.Modifiers
import com.omsingh.telepad.ui.components.QuickActionsRow
import com.omsingh.telepad.ui.components.StatusBar
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Keyboard screen.
 *
 * Layout (top to bottom):
 *  1. Status bar (persistent).
 *  2. **Quick actions row** — Copy/Paste/Cut/Undo/Redo/Alt+Tab/Show desktop +
 *     clipboard sync chips. The single highest-leverage UI feature.
 *  3. Sticky modifier row (Ctrl/Shift/Alt/Win). Tap to toggle, long-press
 *     to send the modifier as a standalone key.
 *  4. F-row toggle + Esc/Tab/Caps.
 *  5. Text input field (sends UTF-8 to PC as you type via Wi-Fi, ASCII
 *     via Bluetooth).
 *  6. Navigation cluster: Ins/Home/PgUp/PrtSc, Del/End/PgDn/Ent.
 *  7. Inverted-T arrow cluster.
 */
@Composable
fun KeyboardScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pcClipboard by viewModel.pcClipboard.collectAsState()
    var modifiers by remember { mutableStateOf(Modifiers()) }
    var showFunctionRow by remember { mutableStateOf(false) }

    fun sendKey(code: Int) {
        viewModel.onInputEvent(InputEvent.KeyPress(code, modifiers))
        viewModel.onInputEvent(InputEvent.KeyRelease(code, modifiers))
        // One-shot semantics: non-empty modifiers reset after key.
        if (modifiers.hasAny) modifiers = Modifiers()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
    ) {
        StatusBar(state = connectionState, onDisconnect = { viewModel.disconnect() })
        Spacer(Modifier.height(8.dp))

        QuickActionsRow(
            onInputEvent = viewModel::onInputEvent,
            onPushClipboardToPc = { viewModel.pushClipboardToPc() },
            onPullClipboardFromPc = { viewModel.pullClipboardFromPc() },
            pcClipboard = pcClipboard,
            onCopyPcClipboardToPhone = { viewModel.copyPcClipboardToPhone() },
            onDismissPcClipboard = { viewModel.clipboardSync.clearLatest() },
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            ModifierButton("Ctrl", modifiers.leftCtrl,
                onTap = { modifiers = modifiers.copy(leftCtrl = !modifiers.leftCtrl) },
                onLongPress = { sendKey(HidKeyCodes.LEFT_CTRL) })
            ModifierButton("Shift", modifiers.leftShift,
                onTap = { modifiers = modifiers.copy(leftShift = !modifiers.leftShift) },
                onLongPress = { sendKey(HidKeyCodes.LEFT_SHIFT) })
            ModifierButton("Alt", modifiers.leftAlt,
                onTap = { modifiers = modifiers.copy(leftAlt = !modifiers.leftAlt) },
                onLongPress = { sendKey(HidKeyCodes.LEFT_ALT) })
            ModifierButton("Win", modifiers.leftMeta,
                onTap = { modifiers = modifiers.copy(leftMeta = !modifiers.leftMeta) },
                onLongPress = { sendKey(HidKeyCodes.LEFT_META) })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            KeyButton(if (showFunctionRow) "F-row ▾" else "F-row ▸") {
                showFunctionRow = !showFunctionRow
            }
            KeyButton("Esc") { sendKey(HidKeyCodes.ESC) }
            KeyButton("Tab") { sendKey(HidKeyCodes.TAB) }
            KeyButton("Caps") { sendKey(HidKeyCodes.CAPS_LOCK) }
        }

        if (showFunctionRow) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..5).forEach { i -> KeyButton("F${i + 1}") { sendKey(HidKeyCodes.F1 + i) } }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (6..11).forEach { i -> KeyButton("F${i + 1}") { sendKey(HidKeyCodes.F1 + i) } }
            }
        }

        TextInputCapture(
            onText = { added ->
                viewModel.onInputEvent(InputEvent.TextInput(added, modifiers))
                if (modifiers.hasAny) modifiers = Modifiers()
            },
            onBackspace = { sendKey(HidKeyCodes.BACKSPACE) },
            onEnter = { sendKey(HidKeyCodes.ENTER) },
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall)) {
            KeyButton("Ins")   { sendKey(HidKeyCodes.INSERT) }
            KeyButton("Home")  { sendKey(HidKeyCodes.HOME) }
            KeyButton("PgUp")  { sendKey(HidKeyCodes.PAGE_UP) }
            KeyButton("PrtSc") { sendKey(HidKeyCodes.PRINT_SCREEN) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall)) {
            KeyButton("Del")  { sendKey(HidKeyCodes.DELETE) }
            KeyButton("End")  { sendKey(HidKeyCodes.END) }
            KeyButton("PgDn") { sendKey(HidKeyCodes.PAGE_DOWN) }
            KeyButton("Ent")  { sendKey(HidKeyCodes.ENTER) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall)) {
            Spacer(modifier = Modifier.weight(1f))
            KeyButton("↑") { sendKey(HidKeyCodes.UP) }
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall)) {
            KeyButton("←") { sendKey(HidKeyCodes.LEFT) }
            KeyButton("↓") { sendKey(HidKeyCodes.DOWN) }
            KeyButton("→") { sendKey(HidKeyCodes.RIGHT) }
        }
        Spacer(Modifier.height(Dimens.ScreenVerticalPadding))
    }
}

@Composable
private fun TextInputCapture(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
) {
    var buffer by remember { mutableStateOf("") }
    OutlinedTextField(
        value = buffer,
        onValueChange = { new ->
            when {
                new.length > buffer.length -> onText(new.substring(buffer.length))
                new.length < buffer.length -> repeat(buffer.length - new.length) { onBackspace() }
            }
            if (new.endsWith('\n')) { onEnter(); buffer = "" }
            else buffer = if (new.length > 64) "" else new
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Type here — sent to PC as you type") },
        singleLine = true,
    )
}

@Composable
private fun RowScope.ModifierButton(
    label: String,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTap()
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
        modifier = Modifier
            .weight(1f)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
    ) { Text(label) }
}

@Composable
private fun RowScope.KeyButton(label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
        modifier = Modifier.weight(1f),
    ) { Text(label) }
}
