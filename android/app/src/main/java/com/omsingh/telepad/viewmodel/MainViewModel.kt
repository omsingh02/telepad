package com.omsingh.telepad.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.core.bluetooth.BluetoothInputDispatcher
import com.omsingh.telepad.core.bluetooth.HidController
import com.omsingh.telepad.core.clipboard.ClipboardSync
import com.omsingh.telepad.core.crypto.PairingStore
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.media.NowPlayingClient
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.core.wifi.FavoriteServersRepository
import com.omsingh.telepad.core.wifi.MulticastDiscoveryService
import com.omsingh.telepad.core.wifi.NsdHelper
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.core.wifi.UdpDiscoveryScanner
import com.omsingh.telepad.core.wifi.WifiInputDispatcher
import com.omsingh.telepad.core.wifi.WifiPerformanceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Top-level coordinator. Owns the two transport dispatchers (Wi-Fi and BT)
 * and routes UI commands to whichever is currently connected.
 *
 * The UI binds to two consolidated flows:
 *  - [connectionState] = whichever transport has "more progress" wins, so the
 *    persistent status bar always reflects the *real* live state.
 *  - [discoveredServers] = de-duplicated union of the three Wi-Fi discovery
 *    channels (multicast, mDNS, manual subnet scan).
 *
 * Inbound packets from the Wi-Fi RX loop are demuxed here into the right
 * client (clipboard, now-playing) so transport details never leak upward.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // ── Stores & services ─────────────────────────────────────────────
    private val pairingStore = PairingStore.get(app)
    private val favoritesRepo = FavoriteServersRepository(app)
    val pairingStorePublic get() = pairingStore
    val favoritesRepoPublic get() = favoritesRepo

    private val multicast = MulticastDiscoveryService(app)
    private val scanner   = UdpDiscoveryScanner(app)
    private val nsd       = NsdHelper(app)
    private val wifiPerf  = WifiPerformanceManager(app)

    // ── Transports ────────────────────────────────────────────────────
    private val hidController = HidController(app)
    private val bluetoothDispatcher = BluetoothInputDispatcher(hidController)
    private val wifiDispatcher = WifiInputDispatcher(
        pairingStore = pairingStore,
        onServerPacket = ::handleServerPacket
    )

    // ── Higher-level clients ──────────────────────────────────────────
    val clipboardSync = ClipboardSync(app) { activeDispatcher }
    val nowPlaying = NowPlayingClient(sendQuery = wifiDispatcher::queryNowPlaying)

    private val _activeTransport = MutableStateFlow(ConnectionState.Transport.WIFI)
    private val activeDispatcher: InputDispatcher
        get() = when (_activeTransport.value) {
            ConnectionState.Transport.WIFI      -> wifiDispatcher
            ConnectionState.Transport.BLUETOOTH -> bluetoothDispatcher
        }

    // ── Public UI state ───────────────────────────────────────────────

    /**
     * Combined transport state. Connected > Connecting > Reconnecting > Error >
     * Disconnected. Whichever transport is "most progressed" wins so the
     * status bar always reflects something meaningful.
     */
    val connectionState: StateFlow<ConnectionState> = combine(
        wifiDispatcher.connectionState,
        bluetoothDispatcher.connectionState
    ) { w, b -> selectDominantState(w, b) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val discoveredServers: StateFlow<List<ServerInfo>> = combine(
        multicast.discoveredServers,
        scanner.discoveredServers,
        nsd.services
    ) { m, s, n -> (m + s + n).distinctBy { it.host } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val onlineFavorites: StateFlow<List<ServerInfo>> = favoritesRepo.onlineFavorites

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val nowPlayingState: StateFlow<NowPlayingState> = nowPlaying.state

    val pcClipboard: StateFlow<String?> = clipboardSync.latestFromPc

    /**
     * Whether the current Wi-Fi host needs pairing (we don't have a trusted
     * pubkey yet). Set transiently by [requestConnect]; pairing UI consumes.
     */
    private val _pendingPairingFor = MutableStateFlow<ServerInfo?>(null)
    val pendingPairingFor: StateFlow<ServerInfo?> = _pendingPairingFor.asStateFlow()

    init {
        multicast.startListening()
        nsd.startDiscovery()
        favoritesRepo.probeAllFavorites()
    }

    // ── Permission & device list refresh ─────────────────────────────

    fun onBluetoothPermissionChanged(granted: Boolean) {
        if (granted) refreshBondedDevices()
    }

    private fun refreshBondedDevices() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            _bondedDevices.value = adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            _bondedDevices.value = emptyList()
        }
    }

    // ── Scanning control ─────────────────────────────────────────────

    fun startScanning() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try { scanner.startScanning() } finally { _isScanning.value = false }
        }
    }

    fun stopScanning() {
        scanner.stopScanning()
        _isScanning.value = false
    }

    // ── Wi-Fi connect with TOFU pairing flow ─────────────────────────

    /**
     * UI entry point for "tap on a discovered PC". If we already trust the
     * host, connect silently. Otherwise stage the [ServerInfo] in
     * [pendingPairingFor] — the [PairingViewModel] then drives the verification
     * UI and calls [completePairingAndConnect] once the user approves.
     */
    fun requestConnect(server: ServerInfo) {
        val trusted = pairingStore.getTrustedPubkey(server.host)
        if (trusted != null) {
            // Already trusted — connect immediately.
            connectWifi(server.copy(pubkeyBase64 = trusted))
        } else {
            // Surface to the pairing flow.
            _pendingPairingFor.value = server
        }
    }

    /**
     * Called by [PairingViewModel] after the user confirms the fingerprint.
     */
    fun completePairingAndConnect(server: ServerInfo, pubkeyBase64: String) {
        pairingStore.trust(server.host, pubkeyBase64)
        _pendingPairingFor.value = null
        connectWifi(server.copy(pubkeyBase64 = pubkeyBase64))
    }

    fun cancelPairing() {
        _pendingPairingFor.value = null
    }

    private fun connectWifi(server: ServerInfo) {
        val pubkey = server.pubkeyBase64 ?: return
        _activeTransport.value = ConnectionState.Transport.WIFI
        viewModelScope.launch {
            wifiPerf.acquire()
            wifiDispatcher.connect(ConnectionTarget.Wifi(server.host, server.port, pubkey))
            if (wifiDispatcher.connectionState.value is ConnectionState.Connected) {
                favoritesRepo.saveFavorite(server)
                nowPlaying.start()
            }
        }
    }

    fun connectToBluetooth(address: String, name: String) {
        _activeTransport.value = ConnectionState.Transport.BLUETOOTH
        hidController.init(
            onReady = {
                viewModelScope.launch {
                    bluetoothDispatcher.connect(ConnectionTarget.Bluetooth(address, name))
                }
            },
            onError = { /* surfaced via HidController.state */ }
        )
    }

    fun disconnect() {
        wifiDispatcher.disconnect()
        bluetoothDispatcher.disconnect()
        nowPlaying.stop()
        clipboardSync.clearLatest()
        wifiPerf.release()
    }

    /**
     * Forward a UI-generated event to whichever dispatcher is active.
     * Called from every touch handler and quick-action button.
     */
    fun onInputEvent(event: InputEvent) {
        activeDispatcher.dispatch(event)
    }

    /** UI helpers for the keyboard's "Pasted from PC" pill. */
    fun pullClipboardFromPc() = clipboardSync.pullFromPc()
    fun pushClipboardToPc() = clipboardSync.pushToPc()
    fun copyPcClipboardToPhone() = clipboardSync.copyPcClipboardToPhone()

    override fun onCleared() {
        multicast.stopListening()
        nsd.stopDiscovery()
        scanner.stopScanning()
        nowPlaying.shutdown()
        hidController.cleanup()
        wifiDispatcher.shutdown()
        wifiPerf.release()
        super.onCleared()
    }

    // ── Inbound packet demux (Wi-Fi → clipboard / now-playing) ───────

    /**
     * Wire format for server → client packets (matches protocol.h):
     *  - `[0x80] [u16 len] [utf8 clipboard data]`
     *  - `[0x81] [packed NowPlayingState bytes]`
     */
    private fun handleServerPacket(buf: ByteArray, len: Int) {
        if (len < 1) return
        when (buf[0]) {
            MSG_CLIPBOARD_DATA -> {
                if (len < 3) return
                val textLen = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)
                if (3 + textLen > len) return
                val text = String(buf, 3, textLen, Charsets.UTF_8)
                clipboardSync.onClipboardReceived(text)
            }
            MSG_NOW_PLAYING -> {
                parseNowPlaying(buf, len)?.let { nowPlaying.onUpdate(it) }
            }
        }
    }

    /**
     * Server packed format:
     *  - byte 0:     msg type (0x81)
     *  - byte 1:     flags: bit0 = isPlaying
     *  - bytes 2-9:  i64 positionMs
     *  - bytes 10-17:i64 durationMs (negative = unknown)
     *  - byte 18:    title length (u8)
     *  - byte 19..:  title (utf-8)
     *  - then:       artist length (u8), artist bytes
     *  - then:       album  length (u8), album bytes
     *  - then:       source length (u8), source bytes
     */
    private fun parseNowPlaying(buf: ByteArray, len: Int): NowPlayingState? {
        if (len < 19) return null
        val bb = ByteBuffer.wrap(buf, 1, len - 1).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.get().toInt() and 0xFF
        val pos   = bb.long
        val dur   = bb.long

        fun readStr(): String? {
            if (bb.remaining() < 1) return null
            val l = bb.get().toInt() and 0xFF
            if (l == 0) return null
            if (bb.remaining() < l) return null
            val arr = ByteArray(l)
            bb.get(arr)
            return String(arr, Charsets.UTF_8)
        }

        return NowPlayingState(
            title      = readStr(),
            artist     = readStr(),
            album      = readStr(),
            sourceApp  = readStr(),
            isPlaying  = (flags and 0x01) != 0,
            positionMs = pos.takeIf { it >= 0 },
            durationMs = dur.takeIf { it >= 0 },
            sampledAtMs = System.currentTimeMillis()
        )
    }

    // ── State resolution ─────────────────────────────────────────────

    private fun selectDominantState(
        wifi: ConnectionState, bt: ConnectionState
    ): ConnectionState {
        // Connected > Reconnecting > Connecting > Error > Disconnected.
        if (wifi is ConnectionState.Connected) return wifi
        if (bt   is ConnectionState.Connected) return bt
        if (wifi is ConnectionState.Reconnecting || bt is ConnectionState.Reconnecting)
            return ConnectionState.Reconnecting
        if (wifi is ConnectionState.Connecting || bt is ConnectionState.Connecting)
            return ConnectionState.Connecting
        if (wifi is ConnectionState.Error) return wifi
        if (bt   is ConnectionState.Error) return bt
        return ConnectionState.Disconnected
    }

    private companion object {
        const val MSG_CLIPBOARD_DATA: Byte = 0x80.toByte()
        const val MSG_NOW_PLAYING: Byte    = 0x81.toByte()
    }
}
