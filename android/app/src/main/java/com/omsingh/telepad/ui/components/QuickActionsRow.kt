package com.omsingh.telepad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.theme.Dimens

/**
 * The single highest-leverage UI feature of the keyboard screen.
 *
 * Top row of the keyboard exposes the chords every remote-keyboard user
 * actually wants: Copy / Paste / Cut / Undo / Redo / Alt+Tab / Win+D.
 * No fiddling with sticky modifiers, no two-handed taps. One press, one
 * action.
 *
 * Each action below sends a press + release for the HID code and the
 * appropriate modifier. We use `Ctrl` for clipboard chords (works on
 * Windows + Linux; macOS users with HID would need `Cmd` but Telepad's
 * Wi-Fi path lets the server decide based on host OS).
 *
 * Pasted-from-PC pill: if [pcClipboard] is non-null, a "📋 from PC" chip
 * appears at the right end of the row, and tapping it copies that text onto
 * the phone's local clipboard. Long-press dismisses the pill.
 */
@Composable
fun QuickActionsRow(
    onInputEvent: (InputEvent) -> Unit,
    onPushClipboardToPc: () -> Unit,
    onPullClipboardFromPc: () -> Unit,
    pcClipboard: String?,
    onCopyPcClipboardToPhone: () -> Unit,
    onDismissPcClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    fun chord(code: Int, modifiers: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val mods = InputEvent.Modifiers(
            leftCtrl  = modifiers and HidModifierMask.LEFT_CTRL  != 0,
            leftShift = modifiers and HidModifierMask.LEFT_SHIFT != 0,
            leftAlt   = modifiers and HidModifierMask.LEFT_ALT   != 0,
            leftMeta  = modifiers and HidModifierMask.LEFT_META  != 0,
        )
        onInputEvent(InputEvent.KeyPress(code, mods))
        onInputEvent(InputEvent.KeyRelease(code, mods))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            ChordChip(
                icon = Icons.Filled.ContentCopy, label = "Copy",
                onClick = { chord(HidKeyCodes.C, HidModifierMask.LEFT_CTRL) },
                modifier = Modifier.weight(1f)
            )
            ChordChip(
                icon = Icons.Filled.ContentPaste, label = "Paste",
                onClick = { chord(HidKeyCodes.V, HidModifierMask.LEFT_CTRL) },
                modifier = Modifier.weight(1f)
            )
            ChordChip(
                icon = Icons.Filled.ContentCut, label = "Cut",
                onClick = { chord(HidKeyCodes.X, HidModifierMask.LEFT_CTRL) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            ChordChip(
                icon = Icons.AutoMirrored.Filled.Undo, label = "Undo",
                onClick = { chord(HidKeyCodes.Z, HidModifierMask.LEFT_CTRL) },
                modifier = Modifier.weight(1f)
            )
            ChordChip(
                icon = Icons.AutoMirrored.Filled.Redo, label = "Redo",
                onClick = { chord(HidKeyCodes.Y, HidModifierMask.LEFT_CTRL) },
                modifier = Modifier.weight(1f)
            )
            ChordChip(
                icon = Icons.AutoMirrored.Filled.KeyboardTab, label = "Alt+Tab",
                onClick = { chord(HidKeyCodes.TAB, HidModifierMask.LEFT_ALT) },
                modifier = Modifier.weight(1f)
            )
            ChordChip(
                icon = Icons.Filled.Monitor, label = "Show desktop",
                onClick = { chord(HidKeyCodes.D, HidModifierMask.LEFT_META) },
                modifier = Modifier.weight(1f)
            )
        }
        // Clipboard sync row (Wi-Fi only). If pcClipboard is set, show pill.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            AssistChip(
                onClick = onPushClipboardToPc,
                label = { Text("Push clip → PC") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.ChipCornerRadius),
            )
            AssistChip(
                onClick = onPullClipboardFromPc,
                label = { Text("Pull clip ← PC") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.ChipCornerRadius),
            )
        }
        if (pcClipboard != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onCopyPcClipboardToPhone,
                    label = {
                        Text(
                            text = "From PC: ${pcClipboard.take(48).replace('\n', ' ')}" +
                                    if (pcClipboard.length > 48) "…" else "",
                            maxLines = 1,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Dimens.ChipCornerRadius),
                )
                AssistChip(
                    onClick = onDismissPcClipboard,
                    label = { Text("×") },
                    shape = RoundedCornerShape(Dimens.ChipCornerRadius),
                )
            }
        }
    }
}

@Composable
private fun ChordChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Box(modifier = Modifier.size(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.ChipCornerRadius),
    )
}
