package com.omsingh.telepad.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.TelepadTheme

/** Whether a row's PC is being connected to, or is the one connected. */
enum class RowState { IDLE, CONNECTING, CONNECTED }

/**
 * One PC in the device list: what it is called, whether it is on the network right now,
 * where it is, and what tapping it will do.
 */
@Composable
fun DeviceRow(
    entry: DeviceEntry,
    state: RowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onForget: (() -> Unit)? = null,
    nowMs: Long = System.currentTimeMillis(),
) {
    val extended = TelepadTheme.extended
    val statusText = when {
        state == RowState.CONNECTED -> stringResource(R.string.devices_status_connected)
        state == RowState.CONNECTING -> stringResource(R.string.devices_status_connecting)
        !entry.paired -> stringResource(R.string.devices_status_new)
        entry.online -> stringResource(R.string.devices_status_online)
        else -> stringResource(R.string.devices_status_offline)
    }
    val usedText = if (entry.paired && entry.lastConnectedMs > 0) {
        stringResource(
            R.string.devices_last_used,
            DateUtils.getRelativeTimeSpanString(entry.lastConnectedMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    } else {
        null
    }
    val description = listOfNotNull(entry.name, statusText, entry.host).joinToString(", ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.devices_connect), onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = Spacing.screen, vertical = Spacing.md)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = statusText
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconTile(
                icon = osIcon(entry.os),
                container = if (entry.online || !entry.paired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                content = if (entry.online || !entry.paired) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusDot(
                color = if (entry.online) extended.success else MaterialTheme.colorScheme.outline,
                size = 14.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The status in colour, then when it was last used in the muted tone.
            val statusColor = if (entry.online && entry.paired) extended.success else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = statusColor)) { append(statusText) }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(entry.host, usedText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (state) {
            RowState.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            RowState.CONNECTED -> Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.devices_connected_short),
                tint = extended.success,
            )
            // A PC that is already paired is connected to by tapping its row; a new one gets a button
            // so that the first step is obvious.
            RowState.IDLE -> if (!entry.paired) {
                Button(onClick = onClick) { Text(stringResource(R.string.devices_pair)) }
            }
        }

        if (onForget != null) ForgetMenu(entry.name, onForget)
    }
}

@Composable
private fun ForgetMenu(name: String, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_forget)) },
                onClick = {
                    open = false
                    onForget()
                },
            )
        }
    }
}
