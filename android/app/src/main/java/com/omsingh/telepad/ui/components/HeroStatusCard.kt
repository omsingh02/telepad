package com.omsingh.telepad.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.StatusOffline
import com.omsingh.telepad.ui.theme.StatusOnline
import com.omsingh.telepad.ui.theme.StatusWarning

/**
 * Big card at the top of the Home screen that summarizes the current
 * connection state and offers the primary action (Launch remote / Disconnect /
 * pulse-to-connect).
 *
 * Four visual states:
 *  - **Disconnected** — neutral surface, "Ready to connect" prompt.
 *  - **Connecting**   — amber dot, spinner inside the card.
 *  - **Connected**    — primary container tint, big device name, two actions.
 *  - **Error**        — red dot, short error message.
 *
 * Why a hero card and not just a status bar? The Home screen is the user's
 * jump-off point. They look here first to see "am I connected?" — making it
 * the visual anchor of the screen avoids visual hunt.
 */
@Composable
fun HeroStatusCard(
    state: ConnectionState,
    onDisconnect: () -> Unit,
    onLaunchRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = state is ConnectionState.Connected
    val containerColor by animateColorAsState(
        targetValue = if (isConnected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        label = "heroContainer"
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadiusLarge),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardInternalPaddingLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            StatusHeader(state, isConnected)
            Text(
                text = mainLine(state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            if (state is ConnectionState.Error) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isConnected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onLaunchRemote,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                    ) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open touchpad")
                    }
                    FilledTonalButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(state: ConnectionState, isConnected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor(state), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel(state),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isConnected) {
            AssistChip(
                onClick = {},
                label = { Text("Live") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        }
    }
}

private fun dotColor(state: ConnectionState): androidx.compose.ui.graphics.Color = when (state) {
    is ConnectionState.Connected   -> StatusOnline
    ConnectionState.Connecting     -> StatusWarning
    ConnectionState.Reconnecting   -> StatusWarning
    is ConnectionState.Error       -> StatusOffline
    ConnectionState.Disconnected   -> androidx.compose.ui.graphics.Color.Gray
}

private fun statusLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected   -> "CONNECTED"
    ConnectionState.Connecting     -> "CONNECTING"
    ConnectionState.Reconnecting   -> "RECONNECTING"
    is ConnectionState.Error       -> "ERROR"
    ConnectionState.Disconnected   -> "READY"
}

private fun mainLine(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected   -> state.deviceName
    ConnectionState.Connecting     -> "Establishing link…"
    ConnectionState.Reconnecting   -> "Connection lost — retrying"
    is ConnectionState.Error       -> "Connection failed"
    ConnectionState.Disconnected   -> "Ready to connect"
}
