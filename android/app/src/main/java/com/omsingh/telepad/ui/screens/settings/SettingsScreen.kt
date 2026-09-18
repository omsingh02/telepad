package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.ui.theme.Dimens

/**
 * Settings hub — a list of category rows that drill into sub-pages.
 *
 * One screen, one job. Each row has an icon, a title, a one-line subtitle
 * summarising current state ("Cyan accent, follow system"), and a chevron.
 *
 * This replaces the flat scrolling list that audited badly: 20+ settings on
 * one page becomes unscannable. The hub model lets users jump straight to
 * what they want.
 */
@Composable
fun SettingsScreen(
    onNavigate: (SettingsDestination) -> Unit,
) {
    val items = listOf(
        SettingsRow(Icons.Filled.TouchApp,   "Touchpad",   "Sensitivity, gestures, calibration", SettingsDestination.Touchpad),
        SettingsRow(Icons.Filled.Keyboard,   "Keyboard",   "Clipboard sync, autocomplete",        SettingsDestination.Keyboard),
        SettingsRow(Icons.Filled.Wifi,       "Connection", "Transport, keep-alive, manual devices", SettingsDestination.Connection),
        SettingsRow(Icons.Filled.Palette,    "Appearance", "Theme, accent color, dynamic color",  SettingsDestination.Appearance),
        SettingsRow(Icons.Filled.Lock,       "Privacy",    "Paired devices, fingerprints, telemetry", SettingsDestination.Privacy),
        SettingsRow(Icons.Filled.Info,       "About",      "Version, source, licenses",            SettingsDestination.About),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Dimens.ScreenHorizontalPadding,
            vertical = Dimens.ScreenVerticalPadding
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = Dimens.ItemSpacing),
            )
        }
        items(items) { row ->
            CategoryCard(row, onClick = { onNavigate(row.destination) })
        }
    }
}

private data class SettingsRow(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val destination: SettingsDestination,
)

@Composable
private fun CategoryCard(row: SettingsRow, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                row.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = Dimens.ItemSpacing)
            )
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

enum class SettingsDestination {
    Touchpad, Keyboard, Connection, Appearance, Privacy, About
}
