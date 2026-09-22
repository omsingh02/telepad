package com.omsingh.telepad.connection

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-wide singleton that owns the lifecycle of active network connections,
 * transports (Wi-Fi and Bluetooth HID), discovery services, and media/clipboard sync.
 *
 * Extracting this from MainViewModel ensures:
 *  1. Connections survive activity recreation, navigation, and backgrounding.
 *  2. TelepadConnectionService can directly control connections (e.g. Disconnect).
 *  3. MainViewModel remains a lightweight UI-binding delegate.
 */
class ConnectionManager private constructor(private val app: Application) {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── Stores & services ─────────────────────────────────────────────
    val pairingStore = PairingStore.get(app)
    val favoritesRepo = FavoriteServersRepository(app)

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
    val activeTransport: StateFlow<ConnectionState.Transport> = _activeTransport.asStateFlow()

    val activeDispatcher: InputDispatcher
        get() = when (_activeTransport.value) {
            ConnectionState.Transport.WIFI      -> wifiDispatcher
            ConnectionState.Transport.BLUETOOTH -> bluetoothDispatcher
        }

    // ── Public UI state ───────────────────────────────────────────────

    val connectionState: StateFlow<ConnectionState> = combine(
        wifiDispatcher.connectionState,
        bluetoothDispatcher.connectionState
    ) { w, b -> selectDominantState(w, b) }
        .stateIn(managerScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val discoveredServers: StateFlow<List<ServerInfo>> = combine(
        multicast.discoveredServers,
        scanner.discoveredServers,
        nsd.services
    ) { m, s, n -> (m + s + n).distinctBy { it.host } }
        .stateIn(managerScope, SharingStarted.Eagerly, emptyList())

    val onlineFavorites: StateFlow<List<ServerInfo>> = favoritesRepo.onlineFavorites

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val nowPlayingState: StateFlow<NowPlayingState> = nowPlaying.state

    val pcClipboard: StateFlow<String?> = clipboardSync.latestFromPc

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

    fun refreshBondedDevices() {
        try {
            val adapter = app.getSystemService(
                BluetoothManager::class.java
            )?.adapter ?: return
            _bondedDevices.value = adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            _bondedDevices.value = emptyList()
        }
    }

    // ── Scanning control ─────────────────────────────────────────────

    fun startScanning() {
        if (_isScanning.value) return
        _isScanning.value = true
        managerScope.launch {
            try { scanner.startScanning() } finally { _isScanning.value = false }
        }
    }

    fun stopScanning() {
        scanner.stopScanning()
        _isScanning.value = false
    }

    // ── Wi-Fi connect with TOFU pairing flow ─────────────────────────

    fun requestConnect(server: ServerInfo) {
        val trusted = pairingStore.getTrustedPubkey(server.host)
        if (trusted != null) {
            // Already trusted by host — connect immediately.
            connectWifi(server.copy(pubkeyBase64 = trusted))
        } else if (server.pubkeyBase64 != null &&
            pairingStore.getTrustedPubkeyByFingerprint(server.pubkeyBase64) != null
        ) {
            // Server changed IP but same crypto identity — update host mapping and connect.
            pairingStore.trust(server.host, server.pubkeyBase64)
            connectWifi(server)
        } else {
            // Surface to the pairing flow.
            _pendingPairingFor.value = server
        }
    }

    fun completePairingAndConnect(server: ServerInfo, pubkeyBase64: String) {
        pairingStore.trust(server.host, pubkeyBase64)
        _pendingPairingFor.value = null
        connectWifi(server.copy(pubkeyBase64 = pubkeyBase64))
    }

    fun cancelPairing() {
        _pendingPairingFor.value = null
    }

    fun connectWifi(server: ServerInfo) {
        val pubkey = server.pubkeyBase64 ?: return
        _activeTransport.value = ConnectionState.Transport.WIFI
        managerScope.launch {
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
                managerScope.launch {
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

    fun onInputEvent(event: InputEvent) {
        activeDispatcher.dispatch(event)
    }

    fun pullClipboardFromPc() = clipboardSync.pullFromPc()
    fun pushClipboardToPc() = clipboardSync.pushToPc()
    fun copyPcClipboardToPhone() = clipboardSync.copyPcClipboardToPhone()

    fun shutdown() {
        multicast.stopListening()
        nsd.stopDiscovery()
        scanner.stopScanning()
        nowPlaying.shutdown()
        hidController.cleanup()
        wifiDispatcher.shutdown()
        wifiPerf.release()
    }

    // ── Inbound packet demux (Wi-Fi → clipboard / now-playing) ───────

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

    companion object {
        private const val MSG_CLIPBOARD_DATA: Byte = 0x80.toByte()
        private const val MSG_NOW_PLAYING: Byte    = 0x81.toByte()

        @Volatile private var INSTANCE: ConnectionManager? = null

        fun getInstance(app: Application): ConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionManager(app).also { INSTANCE = it }
            }
        }
    }
}
