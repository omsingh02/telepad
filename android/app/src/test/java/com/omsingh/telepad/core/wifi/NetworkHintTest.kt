package com.omsingh.telepad.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone's own network says about a PC it could not reach. */
class NetworkHintTest {

    private val home = LocalNetwork("192.168.0.42", 24)

    @Test
    fun `a PC on the phone's own network is the same network, so a firewall is the likely cause`() {
        assertEquals(NetworkHint.SameNetwork("192.168.0.109"), NetworkHint.between(listOf(home), "192.168.0.109"))
    }

    @Test
    fun `a PC on another network is another network, and the hint names both addresses`() {
        assertEquals(
            NetworkHint.DifferentNetwork("192.168.0.42", "10.10.189.161"),
            NetworkHint.between(listOf(home), "10.10.189.161"),
        )
        // Same first three numbers are not enough on a /24: 192.168.1.x is not 192.168.0.x.
        assertEquals(
            NetworkHint.DifferentNetwork("192.168.0.42", "192.168.1.109"),
            NetworkHint.between(listOf(home), "192.168.1.109"),
        )
    }

    @Test
    fun `how wide the network is decides what is on it`() {
        val wide = LocalNetwork("192.168.0.42", 16)
        assertEquals(NetworkHint.SameNetwork("192.168.1.9"), NetworkHint.between(listOf(wide), "192.168.1.9"))
        val narrow = LocalNetwork("192.168.0.130", 25)
        assertEquals(NetworkHint.SameNetwork("192.168.0.200"), NetworkHint.between(listOf(narrow), "192.168.0.200"))
        assertEquals(NetworkHint.DifferentNetwork("192.168.0.130", "192.168.0.5"), NetworkHint.between(listOf(narrow), "192.168.0.5"))
    }

    @Test
    fun `any of several networks will do`() {
        val both = listOf(LocalNetwork("172.17.0.2", 16), home)
        assertEquals(NetworkHint.SameNetwork("192.168.0.109"), NetworkHint.between(both, "192.168.0.109"))
    }

    @Test
    fun `a phone with no network at all says so`() {
        assertEquals(NetworkHint.NoNetwork, NetworkHint.between(emptyList(), "192.168.0.109"))
    }

    @Test
    fun `an address that is a name or not IPv4 says nothing`() {
        for (host in listOf("deed.local", "fe80::1", "", "192.168.0", "192.168.0.256", "192.168.0.1.5", "a.b.c.d", "1234.1.1.1")) {
            assertEquals(host, NetworkHint.Unknown, NetworkHint.between(listOf(home), host))
        }
    }

    @Test
    fun `a PC with several addresses is on the same network if one of them is`() {
        val hosts = listOf("10.10.189.161", "192.168.0.109", "100.95.242.28")
        assertEquals(NetworkHint.SameNetwork("192.168.0.109"), NetworkHint.betweenAny(listOf(home), hosts))
        assertEquals(
            NetworkHint.DifferentNetwork("192.168.0.42", "10.10.189.161"),
            NetworkHint.betweenAny(listOf(home), listOf("10.10.189.161", "100.95.242.28")),
        )
        assertEquals(NetworkHint.Unknown, NetworkHint.betweenAny(listOf(home), emptyList()))
        assertEquals(NetworkHint.Unknown, NetworkHint.betweenAny(listOf(home), listOf("deed.local")))
    }
}
