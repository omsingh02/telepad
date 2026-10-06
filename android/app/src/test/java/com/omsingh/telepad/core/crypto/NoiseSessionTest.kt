package com.omsingh.telepad.core.crypto

import com.omsingh.telepad.testing.NoiseTestServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Unit tests for [NoiseSession].
 *
 * The handshake itself requires UDP I/O and is covered by integration tests.
 * Here we focus on the parts that can be tested in isolation:
 *  - encrypt/decrypt round-trip via direct CipherState manipulation.
 *  - Fail-closed behaviour on unestablished session.
 */
class NoiseSessionTest {

    @Test
    fun `unestablished session returns -1 from encrypt`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val session = NoiseSession(key)
        val out = ByteArray(64)
        val result = session.encrypt(byteArrayOf(1, 2, 3), 0, 3, out, 0)
        assertEquals(-1L, result)
    }

    @Test
    fun `unestablished session returns -1 from decrypt`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val session = NoiseSession(key)
        val out = ByteArray(64)
        val result = session.decrypt(0L, byteArrayOf(1, 2, 3, 4), 0, 4, out, 0)
        assertEquals(-1, result)
    }

    @Test
    fun `replay window handles in-order and rejects duplicates`() {
        val window = ReplayWindow()
        assertTrue(window.check(0L))
        window.update(0L)
        assertFalse(window.check(0L))

        assertTrue(window.check(1L))
        window.update(1L)
        assertFalse(window.check(1L))
        assertFalse(window.check(0L))
    }

    @Test
    fun `replay window handles out-of-order packets`() {
        val window = ReplayWindow()
        // Receive 0 then 2
        assertTrue(window.check(0L))
        window.update(0L)
        assertTrue(window.check(2L))
        window.update(2L)

        // 1 arrives late: should be accepted
        assertTrue(window.check(1L))
        window.update(1L)

        // Duplicate 1 should now be rejected
        assertFalse(window.check(1L))
        assertFalse(window.check(2L))
        assertFalse(window.check(0L))

        // 3 arrives: accepted
        assertTrue(window.check(3L))
        window.update(3L)
    }

    @Test
    fun `replay window drops packets beyond 128 threshold`() {
        val window = ReplayWindow()
        assertTrue(window.check(0L))
        window.update(0L)

        // Advance to 130
        assertTrue(window.check(130L))
        window.update(130L)

        // 0 and 1 are beyond 128 packets behind 130
        assertFalse(window.check(0L))
        assertFalse(window.check(1L))
        assertFalse(window.check(2L))

        // 129 is within window
        assertTrue(window.check(129L))
        window.update(129L)
        assertFalse(window.check(129L))
    }

    @Test
    fun `isEstablished is false at construction`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val session = NoiseSession(key)
        assertFalse(session.isEstablished)
    }

    @Test
    fun `reset clears established state`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val session = NoiseSession(key)
        session.reset()  // safe pre-establishment
        assertFalse(session.isEstablished)
    }

    @Test
    fun `protocol constants match wire format spec`() {
        assertEquals(96, NoiseSession.NOISE_IK_MSG1_LEN)
        assertEquals(48, NoiseSession.NOISE_IK_MSG2_LEN)
        assertEquals(0xC0.toByte(), NoiseSession.WIRE_HANDSHAKE_INIT)
        assertEquals(0xC1.toByte(), NoiseSession.WIRE_HANDSHAKE_RESP)
        assertEquals(0xC2.toByte(), NoiseSession.WIRE_TRANSPORT)
        assertEquals(0xC7.toByte(), NoiseSession.WIRE_PAIRING_REJECTED)
    }

    /**
     * Runs [NoiseSession.runHandshake] against a fake UDP "server" on loopback
     * that answers the first datagram it receives with [reply].
     */
    private fun handshakeAgainstFakeServer(reply: ByteArray): HandshakeResult {
        val loopback = InetAddress.getLoopbackAddress()
        return DatagramSocket(0, loopback).use { server ->
            DatagramSocket().use { client ->
                client.connect(loopback, server.localPort)
                val responder = thread(isDaemon = true) {
                    val buf = ByteArray(256)
                    val request = DatagramPacket(buf, buf.size)
                    server.receive(request)
                    server.send(DatagramPacket(reply, reply.size, request.socketAddress))
                }
                val session = NoiseSession(ByteArray(32).also { SecureRandom().nextBytes(it) })
                val serverKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
                val result = session.runHandshake(client, serverKey)
                responder.join(2000)
                assertFalse(
                    "a failed handshake must not leave an established session",
                    session.isEstablished
                )
                result
            }
        }
    }

    @Test
    fun `a pairing rejection from the server is reported as not paired`() {
        val rejection = byteArrayOf(NoiseSession.WIRE_PAIRING_REJECTED)
        assertEquals(HandshakeResult.NOT_PAIRED, handshakeAgainstFakeServer(rejection))
    }

    @Test
    fun `an unusable reply counts as no reply, not as a rejection`() {
        assertEquals(HandshakeResult.NO_REPLY, handshakeAgainstFakeServer(byteArrayOf(0x42)))
        assertEquals(
            HandshakeResult.NO_REPLY,
            handshakeAgainstFakeServer(byteArrayOf(NoiseSession.WIRE_TRANSPORT, 1, 2, 3))
        )
        // The right tag but the wrong size is not a handshake reply either.
        assertEquals(
            HandshakeResult.NO_REPLY,
            handshakeAgainstFakeServer(byteArrayOf(NoiseSession.WIRE_HANDSHAKE_RESP, 1, 2, 3))
        )
    }

    // ── Against a real Noise responder ───────────────────────────────────

    private fun newClientKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Opens a socket for one handshake attempt towards [server], as the app does. */
    private fun <T> attempt(server: NoiseTestServer, body: (DatagramSocket) -> T): T =
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getLoopbackAddress(), server.port)
            body(socket)
        }

    @Test
    fun `a handshake with a real responder yields a working encrypted session`() {
        NoiseTestServer().use { server ->
            val session = NoiseSession(newClientKey())
            attempt(server) { socket ->
                assertEquals(HandshakeResult.ESTABLISHED, session.runHandshake(socket, server.publicKeyBase64))
                assertTrue(session.isEstablished)

                // Phone to PC: encrypt, frame as the app does, and let the server decrypt it.
                val plain = byteArrayOf(0x04, 0x1E, 0x00, 0x00)
                val cipher = ByteArray(plain.size + 16)
                val nonce = session.encrypt(plain, 0, plain.size, cipher, 0)
                assertEquals(0L, nonce)
                val frame = ByteArray(9 + cipher.size)
                frame[0] = NoiseSession.WIRE_TRANSPORT
                for (i in 0 until 8) frame[1 + i] = ((nonce ushr (8 * i)) and 0xFF).toByte()
                System.arraycopy(cipher, 0, frame, 9, cipher.size)
                socket.send(DatagramPacket(frame, frame.size))

                val deadline = System.currentTimeMillis() + 2000
                while (server.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, server.received.size)
                assertTrue(plain.contentEquals(server.received[0]))

                // PC to phone: the server replies to a host-info query.
                val query = byteArrayOf(0x12)
                val queryCipher = ByteArray(query.size + 16)
                val queryNonce = session.encrypt(query, 0, query.size, queryCipher, 0)
                val queryFrame = ByteArray(9 + queryCipher.size)
                queryFrame[0] = NoiseSession.WIRE_TRANSPORT
                for (i in 0 until 8) queryFrame[1 + i] = ((queryNonce ushr (8 * i)) and 0xFF).toByte()
                System.arraycopy(queryCipher, 0, queryFrame, 9, queryCipher.size)
                socket.send(DatagramPacket(queryFrame, queryFrame.size))

                socket.soTimeout = 2000
                val rx = ByteArray(256)
                val reply = DatagramPacket(rx, rx.size)
                socket.receive(reply)
                assertEquals(NoiseSession.WIRE_TRANSPORT, rx[0])
                var replyNonce = 0L
                for (i in 0 until 8) replyNonce = replyNonce or ((rx[1 + i].toLong() and 0xFF) shl (8 * i))
                val out = ByteArray(reply.length)
                val n = session.decrypt(replyNonce, rx, 9, reply.length - 9, out, 0)
                assertEquals(6, n)
                assertEquals(0x82.toByte(), out[0])

                // The same datagram again is a replay and must be refused.
                assertEquals(-1, session.decrypt(replyNonce, rx, 9, reply.length - 9, out, 0))
            }
        }
    }

    @Test
    fun `a phone the PC has not paired is refused`() {
        NoiseTestServer(allowClient = { false }).use { server ->
            val session = NoiseSession(newClientKey())
            attempt(server) { socket ->
                assertEquals(HandshakeResult.NOT_PAIRED, session.runHandshake(socket, server.publicKeyBase64))
                assertFalse(session.isEstablished)
            }
        }
    }

    @Test
    fun `a PC whose key is not the one we expect stays silent`() {
        NoiseTestServer().use { server ->
            val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
            val session = NoiseSession(newClientKey())
            attempt(server) { socket ->
                // The server cannot decrypt the first message and says nothing at all.
                assertEquals(HandshakeResult.NO_REPLY, session.runHandshake(socket, wrongKey, timeoutMs = 400))
                assertFalse(session.isEstablished)
            }
            assertEquals(0, server.handshakes.get())
        }
    }

    @Test
    fun `a PC that is off gives no reply within the timeout`() {
        NoiseTestServer().use { server ->
            server.silent = true
            val session = NoiseSession(newClientKey())
            attempt(server) { socket ->
                val started = System.currentTimeMillis()
                assertEquals(HandshakeResult.NO_REPLY, session.runHandshake(socket, server.publicKeyBase64, timeoutMs = 300))
                val took = System.currentTimeMillis() - started
                assertTrue("waited about the timeout, not forever: $took ms", took in 250..1500)
            }
        }
    }

    @Test
    fun `a fresh attempt after a lost handshake succeeds`() {
        NoiseTestServer().use { server ->
            server.dropNextHandshakes = 1
            val first = NoiseSession(newClientKey())
            val key = newClientKey()
            val firstResult = attempt(server) { first.runHandshake(it, server.publicKeyBase64, timeoutMs = 300) }
            assertEquals(HandshakeResult.NO_REPLY, firstResult)

            val second = NoiseSession(key)
            val secondResult = attempt(server) { second.runHandshake(it, server.publicKeyBase64, timeoutMs = 2000) }
            assertEquals(HandshakeResult.ESTABLISHED, secondResult)
            assertTrue(second.isEstablished)
        }
    }

    @Test
    fun `a session that was reset can no longer be used`() {
        NoiseTestServer().use { server ->
            val session = NoiseSession(newClientKey())
            attempt(server) { assertEquals(HandshakeResult.ESTABLISHED, session.runHandshake(it, server.publicKeyBase64)) }
            session.reset()
            assertFalse(session.isEstablished)
            assertEquals(-1L, session.encrypt(byteArrayOf(1), 0, 1, ByteArray(32), 0))
        }
    }
}
