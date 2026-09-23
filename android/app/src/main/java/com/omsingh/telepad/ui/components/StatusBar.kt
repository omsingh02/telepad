package com.omsingh.telepad.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.StatusOffline
import com.omsingh.telepad.ui.theme.StatusOnline
import com.omsingh.telepad.ui.theme.StatusWarning

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * Persistent thin bar at the top of every non-home screen.
 *
 * Communicates "you are controlling [hostname] over [transport]" so the user
 * never has to remember which PC they're driving. Tapping the right side
 * disconnects.
 *
 * Hidden when not connected (host has its own hero card for that state).
 */
@Composable
fun StatusBar(
    state: ConnectionState,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is ConnectionState.Connected) return

    val dotColor by animateColorAsState(
        targetValue = StatusOnline,
        label = "statusDot"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.StatusBarHeight)
            .clip(RoundedCornerShape(Dimens.ChipCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (state.transport == ConnectionState.Transport.WIFI)
                Icons.Filled.Wifi else Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = state.deviceName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (state.latencyMs != null) {
            Text(
                text = "${state.latencyMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Disconnect",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDisconnect)
        )
    }
}

/**
 * Visual-only variant for previewing reconnecting state without animation
 * helpers. Used by error banners.
 */
@Composable
fun StatusBarTransient(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.StatusBarHeight)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Dimens.ScreenHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
