package com.omsingh.telepad.core.wifi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryPacketsTest {

    private fun parse(bytes: ByteArray, len: Int = bytes.size) = DiscoveryPackets.parse(bytes, len)

    @Test
    fun `the probe is the tag and eight bytes of magic`() {
        val probe = DiscoveryPackets.probe()
        assertEquals(9, probe.size)
        assertEquals(0xC3.toByte(), probe[0])
        assertArrayEquals(
            byteArrayOf(0x54, 0xE7.toByte(), 0x9A.toByte(), 0x03, 0x21, 0xC8.toByte(), 0xBE.toByte(), 0xFE.toByte()),
            probe.copyOfRange(1, 9),
        )
    }

    @Test
    fun `a tagged announcement carries the hostname`() {
        val packet = byteArrayOf(0xC4.toByte()) + "TELEPAD_PONG:Desk PC".toByteArray()
        assertEquals(DiscoveryPackets.Parsed.Announcement("Desk PC"), parse(packet))
    }

    @Test
    fun `a bare announcement from an older server is accepted too`() {
        assertEquals(
            DiscoveryPackets.Parsed.Announcement("old-server"),
            parse("TELEPAD_PONG:old-server".toByteArray()),
        )
    }

    @Test
    fun `hostnames are cleaned for display`() {
        val dirty = byteArrayOf(0xC4.toByte()) + "TELEPAD_PONG:  Desk\u0007\nPC  ".toByteArray()
        assertEquals(DiscoveryPackets.Parsed.Announcement("DeskPC"), parse(dirty))
        assertEquals(64, DiscoveryPackets.cleanName("x".repeat(500)).length)
    }

    @Test
    fun `an announcement with an empty name still counts`() {
        assertEquals(DiscoveryPackets.Parsed.Announcement(""), parse(byteArrayOf(0xC4.toByte()) + "TELEPAD_PONG:".toByteArray()))
    }

    @Test
    fun `a public key reply is recognised`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val parsed = parse(byteArrayOf(0xC6.toByte()) + key)
        assertEquals(DiscoveryPackets.Parsed.PublicKey(key), parsed)
    }

    @Test
    fun `probes and other noise are not announcements`() {
        assertNull("our own probe echoed back", parse(DiscoveryPackets.probe()))
        assertNull(parse("hello world".toByteArray()))
        assertNull(parse(byteArrayOf(0xC4.toByte()) + "NOT_TELEPAD".toByteArray()))
        assertNull(parse(ByteArray(0)))
        assertNull(parse(ByteArray(3), 99))
    }

    @Test
    fun `only the bytes received are read`() {
        val buffer = ByteArray(64)
        val text = byteArrayOf(0xC4.toByte()) + "TELEPAD_PONG:Box".toByteArray()
        System.arraycopy(text, 0, buffer, 0, text.size)
        // The rest of the buffer holds junk from an earlier, longer packet.
        for (i in text.size until buffer.size) buffer[i] = 'Z'.code.toByte()
        assertEquals(DiscoveryPackets.Parsed.Announcement("Box"), parse(buffer, text.size))
    }
}
