package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.FingerprintTextStyle
import com.omsingh.telepad.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    var trustedList by remember { mutableStateOf(viewModel.listTrustedHosts()) }
    var confirmingForgetAll by remember { mutableStateOf(false) }
    var confirmingResetIdentity by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = Dimens.ScreenHorizontalPadding,
                vertical = Dimens.ScreenVerticalPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            item {
                Text(
                    "Trusted PCs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (trustedList.isEmpty()) {
                item {
                    Text(
                        "You haven't paired with any PC yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(trustedList, key = { it.host }) { host ->
                    TrustedHostCard(
                        host = host.host,
                        fingerprint = host.fingerprint,
                        onForget = {
                            viewModel.forgetTrustedHost(host.host)
                            trustedList = viewModel.listTrustedHosts()
                        }
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { confirmingForgetAll = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text("Forget all trusted PCs")
                    }
                }
            }
            item {
                Text(
                    "Local identity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                Text(
                    "Resetting your local identity throws away your client key. " +
                    "All PCs will see you as a brand-new device on next connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(
                    onClick = { confirmingResetIdentity = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Text("Reset local identity")
                }
            }
        }
    }

    if (confirmingForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmingForgetAll = false },
            title = { Text("Forget all trusted PCs?") },
            text = { Text("Every PC will need to be re-verified on next connect. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetAllTrustedHosts()
                        trustedList = viewModel.listTrustedHosts()
                        confirmingForgetAll = false
                    },
                ) { Text("Forget all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForgetAll = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmingResetIdentity) {
        AlertDialog(
            onDismissRequest = { confirmingResetIdentity = false },
            title = { Text("Reset local identity?") },
            text = { Text("You'll be treated as a new device by every PC. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetLocalIdentity()
                        confirmingResetIdentity = false
                    },
                ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingResetIdentity = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrustedHostCard(host: String, fingerprint: String, onForget: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.CardInternalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(host, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    fingerprint,
                    style = FingerprintTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Delete, contentDescription = "Forget",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
