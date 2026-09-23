package com.omsingh.telepad.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.core.crypto.Fingerprint
import com.omsingh.telepad.core.wifi.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Drives the TOFU pairing screen.
 *
 * Flow:
 *  1. UI calls [beginPairing] with the candidate [ServerInfo].
 *  2. We open a one-off UDP socket, send a `MSG_TYPE_PAIRING_INTRO` query,
 *     and the server replies with its 32-byte X25519 static public key.
 *  3. We compute the SHA-256 fingerprint and surface it via [state] for
 *     the user to verify against what's printed on the PC.
 *  4. User taps "Trust this PC" → UI calls back into [MainViewModel.completePairingAndConnect],
 *     which persists the trust and triggers the real Noise handshake.
 *  5. If the user cancels, no state is persisted.
 *
 * **Why a separate socket and not the dispatcher's?** Pairing happens *before*
 * a session exists. The pairing query is a single round-trip on a throwaway
 * socket — clean separation, no half-states.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State
        data class Loading(val server: ServerInfo) : State
        data class Ready(
            val server: ServerInfo,
            val pubkeyBase64: String,
            /** Pre-formatted `"7F2A · B9C1 · 4E08"`. */
            val fingerprint: String,
        ) : State
        data class Error(val server: ServerInfo, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun beginPairing(server: ServerInfo) {
        _state.value = State.Loading(server)
        viewModelScope.launch {
            val pubkey = withContext(Dispatchers.IO) { fetchServerPubkey(server) }
            _state.value = if (pubkey == null) {
                State.Error(server, "Could not reach ${server.host}. Is the PC app running?")
            } else {
                val rawHex = Fingerprint.ofBase64(pubkey)
                State.Ready(
                    server = server,
                    pubkeyBase64 = pubkey,
                    fingerprint = Fingerprint.format(rawHex)
                )
            }
        }
    }

    fun reset() {
        _state.value = State.Idle
    }

    /**
     * One-shot pairing-intro exchange. Returns base64 pubkey or null on error.
     *
     * Wire format (matches protocol.h):
     *  - Phone → PC: `[0xC5]`                       — WIRE_PAIRING_INTRO_REQ
     *  - PC → Phone: `[0xC6][32 bytes X25519 pub]`  — WIRE_PAIRING_INTRO_RESP
     */
    private suspend fun fetchServerPubkey(server: ServerInfo): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                connect(InetSocketAddress(server.host, server.port))
                soTimeout = PAIRING_TIMEOUT_MS
            }
            socket.send(DatagramPacket(byteArrayOf(WIRE_PAIRING_INTRO_REQ), 1))
            val rxBuf = ByteArray(64)
            val rp = DatagramPacket(rxBuf, rxBuf.size)
            withTimeoutOrNull(PAIRING_TIMEOUT_MS.toLong()) { socket.receive(rp) }
            if (rp.length < 1 + 32) return null
            if (rxBuf[0] != WIRE_PAIRING_INTRO_RESP) return null
            val pub = ByteArray(32)
            System.arraycopy(rxBuf, 1, pub, 0, 32)
            android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private companion object {
        const val PAIRING_TIMEOUT_MS = 3000
        const val WIRE_PAIRING_INTRO_REQ: Byte = 0xC5.toByte()
        const val WIRE_PAIRING_INTRO_RESP: Byte = 0xC6.toByte()
    }
}
