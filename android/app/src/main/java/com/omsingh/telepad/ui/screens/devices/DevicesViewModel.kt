package com.omsingh.telepad.ui.screens.devices

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.connection.BluetoothAvailability
import com.omsingh.telepad.connection.BluetoothDeviceInfo
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.connection.PairingUiState
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.trust.DeviceEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything the device list shows. */
@Immutable
data class DevicesUiState(
    val devices: List<DeviceEntry> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** The device the connection is to, to mark its row. */
    val activeId: String? = null,
    val searching: Boolean = false,
    val pairing: PairingUiState? = null,
    val bluetoothDevices: List<BluetoothDeviceInfo> = emptyList(),
    val bluetooth: BluetoothAvailability = BluetoothAvailability.UNKNOWN,
) {
    val paired: List<DeviceEntry> get() = devices.filter { it.paired }
    val nearby: List<DeviceEntry> get() = devices.filterNot { it.paired }
}

/** What the device list can ask for. */
interface DevicesActions {
    fun connect(entry: DeviceEntry)
    fun forget(entry: DeviceEntry)
    fun refresh()
    fun disconnect()
    fun retry()
    fun connectToAddress(host: String, port: Int)
    fun connectBluetooth(device: BluetoothDeviceInfo)
    fun refreshBluetooth()
    fun confirmPairing()
    fun dismissPairing()

    companion object {
        /** Does nothing; for previews and tests. */
        val None = object : DevicesActions {
            override fun connect(entry: DeviceEntry) = Unit
            override fun forget(entry: DeviceEntry) = Unit
            override fun refresh() = Unit
            override fun disconnect() = Unit
            override fun retry() = Unit
            override fun connectToAddress(host: String, port: Int) = Unit
            override fun connectBluetooth(device: BluetoothDeviceInfo) = Unit
            override fun refreshBluetooth() = Unit
            override fun confirmPairing() = Unit
            override fun dismissPairing() = Unit
        }
    }
}

class DevicesViewModel(application: Application) : AndroidViewModel(application), DevicesActions {

    private val manager = ConnectionManager.getInstance(application)

    private val connectionPart = combine(
        manager.devices, manager.connectionState, manager.activeId, manager.searching, manager.pairing,
    ) { devices, connection, activeId, searching, pairing ->
        DevicesUiState(
            devices = devices,
            connection = connection,
            activeId = activeId,
            searching = searching,
            pairing = pairing,
        )
    }

    val state: StateFlow<DevicesUiState> = combine(
        connectionPart, manager.bluetoothDevices, manager.bluetoothAvailability,
    ) { base, bluetoothDevices, availability ->
        base.copy(bluetoothDevices = bluetoothDevices, bluetooth = availability)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    /** Start looking for PCs while the list is on screen. */
    fun onShown() {
        manager.startDiscovery()
        manager.refreshBluetooth()
    }

    fun onHidden() = manager.stopDiscovery()

    override fun connect(entry: DeviceEntry) = manager.connect(entry)

    override fun forget(entry: DeviceEntry) {
        manager.pairedDevices.value.firstOrNull { it.publicKey == entry.publicKey }?.let(manager::forget)
    }

    override fun refresh() = manager.refreshDiscovery()
    override fun disconnect() = manager.disconnect()
    override fun retry() = manager.retry()
    override fun connectToAddress(host: String, port: Int) = manager.connectToAddress(host, port)
    override fun connectBluetooth(device: BluetoothDeviceInfo) = manager.connectBluetooth(device.address, device.name)
    override fun refreshBluetooth() = manager.refreshBluetooth()
    override fun confirmPairing() = manager.confirmPairing()
    override fun dismissPairing() = manager.dismissPairing()
}
