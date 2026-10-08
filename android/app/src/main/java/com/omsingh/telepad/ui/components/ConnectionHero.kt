package com.omsingh.telepad.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.ui.theme.Motion
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.ui.theme.rememberReducedMotion

/**
 * The top of the device list: how the current connection stands, in words and colour, with
 * the one or two things the person can do next. Draws nothing while disconnected.
 *
 * It is a live region, so a screen reader announces "Reconnecting…" and "Connected" as they
 * happen without the person having to go looking.
 */
@Composable
fun ConnectionHero(
    state: ConnectionState,
    onOpenRemote: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onFixBluetooth: (FailureReason) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = state,
        modifier = modifier.animateContentSize(Motion.spatial(reduced)),
        transitionSpec = { fadeIn(Motion.effects(reduced)) togetherWith fadeOut(Motion.effects(reduced)) },
        contentKey = { it::class },
        label = "connectionHero",
    ) { current ->
        when (current) {
            ConnectionState.Disconnected -> Unit
            is ConnectionState.Connecting -> StatusCard(
                title = stringResource(R.string.devices_connecting_to, current.deviceName),
                subtitle = transportName(current.transport),
                transport = current.transport,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
                progress = true,
            )
            is ConnectionState.Reconnecting -> StatusCard(
                title = stringResource(R.string.devices_reconnecting_to, current.deviceName),
                subtitle = transportName(current.transport),
                transport = current.transport,
                container = TelepadTheme.extended.warningContainer,
                content = TelepadTheme.extended.onWarningContainer,
                progress = true,
                actions = {
                    TextButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.textButtonColors(contentColor = TelepadTheme.extended.onWarningContainer),
                    ) { Text(stringResource(R.string.devices_disconnect)) }
                },
            )
            is ConnectionState.Connected -> StatusCard(
                title = stringResource(R.string.devices_connected_to, current.deviceName),
                subtitle = connectedSubtitle(current),
                transport = current.transport,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                actions = {
                    TextButton(onClick = onDisconnect) { Text(stringResource(R.string.devices_disconnect)) }
                    Button(onClick = onOpenRemote) { Text(stringResource(R.string.devices_open_remote)) }
                },
            )
            is ConnectionState.Failed -> FailureCard(current, onRetry, onDismiss, onFixBluetooth)
        }
    }
}

@Composable
private fun transportName(transport: ConnectionState.Transport) = when (transport) {
    ConnectionState.Transport.WIFI -> stringResource(R.string.devices_via_wifi)
    ConnectionState.Transport.BLUETOOTH -> stringResource(R.string.devices_via_bluetooth)
}

@Composable
private fun connectedSubtitle(state: ConnectionState.Connected): String {
    val parts = buildList {
        add(transportName(state.transport))
        state.latencyMs?.let { add(stringResource(R.string.devices_latency, it)) }
        if (state.unstable) add(stringResource(R.string.devices_weak_connection))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    transport: ConnectionState.Transport,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    progress: Boolean = false,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = if (transport == ConnectionState.Transport.WIFI) Icons.Rounded.Wifi else Icons.Rounded.Bluetooth,
                    container = content.copy(alpha = 0.12f),
                    content = content,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
                }
            }
            if (progress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = content,
                    trackColor = content.copy(alpha = 0.15f),
                )
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun FailureCard(
    state: ConnectionState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onFixBluetooth: (FailureReason) -> Unit,
) {
    val bluetoothFix = state.reason == FailureReason.BLUETOOTH_PERMISSION || state.reason == FailureReason.BLUETOOTH_DISABLED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconTile(
                    icon = Icons.Rounded.ErrorOutline,
                    container = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(failureTitle(state.reason, state.deviceName), style = MaterialTheme.typography.titleMedium)
                    Text(
                        failureBody(state.reason, state.deviceName, state.hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) { Text(stringResource(R.string.action_close)) }
                if (bluetoothFix) {
                    Button(
                        onClick = { onFixBluetooth(state.reason) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (state.reason == FailureReason.BLUETOOTH_PERMISSION) R.string.add_bt_grant else R.string.add_bt_enable,
                            ),
                        )
                    }
                } else if (state.reason != FailureReason.BLUETOOTH_UNSUPPORTED) {
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f)),
                    ) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}
