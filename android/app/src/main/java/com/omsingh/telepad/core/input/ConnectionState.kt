package com.omsingh.telepad.core.input

/**
 * Observable connection state surfaced to the UI by every [InputDispatcher]
 * implementation. The UI binds to this flow to render the connection status.
 */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data class Connecting(val deviceName: String, val transport: Transport) : ConnectionState

    /** The link was lost; the dispatcher is trying to bring it back, so input is paused. */
    data class Reconnecting(
        val deviceName: String,
        val transport: Transport,
        /** Which try this is, counting from 1. */
        val attempt: Int,
    ) : ConnectionState

    data class Connected(
        val deviceName: String,
        val transport: Transport,
        /** Round-trip estimate in ms, or null if not measured (Bluetooth, or not yet). */
        val latencyMs: Int? = null,
        /** The PC has not answered for a few seconds: the link is struggling. */
        val unstable: Boolean = false,
    ) : ConnectionState

    /** The connection could not be made, or could not be kept. */
    data class Failed(
        val deviceName: String?,
        val transport: Transport,
        val reason: FailureReason,
        /** What the phone's own network says about why a PC could not be reached. */
        val hint: com.omsingh.telepad.core.wifi.NetworkHint = com.omsingh.telepad.core.wifi.NetworkHint.Unknown,
    ) : ConnectionState

    enum class Transport { WIFI, BLUETOOTH }
}

/** Why a connection failed, specifically enough to tell the user what to do about it. */
enum class FailureReason {
    /** No answer: the PC is off, the Telepad app is not running, or it is on another network. */
    UNREACHABLE,

    /** The PC is there but has not paired this phone, and is not open to new devices. */
    NOT_PAIRED,

    /** The PC answered with an identity other than the one that was paired. */
    KEY_CHANGED,

    /** The PC is there but a secure connection could not be set up. */
    HANDSHAKE_FAILED,

    /** The connection worked, then dropped and could not be re-established in time. */
    CONNECTION_LOST,

    BLUETOOTH_UNSUPPORTED,
    BLUETOOTH_DISABLED,
    BLUETOOTH_PERMISSION,
    BLUETOOTH_FAILED,
}

/** What we connect *to*. Branches per transport. */
sealed interface ConnectionTarget {
    data class Bluetooth(val address: String, val name: String) : ConnectionTarget

    /**
     * @param pubkeyBase64 the PC's long-term static X25519 public key, which Noise_IK
     *        needs to authenticate the handshake. It comes from the paired-device record.
     * @param name what to call the PC in the UI.
     */
    data class Wifi(
        val host: String,
        val port: Int,
        val pubkeyBase64: String,
        val name: String = host,
    ) : ConnectionTarget
}
