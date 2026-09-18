package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.components.EmptyStateCard
import com.omsingh.telepad.ui.components.HeroStatusCard
import com.omsingh.telepad.ui.components.ServerCard
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Home — the "where am I, what can I do" screen.
 *
 * Visual hierarchy (top to bottom):
 *  1. **Hero status card** — biggest, most prominent. "Are you connected?"
 *  2. **Recent / favorites** — quick reconnect to PCs you've used before.
 *  3. **Discovered on network** — anything else multicast/mDNS finds live.
 *  4. **+ Add a device** — FAB to enter manual IP or pair via Bluetooth.
 *
 * No tabs, no in-line manual IP form, no scan controls. Discovery runs
 * passively in the background; manual subnet scan and BT pairing are now
 * lifted into the dedicated AddDeviceScreen so they don't compete for
 * attention here.
 *
 * Returning-user flow: open app → tap last PC at top → connected. One tap.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToTouchpad: () -> Unit,
    onNavigateToAddDevice: () -> Unit,
    onPairingNeeded: (ServerInfo) -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val favorites by viewModel.favoritesRepoPublic.allFavorites
        .collectAsState(initial = emptyList())
    val pendingPairingFor by viewModel.pendingPairingFor.collectAsState()
    val pairingStore = viewModel.pairingStorePublic

    // Funnel the pending pairing target up to the navigation host.
    val pendingTarget = pendingPairingFor
    if (pendingTarget != null) {
        onPairingNeeded(pendingTarget)
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAddDevice,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add a device") },
                shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            contentPadding = PaddingValues(top = Dimens.ScreenVerticalPadding, bottom = 88.dp),
        ) {
            item {
                HeroStatusCard(
                    state = connectionState,
                    onDisconnect = { viewModel.disconnect() },
                    onLaunchRemote = onNavigateToTouchpad,
                )
            }

            if (favorites.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(favorites, key = { "fav:${it.host}" }) { server ->
                    val trusted = pairingStore.getTrustedPubkey(server.host) != null
                    ServerCard(
                        server = server,
                        isTrusted = trusted,
                        isOnline = discoveredServers.any { it.host == server.host },
                        onClick = { viewModel.requestConnect(server) },
                    )
                }
            }

            val discoveredOnly = discoveredServers.filterNot { d ->
                favorites.any { it.host == d.host }
            }
            if (discoveredOnly.isNotEmpty()) {
                item { SectionHeader("Discovered") }
                items(discoveredOnly, key = { "disc:${it.host}" }) { server ->
                    val trusted = pairingStore.getTrustedPubkey(server.host) != null
                    ServerCard(
                        server = server,
                        isTrusted = trusted,
                        isOnline = true,
                        onClick = { viewModel.requestConnect(server) },
                    )
                }
            }

            if (favorites.isEmpty() && discoveredOnly.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.SettingsRemote,
                        title = "Waiting for a PC",
                        body = "Make sure the Telepad app is running on your PC and you're on the same Wi-Fi network. We'll find it automatically.",
                        actionLabel = "Add manually",
                        onActionClick = onNavigateToAddDevice,
                    )
                }
            }

            item { Spacer(Modifier.height(Dimens.ScreenVerticalPadding)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}
