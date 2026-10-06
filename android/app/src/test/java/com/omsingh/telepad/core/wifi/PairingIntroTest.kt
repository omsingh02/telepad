package com.omsingh.telepad.core.wifi

import com.omsingh.telepad.testing.NoiseTestServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress

class PairingIntroTest {

    @Test
    fun `the request is a single tag byte`() {
        assertArrayEquals(byteArrayOf(0xC5.toByte()), PairingIntro.requestPacket())
    }

    @Test
    fun `a reply carries the 32 byte key`() {
        val key = ByteArray(32) { it.toByte() }
        val packet = byteArrayOf(0xC6.toByte()) + key
        assertArrayEquals(key, PairingIntro.parseReply(packet, packet.size))
    }

    @Test
    fun `replies that are wrong are refused`() {
        val key = ByteArray(32)
        assertNull("wrong tag", PairingIntro.parseReply(byteArrayOf(0xC4.toByte()) + key, 33))
        assertNull("too short", PairingIntro.parseReply(byteArrayOf(0xC6.toByte()) + ByteArray(31), 32))
        assertNull("length beyond the buffer", PairingIntro.parseReply(byteArrayOf(0xC6.toByte()) + key, 99))
        assertNull("empty", PairingIntro.parseReply(ByteArray(0), 0))
    }

    @Test
    fun `extra bytes after the key are ignored`() {
        val key = ByteArray(32) { 5 }
        val packet = byteArrayOf(0xC6.toByte()) + key + byteArrayOf(1, 2, 3)
        assertArrayEquals(key, PairingIntro.parseReply(packet, packet.size))
    }

    @Test
    fun `a running server hands over its public key`() = runBlocking {
        NoiseTestServer().use { server ->
            val key = PairingIntro.fetchPublicKey("127.0.0.1", server.port, timeoutPerAttemptMs = 500)
            assertNotNull(key)
            assertArrayEquals(server.publicKey, key)
        }
    }

    @Test
    fun `a server that is not there gives null instead of hanging`() = runBlocking {
        // A port nobody listens on: bind and release one.
        val port = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val started = System.currentTimeMillis()
        val key = PairingIntro.fetchPublicKey("127.0.0.1", port, attempts = 2, timeoutPerAttemptMs = 150)
        assertNull(key)
        assertEquals(true, System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun `a silent server is not an answer`() = runBlocking {
        NoiseTestServer().use { server ->
            server.silent = true
            assertNull(PairingIntro.fetchPublicKey("127.0.0.1", server.port, attempts = 2, timeoutPerAttemptMs = 150))
        }
    }

    @Test
    fun `an unresolvable address gives null`() = runBlocking {
        assertNull(PairingIntro.fetchPublicKey("not a host name", 5000, attempts = 1, timeoutPerAttemptMs = 100))
    }

    // ── identify: name and key together ──────────────────────────────────

    @Test
    fun `identify returns the PC's name and key`() = runBlocking {
        NoiseTestServer(hostname = "Living-Room").use { server ->
            val identity = PairingIntro.identify("127.0.0.1", server.port)
            assertNotNull(identity)
            assertEquals("Living-Room", identity!!.name)
            assertEquals(server.publicKeyBase64, identity.publicKeyBase64)
        }
    }

    @Test
    fun `identify gives null for a PC that is not there`() = runBlocking {
        val port = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(PairingIntro.identify("127.0.0.1", port, attempts = 1, timeoutPerAttemptMs = 150))
    }
}
