package com.omsingh.telepad.core.input

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-agnostic dispatcher for [InputEvent]s.
 *
 * The UI and gesture-processing layers never know which transport is active:
 * they just call [dispatch]. Implementations route the event to the appropriate
 * Bluetooth HID report or Wi-Fi protocol message.
 *
 * Implementations must be:
 *  - **Non-blocking** on [dispatch]: it is called at up to 240 Hz from the touch handler.
 *  - **Thread-safe**: the UI may call from main, the gesture handler from another thread.
 *  - **Cheap** on the hot path: avoid per-event allocation where possible.
 */
interface InputDispatcher {

    val connectionState: StateFlow<ConnectionState>

    /**
     * Send an event to the connected host. Returns immediately; actual radio TX
     * is asynchronous. If not connected, the event is silently dropped (no
     * exception: the UI is driven off [connectionState]).
     */
    fun dispatch(event: InputEvent)

    /**
     * Establish a connection. Suspends until it either succeeds (the state becomes
     * [ConnectionState.Connected]) or fails (it becomes [ConnectionState.Failed]).
     *
     * Calling while already connected to the same target is a no-op; calling with a
     * different target replaces the connection.
     */
    suspend fun connect(target: ConnectionTarget)

    /**
     * Terminate the connection. State becomes [ConnectionState.Disconnected].
     * Idempotent.
     */
    fun disconnect()
}
