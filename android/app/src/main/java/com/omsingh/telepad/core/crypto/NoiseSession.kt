package com.omsingh.telepad.core.crypto

import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * Noise IK transport security wrapper for Telepad's UDP data plane.
 *
 * Protocol: **Noise_IK_25519_ChaChaPoly_BLAKE2s**
 *
 *  - **IK pattern**: initiator (phone) knows the responder's (PC's) static key
 *    in advance, via TOFU pairing. Gives us mutual authentication and 1-RTT
 *    handshake with the server's identity bound from the very first message.
 *  - **X25519**: ECDH key agreement.
 *  - **ChaCha20-Poly1305**: AEAD. ARM-friendly, no SIMD or AES-NI needed. ~1
 *    cycle/byte on a modern Snapdragon — sub-microsecond for our tiny packets.
 *  - **BLAKE2s**: hash. Smaller/faster than SHA-256 on phone-class CPUs.
 *
 * Wire envelope (every datagram on the wire is prefixed by one tag byte):
 *  - `0xC0` — handshake init (phone → PC): 96 bytes Noise IK msg 1.
 *  - `0xC1` — handshake response (PC → phone): 48 bytes Noise IK msg 2.
 *  - `0xC2` — transport datagram: `[ciphertext | 16-byte Poly1305 tag]`.
 *
 * **Performance:** [encrypt] / [decrypt] are zero-allocation on the steady-state
 * path — they use caller-provided buffers. The session itself holds two
 * CipherStates (~250 bytes each) and nothing more.
 *
 * **Replay resistance.** Noise's per-message nonce is a monotonic counter
 * maintained inside the CipherState. Decrypting an old packet fails because
 * the counter on the receiver has already advanced past it. UDP reordering
 * within a small window will look like replay attacks to Noise; mitigate by
 * having the receiver tolerate a small reorder window (handled server-side).
 */
class NoiseSession(
    /** Our long-term client static key. Persisted in [PairingStore]. */
    private val localStaticPrivateKey: ByteArray
) {

    private var sendCipher: CipherState? = null
    private var recvCipher: CipherState? = null

    /** True once [runHandshake] has succeeded. Cleared by [reset]. */
    val isEstablished: Boolean
        get() = sendCipher != null && recvCipher != null

    /**
     * Perform the Noise IK handshake over [socket].
     *
     * The socket should already be `connect()`ed to the server's `host:port`
     * so [DatagramSocket.send] / [DatagramSocket.receive] use the kernel's
     * fast path (no per-packet route lookup).
     *
     * @param serverStaticPubkeyBase64 The server's long-term X25519 public key,
     *        verified by the user via fingerprint on first pairing.
     * @return true on success (session is now ready to [encrypt] / [decrypt]),
     *         false on any failure (timeout, malformed reply, MAC mismatch).
     *
     * **Blocking.** This call blocks the calling thread for ~1 RTT — typically
     * a few milliseconds on a LAN, but capped at [HANDSHAKE_TIMEOUT_MS].
     * Run from a coroutine on Dispatchers.IO or a dedicated worker.
     */
    fun runHandshake(socket: DatagramSocket, serverStaticPubkeyBase64: String): Boolean {
        val serverPub = Base64.getDecoder().decode(serverStaticPubkeyBase64)
        require(serverPub.size == 32) { "Server pubkey must be 32 bytes" }

        val hs = HandshakeState(
            "Noise_IK_25519_ChaChaPoly_BLAKE2s",
            HandshakeState.INITIATOR
        )
        try {
            hs.localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
            hs.remotePublicKey.setPublicKey(serverPub, 0)
            hs.start()

            // ── Send Noise IK message 1 ────────────────────────────
            val msg1Buf = ByteArray(NOISE_IK_MSG1_LEN)
            val msg1Len = hs.writeMessage(msg1Buf, 0, EMPTY_PAYLOAD, 0, 0)

            val outFrame = ByteArray(1 + msg1Len)
            outFrame[0] = WIRE_HANDSHAKE_INIT
            System.arraycopy(msg1Buf, 0, outFrame, 1, msg1Len)
            socket.send(DatagramPacket(outFrame, outFrame.size))

            // ── Receive Noise IK message 2 ─────────────────────────
            val rxBuf = ByteArray(256)
            val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
            val prevTimeout = socket.soTimeout
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            try {
                socket.receive(rxPacket)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Handshake timeout — no response from server")
                return false
            } finally {
                socket.soTimeout = prevTimeout
            }

            if (rxPacket.length < 2 || rxBuf[0] != WIRE_HANDSHAKE_RESP) {
                Log.w(TAG, "Bad handshake reply: tag=${rxBuf[0]}, len=${rxPacket.length}")
                return false
            }

            // Strip the wire tag, parse the Noise payload.
            hs.readMessage(rxBuf, 1, rxPacket.length - 1, EMPTY_PAYLOAD, 0)

            val pair = hs.split()
            sendCipher = pair.sender
            recvCipher = pair.receiver
            Log.i(TAG, "Noise IK handshake established")
            return true

        } catch (t: Throwable) {
            Log.w(TAG, "Noise handshake failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            hs.destroy()
        }
    }

    /**
     * Encrypt a plaintext slice into `out`.
     *
     * @param plain Source buffer.
     * @param plainOff Offset into [plain].
     * @param plainLen Bytes to encrypt.
     * @param out Destination — must have at least `plainLen + 16` bytes of room.
     * @param outOff Offset into [out].
     * @return Number of bytes written to [out] (always `plainLen + 16`), or
     *         -1 if the session is not established.
     */
    fun encrypt(
        plain: ByteArray, plainOff: Int, plainLen: Int,
        out: ByteArray, outOff: Int
    ): Int {
        val cs = sendCipher ?: return -1
        return try {
            cs.encryptWithAd(null, plain, plainOff, out, outOff, plainLen)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed: ${t.message}")
            -1
        }
    }

    /**
     * Decrypt a ciphertext slice into `out`.
     *
     * @param cipher Source buffer (already stripped of the wire tag).
     * @param cipherOff Offset into [cipher].
     * @param cipherLen Bytes to decrypt (must be at least 16 — the MAC).
     * @param out Destination — must have at least `cipherLen - 16` bytes of room.
     * @param outOff Offset into [out].
     * @return Number of plaintext bytes written, or -1 on authentication
     *         failure (Poly1305 MAC mismatch). Drop the packet silently —
     *         do **not** log the plaintext or expose the failure to the user.
     */
    fun decrypt(
        cipher: ByteArray, cipherOff: Int, cipherLen: Int,
        out: ByteArray, outOff: Int
    ): Int {
        val cs = recvCipher ?: return -1
        return try {
            cs.decryptWithAd(null, cipher, cipherOff, out, outOff, cipherLen)
        } catch (t: Throwable) {
            -1   // Authentication failure — silent drop.
        }
    }

    /** Tear down both cipher states and zero internal key material. */
    fun reset() {
        sendCipher?.destroy(); sendCipher = null
        recvCipher?.destroy(); recvCipher = null
    }

    companion object {
        private const val TAG = "NoiseSession"

        // Wire envelope tags (must match server protocol.h).
        const val WIRE_HANDSHAKE_INIT: Byte = 0xC0.toByte()
        const val WIRE_HANDSHAKE_RESP: Byte = 0xC1.toByte()
        const val WIRE_TRANSPORT: Byte      = 0xC2.toByte()

        // Per the Noise IK pattern with X25519/ChaChaPoly/BLAKE2s and no payload:
        //  msg 1 = e(32) + s+tag(32+16) + 0+tag(0+16)  = 96 bytes
        //  msg 2 = e(32) + 0+tag(0+16)                  = 48 bytes
        const val NOISE_IK_MSG1_LEN = 96
        const val NOISE_IK_MSG2_LEN = 48

        const val HANDSHAKE_TIMEOUT_MS = 3000

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
