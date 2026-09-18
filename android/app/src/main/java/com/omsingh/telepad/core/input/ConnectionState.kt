package com.omsingh.telepad.core.input

/**
 * Observable connection state surfaced to the UI by every [InputDispatcher]
 * implementation. The UI binds to this flow to render the persistent status bar.
 */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    /** Connection lost, dispatcher will retry automatically. */
    data object Reconnecting : ConnectionState

    data class Connected(
        val deviceName: String,
        val transport: Transport,
        /** Round-trip estimate in ms, or null if not measured yet. */
        val latencyMs: Int? = null
    ) : ConnectionState

    data class Error(val message: String) : ConnectionState

    enum class Transport { WIFI, BLUETOOTH }
}

/** What we connect *to*. Branches per transport. */
sealed interface ConnectionTarget {
    data class Bluetooth(val address: String, val name: String) : ConnectionTarget

    /**
     * @param pubkeyBase64 the server's long-term static X25519 public key,
     *        obtained out-of-band on first pairing and persisted thereafter.
     *        Required by Noise_IK to mutually authenticate the handshake.
     */
    data class Wifi(
        val host: String,
        val port: Int,
        val pubkeyBase64: String
    ) : ConnectionTarget
}
