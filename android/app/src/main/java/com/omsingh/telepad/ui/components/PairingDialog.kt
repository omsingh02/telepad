package com.omsingh.telepad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.FingerprintTextStyle

/**
 * Quick-look re-pairing dialog.
 *
 * The full pairing experience lives in [com.omsingh.telepad.ui.screens.PairingScreen]
 * — that's the canonical TOFU UI. This dialog is the lighter-weight variant
 * shown when an *already-trusted* host suddenly presents a different pubkey
 * (e.g. server reinstall). It's a deliberately scarier UI: red theming,
 * explicit "this is unusual" copy, "Forget and re-pair" rather than the
 * casual "Trust" button.
 */
@Composable
fun ReKeyWarningDialog(
    host: String,
    newFingerprint: String,
    onForgetAndRePair: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                "Fingerprint changed for $host",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
            ) {
                Text(
                    "This PC's identity key is different from the last time you " +
                    "connected. This usually means the server was reinstalled, " +
                    "but it can also mean someone else is impersonating your PC."
                )
                Text(
                    text = newFingerprint,
                    style = FingerprintTextStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                )
                Text(
                    "Only continue if you recently reinstalled Telepad on this PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onForgetAndRePair) {
                Text(
                    "Forget and re-pair",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        modifier = modifier
    )
}
