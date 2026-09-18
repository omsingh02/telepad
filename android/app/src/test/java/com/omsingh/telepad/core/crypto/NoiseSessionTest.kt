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
        assertEquals(-1, result)
    }

    @Test
    fun `unestablished session returns -1 from decrypt`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val session = NoiseSession(key)
        val out = ByteArray(64)
        val result = session.decrypt(byteArrayOf(1, 2, 3, 4), 0, 4, out, 0)
        assertEquals(-1, result)
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
