package com.omsingh.telepad.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRegistryTest {

    private val registry = DiscoveryRegistry(ttlMs = 15_000)

    @Test
    fun `an announcement makes a server visible`() {
        assertTrue(registry.onAnnouncement("192.168.1.20", 5000, "Desk PC", nowMs = 0))
        val servers = registry.snapshot(nowMs = 1_000)
        assertEquals(1, servers.size)
        assertEquals("Desk PC", servers[0].name)
        assertEquals("192.168.1.20", servers[0].host)
        assertEquals(5000, servers[0].port)
        assertNull(servers[0].publicKey)
    }

    @Test
    fun `a repeat announcement refreshes rather than duplicates`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        assertFalse("nothing visible changed", registry.onAnnouncement("10.0.0.2", 5000, "PC", 5_000))
        assertEquals(1, registry.snapshot(6_000).size)
    }

    @Test
    fun `a server that stops announcing disappears after the timeout`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        assertEquals(1, registry.snapshot(15_000).size)
        assertTrue(registry.snapshot(15_001).isEmpty())
    }

    @Test
    fun `hearing from a server again keeps it alive`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 14_000)
        assertEquals(1, registry.snapshot(28_000).size)
        assertTrue(registry.snapshot(29_001).isEmpty())
    }

    @Test
    fun `a rename is a visible change`() {
        registry.onAnnouncement("10.0.0.2", 5000, "Old", 0)
        assertTrue(registry.onAnnouncement("10.0.0.2", 5000, "New", 1))
        assertEquals("New", registry.snapshot(2).single().name)
    }

    @Test
    fun `a blank name falls back to the address and long names are cut`() {
        registry.onAnnouncement("10.0.0.2", 5000, "   ", 0)
        registry.onAnnouncement("10.0.0.3", 5000, "x".repeat(200), 0)
        val names = registry.snapshot(1).associate { it.host to it.name }
        assertEquals("10.0.0.2", names["10.0.0.2"])
        assertEquals(64, names["10.0.0.3"]!!.length)
    }

    @Test
    fun `the public key is attached to the right server and survives refreshes`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        assertTrue(registry.needsKey("10.0.0.2", 5000))
        registry.onPublicKey("10.0.0.2", 5000, "KEY")
        assertFalse(registry.needsKey("10.0.0.2", 5000))

        registry.onAnnouncement("10.0.0.2", 5000, "PC", 5_000)
        assertEquals("KEY", registry.snapshot(5_001).single().publicKey)
    }

    @Test
    fun `a key for an address nobody announced is ignored`() {
        registry.onPublicKey("10.9.9.9", 5000, "KEY")
        assertTrue(registry.snapshot(0).isEmpty())
    }

    @Test
    fun `one PC heard on two addresses is shown once, at the newest`() {
        registry.onAnnouncement("192.168.1.20", 5000, "Desk PC", 0)
        registry.onAnnouncement("10.0.0.7", 5000, "Desk PC", 4_000)
        registry.onPublicKey("192.168.1.20", 5000, "SAME-KEY")
        registry.onPublicKey("10.0.0.7", 5000, "SAME-KEY")

        val servers = registry.snapshot(5_000)
        assertEquals(1, servers.size)
        assertEquals("10.0.0.7", servers[0].host)
    }

    @Test
    fun `two PCs with different keys are two entries even with the same name`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        registry.onAnnouncement("10.0.0.3", 5000, "PC", 0)
        registry.onPublicKey("10.0.0.2", 5000, "KEY-A")
        registry.onPublicKey("10.0.0.3", 5000, "KEY-B")
        assertEquals(2, registry.snapshot(1).size)
    }

    @Test
    fun `servers whose key is not known yet are never merged`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        registry.onAnnouncement("10.0.0.3", 5000, "PC", 0)
        assertEquals(2, registry.snapshot(1).size)
    }

    @Test
    fun `an address that never answers is not shown as a second PC`() {
        // A PC with Tailscale: its announcement reaches the phone stamped with the VPN address,
        // which the phone cannot reach, so only the Wi-Fi address ever answers with a key.
        registry.onAnnouncement("100.95.242.28", 5000, "deed", 0)
        registry.onAnnouncement("192.168.0.108", 5000, "deed", 0)
        registry.onPublicKey("192.168.0.108", 5000, "DEED-KEY")

        val servers = registry.snapshot(1_000)
        assertEquals(listOf("192.168.0.108"), servers.map { it.host })
    }

    @Test
    fun `the unanswered address is shown again when it is the only one left`() {
        registry.onAnnouncement("100.95.242.28", 5000, "deed", 0)
        registry.onAnnouncement("192.168.0.108", 5000, "deed", 0)
        registry.onPublicKey("192.168.0.108", 5000, "DEED-KEY")
        registry.onAnnouncement("100.95.242.28", 5000, "deed", 10_000) // keeps being heard; the other goes quiet

        assertEquals(listOf("100.95.242.28"), registry.snapshot(16_000).map { it.host })
    }

    @Test
    fun `an unanswered address of a PC with another name is still shown`() {
        registry.onAnnouncement("192.168.0.108", 5000, "deed", 0)
        registry.onPublicKey("192.168.0.108", 5000, "DEED-KEY")
        registry.onAnnouncement("192.168.0.77", 5000, "Living room", 0)

        assertEquals(listOf("deed", "Living room"), registry.snapshot(1_000).map { it.name })
    }

    @Test
    fun `names are compared ignoring case`() {
        registry.onAnnouncement("192.168.0.108", 5000, "Deed", 0)
        registry.onAnnouncement("100.95.242.28", 5000, "DEED", 0)
        registry.onPublicKey("192.168.0.108", 5000, "DEED-KEY")

        assertEquals(1, registry.snapshot(1_000).size)
    }

    @Test
    fun `the list is sorted by name ignoring case, then address`() {
        registry.onAnnouncement("10.0.0.9", 5000, "beta", 0)
        registry.onAnnouncement("10.0.0.8", 5000, "Alpha", 0)
        registry.onAnnouncement("10.0.0.7", 5000, "alpha", 0)
        assertEquals(listOf("10.0.0.7", "10.0.0.8", "10.0.0.9"), registry.snapshot(1).map { it.host })
    }

    @Test
    fun `the same host on different ports are different servers`() {
        registry.onAnnouncement("10.0.0.2", 5000, "A", 0)
        registry.onAnnouncement("10.0.0.2", 5001, "B", 0)
        assertEquals(2, registry.snapshot(1).size)
    }

    @Test
    fun `address matching ignores case`() {
        registry.onAnnouncement("Desk.local", 5000, "PC", 0)
        registry.onAnnouncement("desk.LOCAL", 5000, "PC", 1)
        assertEquals(1, registry.snapshot(2).size)
    }

    @Test
    fun `clear empties the list`() {
        registry.onAnnouncement("10.0.0.2", 5000, "PC", 0)
        registry.clear()
        assertTrue(registry.snapshot(1).isEmpty())
    }
}
