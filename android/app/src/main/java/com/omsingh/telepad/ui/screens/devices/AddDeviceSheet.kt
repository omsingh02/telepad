package com.omsingh.telepad.ui.screens.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.connection.BluetoothAvailability
import com.omsingh.telepad.connection.BluetoothDeviceInfo
import com.omsingh.telepad.core.wifi.AddressInput
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.ui.components.IconTile
import com.omsingh.telepad.ui.components.StepRow
import com.omsingh.telepad.ui.theme.Spacing

enum class AddTab { ADDRESS, BLUETOOTH }

/** Connect to a PC that discovery cannot see: by its address, or over Bluetooth. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(
    initialTab: AddTab,
    bluetoothDevices: List<BluetoothDeviceInfo>,
    bluetooth: BluetoothAvailability,
    onConnectAddress: (host: String, port: Int) -> Unit,
    onConnectBluetooth: (BluetoothDeviceInfo) -> Unit,
    onDismiss: () -> Unit,
    onScan: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AddDeviceContent(
            tab = tab,
            onTabChange = { tab = it },
            bluetoothDevices = bluetoothDevices,
            bluetooth = bluetooth,
            onConnectAddress = onConnectAddress,
            onConnectBluetooth = onConnectBluetooth,
            onScan = onScan,
        )
    }
}

/** The inside of the add-device sheet, apart from the sheet itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceContent(
    tab: AddTab,
    onTabChange: (AddTab) -> Unit,
    bluetoothDevices: List<BluetoothDeviceInfo>,
    bluetooth: BluetoothAvailability,
    onConnectAddress: (host: String, port: Int) -> Unit,
    onConnectBluetooth: (BluetoothDeviceInfo) -> Unit,
    onScan: () -> Unit = {},
) {
    Column(
        Modifier
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Text(
            stringResource(R.string.add_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        )
        // The way most people should take: point the camera at the PC's screen.
        Column(
            Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                Text(stringResource(R.string.add_scan))
            }
            Text(
                stringResource(R.string.add_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            Tab(selected = tab == AddTab.ADDRESS, onClick = { onTabChange(AddTab.ADDRESS) }, text = { Text(stringResource(R.string.add_tab_address)) })
            Tab(selected = tab == AddTab.BLUETOOTH, onClick = { onTabChange(AddTab.BLUETOOTH) }, text = { Text(stringResource(R.string.add_tab_bluetooth)) })
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            when (tab) {
                AddTab.ADDRESS -> AddressTab(onConnectAddress)
                AddTab.BLUETOOTH -> BluetoothTab(bluetoothDevices, bluetooth, onConnectBluetooth)
            }
        }
    }
}

@Composable
private fun AddressTab(onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var hostTouched by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    val parsed = AddressInput.parse(host, port)
    val hostError = parsed is AddressInput.Result.InvalidHost && (hostTouched || attempted) && host.isNotEmpty() || (attempted && host.isEmpty())
    val portError = parsed is AddressInput.Result.InvalidPort

    fun submit() {
        attempted = true
        if (parsed is AddressInput.Result.Valid) {
            focus.clearFocus()
            onConnect(parsed.host, parsed.port)
        }
    }

    Text(stringResource(R.string.add_address_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.add_address_label)) },
        singleLine = true,
        isError = hostError,
        supportingText = if (hostError) ({ Text(stringResource(R.string.add_address_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused && host.isNotEmpty()) hostTouched = true },
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.add_port_label)) },
        placeholder = { Text("5000") },
        singleLine = true,
        isError = portError,
        supportingText = if (portError) ({ Text(stringResource(R.string.add_port_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = ::submit,
        enabled = parsed is AddressInput.Result.Valid,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.add_connect)) }
}

@Composable
private fun BluetoothTab(
    devices: List<BluetoothDeviceInfo>,
    availability: BluetoothAvailability,
    onConnect: (BluetoothDeviceInfo) -> Unit,
) {
    val platform = LocalPlatformActions.current

    Text(stringResource(R.string.add_bt_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

    when (availability) {
        BluetoothAvailability.UNSUPPORTED ->
            Text(stringResource(R.string.add_bt_unsupported), style = MaterialTheme.typography.bodyLarge)

        BluetoothAvailability.NEEDS_PERMISSION -> {
            Text(stringResource(R.string.add_bt_permission_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = platform::requestBluetoothPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_bt_grant))
            }
        }

        BluetoothAvailability.OFF ->
            Button(onClick = platform::enableBluetooth, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_bt_enable))
            }

        BluetoothAvailability.UNKNOWN, BluetoothAvailability.READY -> {
            StepRow(1, stringResource(R.string.add_bt_step_visible))
            StepRow(2, stringResource(R.string.add_bt_step_pair))
            StepRow(3, stringResource(R.string.add_bt_step_pick))
            OutlinedButton(onClick = platform::openBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                Text(stringResource(R.string.add_bt_open_settings))
            }
            if (devices.isEmpty()) {
                Text(stringResource(R.string.add_bt_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.add_bt_choose), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Column {
                    for (device in devices) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { onConnect(device) }
                                .padding(vertical = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(Icons.Rounded.Bluetooth)
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.add_bt_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
