package com.omsingh.telepad.core.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** The list of Telepad PCs currently answering on the network. */
interface DeviceDiscovery {
    val servers: StateFlow<List<DiscoveredServer>>

    /** True for the first moments of a search, while the list may still be filling. */
    val searching: StateFlow<Boolean>

    fun start()
    fun stop()

    /** Asks again right away, including a sweep of the local subnet. */
    fun refresh()
}

/**
 * Finds Telepad PCs on the local network, for as long as it is started.
 *
 * It listens on the discovery port, asks (probes) in four ways at once so that at
 * least one gets through whatever the router filters (multicast, broadcast, each
 * network's directed broadcast, and, when nothing answers, a unicast sweep of the
 * local subnet), and asks every PC that answers for its public key, so the UI knows
 * which ones are already paired.
 *
 * It is meant to run only while the device list is on screen: listening holds a
 * multicast lock that costs battery. The list it publishes forgets PCs that stop
 * announcing ([DiscoveryRegistry]).
 */
class DiscoveryService(
    context: Context,
    private val lan: LanNetworks,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DeviceDiscovery {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val registry = DiscoveryRegistry()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())

    /** The PCs currently answering on the network. */
    override val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val _searching = MutableStateFlow(false)

    override val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var refreshRequested = false
    @Volatile private var searchUntilMs = 0L

    /** Key requests already made, so a PC that never answers is not asked again and again. */
    private val keyRequests = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    override fun start() {
        if (job?.isActive == true) return
        acquireMulticastLock()
        searchUntilMs = nowMs() + SEARCH_WINDOW_MS
        _searching.value = true
        job = scope.launch { run() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
        _searching.value = false
    }

    override fun refresh() {
        searchUntilMs = nowMs() + SEARCH_WINDOW_MS
        _searching.value = true
        keyRequests.clear()
        refreshRequested = true
    }

    // ── The loop ─────────────────────────────────────────────────────

    private suspend fun run() = withContext(Dispatchers.IO) {
        val sock = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                lan.bind(this)
                bind(InetSocketAddress(DiscoveryPackets.PORT))
                soTimeout = RECEIVE_TIMEOUT_MS
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot listen for PCs: ${t.javaClass.simpleName}: ${t.message}")
            _searching.value = false
            return@withContext
        }
        socket = sock
        joinMulticast(sock)

        val prober = launch { probeLoop(sock) }
        try {
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    // Offer the whole buffer every time.
                    packet.setLength(buf.size)
                    sock.receive(packet)
                    handle(sock, buf, packet)
                } catch (_: SocketTimeoutException) {
                    // Nothing arrived: fall through to housekeeping.
                } catch (t: Throwable) {
                    if (sock.isClosed) break
                    Log.w(TAG, "receive failed: ${t.javaClass.simpleName}")
                    delay(200)
                }
                publish()
            }
        } finally {
            prober.cancel()
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun handle(sock: MulticastSocket, buf: ByteArray, packet: DatagramPacket) {
        val host = packet.address?.hostAddress ?: return
        if (packet.address is Inet4Address && isOurOwnAddress(packet.address)) return
        when (val parsed = DiscoveryPackets.parse(buf, packet.length)) {
            is DiscoveryPackets.Parsed.Announcement -> {
                registry.onAnnouncement(host, DiscoveryPackets.PORT, parsed.name, nowMs())
                requestKeyIfNeeded(sock, host)
            }
            is DiscoveryPackets.Parsed.PublicKey ->
                registry.onPublicKey(host, DiscoveryPackets.PORT, java.util.Base64.getEncoder().encodeToString(parsed.key))
            null -> Unit
        }
    }

    private fun requestKeyIfNeeded(sock: MulticastSocket, host: String) {
        if (!registry.needsKey(host, DiscoveryPackets.PORT)) return
        val now = nowMs()
        val (tries, last) = keyRequests[host] ?: (0 to 0L)
        if (tries >= MAX_KEY_REQUESTS || now - last < KEY_REQUEST_GAP_MS) return
        keyRequests[host] = (tries + 1) to now
        send(sock, PairingIntro.requestPacket(), InetAddress.getByName(host))
    }

    private fun publish() {
        val now = nowMs()
        if (_searching.value && now >= searchUntilMs) _searching.value = false
        val list = registry.snapshot(now)
        if (list != _servers.value) _servers.value = list
    }

    // ── Probing ──────────────────────────────────────────────────────

    private suspend fun probeLoop(sock: MulticastSocket) {
        val schedule = longArrayOf(0, 300, 700, 1500)
        for (wait in schedule) {
            delay(wait)
            probeAll(sock)
        }
        var sinceLastSweep = 0L
        var swept = false
        while (true) {
            delay(TICK_MS)
            sinceLastSweep += TICK_MS
            if (refreshRequested) {
                refreshRequested = false
                probeAll(sock)
                sweepSubnets(sock)
                swept = true
                sinceLastSweep = 0
                continue
            }
            // Nothing found by the usual means after a few seconds: try every address.
            if (!swept && registry.snapshot(nowMs()).isEmpty() && sinceLastSweep >= FIRST_SWEEP_AFTER_MS) {
                sweepSubnets(sock)
                swept = true
            }
            if (sinceLastSweep >= REPROBE_MS) {
                probeAll(sock)
                sinceLastSweep = 0
            }
        }
    }

    /** Multicast, global broadcast and each local network's directed broadcast. */
    private fun probeAll(sock: MulticastSocket) {
        val probe = DiscoveryPackets.probe()
        send(sock, probe, InetAddress.getByName(DiscoveryPackets.MULTICAST_GROUP))
        send(sock, probe, InetAddress.getByName("255.255.255.255"))
        for (broadcast in lanInterfaces().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }) {
            send(sock, probe, broadcast)
        }
    }

    /** Asks every address of each local subnet directly, for networks that drop broadcast but not unicast. */
    private suspend fun sweepSubnets(sock: MulticastSocket) {
        val probe = DiscoveryPackets.probe()
        var sent = 0
        for (iface in lanInterfaces()) {
            for (ia in iface.interfaceAddresses) {
                val address = ia.address as? Inet4Address ?: continue
                val prefix = ia.networkPrefixLength.toInt().coerceAtLeast(24)
                val own = address.address.toIntBigEndian()
                val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
                val network = own and mask
                val hosts = (1 shl (32 - prefix)) - 2
                for (i in 1..hosts) {
                    val target = network or i
                    if (target == own) continue
                    send(sock, probe, InetAddress.getByAddress(target.toByteArrayBigEndian()))
                    if (++sent % SWEEP_BURST == 0) delay(2)
                }
            }
        }
    }

    private fun ByteArray.toIntBigEndian(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

    private fun Int.toByteArrayBigEndian() =
        byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())

    private fun send(sock: MulticastSocket, data: ByteArray, to: InetAddress) {
        try {
            sock.send(DatagramPacket(data, data.size, to, DiscoveryPackets.PORT))
        } catch (_: Exception) {
            // Unreachable network or no route: expected on some interfaces.
        }
    }

    // ── Interfaces and the multicast lock ────────────────────────────

    private fun lanInterfaces(): List<NetworkInterface> = try {
        val up = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.interfaceAddresses.any { a -> a.address is Inet4Address } }
        val wanted = LanInterfaces.pick(up.map { it.name }).toSet()
        up.filter { it.name in wanted }
    } catch (_: Exception) {
        emptyList()
    }

    private fun joinMulticast(sock: MulticastSocket) {
        val group = InetAddress.getByName(DiscoveryPackets.MULTICAST_GROUP)
        for (iface in lanInterfaces()) {
            try {
                sock.joinGroup(InetSocketAddress(group, DiscoveryPackets.PORT), iface)
            } catch (t: Exception) {
                Log.d(TAG, "could not join the multicast group on ${iface.name}: ${t.message}")
            }
        }
    }

    private fun isOurOwnAddress(address: InetAddress): Boolean = try {
        NetworkInterface.getByInetAddress(address) != null
    } catch (_: Exception) {
        false
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifi?.createMulticastLock(LOCK_TAG)?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L

    private companion object {
        const val TAG = "DiscoveryService"
        const val LOCK_TAG = "Telepad:Discovery"
        const val RECEIVE_TIMEOUT_MS = 1_000
        const val TICK_MS = 500L
        const val REPROBE_MS = 4_000L
        const val FIRST_SWEEP_AFTER_MS = 3_000L
        const val SEARCH_WINDOW_MS = 6_000L
        const val SWEEP_BURST = 8
        const val MAX_KEY_REQUESTS = 3
        const val KEY_REQUEST_GAP_MS = 1_500L
    }
}
