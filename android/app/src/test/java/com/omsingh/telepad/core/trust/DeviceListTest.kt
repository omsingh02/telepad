package com.omsingh.telepad.core.trust

import com.omsingh.telepad.core.wifi.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListTest {

    private val desk = PairedDevice("KEY-DESK", "Desk PC", "192.168.1.20", 5000, lastConnectedMs = 500)
    private val laptop = PairedDevice("KEY-LAPTOP", "Laptop", "192.168.1.31", 5000, lastConnectedMs = 900)

    private fun seen(name: String, host: String, key: String? = null, port: Int = 5000) =
        DiscoveredServer(name, host, port, key, lastSeenMs = 0)

    @Test
    fun `nothing paired and nothing nearby is an empty list`() {
        assertTrue(DeviceList.build(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a PC nobody has paired shows up as new`() {
        val list = DeviceList.build(emptyList(), listOf(seen("Guest", "10.0.0.8", "K1")))
        val entry = list.single()
        assertFalse(entry.paired)
        assertTrue(entry.online)
        assertEquals("K1", entry.publicKey)
    }

    @Test
    fun `a paired PC that is not announcing is offline at its last address`() {
        val entry = DeviceList.build(listOf(desk), emptyList()).single()
        assertTrue(entry.paired)
        assertFalse(entry.online)
        assertEquals("192.168.1.20", entry.host)
        assertEquals("Desk PC", entry.name)
    }

    @Test
    fun `a paired PC is online when its key is on the network, at the address it was seen`() {
        val moved = seen("Desk PC", "192.168.1.77", "KEY-DESK")
        val entry = DeviceList.build(listOf(desk), listOf(moved)).single()
        assertTrue(entry.online)
        assertEquals("the fresh address wins over the stored one", "192.168.1.77", entry.host)
    }

    @Test
    fun `before the key is known a PC at the paired address counts as that PC`() {
        val entry = DeviceList.build(listOf(desk), listOf(seen("Desk PC", "192.168.1.20"))).single()
        assertTrue(entry.paired)
        assertTrue(entry.online)
    }

    @Test
    fun `a different key at the paired address is not the paired PC`() {
        val list = DeviceList.build(listOf(desk), listOf(seen("Desk PC", "192.168.1.20", "KEY-OTHER")))
        assertEquals(2, list.size)
        assertTrue(list[0].paired && !list[0].online)
        assertTrue(!list[1].paired && list[1].online)
    }

    @Test
    fun `a rename on the PC shows its current name`() {
        val entry = DeviceList.build(listOf(desk), listOf(seen("Office PC", "192.168.1.20", "KEY-DESK"))).single()
        assertEquals("Office PC", entry.name)
    }

    @Test
    fun `a blank announced name keeps the stored one`() {
        val entry = DeviceList.build(listOf(desk), listOf(seen("", "192.168.1.20", "KEY-DESK"))).single()
        assertEquals("Desk PC", entry.name)
    }

    @Test
    fun `online paired PCs come first, then by how recently they were used, then new ones`() {
        val third = PairedDevice("KEY-3", "Third", "10.0.0.3", 5000, lastConnectedMs = 100)
        val list = DeviceList.build(
            paired = listOf(desk, laptop, third),
            discovered = listOf(seen("Desk PC", "192.168.1.20", "KEY-DESK"), seen("Zed", "10.0.0.9", "KEY-ZED"), seen("Alpha", "10.0.0.8", "KEY-A")),
        )
        assertEquals(listOf("Desk PC", "Laptop", "Third", "Alpha", "Zed"), list.map { it.name })
        assertEquals(listOf(true, false, false, true, true), list.map { it.online })
    }

    @Test
    fun `one discovered PC matches at most one paired entry`() {
        val twin = desk.copy(publicKey = "KEY-TWIN", name = "Twin")
        val list = DeviceList.build(listOf(desk, twin), listOf(seen("Desk PC", "192.168.1.20")))
        assertEquals(1, list.count { it.online })
    }

    @Test
    fun `an entry's id is its key, or its address while the key is unknown`() {
        assertEquals("KEY-DESK", DeviceList.build(listOf(desk), emptyList()).single().id)
        assertEquals("10.0.0.8:5000", DeviceList.build(emptyList(), listOf(seen("X", "10.0.0.8"))).single().id)
    }

    // ── Automatic connection ─────────────────────────────────────────────

    @Test
    fun `the most recently used online PC is the one to connect to automatically`() {
        val entries = DeviceList.build(
            listOf(desk, laptop),
            listOf(seen("Desk PC", "192.168.1.20", "KEY-DESK"), seen("Laptop", "192.168.1.31", "KEY-LAPTOP")),
        )
        assertEquals("Laptop", DeviceList.autoConnectCandidate(entries)?.name)
    }

    @Test
    fun `an offline PC is never auto connected, even if it is the latest`() {
        val entries = DeviceList.build(listOf(desk, laptop), listOf(seen("Desk PC", "192.168.1.20", "KEY-DESK")))
        assertEquals("Desk PC", DeviceList.autoConnectCandidate(entries)?.name)
    }

    @Test
    fun `new PCs and never-used PCs are not auto connected`() {
        val unused = desk.copy(lastConnectedMs = 0)
        val entries = DeviceList.build(listOf(unused), listOf(seen("Desk PC", "192.168.1.20", "KEY-DESK"), seen("New", "10.0.0.9", "K")))
        assertNull(DeviceList.autoConnectCandidate(entries))
    }

    @Test
    fun `the operating system of a paired PC comes from what was remembered`() {
        val mac = desk.copy(osName = "MACOS")
        assertEquals(com.omsingh.telepad.core.host.HostOs.MACOS, DeviceList.build(listOf(mac), emptyList()).single().os)
        assertEquals(com.omsingh.telepad.core.host.HostOs.UNKNOWN, DeviceList.build(listOf(desk), emptyList()).single().os)
        val garbage = desk.copy(osName = "BEOS")
        assertEquals(com.omsingh.telepad.core.host.HostOs.UNKNOWN, DeviceList.build(listOf(garbage), emptyList()).single().os)
    }
}
