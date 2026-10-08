package com.omsingh.telepad.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.connection.PairingUiState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.ui.components.FingerprintBlock
import com.omsingh.telepad.ui.components.IconTile
import com.omsingh.telepad.ui.components.failureBody
import com.omsingh.telepad.ui.components.failureTitle
import com.omsingh.telepad.ui.theme.Spacing

/**
 * The conversation that decides whether a PC is trusted.
 *
 * The one thing a person has to do here is compare two short codes, so that is what the
 * sheet is built around: the code is large, and the instruction says exactly what to
 * compare it with. When a PC that was paired before answers with a different key, the
 * same sheet turns into a warning, and the safe choice (cancel) becomes the prominent one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(
    state: PairingUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PairingSheetContent(state, onConfirm, onDismiss, onRetry)
    }
}

/** The inside of the pairing sheet, apart from the sheet itself, so it can be shown and tested on its own. */
@Composable
fun PairingSheetContent(
    state: PairingUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl)
            .padding(bottom = Spacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is PairingUiState.Contacting -> Busy(stringResource(R.string.pairing_contacting, state.label))
            is PairingUiState.Connecting -> Busy(stringResource(R.string.pairing_connecting))
            is PairingUiState.Verify ->
                if (state.replaces != null) KeyChanged(state, onConfirm, onDismiss) else Verify(state, onConfirm, onDismiss)
            is PairingUiState.Failed -> Failed(state, onDismiss, onRetry)
        }
    }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator(Modifier.padding(top = Spacing.lg))
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = Spacing.lg))
}

@Composable
private fun Verify(state: PairingUiState.Verify, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    IconTile(
        icon = Icons.Rounded.Shield,
        size = 56.dp,
        iconSize = 30.dp,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Text(stringResource(R.string.pairing_verify_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.pairing_verify_body, state.candidate.name),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    FingerprintBlock(state.fingerprint)
    Text(
        stringResource(R.string.pairing_verify_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.pairing_security_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pairing_matches)) }
    }
}

@Composable
private fun KeyChanged(state: PairingUiState.Verify, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    IconTile(
        icon = Icons.Rounded.GppMaybe,
        size = 56.dp,
        iconSize = 30.dp,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
    Text(
        stringResource(R.string.pairing_changed_title, state.candidate.name),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.pairing_changed_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    FingerprintBlock(state.fingerprint, emphasis = MaterialTheme.colorScheme.error)
    Text(
        stringResource(R.string.pairing_changed_advice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    // The safe choice is the prominent one.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        OutlinedButton(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.pairing_changed_continue)) }
    }
}

@Composable
private fun Failed(state: PairingUiState.Failed, onDismiss: () -> Unit, onRetry: () -> Unit) {
    IconTile(
        icon = Icons.Rounded.ErrorOutline,
        size = 56.dp,
        iconSize = 30.dp,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
    val notPaired = state.reason == FailureReason.NOT_PAIRED
    Text(
        text = if (notPaired) stringResource(R.string.pairing_not_paired_title, state.label) else failureTitle(state.reason, state.label),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(
        text = if (notPaired) stringResource(R.string.pairing_not_paired_body) else failureBody(state.reason, state.label, state.hint),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_close)) }
        Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_retry)) }
    }
}
