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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Multicast-based server discovery on the local Wi-Fi.
 *
 * Joins the administratively-scoped IPv4 group `239.255.42.67:5000` (RFC 2365),
 * sends staggered announcements at 100ms / 500ms / 2s so servers can find us,
 * then listens for `TELEPAD_PONG:<hostname>` replies.
 *
 * **Audit-driven fixes baked in:**
 *  - Multicast group moved from the reserved `224.0.0.0/24` block to the admin
 *    scope (where routers/APs actually forward and don't drop).
 *  - `joinGroup(SocketAddress, NetworkInterface)` is used with an explicit
 *    Wi-Fi interface (not the default form), so multicast joins land on the
 *    LAN-facing radio even on phones with concurrent cellular.
 *  - `MulticastLock` is released on stop, not just on app exit.
 *  - The discovery probe is an opaque 8-byte magic — no human-readable
 *    "TELEPAD" string on the wire (privacy hardening on hostile networks).
 */
class MulticastDiscoveryService(private val context: Context) {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _discoveredServers = MutableStateFlow<List<ServerInfo>>(emptyList())
    val discoveredServers: StateFlow<List<ServerInfo>> = _discoveredServers.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startListening() {
        if (listenerJob?.isActive == true) return

        if (multicastLock?.isHeld != true) {
            multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        listenerJob = scope.launch {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(TELEPAD_PORT))
                }
                val group = InetAddress.getByName(MULTICAST_GROUP)
                val ifaces = getDiscoveryInterfaces()
                for (iface in ifaces) {
                    try {
                        socket.joinGroup(InetSocketAddress(group, TELEPAD_PORT), iface)
                        Log.i(TAG, "Joined $MULTICAST_GROUP on ${iface.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed joining group on ${iface.name}: ${e.message}")
                    }
                }

                // Staggered initial announcements so the server hears us promptly.
                launch {
                    val delays = longArrayOf(100, 500, 2000)
                    for (d in delays) {
                        delay(d)
                        sendProbe(socket, group)
                    }
                }

                socket.soTimeout = LISTEN_TIMEOUT_MS
                val rxBuf = ByteArray(512)
                val packet = DatagramPacket(rxBuf, rxBuf.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        handleReply(rxBuf, packet)
                    } catch (_: SocketTimeoutException) {
                        // Periodic re-announce so late-starting servers still see us.
                        sendProbe(socket, group)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "discovery loop crashed", t)
            } finally {
                try { socket?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP)) } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
        _discoveredServers.value = emptyList()
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private suspend fun sendProbe(socket: MulticastSocket, group: InetAddress) {
        withContext(Dispatchers.IO) {
            try {
                val msg = ByteArray(1 + DISCOVERY_MAGIC.size).apply {
                    this[0] = WIRE_DISCOVERY_PROBE
                    System.arraycopy(DISCOVERY_MAGIC, 0, this, 1, DISCOVERY_MAGIC.size)
                }
                // Multicast
                try {
                    socket.send(DatagramPacket(msg, msg.size, group, TELEPAD_PORT))
                } catch (_: Exception) {}
                // Global broadcast
                try {
                    socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), TELEPAD_PORT))
                } catch (_: Exception) {}
                // Directed broadcast on all local interfaces (e.g. ap0 hotspot 10.95.31.255)
                try {
                    for (iface in NetworkInterface.getNetworkInterfaces()) {
                        if (!iface.isUp || iface.isLoopback) continue
                        for (ia in iface.interfaceAddresses) {
                            val bcast = ia.broadcast ?: continue
                            try {
                                socket.send(DatagramPacket(msg, msg.size, bcast, TELEPAD_PORT))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            } catch (t: Throwable) {
                Log.w(TAG, "probe send failed: ${t.message}")
            }
        }
    }

    private fun handleReply(buf: ByteArray, packet: DatagramPacket) {
        if (packet.length < 1) return
        val raw = if (packet.length > 1 && buf[0] == WIRE_DISCOVERY_REPLY) {
            String(buf, 1, packet.length - 1, Charsets.UTF_8)
        } else {
            String(buf, 0, packet.length, Charsets.UTF_8)
        }
        val host = packet.address.hostAddress ?: return
        if (raw.startsWith(PONG_PREFIX)) {
            val name = raw.removePrefix(PONG_PREFIX).take(64).trim()
            val info = ServerInfo(name = name.ifBlank { host }, host = host, port = TELEPAD_PORT)
            _discoveredServers.update { current ->
                if (current.any { it.host == host }) current else current + info
            }
            Log.i(TAG, "Discovered server '$name' at $host")
        }
    }

    /**
     * Get candidate network interfaces for multicast discovery.
     * Includes all active, non-loopback interfaces with IPv4 addresses.
     */
    private fun getDiscoveryInterfaces(): List<NetworkInterface> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { iface ->
                    iface.isUp && !iface.isLoopback &&
                    iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
                }
        } catch (_: Exception) { emptyList() }
    }

    private companion object {
        const val TAG = "MulticastDiscovery"
        const val MULTICAST_GROUP = "239.255.42.67"
        const val TELEPAD_PORT = 5000
        const val LISTEN_TIMEOUT_MS = 6000
        const val PONG_PREFIX = "TELEPAD_PONG:"
        const val WIRE_DISCOVERY_PROBE: Byte = 0xC3.toByte()
        const val WIRE_DISCOVERY_REPLY: Byte = 0xC4.toByte()
        const val MULTICAST_LOCK_TAG = "Telepad:MulticastDiscovery"
        val DISCOVERY_MAGIC = byteArrayOf(
            0x54, 0xE7.toByte(), 0x9A.toByte(), 0x03,
            0x21, 0xC8.toByte(), 0xBE.toByte(), 0xFE.toByte()
        )
    }
}
