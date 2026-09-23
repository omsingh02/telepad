package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.components.BluetoothDeviceCard
import com.omsingh.telepad.ui.components.EmptyStateCard
import com.omsingh.telepad.ui.components.ManualConnectDialog
import com.omsingh.telepad.ui.components.PermissionRationaleCard
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Add-a-device flow, separated from Home.
 *
 * Two tabs: Wi-Fi (manual scan + manual IP), Bluetooth (paired devices list).
 *
 * **Why a separate screen?** Home's job is "reconnect fast." This screen's job
 * is "set up a new connection." Mixing them produced the visual hunt the audit
 * flagged. Now the user enters here only when they explicitly want to add
 * something new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    viewModel: MainViewModel,
    bluetoothGranted: Boolean,
    onRequestBluetooth: () -> Unit,
    onBack: () -> Unit,
    onPairingNeeded: (ServerInfo) -> Unit,
) {
    val discovered by viewModel.discoveredServers.collectAsState()
    val bonded by viewModel.bondedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val pendingPairingFor by viewModel.pendingPairingFor.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showManualDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingPairingFor) {
        pendingPairingFor?.let { onPairingNeeded(it) }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            TopAppBar(
                title = { Text("Add a device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .padding(horizontal = Dimens.ScreenHorizontalPadding)
                    .clip(RoundedCornerShape(Dimens.ButtonCornerRadius)),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Wi-Fi") },
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Bluetooth") },
                    icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> WifiTab(
                    discovered = discovered,
                    isScanning = isScanning,
                    onConnect = { viewModel.requestConnect(it) },
                    onStartScan = { viewModel.startScanning() },
                    onStopScan = { viewModel.stopScanning() },
                    onManualClick = { showManualDialog = true },
                )
                else -> BluetoothTab(
                    bluetoothGranted = bluetoothGranted,
                    bonded = bonded,
                    onRequestPermission = onRequestBluetooth,
                    onConnect = { dev ->
                        try {
                            viewModel.connectToBluetooth(dev.address, dev.name ?: dev.address)
                        } catch (_: SecurityException) { /* surfaced via state */ }
                    }
                )
            }
        }
    }

    if (showManualDialog) {
        ManualConnectDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { host, port ->
                showManualDialog = false
                viewModel.requestConnect(ServerInfo(name = host, host = host, port = port))
            }
        )
    }
}

@Composable
private fun WifiTab(
    discovered: List<ServerInfo>,
    isScanning: Boolean,
    onConnect: (ServerInfo) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onManualClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Dimens.ScreenHorizontalPadding,
            vertical = Dimens.ScreenVerticalPadding
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                if (isScanning) {
                    FilledTonalButton(
                        onClick = onStopScan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                    ) {
                        Row {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("  Stop")
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onStartScan,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("  Search network")
                    }
                }
                FilledTonalButton(
                    onClick = onManualClick,
                    shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                ) {
                    Text("Enter IP")
                }
            }
        }

        if (discovered.isEmpty() && !isScanning) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.Wifi,
                    title = "No PCs visible yet",
                    body = "Telepad listens automatically — your PC should appear here when ready. Tap 'Search network' to actively scan, or 'Enter IP' to connect manually.",
                )
            }
        } else {
            items(discovered, key = { "add:${it.host}" }) { server ->
                com.omsingh.telepad.ui.components.ServerCard(
                    server = server,
                    isOnline = true,
                    onClick = { onConnect(server) }
                )
            }
        }
    }
}

@Composable
private fun BluetoothTab(
    bluetoothGranted: Boolean,
    bonded: List<android.bluetooth.BluetoothDevice>,
    onRequestPermission: () -> Unit,
    onConnect: (android.bluetooth.BluetoothDevice) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Dimens.ScreenHorizontalPadding,
            vertical = Dimens.ScreenVerticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        if (!bluetoothGranted) {
            item {
                PermissionRationaleCard(
                    title = "Bluetooth permission needed",
                    body = "Telepad needs Bluetooth access to connect to your PC as a wireless keyboard and mouse.",
                    onGrantClick = onRequestPermission,
                )
            }
        } else if (bonded.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.PhonelinkOff,
                    title = "No paired Bluetooth devices",
                    body = "Pair your PC or smart TV in Android Bluetooth settings first, then return here.",
                )
            }
        } else {
            items(bonded, key = { it.address }) { dev ->
                val name = try { dev.name } catch (_: SecurityException) { null } ?: dev.address
                BluetoothDeviceCard(
                    name = name,
                    address = dev.address,
                    onClick = { onConnect(dev) },
                )
            }
        }
    }
}
