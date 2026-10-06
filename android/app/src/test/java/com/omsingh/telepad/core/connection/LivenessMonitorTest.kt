package com.omsingh.telepad.core.connection

import com.omsingh.telepad.core.connection.LivenessMonitor.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessMonitorTest {

    private val monitor = LivenessMonitor(pingIntervalMs = 2_000, degradedAfterMs = 4_500, lostAfterMs = 9_000)

    @Test
    fun `a fresh connection is healthy and asks straight away`() {
        monitor.reset(1_000)
        assertEquals(Health.HEALTHY, monitor.health(1_000))
        assertTrue(monitor.shouldPing(1_000))
        assertNull(monitor.latencyMs)
    }

    @Test
    fun `pings are paced by the interval`() {
        monitor.reset(0)
        monitor.onPingSent(0)
        assertFalse(monitor.shouldPing(1_999))
        assertTrue(monitor.shouldPing(2_000))
    }

    @Test
    fun `silence degrades and then loses the connection`() {
        monitor.reset(0)
        assertEquals(Health.HEALTHY, monitor.health(4_499))
        assertEquals(Health.DEGRADED, monitor.health(4_500))
        assertEquals(Health.DEGRADED, monitor.health(8_999))
        assertEquals(Health.LOST, monitor.health(9_000))
    }

    @Test
    fun `an answer restores health`() {
        monitor.reset(0)
        monitor.onPingSent(7_000)
        assertEquals(Health.DEGRADED, monitor.health(8_000))
        monitor.onPong(8_000)
        assertEquals(Health.HEALTHY, monitor.health(8_000))
        assertEquals(Health.HEALTHY, monitor.health(12_000))
    }

    @Test
    fun `any packet from the PC counts as proof of life`() {
        monitor.reset(0)
        monitor.onPacket(8_000)
        assertEquals(Health.HEALTHY, monitor.health(12_000))
        assertEquals(Health.LOST, monitor.health(17_000))
    }

    @Test
    fun `latency is the round trip of the ping`() {
        monitor.reset(0)
        monitor.onPingSent(100)
        monitor.onPong(112)
        assertEquals(12, monitor.latencyMs)
    }

    @Test
    fun `latency is smoothed so it does not flicker`() {
        monitor.reset(0)
        monitor.onPingSent(0); monitor.onPong(10)
        monitor.onPingSent(2_000); monitor.onPong(2_050) // one slow sample: 50 ms
        val after = monitor.latencyMs!!
        assertTrue("one spike must not swing it fully: $after", after in 11..30)
    }

    @Test
    fun `latency is never reported as zero`() {
        monitor.reset(0)
        monitor.onPingSent(5); monitor.onPong(5)
        assertEquals(1, monitor.latencyMs)
    }

    @Test
    fun `a stray answer before any ping does not invent a latency`() {
        monitor.reset(0)
        monitor.onPong(100)
        assertNull(monitor.latencyMs)
        assertEquals(Health.HEALTHY, monitor.health(100))
    }

    @Test
    fun `reset forgets the previous connection`() {
        monitor.reset(0)
        monitor.onPingSent(0); monitor.onPong(40)
        assertEquals(40, monitor.latencyMs)
        monitor.reset(50_000)
        assertNull(monitor.latencyMs)
        assertEquals(Health.HEALTHY, monitor.health(50_000))
        assertTrue(monitor.shouldPing(50_000))
    }
}
