package com.omsingh.telepad.core.connection

/**
 * Decides whether a UDP "connection" is still alive.
 *
 * UDP has no connection to break, so without help the app would show *Connected*
 * long after the PC was switched off or the phone left the Wi-Fi, and the user
 * would only find out by their input going nowhere. The remedy is a tiny
 * question every [pingIntervalMs] that the PC answers (a host-info query); this
 * class keeps the books on when it was asked and when it last heard back.
 *
 * It also measures the round trip, smoothed so the number shown to the user
 * does not flicker.
 *
 * Time is always passed in, so behaviour is exact and testable. Not thread-safe.
 */
class LivenessMonitor(
    private val pingIntervalMs: Long = 2_000L,
    private val degradedAfterMs: Long = 4_500L,
    private val lostAfterMs: Long = 9_000L,
) {

    enum class Health {
        /** The PC answered recently. */
        HEALTHY,

        /** No answer for a while: the link is struggling, or about to drop. */
        DEGRADED,

        /** No answer for so long that the connection should be rebuilt. */
        LOST,
    }

    private var lastHeardMs = 0L
    private var lastPingSentMs = NEVER
    private var smoothedRtt = -1.0

    /** Smoothed round-trip time in milliseconds, or null before the first answer. */
    val latencyMs: Int? get() = if (smoothedRtt < 0) null else smoothedRtt.toInt().coerceAtLeast(1)

    /** Starts afresh for a new connection that was established at [nowMs]. */
    fun reset(nowMs: Long) {
        lastHeardMs = nowMs
        lastPingSentMs = NEVER
        smoothedRtt = -1.0
    }

    /** Whether it is time to ask the PC again. */
    fun shouldPing(nowMs: Long): Boolean = lastPingSentMs == NEVER || nowMs - lastPingSentMs >= pingIntervalMs

    fun onPingSent(nowMs: Long) {
        lastPingSentMs = nowMs
    }

    /** The PC answered the latest question; updates the round-trip time. */
    fun onPong(nowMs: Long) {
        if (lastPingSentMs != NEVER) {
            val rtt = (nowMs - lastPingSentMs).coerceAtLeast(0L).toDouble()
            smoothedRtt = if (smoothedRtt < 0) rtt else SMOOTHING * rtt + (1 - SMOOTHING) * smoothedRtt
        }
        lastHeardMs = nowMs
    }

    /** Any packet from the PC proves it is there, even one that is not an answer to a ping. */
    fun onPacket(nowMs: Long) {
        lastHeardMs = nowMs
    }

    /** Whether anything at all has been heard from the PC at or after [timeMs]. */
    fun heardSince(timeMs: Long): Boolean = lastHeardMs >= timeMs

    fun health(nowMs: Long): Health {
        val silence = nowMs - lastHeardMs
        return when {
            silence >= lostAfterMs -> Health.LOST
            silence >= degradedAfterMs -> Health.DEGRADED
            else -> Health.HEALTHY
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val SMOOTHING = 0.3
    }
}
