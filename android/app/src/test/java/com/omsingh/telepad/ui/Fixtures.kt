package com.omsingh.telepad.ui

import com.omsingh.telepad.connection.BluetoothAvailability
import com.omsingh.telepad.connection.BluetoothDeviceInfo
import com.omsingh.telepad.connection.Candidate
import com.omsingh.telepad.connection.PairingUiState
import com.omsingh.telepad.core.host.HostCapabilities
import com.omsingh.telepad.core.host.HostInfo
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.screens.devices.DevicesUiState
import com.omsingh.telepad.ui.screens.remote.RemoteUiState

/** Fixed data for screenshots, so that pictures only change when the design does. */
object Fixtures {
    const val NOW = 1_760_000_000_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    val desk = DeviceEntry("Desk PC", "192.168.1.20", 5000, "KEY-DESK", paired = true, online = true, lastConnectedMs = NOW - 2 * HOUR, os = HostOs.WINDOWS)
    val macbook = DeviceEntry("Maya’s MacBook Pro", "192.168.1.31", 5000, "KEY-MAC", paired = true, online = true, lastConnectedMs = NOW - 3 * DAY, os = HostOs.MACOS)
    val tower = DeviceEntry("Living room tower", "192.168.1.44", 5000, "KEY-TOWER", paired = true, online = false, lastConnectedMs = NOW - 12 * DAY, os = HostOs.LINUX)
    val guest = DeviceEntry("DESKTOP-9F2K1", "192.168.1.58", 5000, "KEY-GUEST", paired = false, online = true)

    /** A real-looking key (32 bytes, base64), so that fingerprints can be worked out from it. */
    private fun key(seed: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (seed * 31 + it * 7).toByte() })

    val pairedDevices = listOf(
        PairedDevice(key(1), "Desk PC", "192.168.1.20", 5000, lastConnectedMs = NOW - 2 * HOUR, osName = "WINDOWS"),
        PairedDevice(key(2), "Maya’s MacBook Pro", "192.168.1.31", 5000, lastConnectedMs = NOW - 3 * DAY, osName = "MACOS"),
        PairedDevice(key(3), "Living room tower", "192.168.1.44", 5000, lastConnectedMs = NOW - 12 * DAY, osName = "LINUX"),
    )

    const val FINGERPRINT = "7F2A · B9C1 · 4E08 · 91D3 · 0AC7"

    val connectedToDesk = ConnectionState.Connected("Desk PC", ConnectionState.Transport.WIFI, latencyMs = 4)

    val devicesList = DevicesUiState(
        devices = listOf(desk, macbook, tower, guest),
        connection = ConnectionState.Disconnected,
        searching = false,
    )

    val devicesConnected = devicesList.copy(connection = connectedToDesk, activeId = "KEY-DESK")

    val bluetoothDevices = listOf(
        BluetoothDeviceInfo("Desk PC", "00:1A:7D:DA:71:13"),
        BluetoothDeviceInfo("Office laptop", "F4:5C:89:AB:12:90"),
    )

    val verify = PairingUiState.Verify(Candidate("DESKTOP-9F2K1", "192.168.1.58", 5000, "KEY-GUEST"), FINGERPRINT)
    val keyChanged = PairingUiState.Verify(
        Candidate("Desk PC", "192.168.1.20", 5000, "KEY-NEW"),
        "C617 · FAA4 · DC8A · 5E21 · B7F0",
        replaces = PairedDevice("KEY-DESK", "Desk PC", "192.168.1.20", 5000),
    )

    val playing = NowPlayingState(
        title = "Midnight City",
        artist = "M83",
        sourceApp = "Spotify",
        isPlaying = false,
        positionMs = 94_000,
        durationMs = 244_000,
        sampledAtMs = NOW,
    )

    fun remote(
        os: HostOs = HostOs.WINDOWS,
        connection: ConnectionState = connectedToDesk,
        nowPlaying: Boolean = true,
        preferences: UserPreferences = UserPreferences(),
        pcClipboard: String? = null,
    ) = RemoteUiState(
        connection = connection,
        host = HostProfile(os),
        hostInfo = HostInfo(os, HostCapabilities(nowPlaying = nowPlaying, clipboard = true), "2.0.0"),
        preferences = preferences,
        nowPlaying = if (nowPlaying) playing else NowPlayingState.EMPTY,
        pcClipboard = pcClipboard,
    )

    val bluetoothReady = BluetoothAvailability.READY
}
