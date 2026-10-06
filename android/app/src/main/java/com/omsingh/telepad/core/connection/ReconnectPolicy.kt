package com.omsingh.telepad.core.connection

import kotlin.math.min

/**
 * When to retry a dropped connection, and when to stop trying.
 *
 * The first retry is immediate, because most drops are a blink of the Wi-Fi radio
 * and the user is looking at the screen. After that the waits double up to a
 * ceiling, so a PC that really is switched off is not hammered, and the phone's
 * battery is not drained by a retry loop. After [giveUpAfterMs] the connection is
 * declared lost and the user is told.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 400L,
    private val maxDelayMs: Long = 8_000L,
    private val giveUpAfterMs: Long = 120_000L,
    /** Spreads retries out so many phones do not hit a rebooting PC at the same instant. */
    private val jitter: (Long) -> Long = { it },
) {

    /** How long to wait before retry number [attempt], counting from 1. */
    fun delayBeforeAttempt(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val exponent = min(attempt - 2, MAX_DOUBLINGS)
        val delay = min(baseDelayMs shl exponent, maxDelayMs)
        return jitter(delay).coerceIn(0L, maxDelayMs)
    }

    /** Whether it has been [elapsedMs] since the connection dropped, so long that trying on is pointless. */
    fun shouldGiveUp(elapsedMs: Long): Boolean = elapsedMs >= giveUpAfterMs

    private companion object {
        /** Keeps `baseDelayMs shl exponent` from overflowing. */
        const val MAX_DOUBLINGS = 20
    }
}
