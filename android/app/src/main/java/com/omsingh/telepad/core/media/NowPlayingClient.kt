package com.omsingh.telepad.core.media

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Polls the PC's SMTC (System Media Transport Controls) for now-playing
 * metadata while the Controls screen is visible.
 *
 * **Why a poll and not a push?** SMTC events fire too aggressively — every
 * 100 ms during playback — and pushing that over UDP would be wasteful for
 * what the UI actually needs (a refresh every ~2 seconds, with immediate
 * updates on track change). A pull model with a slow loop is simpler and
 * gives the UI control over when to spend bandwidth.
 *
 * **Refresh rate**: 2 seconds while the screen is active. Stops entirely when
 * the screen is dismissed. The PC's metadata is cached for the smoothness
 * between polls; the UI extrapolates position locally via [NowPlayingState.extrapolatedPositionMs].
 *
 * **Wire protocol** (see protocol.h):
 *  - Phone → PC: `MSG_TYPE_NOW_PLAYING_QUERY` (1 byte)
 *  - PC → Phone: `MSG_TYPE_NOW_PLAYING_REPLY` (variable, packed struct + UTF-8 strings)
 *
 * The dispatcher hands decoded replies to [onUpdate].
 */
class NowPlayingClient(
    private val sendQuery: () -> Unit,
    /**
     * How often to poll while [start] is active. Default 2 s gives smooth UI
     * without flooding the radio. Reduce to 1 s if a user complains; bumping
     * higher saves a few mA at the cost of slightly stale art/title.
     */
    private val pollIntervalMs: Long = 2000L,
) {

    private val _state = MutableStateFlow(NowPlayingState.EMPTY)
    val state: StateFlow<NowPlayingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /**
     * Begin polling. Safe to call repeatedly — only one poll loop runs.
     */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            // Immediate first query so the UI fills in fast.
            try { sendQuery() } catch (t: Throwable) { Log.w(TAG, "query failed", t) }
            while (isActive) {
                delay(pollIntervalMs)
                try { sendQuery() } catch (t: Throwable) { Log.w(TAG, "query failed", t) }
            }
        }
    }

    /** Stop polling and clear cached state. The UI hides the card. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _state.value = NowPlayingState.EMPTY
    }

    /**
     * Called by the active dispatcher when a `MSG_TYPE_NOW_PLAYING_REPLY`
     * arrives. The dispatcher is responsible for parsing the packed binary
     * into a [NowPlayingState] (with [NowPlayingState.sampledAtMs] set to
     * `System.currentTimeMillis()` on receive).
     */
    fun onUpdate(snapshot: NowPlayingState) {
        _state.value = snapshot
    }

    /** For lifecycle teardown (e.g. ViewModel.onCleared). */
    fun shutdown() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val TAG = "NowPlayingClient"
    }
}
