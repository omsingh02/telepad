package com.omsingh.telepad.core.media

/**
 * UI-facing snapshot of what's playing on the PC.
 *
 * Populated from the server's Windows SMTC (System Media Transport Controls)
 * query response. Fields are best-effort — any app that doesn't report
 * metadata via SMTC will leave them null.
 *
 * **Why a frozen snapshot rather than a stream of granular events?** The PC
 * only sends updates when the now-playing state changes — typically every few
 * seconds for position, instantaneously for play/pause/track-change. The UI
 * binds to a single [State] flow and re-renders on whole-object replacement,
 * which is the natural Compose pattern.
 */
data class NowPlayingState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,

    /** App ID / process name of the player (e.g. `"Spotify"`, `"chrome.exe"`). */
    val sourceApp: String? = null,

    /** True if the server reports active playback. */
    val isPlaying: Boolean = false,

    /** Current position in milliseconds, or null if unknown / non-seekable. */
    val positionMs: Long? = null,

    /** Total duration in milliseconds, or null if unknown. */
    val durationMs: Long? = null,

    /**
     * Wall-clock at which positionMs was sampled (System.currentTimeMillis on
     * receive). UI uses this + isPlaying to interpolate a smoothly-advancing
     * position without spamming the network with refresh requests.
     */
    val sampledAtMs: Long = 0L,
) {
    /** Convenience: is there enough metadata to render the card at all? */
    val hasContent: Boolean
        get() = !title.isNullOrBlank() || !artist.isNullOrBlank()

    /** Estimated current position with simple linear extrapolation. */
    fun extrapolatedPositionMs(nowMs: Long): Long? {
        if (positionMs == null) return null
        if (!isPlaying) return positionMs
        val elapsed = (nowMs - sampledAtMs).coerceAtLeast(0L)
        val raw = positionMs + elapsed
        return if (durationMs != null) raw.coerceAtMost(durationMs) else raw
    }

    companion object {
        val EMPTY = NowPlayingState()
    }
}
