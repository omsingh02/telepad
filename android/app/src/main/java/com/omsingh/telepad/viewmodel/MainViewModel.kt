package com.omsingh.telepad.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.core.wifi.ServerInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level coordinator for Compose UI.
 *
 * Delegates state observation and transport actions to the singleton [ConnectionManager],
 * ensuring UI interactions remain simple while connection lifecycles survive Activity
 * destructions and backgrounding.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val cm = ConnectionManager.getInstance(application)

    val pairingStorePublic get() = cm.pairingStore
    val favoritesRepoPublic get() = cm.favoritesRepo
    val clipboardSync get() = cm.clipboardSync
    val nowPlaying get() = cm.nowPlaying

    // ── Public UI state ───────────────────────────────────────────────

    val connectionState: StateFlow<ConnectionState> = cm.connectionState
    val discoveredServers: StateFlow<List<ServerInfo>> = cm.discoveredServers
    val onlineFavorites: StateFlow<List<ServerInfo>> = cm.onlineFavorites
    val bondedDevices: StateFlow<List<BluetoothDevice>> = cm.bondedDevices
    val isScanning: StateFlow<Boolean> = cm.isScanning
    val nowPlayingState: StateFlow<NowPlayingState> = cm.nowPlayingState
    val pcClipboard: StateFlow<String?> = cm.pcClipboard
    val pendingPairingFor: StateFlow<ServerInfo?> = cm.pendingPairingFor

    // ── Permission & device list refresh ─────────────────────────────

    fun onBluetoothPermissionChanged(granted: Boolean) {
        cm.onBluetoothPermissionChanged(granted)
    }

    // ── Scanning control ─────────────────────────────────────────────

    fun startScanning() {
        cm.startScanning()
    }

    fun stopScanning() {
        cm.stopScanning()
    }

    // ── Wi-Fi connect with TOFU pairing flow ─────────────────────────

    fun requestConnect(server: ServerInfo) {
        cm.requestConnect(server)
    }

    fun completePairingAndConnect(server: ServerInfo, pubkeyBase64: String) {
        cm.completePairingAndConnect(server, pubkeyBase64)
    }

    fun cancelPairing() {
        cm.cancelPairing()
    }

    fun connectToBluetooth(address: String, name: String) {
        cm.connectToBluetooth(address, name)
    }

    fun disconnect() {
        cm.disconnect()
    }

    fun onInputEvent(event: InputEvent) {
        cm.onInputEvent(event)
    }

    fun pullClipboardFromPc() = cm.pullClipboardFromPc()
    fun pushClipboardToPc() = cm.pushClipboardToPc()
    fun copyPcClipboardToPhone() = cm.copyPcClipboardToPhone()
}
