package com.omsingh.telepad.ui.components

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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.StatusOnline

/**
 * Single row in the discovered servers list.
 *
 * Three variants implicitly:
 *  - **Discovered (online)** — small green dot in the icon corner.
 *  - **Saved favorite (offline-confirmed)** — no online dot.
 *  - **Trusted (paired)** — small lock icon to communicate that this PC will
 *    connect silently. Untrusted PCs show an open-lock to telegraph the
 *    pairing prompt that will follow.
 */
@Composable
fun ServerCard(
    server: ServerInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOnline: Boolean = false,
    isTrusted: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardInternalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                if (isOnline) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.BottomEnd)
                            .background(StatusOnline, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(Dimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.displayLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isTrusted) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = if (isTrusted) "Trusted" else "Pair on connect",
                tint = if (isTrusted) StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Variant for the Bluetooth tab — looks like [ServerCard] but uses the
 * Bluetooth glyph and renders `address` as the subtitle.
 */
@Composable
fun BluetoothDeviceCard(
    name: String,
    address: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardInternalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Dimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
