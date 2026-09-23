package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.components.EmptyStateCard
import com.omsingh.telepad.ui.components.HeroStatusCard
import com.omsingh.telepad.ui.components.ServerCard
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Immutable state model for the Home dashboard.
 */
data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val favorites: List<ServerInfo> = emptyList(),
    val discoveredServers: List<ServerInfo> = emptyList(),
    val trustedHosts: Set<String> = emptySet(),
)

/**
 * Route composable: collects state from [MainViewModel] and forwards actions to [HomeScreen].
 */
@Composable
fun HomeRoute(
    viewModel: MainViewModel,
    onNavigateToTouchpad: () -> Unit,
    onNavigateToAddDevice: () -> Unit,
    onPairingNeeded: (ServerInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val favorites by viewModel.favoritesRepoPublic.allFavorites.collectAsState(initial = emptyList())
    val pendingPairingFor by viewModel.pendingPairingFor.collectAsState()
    val pairingStore = viewModel.pairingStorePublic

    // Calculate trusted hosts from pairing store
    val allServers = remember(favorites, discoveredServers) {
        (favorites + discoveredServers).distinctBy { it.host }
    }
    val trustedHosts = remember(allServers) {
        allServers.filter { pairingStore.getTrustedPubkey(it.host) != null }
            .map { it.host }
            .toSet()
    }

    LaunchedEffect(pendingPairingFor) {
        pendingPairingFor?.let { onPairingNeeded(it) }
    }

    HomeScreen(
        uiState = HomeUiState(
            connectionState = connectionState,
            favorites = favorites,
            discoveredServers = discoveredServers,
            trustedHosts = trustedHosts,
        ),
        onConnectServer = viewModel::requestConnect,
        onDisconnect = viewModel::disconnect,
        onNavigateToTouchpad = onNavigateToTouchpad,
        onNavigateToAddDevice = onNavigateToAddDevice,
        modifier = modifier,
    )
}

/**
 * Pure stateless Home dashboard composable.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onConnectServer: (ServerInfo) -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToTouchpad: () -> Unit,
    onNavigateToAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            contentPadding = PaddingValues(top = Dimens.ScreenVerticalPadding, bottom = 88.dp),
        ) {
            item {
                HeroStatusCard(
                    state = uiState.connectionState,
                    onDisconnect = onDisconnect,
                    onLaunchRemote = onNavigateToTouchpad,
                )
            }

            if (uiState.favorites.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(uiState.favorites, key = { "fav:${it.host}" }) { server ->
                    ServerCard(
                        server = server,
                        isTrusted = server.host in uiState.trustedHosts,
                        isOnline = uiState.discoveredServers.any { it.host == server.host },
                        onClick = { onConnectServer(server) },
                    )
                }
            }

            val discoveredOnly = uiState.discoveredServers.filterNot { d ->
                uiState.favorites.any { it.host == d.host }
            }
            if (discoveredOnly.isNotEmpty()) {
                item { SectionHeader("Discovered") }
                items(discoveredOnly, key = { "disc:${it.host}" }) { server ->
                    ServerCard(
                        server = server,
                        isTrusted = server.host in uiState.trustedHosts,
                        isOnline = true,
                        onClick = { onConnectServer(server) },
                    )
                }
            }

            if (uiState.favorites.isEmpty() && discoveredOnly.isEmpty()) {
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

        ExtendedFloatingActionButton(
            onClick = onNavigateToAddDevice,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add a device") },
            shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.ScreenHorizontalPadding),
        )
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
