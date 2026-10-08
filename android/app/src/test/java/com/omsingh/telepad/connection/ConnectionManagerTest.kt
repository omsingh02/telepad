package com.omsingh.telepad.connection

import android.app.Application
import android.os.Looper
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.core.wifi.DiscoveredServer
import com.omsingh.telepad.core.wifi.LanNetworks
import com.omsingh.telepad.core.wifi.LocalNetwork
import com.omsingh.telepad.core.wifi.LocalNetworks
import com.omsingh.telepad.core.wifi.NetworkHint
import com.omsingh.telepad.core.wifi.PairingInvite
import com.omsingh.telepad.core.wifi.WifiInputDispatcher
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.testing.FakeDiscovery
import com.omsingh.telepad.testing.InMemoryTrustStore
import com.omsingh.telepad.testing.NoiseTestServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * The connection logic end to end: the manager, the real Wi-Fi dispatcher and a stand-in PC
 * speaking the real protocol on loopback. Only the Keystore and the network discovery are
 * replaced. These are the rules that decide who is trusted, so they are the ones worth testing
 * as a whole: a PC is remembered only after it was confirmed *and* really connected, a changed
 * key is never accepted silently, and nothing is kept from a failed attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionManagerTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val servers = mutableListOf<NoiseTestServer>()
    private lateinit var store: InMemoryTrustStore
    private lateinit var discovery: FakeDiscovery
    private lateinit var manager: ConnectionManager

    private val quick = WifiInputDispatcher.Timing(
        firstAttemptMs = 400, secondAttemptMs = 400, reconnectAttemptMs = 400,
        tickMs = 20, diagnosisAttempts = 2, diagnosisTimeoutMs = 150,
    )

    @Before
    fun setUp() {
        build(emptyList())
    }

    private fun build(initial: List<PairedDevice>, localNetworks: LocalNetworks = LocalNetworks { emptyList() }) {
        if (::manager.isInitialized) manager.shutdown()
        store = InMemoryTrustStore(initial)
        discovery = FakeDiscovery()
        manager = ConnectionManager(app, store, discovery, LanNetworks(app), quick, localNetworks = localNetworks)
        settle()
    }

    @After
    fun tearDown() {
        manager.shutdown()
        servers.forEach { it.close() }
    }

    private fun server(allow: (ByteArray) -> Boolean = { true }, name: String = "Test-PC") =
        NoiseTestServer(allowClient = allow, hostname = name).also { servers += it }

    private fun entryFor(pc: NoiseTestServer, name: String = "Test-PC", paired: Boolean = false, key: String? = null) =
        DeviceEntry(name, "127.0.0.1", pc.port, key, paired = paired, online = true)

    private fun pairedDevice(pc: NoiseTestServer, name: String = "Test-PC", key: String = pc.publicKeyBase64) =
        PairedDevice(key, name, "127.0.0.1", pc.port, pairedAtMs = 1, lastConnectedMs = 1)

    private fun randomKey() = Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    /** Lets the main thread run what the manager has posted to it. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 6_000, what: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            settle()
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what; state=${manager.connectionState.value}, pairing=${manager.pairing.value}")
            Thread.sleep(10)
        }
    }

    private fun awaitConnected() = await(what = "a connection") { manager.connectionState.value is ConnectionState.Connected }

    // ── A PC seen for the first time ─────────────────────────────────

    @Test
    fun `a new PC is shown for verification and nothing is remembered yet`() {
        val pc = server()
        manager.connect(entryFor(pc))
        await(what = "the verification sheet") { manager.pairing.value is PairingUiState.Verify }

        val verify = manager.pairing.value as PairingUiState.Verify
        assertEquals("Test-PC", verify.candidate.name)
        assertEquals(pc.publicKeyBase64, verify.candidate.publicKey)
        assertNull("an ordinary new PC is not a changed identity", verify.replaces)
        assertEquals(5, verify.fingerprint.split("·").size)
        assertTrue("nothing remembered", store.all().isEmpty())
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `confirming the fingerprint connects and only then remembers the PC`() {
        val pc = server()
        manager.connect(entryFor(pc))
        await { manager.pairing.value is PairingUiState.Verify }

        manager.confirmPairing()
        awaitConnected()
        await(what = "the PC to be saved") { store.all().isNotEmpty() }

        val saved = store.all().single()
        assertEquals(pc.publicKeyBase64, saved.publicKey)
        assertEquals("Test-PC", saved.name)
        assertEquals("127.0.0.1", saved.host)
        assertEquals(pc.port, saved.port)
        assertTrue("last connected is set", saved.lastConnectedMs > 0)
        await(what = "the sheet to close") { manager.pairing.value == null }
        assertEquals(pc.publicKeyBase64, manager.activeId.value)
    }

    @Test
    fun `cancelling the verification leaves no trace`() {
        val pc = server()
        manager.connect(entryFor(pc))
        await { manager.pairing.value is PairingUiState.Verify }

        manager.dismissPairing()
        settle()
        assertNull(manager.pairing.value)
        assertTrue(store.all().isEmpty())
        assertEquals(0, pc.handshakes.get())
    }

    @Test
    fun `a PC that has not paired this phone is not remembered`() {
        val pc = server(allow = { false })
        manager.connect(entryFor(pc))
        await { manager.pairing.value is PairingUiState.Verify }

        manager.confirmPairing()
        await(what = "the refusal") { manager.pairing.value is PairingUiState.Failed }
        val failed = manager.pairing.value as PairingUiState.Failed
        assertEquals(FailureReason.NOT_PAIRED, failed.reason)
        assertTrue("a refused PC must not be remembered", store.all().isEmpty())
    }

    @Test
    fun `a PC that does not answer on the phone's own network is said to be a firewall matter`() {
        build(emptyList(), LocalNetworks { listOf(LocalNetwork("127.0.0.2", 8)) })
        val pc = server()
        pc.silent = true
        manager.connectToAddress("127.0.0.1", pc.port)
        await(what = "the failure") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(NetworkHint.SameNetwork("127.0.0.1"), (manager.pairing.value as PairingUiState.Failed).hint)
    }

    @Test
    fun `a PC on another network is said to be on another network`() {
        build(emptyList(), LocalNetworks { listOf(LocalNetwork("192.168.5.20", 24)) })
        val pc = server()
        pc.silent = true
        manager.connectToAddress("127.0.0.1", pc.port)
        await(what = "the failure") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(NetworkHint.DifferentNetwork("192.168.5.20", "127.0.0.1"), (manager.pairing.value as PairingUiState.Failed).hint)
    }

    @Test
    fun `a PC that is off is reported in the sheet and not remembered`() {
        val pc = server()
        pc.silent = true
        manager.connectToAddress("127.0.0.1", pc.port)
        await(what = "the failure") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(FailureReason.UNREACHABLE, (manager.pairing.value as PairingUiState.Failed).reason)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `an address typed in is looked up and the PC names itself`() {
        val pc = server(name = "Living-Room-PC")
        manager.connectToAddress("127.0.0.1", pc.port)
        await { manager.pairing.value is PairingUiState.Verify }
        assertEquals("Living-Room-PC", (manager.pairing.value as PairingUiState.Verify).candidate.name)
    }

    // ── A PC already paired ──────────────────────────────────────────

    @Test
    fun `a paired PC connects without asking and its last use is updated`() {
        val pc = server()
        build(listOf(pairedDevice(pc).copy(lastConnectedMs = 1)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        assertNull("no sheet for a trusted PC", manager.pairing.value)
        await(what = "last use to be saved") { store.all().single().lastConnectedMs > 1 }
    }

    @Test
    fun `the connected PC tells the phone what it runs and the list remembers it`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        await(what = "the operating system") { manager.hostProfile.value.os == HostOs.LINUX }
        await(what = "the OS to be saved") { store.all().single().osName == "LINUX" }
    }

    @Test
    fun `a trusted PC that moved to another address is still trusted`() {
        val pc = server()
        build(listOf(pairedDevice(pc).copy(host = "192.168.99.99")))
        // It is now found at loopback, not where it was paired.
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        await(what = "the new address to be saved") { store.all().single().host == "127.0.0.1" }
    }

    @Test
    fun `a PC that is off is reported as unreachable without opening any sheet`() {
        val pc = server()
        pc.silent = true
        build(listOf(pairedDevice(pc)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        await(what = "the failure") { manager.connectionState.value is ConnectionState.Failed }
        assertEquals(FailureReason.UNREACHABLE, (manager.connectionState.value as ConnectionState.Failed).reason)
        assertNull(manager.pairing.value)
        assertEquals("the PC stays paired", 1, store.all().size)
    }

    // ── A key that changed ───────────────────────────────────────────

    @Test
    fun `a paired PC that now has another key is a warning, never a silent connection`() {
        val pc = server()
        val oldKey = randomKey()
        build(listOf(pairedDevice(pc, key = oldKey)))

        manager.connect(entryFor(pc, paired = true, key = oldKey))
        await(what = "the key-change warning") { manager.pairing.value is PairingUiState.Verify }

        val warning = manager.pairing.value as PairingUiState.Verify
        assertNotNull("this is a changed identity", warning.replaces)
        assertEquals(oldKey, warning.replaces!!.publicKey)
        assertEquals(pc.publicKeyBase64, warning.candidate.publicKey)
        assertEquals("nothing was trusted meanwhile", listOf(oldKey), store.all().map { it.publicKey })
        assertEquals(0, pc.handshakes.get())
    }

    @Test
    fun `trusting the new key replaces the old one`() {
        val pc = server()
        val oldKey = randomKey()
        build(listOf(pairedDevice(pc, key = oldKey)))
        manager.connect(entryFor(pc, paired = true, key = oldKey))
        await { manager.pairing.value is PairingUiState.Verify }

        manager.confirmPairing()
        awaitConnected()
        await(what = "the keys to be swapped") { store.all().map { it.publicKey } == listOf(pc.publicKeyBase64) }
    }

    @Test
    fun `refusing the new key keeps the old one`() {
        val pc = server()
        val oldKey = randomKey()
        build(listOf(pairedDevice(pc, key = oldKey)))
        manager.connect(entryFor(pc, paired = true, key = oldKey))
        await { manager.pairing.value is PairingUiState.Verify }

        manager.dismissPairing()
        settle()
        assertEquals(listOf(oldKey), store.all().map { it.publicKey })
        assertNull(manager.pairing.value)
    }

    // ── Staying in control ───────────────────────────────────────────

    @Test
    fun `input goes through to the connected PC`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        manager.dispatch(InputEvent.RightClick)
        val deadline = System.currentTimeMillis() + 3000
        while (pc.receivedOfType(0x02).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(pc.receivedOfType(0x02).isNotEmpty())
    }

    @Test
    fun `disconnecting ends the connection and clears what was shown`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        manager.disconnect()
        settle()
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertNull(manager.activeId.value)
    }

    @Test
    fun `forgetting the connected PC disconnects from it`() {
        val pc = server()
        val device = pairedDevice(pc)
        build(listOf(device))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        manager.forget(device)
        await(what = "the PC to be forgotten") { store.all().isEmpty() }
        // The connection state is derived on the main thread, which has to run before it shows the change.
        await(what = "the connection to end") { manager.connectionState.value == ConnectionState.Disconnected }
    }

    @Test
    fun `forgetting everything empties the list`() {
        val pc = server()
        build(listOf(pairedDevice(pc), pairedDevice(pc, name = "Other", key = randomKey())))
        manager.forgetAll()
        await { store.all().isEmpty() }
        await { manager.pairedDevices.value.isEmpty() }
    }

    @Test
    fun `replacing the phone's identity is passed to the store`() {
        manager.resetIdentity()
        await { store.identityResets == 1 }
    }

    @Test
    fun `retry repeats the last attempt`() {
        val pc = server()
        pc.silent = true
        build(listOf(pairedDevice(pc)))
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        await { manager.connectionState.value is ConnectionState.Failed }

        pc.silent = false
        manager.retry()
        awaitConnected()
    }

    // ── The device list and automatic connection ─────────────────────

    private fun announced(pc: NoiseTestServer, name: String = "Test-PC") =
        DiscoveredServer(name, "127.0.0.1", pc.port, pc.publicKeyBase64, lastSeenMs = 0)

    @Test
    fun `the list merges paired PCs with what discovery sees`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        discovery.found(announced(pc), DiscoveredServer("Stranger", "10.0.0.9", 5000, randomKey(), 0))
        await(what = "the merged list") { manager.devices.value.size == 2 }
        val byName = manager.devices.value.associateBy { it.name }
        assertTrue(byName.getValue("Test-PC").paired && byName.getValue("Test-PC").online)
        assertTrue(!byName.getValue("Stranger").paired)
    }

    @Test
    fun `opening the app connects to the PC used last, once`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        runBlocking { SettingsRepository(app).update { it.copy(onboardingShown = true) } }
        manager.startDiscovery()
        discovery.found(announced(pc))
        awaitConnected()
        assertEquals(1, pc.handshakes.get())

        // Disconnecting by hand must not bring it straight back.
        manager.disconnect()
        discovery.found(announced(pc))
        val until = System.currentTimeMillis() + 600
        while (System.currentTimeMillis() < until) { settle(); Thread.sleep(20) }
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `automatic connection waits for the first-run guide to be finished`() {
        val pc = server()
        build(listOf(pairedDevice(pc)))
        manager.startDiscovery()
        discovery.found(announced(pc))
        val until = System.currentTimeMillis() + 600
        while (System.currentTimeMillis() < until) { settle(); Thread.sleep(20) }
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `discovery runs only while the list asks for it`() {
        manager.startDiscovery()
        assertTrue(discovery.started)
        manager.stopDiscovery()
        assertTrue(!discovery.started)
        manager.refreshDiscovery()
        assertEquals(1, discovery.refreshes)
    }

    // ── Pairing by QR code ───────────────────────────────────────────

    private val token = ByteArray(16) { (0x40 + it).toByte() }

    private fun inviteFor(
        pc: NoiseTestServer,
        token: ByteArray = this.token,
        hosts: List<String> = listOf("127.0.0.1"),
        name: String? = "Test-PC",
    ) = PairingInvite(pc.publicKey, token, pc.port, hosts, name)

    @Test
    fun `scanning the code of a PC that is closed to new phones pairs with it, with nothing to compare`() {
        val pc = server(allow = { false }).let { NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it } }
        manager.pairWithInvite(inviteFor(pc))
        awaitConnected()
        await(what = "the PC to be saved") { store.all().isNotEmpty() }

        val saved = store.all().single()
        assertEquals(pc.publicKeyBase64, saved.publicKey)
        assertEquals("Test-PC", saved.name)
        assertEquals("127.0.0.1", saved.host)
        assertEquals(pc.port, saved.port)
        await(what = "the sheet to close") { manager.pairing.value == null }
        assertTrue("the token travelled inside the handshake", pc.handshakePayloads.any { it.contentEquals(token) })
    }

    @Test
    fun `after pairing by code the PC is paired for good and needs no code again`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        manager.pairWithInvite(inviteFor(pc))
        awaitConnected()
        await(what = "the PC to be saved") { store.all().isNotEmpty() }

        manager.disconnect()
        settle()
        manager.connect(entryFor(pc, paired = true, key = pc.publicKeyBase64))
        awaitConnected()
        assertTrue("an ordinary connection carries no token", pc.handshakePayloads.last().isEmpty())
    }

    @Test
    fun `a code that has been used pairs no second phone`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        manager.pairWithInvite(inviteFor(pc))
        awaitConnected()

        // Another phone scans the same code (a photo of the screen, say).
        build(emptyList())
        manager.pairWithInvite(inviteFor(pc))
        await(what = "the refusal") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(FailureReason.NOT_PAIRED, (manager.pairing.value as PairingUiState.Failed).reason)
        assertTrue("a refused PC is not remembered", store.all().isEmpty())
        assertTrue(pc.tokenSpent)
    }

    @Test
    fun `a wrong token is refused and nothing is remembered`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        manager.pairWithInvite(inviteFor(pc, token = ByteArray(16) { 1 }))
        await(what = "the refusal") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(FailureReason.NOT_PAIRED, (manager.pairing.value as PairingUiState.Failed).reason)
        assertTrue(store.all().isEmpty())
        assertEquals(0, pc.handshakes.get())
    }

    @Test
    fun `an address in the code that holds another PC is not used`() {
        val real = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        val other = server()
        // The code names the real PC, but its address now belongs to another Telepad PC.
        discovery.found(DiscoveredServer("Test-PC", "127.0.0.1", real.port, real.publicKeyBase64, lastSeenMs = 1))
        manager.pairWithInvite(PairingInvite(real.publicKey, token, other.port, listOf("127.0.0.1"), "Test-PC"))
        // The wrong PC answers with a different key, so the code's PC is looked for by its key instead.
        await(timeoutMs = 10_000, what = "a connection to the right PC") { manager.connectionState.value is ConnectionState.Connected }
        assertEquals(real.publicKeyBase64, store.all().single().publicKey)
        assertEquals("the other PC never saw the token", 0, other.handshakePayloads.size)
    }

    @Test
    fun `a stale address falls back to finding the PC by its key`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        discovery.found(DiscoveredServer("Test-PC", "127.0.0.1", pc.port, pc.publicKeyBase64, lastSeenMs = 1))
        manager.pairWithInvite(inviteFor(pc, hosts = listOf("192.168.99.99")))
        await(timeoutMs = 12_000, what = "a connection") { manager.connectionState.value is ConnectionState.Connected }
        assertEquals("127.0.0.1", store.all().single().host)
    }

    @Test
    fun `a PC that cannot be found at all is reported as unreachable and not remembered`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        pc.silent = true
        manager.pairWithInvite(inviteFor(pc))
        await(timeoutMs = 12_000, what = "the failure") { manager.pairing.value is PairingUiState.Failed }
        assertEquals(FailureReason.UNREACHABLE, (manager.pairing.value as PairingUiState.Failed).reason)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a PC paired before under another key is replaced by its code`() {
        val reinstalled = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        val oldKey = randomKey()
        build(listOf(PairedDevice(oldKey, "Test-PC", "127.0.0.1", reinstalled.port, pairedAtMs = 1, lastConnectedMs = 1)))

        manager.pairWithInvite(inviteFor(reinstalled))
        awaitConnected()
        await(what = "the old record to be replaced") { store.all().singleOrNull()?.publicKey == reinstalled.publicKeyBase64 }
        assertTrue("the old identity is gone", store.all().none { it.publicKey == oldKey })
    }

    @Test
    fun `scanning a code while another attempt is under way starts again cleanly`() {
        val pc = NoiseTestServer(allowClient = { false }, requiredToken = token).also { servers += it }
        manager.connectToAddress("127.0.0.1", pc.port)
        await { manager.pairing.value is PairingUiState.Verify }

        manager.pairWithInvite(inviteFor(pc))
        awaitConnected()
        assertNull("the fingerprint sheet is gone", (manager.pairing.value as? PairingUiState.Verify))
    }
}
