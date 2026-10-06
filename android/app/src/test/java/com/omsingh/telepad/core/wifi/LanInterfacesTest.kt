package com.omsingh.telepad.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanInterfacesTest {

    @Test
    fun `wifi hotspot ethernet and tethering are local networks`() {
        for (name in listOf("wlan0", "wlan1", "ap0", "swlan0", "eth0", "rndis0", "usb0", "bt-pan", "p2p0", "WLAN0")) {
            assertTrue(name, LanInterfaces.isLan(name))
        }
    }

    @Test
    fun `mobile data and tunnels are not`() {
        for (name in listOf("rmnet_data0", "rmnet0", "ccmni0", "v4-rmnet_data0", "tun0", "ppp0", "dummy0", "lo", "wg0")) {
            assertFalse(name, LanInterfaces.isLan(name))
            assertTrue(name, LanInterfaces.isNotLan(name))
        }
    }

    @Test
    fun `picking keeps only the local interfaces when there are some`() {
        val picked = LanInterfaces.pick(listOf("lo", "rmnet_data0", "wlan0", "tun0", "ap0"))
        assertEquals(listOf("wlan0", "ap0"), picked)
    }

    @Test
    fun `unfamiliar names are used when nothing is recognised`() {
        // Some manufacturers name the Wi-Fi link in their own way.
        val picked = LanInterfaces.pick(listOf("lo", "rmnet0", "xyz0"))
        assertEquals(listOf("xyz0"), picked)
    }

    @Test
    fun `picking from nothing gives nothing`() {
        assertTrue(LanInterfaces.pick(emptyList()).isEmpty())
        assertTrue(LanInterfaces.pick(listOf("lo", "rmnet0")).isEmpty())
    }
}
