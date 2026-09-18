package com.omsingh.telepad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.ui.theme.Dimens

/**
 * Just-in-time permission rationale.
 *
 * Shown only when the user lands on a screen that *actually needs* the
 * permission and we don't have it yet. Avoids the upfront permission-batch
 * pattern that conditions users to tap "deny" reflexively.
 */
@Composable
fun PermissionRationaleCard(
    title: String,
    body: String,
    grantLabel: String = "Grant permission",
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardInternalPaddingLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Dimens.ItemSpacing))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onGrantClick,
                shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
            ) {
                Text(grantLabel)
            }
        }
    }
}
