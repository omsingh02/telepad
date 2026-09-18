package com.omsingh.telepad.core.input

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-agnostic dispatcher for [InputEvent]s.
 *
 * The UI and gesture-processing layers never know which transport is active —
 * they just call [dispatch]. Implementations route the event to the appropriate
 * Bluetooth HID report or Wi-Fi protocol message.
 *
 * Implementations must be:
 *  - **Non-blocking** on [dispatch] — called at up to 240 Hz from the touch handler.
 *  - **Thread-safe** — UI may call from main, gesture handler from input dispatcher.
 *  - **Zero-allocation** on the hot path where possible (see WifiInputDispatcher).
 */
interface InputDispatcher {

    val connectionState: StateFlow<ConnectionState>

    /**
     * Send an event to the connected host. Returns immediately; actual radio TX
     * is asynchronous. If not connected, the event is silently dropped (no
     * exception — the UI is driven off [connectionState]).
     */
    fun dispatch(event: InputEvent)

    /**
     * Establish a connection. Blocks the calling coroutine until the connection
     * either succeeds (state flips to [ConnectionState.Connected]) or fails
     * (state flips to [ConnectionState.Error]).
     *
     * Idempotent: calling while already connected is a no-op.
     */
    suspend fun connect(target: ConnectionTarget)

    /**
     * Terminate the connection. State flips to [ConnectionState.Disconnected].
     * Idempotent.
     */
    fun disconnect()
}
