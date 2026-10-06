package com.omsingh.telepad.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FingerprintTest {

    @Test
    fun `same key produces same fingerprint`() {
        val key = ByteArray(32) { it.toByte() }
        val a = Fingerprint.of(key)
        val b = Fingerprint.of(key)
        assertEquals(a, b)
    }

    @Test
    fun `different keys produce different fingerprints`() {
        val k1 = ByteArray(32) { it.toByte() }
        val k2 = ByteArray(32) { (it + 1).toByte() }
        assertFalse(Fingerprint.of(k1) == Fingerprint.of(k2))
    }

    @Test
    fun `fingerprint is exactly 20 hex characters`() {
        val key = ByteArray(32) { 0x42 }
        val fp = Fingerprint.of(key)
        assertEquals(20, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun `format adds expected separators`() {
        val raw = "7F2AB9C14E0891D30AC7"
        assertEquals("7F2A · B9C1 · 4E08 · 91D3 · 0AC7", Fingerprint.format(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `format refuses a fingerprint of the old length`() {
        Fingerprint.format("7F2AB9C14E08")
    }

    @Test
    fun `base64 helper matches raw bytes path`() {
        val key = ByteArray(32) { 0x77 }
        val b64 = Base64.getEncoder().encodeToString(key)
        assertEquals(Fingerprint.of(key), Fingerprint.ofBase64(b64))
    }

    @Test
    fun `matches is constant-time-equal`() {
        val a = "7F2AB9C14E0891D30AC7"
        val b = "7F2AB9C14E0891D30AC7"
        val c = "7F2AB9C14E0891D30AC8"
        assertTrue(Fingerprint.matches(a, b))
        assertFalse(Fingerprint.matches(a, c))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-32-byte key throws`() {
        Fingerprint.of(ByteArray(31))
    }

    @Test
    fun `canonical cross-implementation test vectors`() {
        val all42 = ByteArray(32) { 0x42.toByte() }
        assertEquals("425E · D4E4 · A36B · 30EA · 21B9", Fingerprint.format(Fingerprint.of(all42)))

        val range0to31 = ByteArray(32) { it.toByte() }
        assertEquals("630D · CD29 · 66C4 · 3366 · 9112", Fingerprint.format(Fingerprint.of(range0to31)))
    }

    @Test
    fun `the first three groups are what the shorter fingerprint used to show`() {
        val all42 = ByteArray(32) { 0x42.toByte() }
        assertTrue(Fingerprint.format(Fingerprint.of(all42)).startsWith("425E · D4E4 · A36B"))
    }
}
