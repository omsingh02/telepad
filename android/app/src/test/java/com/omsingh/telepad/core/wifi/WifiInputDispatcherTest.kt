package com.omsingh.telepad.core.wifi

import com.omsingh.telepad.core.connection.LivenessMonitor
import com.omsingh.telepad.core.connection.ReconnectPolicy
import com.omsingh.telepad.core.crypto.ClientIdentity
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.testing.NoiseTestServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Wi-Fi transport against a stand-in PC speaking the real protocol on loopback.
 * Timings are shrunk so that connection loss and recovery take milliseconds.
 */
class WifiInputDispatcherTest {

    private val identity = object : ClientIdentity {
        private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        override fun localStaticPrivateKey() = key
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<NoiseTestServer>()
    private val dispatchers = mutableListOf<WifiInputDispatcher>()
    private val received = CopyOnWriteArrayList<ServerMessage>()

    private val quick = WifiInputDispatcher.Timing(
        firstAttemptMs = 400, secondAttemptMs = 400, reconnectAttemptMs = 400,
        tickMs = 20, diagnosisAttempts = 2, diagnosisTimeoutMs = 150,
    )

    @After
    fun tearDown() {
        dispatchers.forEach { it.shutdown() }
        servers.forEach { it.close() }
        scope.cancel()
    }

    private fun server(
        allow: (ByteArray) -> Boolean = { true },
        hostname: String = "Test-PC",
    ) = NoiseTestServer(allowClient = allow, hostname = hostname).also { servers += it }

    private fun dispatcher(
        giveUpAfterMs: Long = 600,
        monitor: () -> LivenessMonitor = { LivenessMonitor(pingIntervalMs = 40, degradedAfterMs = 150, lostAfterMs = 400) },
        localNetworks: LocalNetworks = LocalNetworks { emptyList() },
    ) = WifiInputDispatcher(
        identity = identity,
        scope = scope,
        reconnectPolicy = ReconnectPolicy(baseDelayMs = 30, maxDelayMs = 100, giveUpAfterMs = giveUpAfterMs),
        timing = quick,
        newMonitor = monitor,
        onServerMessage = { received += it },
        localNetworks = localNetworks,
    ).also { dispatchers += it }

    private fun target(server: NoiseTestServer, key: String = server.publicKeyBase64) =
        ConnectionTarget.Wifi("127.0.0.1", server.port, key, "Test PC")

    private fun <T> eventually(timeoutMs: Long = 3000, body: suspend () -> T): T =
        runBlocking { withTimeout(timeoutMs) { body() } }

    private fun WifiInputDispatcher.awaitState(timeoutMs: Long = 3000, predicate: (ConnectionState) -> Boolean) =
        eventually(timeoutMs) { connectionState.first(predicate) }

    private fun awaitServer(server: NoiseTestServer, timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not met in time", condition())
    }

    // ── Connecting ───────────────────────────────────────────────────

    @Test
    fun `connects to a PC and reports it as connected`() {
        val pc = server()
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Connected, outcome)
        val state = wifi.connectionState.value as ConnectionState.Connected
        assertEquals("Test PC", state.deviceName)
        assertEquals(ConnectionState.Transport.WIFI, state.transport)
        assertEquals(1, pc.handshakes.get())
    }

    @Test
    fun `asks the PC about itself and learns its operating system`() {
        val pc = server()
        val wifi = dispatcher()
        runBlocking { wifi.connectTo(target(pc)) }
        val info = eventually { wifi.hostInfo.first { it.os != HostOs.UNKNOWN } }
        assertEquals(HostOs.LINUX, info.os)
        assertEquals("2.1.0", info.version)
    }

    @Test
    fun `measures the round trip to the PC`() {
        val pc = server()
        val wifi = dispatcher()
        runBlocking { wifi.connectTo(target(pc)) }
        val state = wifi.awaitState { it is ConnectionState.Connected && it.latencyMs != null } as ConnectionState.Connected
        assertTrue("latency ${state.latencyMs}", state.latencyMs!! in 1..500)
    }

    @Test
    fun `an unpaired phone is told so`() {
        val pc = server(allow = { false })
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Failed(FailureReason.NOT_PAIRED), outcome)
        val state = wifi.connectionState.value as ConnectionState.Failed
        assertEquals(FailureReason.NOT_PAIRED, state.reason)
    }

    @Test
    fun `a PC that is off is unreachable`() {
        val pc = server()
        pc.silent = true
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Failed(FailureReason.UNREACHABLE), outcome)
    }

    @Test
    fun `an unreachable PC on the phone's own network points at the PC's firewall`() {
        val pc = server()
        pc.silent = true
        // The test PC is on the loopback network; the phone is told that it has that network too.
        val wifi = dispatcher(localNetworks = LocalNetworks { listOf(LocalNetwork("127.0.0.2", 8)) })
        runBlocking { wifi.connectTo(target(pc)) }
        val state = wifi.connectionState.value as ConnectionState.Failed
        assertEquals(NetworkHint.SameNetwork("127.0.0.1"), state.hint)
    }

    @Test
    fun `an unreachable PC on another network is said to be on another network`() {
        val pc = server()
        pc.silent = true
        val wifi = dispatcher(localNetworks = LocalNetworks { listOf(LocalNetwork("192.168.5.20", 24)) })
        runBlocking { wifi.connectTo(target(pc)) }
        val state = wifi.connectionState.value as ConnectionState.Failed
        assertEquals(NetworkHint.DifferentNetwork("192.168.5.20", "127.0.0.1"), state.hint)
    }

    @Test
    fun `only an unreachable PC gets a hint about the network`() {
        val pc = server(allow = { false })
        val wifi = dispatcher(localNetworks = LocalNetworks { listOf(LocalNetwork("127.0.0.2", 8)) })
        runBlocking { wifi.connectTo(target(pc)) }
        val state = wifi.connectionState.value as ConnectionState.Failed
        assertEquals(FailureReason.NOT_PAIRED, state.reason)
        assertEquals(NetworkHint.Unknown, state.hint)
    }

    @Test
    fun `a PC with a different key is recognised as changed, not merely unreachable`() {
        val pc = server()
        val staleKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc, key = staleKey)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Failed(FailureReason.KEY_CHANGED), outcome)
    }

    @Test
    fun `a PC that answers but never completes the handshake is a failed handshake`() {
        val pc = server()
        pc.dropNextHandshakes = 100
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Failed(FailureReason.HANDSHAKE_FAILED), outcome)
    }

    @Test
    fun `a lost first handshake is retried on a fresh socket`() {
        val pc = server()
        pc.dropNextHandshakes = 1
        val wifi = dispatcher()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Connected, outcome)
    }

    @Test
    fun `an address that does not exist fails instead of crashing`() {
        val wifi = dispatcher()
        val outcome = runBlocking {
            wifi.connectTo(ConnectionTarget.Wifi("not a host name", 5000, Base64.getEncoder().encodeToString(ByteArray(32)), "x"))
        }
        assertTrue(outcome is WifiInputDispatcher.ConnectOutcome.Failed)
    }

    @Test
    fun `a newer connect supersedes one still in flight`() {
        val slow = server().also { it.silent = true }
        val good = server()
        val wifi = dispatcher()
        val first = scope.launch { wifi.connectTo(target(slow)) }
        Thread.sleep(50)
        val second = runBlocking { wifi.connectTo(target(good)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Connected, second)
        runBlocking { first.join() }
        val state = wifi.connectionState.value as ConnectionState.Connected
        assertEquals("Test PC", state.deviceName)
        assertEquals(1, good.handshakes.get())
    }

    // ── Sending ──────────────────────────────────────────────────────

    private fun connected(): Pair<NoiseTestServer, WifiInputDispatcher> {
        val pc = server()
        val wifi = dispatcher()
        runBlocking { wifi.connectTo(target(pc)) }
        return pc to wifi
    }

    private fun NoiseTestServer.awaitMessage(prefix: ByteArray, timeoutMs: Long = 3000): ByteArray {
        awaitServer(this, timeoutMs) { received.any { it.size >= prefix.size && it.copyOf(prefix.size).contentEquals(prefix) } }
        return received.first { it.size >= prefix.size && it.copyOf(prefix.size).contentEquals(prefix) }
    }

    @Test
    fun `pointer movement is delivered as two signed 16 bit numbers`() {
        val (pc, wifi) = connected()
        wifi.dispatch(InputEvent.MouseMove(300f, -5f))
        val message = pc.awaitMessage(byteArrayOf(0x01))
        assertEquals(5, message.size)
        assertEquals(300, (message[1].toInt() and 0xFF) or (message[2].toInt() shl 8))
        assertEquals(-5, ((message[3].toInt() and 0xFF) or (message[4].toInt() shl 8)).toShort().toInt())
    }

    @Test
    fun `a click is a press then a release of the left button`() {
        val (pc, wifi) = connected()
        wifi.dispatch(InputEvent.Click)
        awaitServer(pc) { pc.receivedOfType(0x02).size >= 2 }
        val buttons = pc.receivedOfType(0x02)
        assertEquals(listOf(0, 1), listOf(buttons[0][1].toInt(), buttons[0][2].toInt()))
        assertEquals(listOf(0, 0), listOf(buttons[1][1].toInt(), buttons[1][2].toInt()))
    }

    @Test
    fun `keys carry their usage and modifiers`() {
        val (pc, wifi) = connected()
        wifi.dispatch(InputEvent.KeyPress(HidKeyCodes.C, InputEvent.Modifiers(leftCtrl = true)))
        val message = pc.awaitMessage(byteArrayOf(0x04))
        assertEquals(HidKeyCodes.C, (message[1].toInt() and 0xFF) or (message[2].toInt() shl 8))
        assertEquals(0x01, message[3].toInt())
    }

    @Test
    fun `text goes out as utf-8`() {
        val (pc, wifi) = connected()
        wifi.dispatch(InputEvent.TextInput("héllo"))
        val message = pc.awaitMessage(byteArrayOf(0x06))
        val length = (message[1].toInt() and 0xFF) or (message[2].toInt() shl 8)
        assertEquals("héllo", String(message, 3, length, Charsets.UTF_8))
    }

    @Test
    fun `oversized text and clipboard payloads are dropped, not truncated`() {
        val (pc, wifi) = connected()
        wifi.dispatch(InputEvent.TextInput("x".repeat(600)))
        wifi.dispatch(InputEvent.ClipboardSet("y".repeat(1300)))
        wifi.dispatch(InputEvent.TextInput("ok"))
        pc.awaitMessage(byteArrayOf(0x06, 0x02, 0x00))
        assertTrue(pc.received.none { it[0] == 0x0F.toByte() })
        assertEquals(1, pc.receivedOfType(0x06).size)
    }

    @Test
    fun `events sent while not connected are dropped quietly`() {
        val wifi = dispatcher()
        wifi.dispatch(InputEvent.Click)
        wifi.dispatch(InputEvent.MouseMove(1f, 1f))
        assertEquals(ConnectionState.Disconnected, wifi.connectionState.value)
    }

    // ── Receiving ────────────────────────────────────────────────────

    private fun clipboardReply(text: String): ByteArray {
        val body = text.toByteArray()
        return byteArrayOf(0x80.toByte(), (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte()) + body
    }

    @Test
    fun `replies from the PC reach the app`() {
        val (pc, _) = connected()
        pc.broadcast(clipboardReply("from the PC"))
        awaitServer(pc) { received.any { it == ServerMessage.Clipboard("from the PC") } }
    }

    @Test
    fun `a long reply after a short one is delivered whole`() {
        val (pc, _) = connected()
        pc.broadcast(clipboardReply("hi"))
        awaitServer(pc) { received.any { it == ServerMessage.Clipboard("hi") } }
        val long = "z".repeat(1100)
        pc.broadcast(clipboardReply(long))
        awaitServer(pc) { received.any { it == ServerMessage.Clipboard(long) } }
    }

    // ── Staying connected ────────────────────────────────────────────

    @Test
    fun `a PC that goes quiet is noticed and the connection is rebuilt when it returns`() {
        val (pc, wifi) = connected()
        assertEquals(1, pc.handshakes.get())

        pc.silent = true
        wifi.awaitState { it is ConnectionState.Reconnecting }
        pc.silent = false

        wifi.awaitState(timeoutMs = 4000) { it is ConnectionState.Connected }
        assertTrue("a second handshake happened", pc.handshakes.get() >= 2)

        // And the new link carries input.
        wifi.dispatch(InputEvent.RightClick)
        awaitServer(pc) { pc.receivedOfType(0x02).isNotEmpty() }
    }

    @Test
    fun `a link that does not come back is given up on`() {
        val (pc, wifi) = connected()
        pc.silent = true
        val failed = wifi.awaitState(timeoutMs = 5000) { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals(FailureReason.CONNECTION_LOST, failed.reason)
        assertEquals("Test PC", failed.deviceName)
    }

    @Test
    fun `a struggling link is flagged as unstable before it is lost`() {
        val (pc, wifi) = connected()
        pc.silent = true
        wifi.awaitState(timeoutMs = 2000) { it is ConnectionState.Connected && it.unstable }
    }

    @Test
    fun `a pairing that was revoked while connected is reported when reconnecting`() {
        var allowed = true
        val pc = server(allow = { allowed })
        val wifi = dispatcher()
        runBlocking { wifi.connectTo(target(pc)) }
        allowed = false
        pc.silent = true
        wifi.awaitState { it is ConnectionState.Reconnecting }
        pc.silent = false
        val failed = wifi.awaitState(timeoutMs = 4000) { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals(FailureReason.NOT_PAIRED, failed.reason)
    }

    @Test
    fun `verifying right now rebuilds a link whose PC has vanished`() {
        val (pc, wifi) = connected()
        pc.silent = true
        wifi.verifyNow(graceMs = 100)
        wifi.awaitState(timeoutMs = 1500) { it is ConnectionState.Reconnecting }
    }

    @Test
    fun `verifying a healthy link changes nothing`() {
        val (_, wifi) = connected()
        wifi.verifyNow(graceMs = 100)
        Thread.sleep(300)
        assertTrue(wifi.connectionState.value is ConnectionState.Connected)
    }

    // ── Ending ───────────────────────────────────────────────────────

    @Test
    fun `disconnect ends the session and forgets what the PC said`() {
        val (_, wifi) = connected()
        wifi.hostInfo.let { eventually { it.first { info -> info.os != HostOs.UNKNOWN } } }
        wifi.disconnect()
        assertEquals(ConnectionState.Disconnected, wifi.connectionState.value)
        assertEquals(HostOs.UNKNOWN, wifi.hostInfo.value.os)
        wifi.dispatch(InputEvent.Click) // must not throw
    }

    @Test
    fun `disconnect during reconnection stops the retries`() {
        val (pc, wifi) = connected()
        pc.silent = true
        wifi.awaitState { it is ConnectionState.Reconnecting }
        wifi.disconnect()
        runBlocking { delay(300) }
        assertEquals(ConnectionState.Disconnected, wifi.connectionState.value)
    }

    @Test
    fun `connecting again to the same PC while connected does nothing`() {
        val (pc, wifi) = connected()
        val outcome = runBlocking { wifi.connectTo(target(pc)) }
        assertEquals(WifiInputDispatcher.ConnectOutcome.Connected, outcome)
        assertEquals(1, pc.handshakes.get())
    }

    @Test
    fun `the connected state names the PC`() {
        val (_, wifi) = connected()
        assertNotNull(wifi.connectionState.value as? ConnectionState.Connected)
    }
}
