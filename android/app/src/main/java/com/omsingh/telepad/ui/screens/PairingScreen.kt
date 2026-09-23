package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.FingerprintTextStyle
import com.omsingh.telepad.viewmodel.PairingViewModel

/**
 * Full-screen TOFU pairing UI.
 *
 * Lifecycle:
 *  1. Caller passes [server] — the candidate PC the user just tapped.
 *  2. We trigger [PairingViewModel.beginPairing] which fetches the server's
 *     X25519 public key via a one-off UDP exchange.
 *  3. On [PairingViewModel.State.Ready] we render the fingerprint in giant
 *     monospace, with a primary "Trust this PC" button.
 *  4. User taps Trust → [onTrust] fires with the pubkey base64, the caller
 *     persists it and starts the real Noise handshake.
 *  5. User taps Cancel or fingerprint is unreachable → [onCancel].
 *
 * Visual hierarchy is deliberately stark: this is a security decision.
 * Big fingerprint, plain copy, only two buttons. No icons fighting for
 * attention, no rounded-corner cards trying to make crypto look friendly.
 */
@Composable
fun PairingScreen(
    server: ServerInfo,
    viewModel: PairingViewModel,
    onTrust: (ServerInfo, String) -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(server.host) {
        viewModel.beginPairing(server)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Dimens.ScreenHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Verify this PC",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Make sure the characters below match what's shown on your PC. If they don't match, someone may be impersonating your PC.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (val s = state) {
                is PairingViewModel.State.Loading -> LoadingContent(server)
                is PairingViewModel.State.Ready   -> FingerprintContent(s)
                is PairingViewModel.State.Error   -> ErrorContent(s.message, onRetry = { viewModel.beginPairing(server) })
                else -> LoadingContent(server)
            }
        }

        val ready = state as? PairingViewModel.State.Ready
        Button(
            onClick = {
                ready?.let { onTrust(it.server, it.pubkeyBase64) }
            },
            enabled = ready != null,
            shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.ButtonMinHeight),
        ) {
            Text("Trust this PC")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                viewModel.reset()
                onCancel()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Cancel",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LoadingContent(server: ServerInfo) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        CircularProgressIndicator()
        Text(
            text = "Asking ${server.host} for its identity…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FingerprintContent(ready: PairingViewModel.State.Ready) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardInternalPaddingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
            ) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = ready.server.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = ready.fingerprint,
                style = FingerprintTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Compare to the fingerprint shown on your PC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Couldn't reach PC",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
        ) {
            Text("Retry")
        }
    }
}
