package com.omsingh.telepad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LaptopWindows
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.ui.theme.FingerprintStyle
import com.omsingh.telepad.ui.theme.Spacing

/** The icon for a PC's operating system. */
fun osIcon(os: HostOs): ImageVector = when (os) {
    HostOs.MACOS -> Icons.Rounded.LaptopMac
    HostOs.LINUX -> Icons.Rounded.Terminal
    HostOs.WINDOWS -> Icons.Rounded.LaptopWindows
    HostOs.UNKNOWN -> Icons.Rounded.Computer
}

/** A heading above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, horizontalPadding: Dp = Spacing.screen) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = horizontalPadding, vertical = Spacing.sm)
            .semantics { heading() },
    )
}

/** A small coloured dot, for presence and status. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** An icon on a rounded tonal tile: the leading visual of rows and cards. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/**
 * A tonal card with a leading icon, a title and a body, and optional actions underneath.
 * Used for tips, warnings and results, with the colours saying which.
 */
@Composable
fun MessageCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconTile(icon, container = iconContainer, content = iconTint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (body != null) {
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.85f))
                    }
                }
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

/** A numbered instruction. */
@Composable
fun StepRow(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * A fingerprint, large and monospaced, as a grid of four-character groups: the unit a person
 * compares, left to right and top to bottom. A screen reader reads it a character at a time,
 * because "seven F two A" is something a person can compare and "7F2A" read as a word is not.
 */
@Composable
fun FingerprintBlock(fingerprint: String, modifier: Modifier = Modifier, emphasis: Color = MaterialTheme.colorScheme.primary) {
    val spoken = fingerprint.filter { it.isLetterOrDigit() }.map { it.toString() }.joinToString(" ")
    val groups = fingerprint.split("·").map { it.trim() }.filter { it.isNotEmpty() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Three to a row, so that the second row lines up under the first.
        for (row in groups.chunked(GROUPS_PER_ROW)) {
            Row(Modifier.fillMaxWidth()) {
                for (group in row) {
                    Text(
                        text = group,
                        style = FingerprintStyle,
                        color = emphasis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
                // An empty cell keeps a short last row aligned with the columns above.
                repeat(GROUPS_PER_ROW - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

private const val GROUPS_PER_ROW = 3

/** An inline label with the look of a keyboard key, for naming keys in instructions. */
@Composable
fun KeyLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
            .semantics { role = Role.Image },
    )
}
