package com.omsingh.telepad.core.trust

import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.wifi.DiscoveredServer

/** One row of the device list: a PC the phone has paired with, or one it just found. */
data class DeviceEntry(
    val name: String,
    /** Where to reach it now: where it was just seen if it is online, else where it was last. */
    val host: String,
    val port: Int,
    /** The PC's public key, or null for a PC that was found but has not been asked yet. */
    val publicKey: String?,
    val paired: Boolean,
    /** It announced itself recently. */
    val online: Boolean,
    val lastConnectedMs: Long = 0L,
    /** The PC's operating system if it has been connected to before. */
    val os: HostOs = HostOs.UNKNOWN,
) {
    /** A stable identity for list keys and comparisons. */
    val id: String get() = publicKey ?: "$host:$port"
}

/** Builds and uses the device list. Pure, so every rule is a plain unit test. */
object DeviceList {

    /**
     * Merges the PCs the phone has paired with and the PCs currently on the network.
     * A paired PC is online when something announcing the same key is on the network
     * (or, when that key has not been fetched yet, something at the same address).
     */
    fun build(paired: List<PairedDevice>, discovered: List<DiscoveredServer>): List<DeviceEntry> {
        val unmatched = discovered.toMutableList()

        val pairedEntries = paired.map { device ->
            val seen = unmatched.firstOrNull { it.publicKey == device.publicKey }
                ?: unmatched.firstOrNull { it.publicKey == null && it.host.equals(device.host, true) && it.port == device.port }
            if (seen != null) unmatched.remove(seen)
            DeviceEntry(
                name = seen?.name?.takeIf { it.isNotBlank() } ?: device.name,
                host = seen?.host ?: device.host,
                port = seen?.port ?: device.port,
                publicKey = device.publicKey,
                paired = true,
                online = seen != null,
                lastConnectedMs = device.lastConnectedMs,
                os = device.osName?.let { name -> runCatching { HostOs.valueOf(name) }.getOrNull() } ?: HostOs.UNKNOWN,
            )
        }

        val newEntries = unmatched.map {
            DeviceEntry(
                name = it.name,
                host = it.host,
                port = it.port,
                publicKey = it.publicKey,
                paired = false,
                online = true,
            )
        }

        return pairedEntries.sortedWith(
            compareByDescending<DeviceEntry> { it.online }
                .thenByDescending { it.lastConnectedMs }
                .thenBy { it.name.lowercase() },
        ) + newEntries.sortedWith(compareBy({ it.name.lowercase() }, { it.host }))
    }

    /**
     * The PC to connect to automatically when the app opens: the most recently used
     * paired PC, if it is online. Nothing is connected to if no PC has been used yet.
     */
    fun autoConnectCandidate(entries: List<DeviceEntry>): DeviceEntry? =
        entries.filter { it.paired && it.online && it.lastConnectedMs > 0L }
            .maxByOrNull { it.lastConnectedMs }
}
