package com.omsingh.telepad.core.trust

import kotlinx.serialization.Serializable

/**
 * A PC this phone has paired with.
 *
 * The PC's identity is its public key. Its address is only where it was last
 * seen, and changes whenever the router hands out a new lease, so nothing is
 * keyed by address: a paired PC that moves to another IP is still the same PC.
 */
@Serializable
data class PairedDevice(
    /** The PC's long-term X25519 public key, base64. This *is* the identity. */
    val publicKey: String,
    val name: String,
    /** Where the PC was last reached. */
    val host: String,
    val port: Int,
    val pairedAtMs: Long = 0L,
    val lastConnectedMs: Long = 0L,
    /** The PC's operating system as last reported (a [com.omsingh.telepad.core.host.HostOs] name), for the icon. */
    val osName: String? = null,
)

/** Where [PairedDevice]s are kept. The production implementation encrypts them at rest. */
interface PairedDeviceStore {
    fun all(): List<PairedDevice>

    /** Adds the device, or replaces the one with the same public key. */
    fun put(device: PairedDevice)

    fun remove(publicKey: String)

    fun clear()
}

/**
 * Everything security-relevant the phone remembers: the PCs it trusts, and the phone's own
 * identity key (what the PCs trust it by). One interface so that the connection logic can be
 * tested against a store in memory instead of the Android Keystore.
 */
interface TrustStore : PairedDeviceStore, com.omsingh.telepad.core.crypto.ClientIdentity {
    /** Whether trust is still stored the old way, by address, and [migrateLegacy] has work to do. */
    val needsMigration: Boolean

    /** Converts the old address-keyed trust into [PairedDevice]s, once. Returns how many were carried over. */
    fun migrateLegacy(favorites: List<LegacyTrust.Favorite>, nowMs: Long = System.currentTimeMillis()): Int

    /** Throws away the phone's identity; every PC has to pair with it again. */
    fun resetLocalIdentity()
}

class InMemoryPairedDeviceStore(initial: List<PairedDevice> = emptyList()) : PairedDeviceStore {
    private val devices = LinkedHashMap<String, PairedDevice>().apply { initial.forEach { put(it.publicKey, it) } }

    override fun all(): List<PairedDevice> = devices.values.toList()
    override fun put(device: PairedDevice) { devices[device.publicKey] = device }
    override fun remove(publicKey: String) { devices.remove(publicKey) }
    override fun clear() = devices.clear()
}
