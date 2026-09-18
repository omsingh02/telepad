package com.omsingh.telepad.core.wifi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Manual "scan the subnet" fallback for users whose router blocks multicast.
 *
 * Strategy: for every active Wi-Fi /24 on the device, fire a discovery probe
 * at all 254 host addresses in parallel and wait briefly for a PONG reply.
 *
 * **Concurrency:** Uses `Dispatchers.IO.limitedParallelism(32)`. Per Kotlin
 * docs, views obtained via `limitedParallelism` share the underlying 64-thread
 * IO pool — so we don't spend extra RAM on dedicated threads, but we cap our
 * own fan-out to 32 to avoid starving the rest of the app.
 *
 * **Socket-binding:** Each probe socket is bound to the Wi-Fi-side local IPv4
 * address. This avoids the kernel default-route race that can otherwise send
 * probes out the cellular interface on multi-network phones.
 *
 * **Wire format:** Same opaque discovery probe as multicast — a 0xC3 tag
 * followed by 8 magic bytes. PONG reply parsing is identical to the multicast
 * service. The two discovery channels populate a shared list at the ViewModel
 * level (de-duped by host).
 */
class UdpDiscoveryScanner(private val context: Context) {

    private val _discoveredServers = MutableStateFlow<List<ServerInfo>>(emptyList())
    val discoveredServers: StateFlow<List<ServerInfo>> = _discoveredServers.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLEL_PROBES)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    fun startScanning() {
        stopScanning()
        scanJob = scope.launch {
            val subnets = getLocalSubnets()
            if (subnets.isEmpty()) {
                Log.w(TAG, "No active local subnets found")
                return@launch
            }
            Log.i(TAG, "Scanning subnets: $subnets")
            for (subnet in subnets) {
                val probes = (1..254).map { host ->
                    async(scanDispatcher) {
                        val ip = "$subnet.$host"
                        if (probeHost(ip, TELEPAD_PORT)) {
                            _discoveredServers.update { cur ->
                                if (cur.any { it.host == ip }) cur
                                else cur + ServerInfo(name = ip, host = ip, port = TELEPAD_PORT)
                            }
                        }
                    }
                }
                probes.awaitAll()
            }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    private suspend fun probeHost(ip: String, port: Int): Boolean = withContext(scanDispatcher) {
        var socket: DatagramSocket? = null
        try {
            val localBind = getWifiLocalIpv4()
            socket = if (localBind != null)
                DatagramSocket(InetSocketAddress(localBind, 0))
            else
                DatagramSocket()
            socket.soTimeout = PROBE_TIMEOUT_MS

            val msg = ByteArray(1 + DISCOVERY_MAGIC.size).apply {
                this[0] = WIRE_DISCOVERY_PROBE
                System.arraycopy(DISCOVERY_MAGIC, 0, this, 1, DISCOVERY_MAGIC.size)
            }
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), port))

            val rxBuf = ByteArray(256)
            val rp = DatagramPacket(rxBuf, rxBuf.size)
            socket.receive(rp)

            // Any PONG-shaped reply is a hit. Parse the hostname for display.
            val payload = String(rxBuf, 0, rp.length, Charsets.UTF_8)
            if (payload.startsWith(PONG_PREFIX)) {
                val name = payload.removePrefix(PONG_PREFIX).take(64).trim()
                if (name.isNotBlank() && name != ip) {
                    _discoveredServers.update { cur ->
                        cur.map { if (it.host == ip) it.copy(name = name) else it }
                    }
                }
                true
            } else false
        } catch (_: Exception) {
            false  // expected for the vast majority of IPs
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** Enumerate local /24 subnet prefixes from active interfaces, e.g. `["192.168.1", "10.0.0"]`. */
    private fun getLocalSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp || iface.isPointToPoint) continue
                // Skip non-Wi-Fi interfaces by name. Names vary by OEM but these
                // patterns cover ~99% of devices.
                val n = iface.name.lowercase()
                if (!(n.startsWith("wlan") || n.startsWith("wifi") || n == "wlp2s0")) continue

                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot > 0) {
                            val prefix = ip.substring(0, lastDot)
                            if (prefix !in subnets) subnets += prefix
                        }
                    }
                }
            }
            // Fallback: if the name filter rejected everything (some OEMs use
            // unexpected names), fall back to the original "any non-loopback" path.
            if (subnets.isEmpty()) {
                for (iface in NetworkInterface.getNetworkInterfaces()) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            val lastDot = ip.lastIndexOf('.')
                            if (lastDot > 0) {
                                val prefix = ip.substring(0, lastDot)
                                if (prefix !in subnets) subnets += prefix
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "subnet enumeration failed", e)
        }
        return subnets
    }

    private fun getWifiLocalIpv4(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    } catch (_: Exception) { null }

    private companion object {
        const val TAG = "UdpScanner"
        const val TELEPAD_PORT = 5000
        const val PROBE_TIMEOUT_MS = 300
        const val MAX_PARALLEL_PROBES = 32
        const val PONG_PREFIX = "TELEPAD_PONG:"
        const val WIRE_DISCOVERY_PROBE: Byte = 0xC3.toByte()
        val DISCOVERY_MAGIC = byteArrayOf(
            0x54, 0xE7.toByte(), 0x9A.toByte(), 0x03,
            0x21, 0xC8.toByte(), 0xBE.toByte(), 0xFE.toByte()
        )
    }
}
