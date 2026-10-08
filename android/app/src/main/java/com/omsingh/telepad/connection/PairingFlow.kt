package com.omsingh.telepad.connection

import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.trust.PairedDevice

/** A PC the phone is about to connect to, with the key it presented. */
data class Candidate(
    val name: String,
    val host: String,
    val port: Int,
    /** The key the PC presented, base64. */
    val publicKey: String,
)

/**
 * Where the pairing conversation with the user stands. The UI shows a sheet whenever
 * this is not null.
 */
sealed interface PairingUiState {

    /** Asking a PC for its key, so that it can be checked against what is trusted. */
    data class Contacting(val label: String) : PairingUiState

    /**
     * The user must compare [fingerprint] with the one the PC displays, and only then
     * is the PC trusted. If [replaces] is set, this PC has the name and address of one
     * that was paired but now presents a different key, which deserves a louder warning.
     */
    data class Verify(
        val candidate: Candidate,
        val fingerprint: String,
        val replaces: PairedDevice? = null,
    ) : PairingUiState

    /** The user confirmed; connecting to the PC. */
    data class Connecting(val candidate: Candidate) : PairingUiState

    /** It did not work; [reason] says why, so the sheet can say what to do. */
    data class Failed(
        val label: String,
        val reason: FailureReason,
        val candidate: Candidate? = null,
        /** What the phone's own network says about a PC that could not be reached. */
        val hint: com.omsingh.telepad.core.wifi.NetworkHint = com.omsingh.telepad.core.wifi.NetworkHint.Unknown,
    ) : PairingUiState
}

/** A phone or PC already paired in Android's Bluetooth settings. */
data class BluetoothDeviceInfo(val name: String, val address: String)

/** Something worth telling the user once, as a brief message. */
sealed interface Notice {
    /** Some typed text contained characters a Bluetooth keyboard cannot send. */
    data class UntypeableText(val count: Int) : Notice
    data object ClipboardSent : Notice
    data object ClipboardEmpty : Notice
    data object ClipboardDisabled : Notice
    data object ClipboardFromPc : Notice
}
