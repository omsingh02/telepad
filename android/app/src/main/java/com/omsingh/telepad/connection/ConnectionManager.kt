package com.omsingh.telepad.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.omsingh.telepad.core.bluetooth.BluetoothInputDispatcher
import com.omsingh.telepad.core.bluetooth.HidController
import com.omsingh.telepad.core.clipboard.ClipboardSync
import com.omsingh.telepad.core.crypto.Fingerprint
import com.omsingh.telepad.core.crypto.PairingStore
import com.omsingh.telepad.core.trust.LazyTrustStore
import com.omsingh.telepad.core.trust.TrustStore
import com.omsingh.telepad.core.wifi.DeviceDiscovery
import com.omsingh.telepad.core.host.HostInfo
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.media.NowPlayingClient
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.core.trust.DeviceList
import com.omsingh.telepad.core.trust.LegacyFavorites
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.core.trust.TrustDecision
import com.omsingh.telepad.core.trust.TrustResolver
import com.omsingh.telepad.core.wifi.DiscoveryService
import com.omsingh.telepad.core.wifi.LanNetworks
import com.omsingh.telepad.core.wifi.PairingIntro
import com.omsingh.telepad.core.wifi.PairingInvite
import com.omsingh.telepad.core.wifi.ServerMessage
import com.omsingh.telepad.core.wifi.WifiInputDispatcher
import com.omsingh.telepad.core.wifi.WifiPerformanceManager
import com.omsingh.telepad.service.TelepadConnectionService
import com.omsingh.telepad.settings.HostOsChoice
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.settings.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

/**
 * The one place that knows about connections: which PC (or Bluetooth host) the phone
 * is talking to, how, and how that is going.
 *
 * It lives for the whole process, so a connection survives the screen rotating, the
 * user moving between screens, and the app going to the background. The UI never touches
 * a socket: it observes the flows below and calls the functions.
 *
 *  - **Who to trust** is decided here, from what the PC proves and what is already paired
 *    ([TrustResolver]). A PC is only remembered after the user has confirmed its
 *    fingerprint *and* the encrypted connection has actually been established.
 *  - **Staying connected** is the Wi-Fi dispatcher's job ([WifiInputDispatcher]); this class
 *    nudges it when the network changes or the app returns to the foreground.
 *  - **The device list** merges what is paired with what discovery sees ([DeviceList]).
 */
class ConnectionManager internal constructor(
    private val app: Application,
    private val store: TrustStore,
    private val discovery: DeviceDiscovery,
    private val lan: LanNetworks = LanNetworks(app),
    wifiTiming: WifiInputDispatcher.Timing = WifiInputDispatcher.Timing(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val settings = SettingsRepository(app)
    private val hid = HidController(app)
    private val wifiPerformance = WifiPerformanceManager(app)

    private val prefs: StateFlow<UserPreferences> =
        settings.preferences.stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    // ── Transports ───────────────────────────────────────────────────

    private val _activeTransport = MutableStateFlow(ConnectionState.Transport.WIFI)
    val activeTransport: StateFlow<ConnectionState.Transport> = _activeTransport.asStateFlow()

    private val wifi = WifiInputDispatcher(
        identity = store,
        prepareSocket = lan::bind,
        timing = wifiTiming,
        onServerMessage = { onServerMessage(it) },
    )
    private val bluetooth = BluetoothInputDispatcher(hid, host = { hostProfile.value })

    private val activeDispatcher: InputDispatcher
        get() = if (_activeTransport.value == ConnectionState.Transport.WIFI) wifi else bluetooth

    // ── Observable state ─────────────────────────────────────────────

    val connectionState: StateFlow<ConnectionState> = combine(
        wifi.connectionState, bluetooth.connectionState, _activeTransport,
    ) { w, b, active -> if (active == ConnectionState.Transport.WIFI) w else b }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /** What the connected PC says about itself. Unknown over Bluetooth and for older servers. */
    val hostInfo: StateFlow<HostInfo> = wifi.hostInfo

    /**
     * The operating system the PC runs, as far as it is known: what the PC reported, or
     * else what the user said in settings. Drives key names and shortcuts.
     */
    val hostProfile: StateFlow<HostProfile> = combine(wifi.hostInfo, _activeTransport, prefs) { info, transport, p ->
        val reported = info.os.takeIf { transport == ConnectionState.Transport.WIFI && it != HostOs.UNKNOWN }
        HostProfile(reported ?: p.assumedHostOs.toHostOs())
    }.stateIn(scope, SharingStarted.Eagerly, HostProfile(HostOs.UNKNOWN))

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    /** Paired PCs and PCs found on the network, merged. */
    val devices: StateFlow<List<DeviceEntry>> = combine(_paired, discovery.servers) { paired, found ->
        DeviceList.build(paired, found)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True for the first moments of a search, while the list may still be filling. */
    val searching: StateFlow<Boolean> = discovery.searching

    private val _activeId = MutableStateFlow<String?>(null)

    /**
     * Which device the connection is to: the PC's public key, or a Bluetooth address. It matches
     * [DeviceEntry.id] for PCs, so the list can mark the row that is connecting or connected.
     */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUiState?>(null)
    val pairing: StateFlow<PairingUiState?> = _pairing.asStateFlow()

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDeviceInfo>> = _bluetoothDevices.asStateFlow()

    private val _bluetoothReady = MutableStateFlow(BluetoothAvailability.UNKNOWN)
    val bluetoothAvailability: StateFlow<BluetoothAvailability> = _bluetoothReady.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    val clipboard = ClipboardSync(app, enabled = { prefs.value.clipboardSync }) { activeDispatcher }
    val nowPlaying = NowPlayingClient(sendQuery = wifi::queryNowPlaying)
    val nowPlayingState: StateFlow<NowPlayingState> = nowPlaying.state
    val pcClipboard: StateFlow<String?> = clipboard.latestFromPc

    // ── Internal bookkeeping ─────────────────────────────────────────

    private var connectJob: Job? = null
    private var retry: (() -> Unit)? = null
    private var discoveryWanted = false
    private var autoConnectDone = false
    private var userDisconnected = false

    init {
        scope.launch(Dispatchers.IO) {
            if (store.needsMigration) {
                store.migrateLegacy(LegacyFavorites.read(app))
                LegacyFavorites.delete(app)
            }
            _paired.value = store.all()
        }
        observeConnectionState()
        observeAutoConnect()
        observeNetwork()
        scope.launch { bluetooth.untypeableCharacters.collect { _notices.tryEmit(Notice.UntypeableText(it)) } }
    }

    // ── Discovery ────────────────────────────────────────────────────

    /** Start looking for PCs. Call while the device list is on screen. */
    fun startDiscovery() {
        discoveryWanted = true
        discovery.start()
    }

    fun stopDiscovery() {
        discoveryWanted = false
        discovery.stop()
    }

    /** Look again right now, including a sweep of the local subnet. */
    fun refreshDiscovery() = discovery.refresh()

    // ── Connecting over Wi-Fi ────────────────────────────────────────

    /**
     * Connect to a PC from the device list. A paired PC connects straight away; a new
     * one first goes through key verification.
     */
    fun connect(entry: DeviceEntry) {
        retry = { connect(entry) }
        beginAttempt()
        connectJob = scope.launch {
            val key = entry.publicKey ?: run {
                _pairing.value = PairingUiState.Contacting(entry.name)
                val fetched = PairingIntro.fetchPublicKey(entry.host, entry.port, prepare = lan::bind)
                if (fetched == null) {
                    _pairing.value = PairingUiState.Failed(entry.name, FailureReason.UNREACHABLE)
                    return@launch
                }
                Base64.getEncoder().encodeToString(fetched)
            }
            decide(entry.name, entry.host, entry.port, key)
        }
    }

    /** Connect to an address typed in by hand, asking the PC who it is first. */
    fun connectToAddress(host: String, port: Int) {
        retry = { connectToAddress(host, port) }
        beginAttempt()
        connectJob = scope.launch {
            _pairing.value = PairingUiState.Contacting(host)
            val identity = PairingIntro.identify(host, port, prepare = lan::bind)
            if (identity == null) {
                _pairing.value = PairingUiState.Failed(host, FailureReason.UNREACHABLE)
                return@launch
            }
            decide(identity.name ?: host, host, port, identity.publicKeyBase64, resolverName = identity.name)
        }
    }

    /**
     * Pair with the PC whose QR code was scanned.
     *
     * The code was read off the PC's own screen and carries the PC's key, so there is nothing for
     * the person to compare: seeing it is the proof, and this is the one way a PC is trusted without
     * the fingerprint step. It also carries a one-time token, which goes to the PC inside the encrypted
     * handshake and lets this phone pair although the PC is not otherwise open to new phones.
     *
     * If a PC was paired before at the same address under the same name but with another key (it was
     * reinstalled), the code replaces it: the person is looking at the PC that says so.
     */
    fun pairWithInvite(invite: PairingInvite) {
        retry = { pairWithInvite(invite) }
        beginAttempt()
        val label = invite.name ?: invite.hosts.firstOrNull() ?: "PC"
        connectJob = scope.launch {
            _pairing.value = PairingUiState.Contacting(label)
            val reached = locate(invite)
            if (reached == null) {
                _pairing.value = PairingUiState.Failed(label, FailureReason.UNREACHABLE)
                return@launch
            }
            val key = invite.publicKeyBase64
            val paired = withContext(Dispatchers.IO) { store.all() }
            val replacing = (TrustResolver.resolve(key, reached.host, invite.name, paired) as? TrustDecision.KeyChanged)?.expected
            val candidate = Candidate(invite.name ?: reached.host, reached.host, reached.port, key)
            _pairing.value = PairingUiState.Connecting(candidate)
            attemptWifi(candidate, replacing, pairingToken = invite.token)
        }
    }

    private class Reached(val host: String, val port: Int)

    /**
     * Where the PC in a QR code can be reached right now: the first address in the code that answers
     * with the code's key, else wherever discovery has seen that key. An address may be stale, or
     * on a network this phone is not on; the key is what identifies the PC.
     */
    private suspend fun locate(invite: PairingInvite): Reached? = withContext(Dispatchers.IO) {
        // Off the main thread: this waits, and waiting is not the interface's business.
        val wanted = invite.publicKeyBase64
        for (host in invite.hosts) {
            val key = PairingIntro.fetchPublicKey(host, invite.port, prepare = lan::bind)
            if (key != null && Base64.getEncoder().encodeToString(key) == wanted) return@withContext Reached(host, invite.port)
        }
        discovery.refresh()
        val seen = withTimeoutOrNull(DISCOVERY_WAIT_MS) {
            discovery.servers.first { servers -> servers.any { it.publicKey == wanted } }
        }
        seen?.firstOrNull { it.publicKey == wanted }?.let { Reached(it.host, it.port) }
    }

    /**
     * Decide what the PC's key means: trusted already, new, or changed. Trusted PCs
     * connect; the others wait for the user.
     */
    private suspend fun decide(
        name: String,
        host: String,
        port: Int,
        key: String,
        resolverName: String? = name,
    ) {
        val paired = withContext(Dispatchers.IO) { store.all() }
        val candidate = Candidate(name, host, port, key)
        when (val decision = TrustResolver.resolve(key, host, resolverName, paired)) {
            is TrustDecision.Connect -> {
                _pairing.value = null
                attemptWifi(candidate.copy(name = decision.device.name.ifBlank { name }), replacing = null)
            }
            is TrustDecision.Verify ->
                _pairing.value = PairingUiState.Verify(candidate, formatFingerprint(key))
            is TrustDecision.KeyChanged ->
                _pairing.value = PairingUiState.Verify(candidate, formatFingerprint(key), replaces = decision.expected)
        }
    }

    /** The user compared the fingerprints and agrees: connect, and remember the PC if that works. */
    fun confirmPairing() {
        val verify = _pairing.value as? PairingUiState.Verify ?: return
        _pairing.value = PairingUiState.Connecting(verify.candidate)
        retry = { _pairing.value = PairingUiState.Verify(verify.candidate, verify.fingerprint, verify.replaces); confirmPairing() }
        connectJob?.cancel()
        connectJob = scope.launch { attemptWifi(verify.candidate, replacing = verify.replaces) }
    }

    /** Close the pairing sheet, abandoning an attempt that has not finished. */
    fun dismissPairing() {
        val current = _pairing.value
        _pairing.value = null
        if (current is PairingUiState.Connecting || current is PairingUiState.Contacting) {
            connectJob?.cancel()
            if (connectionState.value is ConnectionState.Connecting) wifi.disconnect()
        }
    }

    /** Try the last attempt again (after a failure the user may have fixed on the PC). */
    fun retry() {
        retry?.invoke()
    }

    private fun beginAttempt() {
        userDisconnected = false
        connectJob?.cancel()
        _pairing.value = null
    }

    private suspend fun attemptWifi(candidate: Candidate, replacing: PairedDevice?, pairingToken: ByteArray? = null) {
        if (bluetooth.connectionState.value !is ConnectionState.Disconnected) bluetooth.disconnect()
        _activeTransport.value = ConnectionState.Transport.WIFI
        _activeId.value = candidate.publicKey

        val target = ConnectionTarget.Wifi(candidate.host, candidate.port, candidate.publicKey, candidate.name)
        when (val outcome = wifi.connectTo(target, pairingToken)) {
            WifiInputDispatcher.ConnectOutcome.Connected -> onWifiConnected(candidate, replacing)
            is WifiInputDispatcher.ConnectOutcome.Failed -> onWifiFailed(candidate, outcome.reason)
            WifiInputDispatcher.ConnectOutcome.Superseded -> Unit
        }
    }

    /** The encrypted connection is up: only now is the PC remembered. */
    private suspend fun onWifiConnected(candidate: Candidate, replacing: PairedDevice?) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = store.all().firstOrNull { it.publicKey == candidate.publicKey }
            store.put(
                PairedDevice(
                    publicKey = candidate.publicKey,
                    name = candidate.name,
                    host = candidate.host,
                    port = candidate.port,
                    pairedAtMs = existing?.pairedAtMs ?: now,
                    lastConnectedMs = now,
                ),
            )
            if (replacing != null && replacing.publicKey != candidate.publicKey) store.remove(replacing.publicKey)
            _paired.value = store.all()
        }
        _pairing.value = null
        rememberOperatingSystem(candidate.publicKey)
    }

    /** Once the PC says what it runs, keep that with the paired device so the list can show it. */
    private fun rememberOperatingSystem(publicKey: String) {
        scope.launch {
            val info = withTimeoutOrNull(OS_WAIT_MS) { wifi.hostInfo.first { it.os != HostOs.UNKNOWN } } ?: return@launch
            withContext(Dispatchers.IO) {
                val device = store.all().firstOrNull { it.publicKey == publicKey } ?: return@withContext
                if (device.osName != info.os.name) {
                    store.put(device.copy(osName = info.os.name))
                    _paired.value = store.all()
                }
            }
        }
    }

    private suspend fun onWifiFailed(candidate: Candidate, reason: FailureReason) {
        when (reason) {
            FailureReason.KEY_CHANGED -> {
                // The PC answers, but not with the key we hold. Ask for the new one and let
                // the user decide whether it is the same PC reinstalled.
                val fresh = PairingIntro.fetchPublicKey(candidate.host, candidate.port, prepare = lan::bind)
                if (fresh == null) {
                    _pairing.value = PairingUiState.Failed(candidate.name, FailureReason.UNREACHABLE, candidate)
                } else {
                    decide(candidate.name, candidate.host, candidate.port, Base64.getEncoder().encodeToString(fresh))
                }
            }
            FailureReason.NOT_PAIRED ->
                _pairing.value = PairingUiState.Failed(candidate.name, reason, candidate)
            else ->
                // The sheet is only open if the user is in the middle of pairing; otherwise
                // the device list reports the failure through the connection state.
                if (_pairing.value != null) _pairing.value = PairingUiState.Failed(candidate.name, reason, candidate)
        }
    }

    private fun formatFingerprint(key: String): String =
        Fingerprint.format(Fingerprint.ofBase64(key))

    // ── Connecting over Bluetooth ────────────────────────────────────

    /** Re-read the Bluetooth state and the list of devices paired in Android's settings. */
    fun refreshBluetooth() {
        _bluetoothReady.value = when {
            !hid.hasAdapter -> BluetoothAvailability.UNSUPPORTED
            !hid.hasConnectPermission() -> BluetoothAvailability.NEEDS_PERMISSION
            !hid.isEnabled -> BluetoothAvailability.OFF
            else -> BluetoothAvailability.READY
        }
        _bluetoothDevices.value = if (_bluetoothReady.value == BluetoothAvailability.READY) {
            hid.bondedDevices().map { BluetoothDeviceInfo(safeName(it), it.address) }
                .sortedBy { it.name.lowercase() }
        } else {
            emptyList()
        }
    }

    private fun safeName(device: android.bluetooth.BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    fun connectBluetooth(address: String, name: String) {
        retry = { connectBluetooth(address, name) }
        beginAttempt()
        connectJob = scope.launch {
            wifi.disconnect()
            _activeTransport.value = ConnectionState.Transport.BLUETOOTH
            _activeId.value = address
            bluetooth.connect(ConnectionTarget.Bluetooth(address, name))
        }
    }

    // ── Ending and housekeeping ──────────────────────────────────────

    fun disconnect() {
        userDisconnected = true
        _activeId.value = null
        connectJob?.cancel()
        _pairing.value = null
        wifi.disconnect()
        bluetooth.disconnect()
        nowPlaying.stop()
        clipboard.clearLatest()
    }

    /** Forget a paired PC. The PC will have to be verified again to reconnect. */
    fun forget(device: PairedDevice) {
        scope.launch(Dispatchers.IO) {
            store.remove(device.publicKey)
            _paired.value = store.all()
        }
        val connected = connectionState.value
        if (connected is ConnectionState.Connected && connected.deviceName == device.name) disconnect()
    }

    fun forgetAll() {
        scope.launch(Dispatchers.IO) {
            store.clear()
            _paired.value = store.all()
        }
        disconnect()
    }

    /** Give this phone a new identity. Every PC will have to pair with it again. */
    fun resetIdentity() {
        disconnect()
        scope.launch(Dispatchers.IO) { store.resetLocalIdentity() }
    }

    /** Called when the app comes to the foreground: check at once that the PC is still there. */
    fun onAppForegrounded() {
        wifi.verifyNow()
        refreshBluetooth()
    }

    /** Start or stop asking the PC what is playing (only while that is on screen). */
    fun setNowPlayingActive(active: Boolean) {
        val supported = hostInfo.value.capabilities.nowPlaying
        val connected = connectionState.value is ConnectionState.Connected &&
            _activeTransport.value == ConnectionState.Transport.WIFI
        if (active && supported && connected) nowPlaying.start() else nowPlaying.stop()
    }

    fun dispatch(event: InputEvent) = activeDispatcher.dispatch(event)

    fun pushClipboardToPc() {
        _notices.tryEmit(
            when (clipboard.pushToPc()) {
                ClipboardSync.PushResult.SENT -> Notice.ClipboardSent
                ClipboardSync.PushResult.EMPTY -> Notice.ClipboardEmpty
                ClipboardSync.PushResult.DISABLED -> Notice.ClipboardDisabled
            },
        )
    }

    fun pullClipboardFromPc() = clipboard.pullFromPc()

    fun copyPcClipboardToPhone() {
        clipboard.copyPcClipboardToPhone()
        _notices.tryEmit(Notice.ClipboardFromPc)
    }

    // ── Reactions ────────────────────────────────────────────────────

    private fun onServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Clipboard -> clipboard.onClipboardReceived(message.text)
            is ServerMessage.NowPlaying -> nowPlaying.onUpdate(message.state)
            is ServerMessage.Host -> Unit // handled by the dispatcher
        }
    }

    /** Wi-Fi radio locks, the background notification and now-playing follow the connection. */
    private fun observeConnectionState() {
        scope.launch {
            combine(connectionState, prefs.map { it.keepConnectionAlive }.distinctUntilChanged()) { state, keepAlive ->
                state to keepAlive
            }.collect { (state, keepAlive) ->
                val online = state is ConnectionState.Connected || state is ConnectionState.Reconnecting
                val overWifi = _activeTransport.value == ConnectionState.Transport.WIFI
                if (online && overWifi) wifiPerformance.acquire() else wifiPerformance.release()

                if (state is ConnectionState.Connected && keepAlive) {
                    try { TelepadConnectionService.start(app) } catch (t: Exception) {
                        // Starting a foreground service from the background can be refused.
                        Log.w(TAG, "could not start the connection service: ${t.javaClass.simpleName}")
                    }
                } else if (state !is ConnectionState.Reconnecting) {
                    TelepadConnectionService.stop(app)
                }

                if (state !is ConnectionState.Connected) nowPlaying.stop()
            }
        }
    }

    /** Opening the app connects to the PC used last, if it is on the network. */
    private fun observeAutoConnect() {
        scope.launch {
            combine(devices, connectionState, prefs) { list, state, p -> Triple(list, state, p) }
                .collect { (list, state, p) ->
                    if (autoConnectDone || userDisconnected || !p.autoConnect || !p.onboardingShown) return@collect
                    if (!discoveryWanted || state !is ConnectionState.Disconnected || _pairing.value != null) return@collect
                    val candidate = DeviceList.autoConnectCandidate(list) ?: return@collect
                    autoConnectDone = true
                    connect(candidate)
                }
        }
    }

    /** A Wi-Fi network appearing or disappearing is the cue to check the link now. */
    private fun observeNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
            })
        } catch (t: Exception) {
            Log.w(TAG, "network callback unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun onNetworkChanged() {
        wifi.verifyNow()
        if (discoveryWanted) discovery.refresh()
    }

    /** Stops everything. Only for tests and process teardown. */
    fun shutdown() {
        discovery.stop()
        nowPlaying.shutdown()
        hid.cleanup()
        wifi.shutdown()
        wifiPerformance.release()
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val OS_WAIT_MS = 4_000L

        /** How long to wait for discovery to show a PC whose QR code gave no address that answered. */
        private const val DISCOVERY_WAIT_MS = 4_000L

        @Volatile private var INSTANCE: ConnectionManager? = null

        fun getInstance(app: Application): ConnectionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionManager(
                    app = app,
                    store = LazyTrustStore { PairingStore.get(app) },
                    discovery = DiscoveryService(app, LanNetworks(app)),
                ).also { INSTANCE = it }
            }
    }
}

/** Whether this phone can act as a Bluetooth keyboard right now, and if not, why. */
enum class BluetoothAvailability { UNKNOWN, READY, OFF, NEEDS_PERMISSION, UNSUPPORTED }

private fun HostOsChoice.toHostOs(): HostOs = when (this) {
    HostOsChoice.WINDOWS -> HostOs.WINDOWS
    HostOsChoice.MACOS -> HostOs.MACOS
    HostOsChoice.LINUX -> HostOs.LINUX
}
