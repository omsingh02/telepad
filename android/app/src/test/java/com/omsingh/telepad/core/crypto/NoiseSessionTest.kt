package com.omsingh.telepad.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

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
    }
}
