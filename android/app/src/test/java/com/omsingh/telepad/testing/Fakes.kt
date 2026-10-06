package com.omsingh.telepad.testing

import com.omsingh.telepad.core.trust.InMemoryPairedDeviceStore
import com.omsingh.telepad.core.trust.LegacyTrust
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.core.trust.TrustStore
import com.omsingh.telepad.core.wifi.DeviceDiscovery
import com.omsingh.telepad.core.wifi.DiscoveredServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** The trust store without the Keystore: devices and the phone's key, in memory. */
class InMemoryTrustStore(initial: List<PairedDevice> = emptyList()) : TrustStore {
    private val devices = InMemoryPairedDeviceStore(initial)
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    var identityResets = 0
        private set

    override fun all() = devices.all()
    override fun put(device: PairedDevice) = devices.put(device)
    override fun remove(publicKey: String) = devices.remove(publicKey)
    override fun clear() = devices.clear()
    override fun localStaticPrivateKey() = key
    override val needsMigration = false
    override fun migrateLegacy(favorites: List<LegacyTrust.Favorite>, nowMs: Long) = 0
    override fun resetLocalIdentity() { identityResets++ }
}

/** Discovery whose findings the test decides. */
class FakeDiscovery : DeviceDiscovery {
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    private val _searching = MutableStateFlow(false)
    override val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()
    override val searching: StateFlow<Boolean> = _searching.asStateFlow()
    var started = false
        private set
    var refreshes = 0
        private set

    override fun start() { started = true }
    override fun stop() { started = false }
    override fun refresh() { refreshes++ }

    fun found(vararg servers: DiscoveredServer) { _servers.value = servers.toList() }
}
