package com.omsingh.telepad.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    private val policy = ReconnectPolicy(baseDelayMs = 400, maxDelayMs = 8_000, giveUpAfterMs = 120_000)

    @Test
    fun `the first retry is immediate`() {
        assertEquals(0L, policy.delayBeforeAttempt(1))
        assertEquals(0L, policy.delayBeforeAttempt(0))
    }

    @Test
    fun `waits double from the base delay`() {
        assertEquals(400L, policy.delayBeforeAttempt(2))
        assertEquals(800L, policy.delayBeforeAttempt(3))
        assertEquals(1_600L, policy.delayBeforeAttempt(4))
        assertEquals(3_200L, policy.delayBeforeAttempt(5))
        assertEquals(6_400L, policy.delayBeforeAttempt(6))
    }

    @Test
    fun `waits never exceed the ceiling`() {
        assertEquals(8_000L, policy.delayBeforeAttempt(7))
        assertEquals(8_000L, policy.delayBeforeAttempt(50))
        assertEquals(8_000L, policy.delayBeforeAttempt(Int.MAX_VALUE))
    }

    @Test
    fun `gives up only after the deadline`() {
        assertFalse(policy.shouldGiveUp(0))
        assertFalse(policy.shouldGiveUp(119_999))
        assertTrue(policy.shouldGiveUp(120_000))
        assertTrue(policy.shouldGiveUp(10 * 60_000))
    }

    @Test
    fun `jitter is applied but still capped`() {
        val jittery = ReconnectPolicy(baseDelayMs = 400, maxDelayMs = 8_000, jitter = { it * 3 })
        assertEquals(1_200L, jittery.delayBeforeAttempt(2))
        assertEquals(8_000L, jittery.delayBeforeAttempt(10))

        val negative = ReconnectPolicy(jitter = { -5L })
        assertEquals(0L, negative.delayBeforeAttempt(3))
    }

    @Test
    fun `total patience matches the deadline order of magnitude`() {
        // Sum of waits before the deadline: sanity check that the schedule is not absurd.
        var elapsed = 0L
        var attempts = 0
        while (!policy.shouldGiveUp(elapsed)) {
            attempts++
            elapsed += policy.delayBeforeAttempt(attempts) + 50 /* the attempt itself */
        }
        assertTrue("attempts=$attempts", attempts in 15..40)
    }
}
