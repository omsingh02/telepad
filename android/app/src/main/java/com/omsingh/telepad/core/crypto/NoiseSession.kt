package com.omsingh.telepad.core.crypto

import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * 128-packet sliding replay window to defend against UDP replay and reordering.
 */
class ReplayWindow {
    var maxNonce: Long = 0L
        private set
    private var bitmapLow: Long = 0L
    private var bitmapHigh: Long = 0L
    private var initialized: Boolean = false

    fun check(nonce: Long): Boolean {
        if (!initialized) return true
        if (nonce > maxNonce) return true
        val diff = maxNonce - nonce
        if (diff >= 128) return false
        return if (diff < 64) {
            (bitmapLow and (1L shl diff.toInt())) == 0L
        } else {
            (bitmapHigh and (1L shl (diff - 64).toInt())) == 0L
        }
    }

    fun update(nonce: Long) {
        if (!initialized) {
            initialized = true
            maxNonce = nonce
            bitmapLow = 1L
            bitmapHigh = 0L
            return
        }
        if (nonce > maxNonce) {
            val diff = nonce - maxNonce
            if (diff >= 128) {
                bitmapLow = 1L
                bitmapHigh = 0L
            } else if (diff >= 64) {
                val shift = (diff - 64).toInt()
                bitmapHigh = bitmapLow shl shift
                bitmapLow = 1L
            } else {
                val shift = diff.toInt()
                bitmapHigh = (bitmapHigh shl shift) or (bitmapLow ushr (64 - shift))
                bitmapLow = (bitmapLow shl shift) or 1L
            }
            maxNonce = nonce
        } else {
            val diff = maxNonce - nonce
            if (diff < 64) {
                bitmapLow = bitmapLow or (1L shl diff.toInt())
            } else if (diff < 128) {
                bitmapHigh = bitmapHigh or (1L shl (diff - 64).toInt())
            }
        }
    }
}

/** How [NoiseSession.runHandshake] ended. */
enum class HandshakeResult {
    /** Keys agreed; the session is ready to encrypt and decrypt. */
    ESTABLISHED,

    /**
     * The PC answered that it has not paired this phone (wire tag `0xC7`).
     * Retrying will not help until the owner opens pairing on the PC.
     */
    NOT_PAIRED,

    /**
     * Nothing usable came back in time. The PC may be off or unreachable, or it may
     * be running with a different key (a server that dropped a message it cannot
     * decrypt stays silent), which only the caller can tell apart.
     */
    NO_REPLY,

    /** A local failure: bad key material or a crypto error. */
    FAILED
}

/**
 * Noise IK transport security wrapper for Telepad's UDP data plane.
 *
 * Protocol: **Noise_IK_25519_ChaChaPoly_BLAKE2s**
 *
 *  - **IK pattern**: initiator (phone) knows the responder's (PC's) static key
 *    in advance, via TOFU pairing. Gives mutual authentication and 1-RTT
 *    handshake with the server's identity bound from the very first message.
 *  - **X25519**: ECDH key agreement.
 *  - **ChaCha20-Poly1305**: AEAD.
 *  - **BLAKE2s**: hash.
 *
 * Wire envelope:
 *  - `0xC0` — handshake init (phone → PC): 96 bytes Noise IK msg 1.
 *  - `0xC1` — handshake response (PC → phone): 48 bytes Noise IK msg 2.
 *  - `0xC2` — transport datagram: `[0xC2][nonce: 8B LE][ciphertext | 16-byte Poly1305 tag]`.
 */
class NoiseSession(
    /** Our long-term client static key. Persisted in [PairingStore]. */
    private val localStaticPrivateKey: ByteArray
) {

    private var sendCipher: CipherState? = null
    private var recvCipher: CipherState? = null
    private var sendNonce: Long = 0L
    val replayWindow = ReplayWindow()

    /** True once [runHandshake] has succeeded. Cleared by [reset]. */
    val isEstablished: Boolean
        get() = sendCipher != null && recvCipher != null

    /**
     * Perform the Noise IK handshake over [socket], once.
     *
     * A fresh socket is best for every attempt: a late reply to an earlier attempt then
     * can never be mistaken for the answer to this one (feeding a handshake a reply that
     * is not meant for it spoils it for good).
     *
     * @param serverStaticPubkeyBase64 The server's long-term X25519 public key.
     * @param timeoutMs How long to wait for the PC's answer.
     * @return [HandshakeResult.ESTABLISHED] on success (session is now ready to
     *         [encrypt] / [decrypt]); otherwise why it did not.
     */
    fun runHandshake(
        socket: DatagramSocket,
        serverStaticPubkeyBase64: String,
        timeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
    ): HandshakeResult {
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

            // ── Receive Noise IK message 2 (or a rejection) ────────
            val rxBuf = ByteArray(256)
            val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
            val prevTimeout = socket.soTimeout
            socket.soTimeout = timeoutMs
            try {
                socket.receive(rxPacket)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Handshake timeout: no response from server")
                return HandshakeResult.NO_REPLY
            } finally {
                socket.soTimeout = prevTimeout
            }

            if (rxPacket.length >= 1 && rxBuf[0] == WIRE_PAIRING_REJECTED) {
                Log.w(TAG, "Server has not paired this phone")
                return HandshakeResult.NOT_PAIRED
            }
            if (rxPacket.length != 1 + NOISE_IK_MSG2_LEN || rxBuf[0] != WIRE_HANDSHAKE_RESP) {
                Log.w(TAG, "Unusable handshake reply: tag=${rxBuf[0]}, len=${rxPacket.length}")
                return HandshakeResult.NO_REPLY
            }

            // Strip the wire tag, parse the Noise payload.
            hs.readMessage(rxBuf, 1, rxPacket.length - 1, EMPTY_PAYLOAD, 0)

            val pair = hs.split()
            sendCipher = pair.sender
            recvCipher = pair.receiver
            sendNonce = 0L
            Log.i(TAG, "Noise IK handshake established")
            return HandshakeResult.ESTABLISHED

        } catch (t: Throwable) {
            Log.w(TAG, "Noise handshake failed: ${t.javaClass.simpleName}: ${t.message}")
            return HandshakeResult.FAILED
        } finally {
            hs.destroy()
        }
    }

    /**
     * Encrypt a plaintext slice into `out` with an explicit monotonically advancing nonce.
     *
     * @param plain Source buffer.
     * @param plainOff Offset into [plain].
     * @param plainLen Bytes to encrypt.
     * @param out Destination — must have at least `plainLen + 16` bytes of room.
     * @param outOff Offset into [out].
     * @return Assigned nonce (>= 0) on success, or -1L if not established.
     */
    fun encrypt(
        plain: ByteArray, plainOff: Int, plainLen: Int,
        out: ByteArray, outOff: Int
    ): Long {
        val cs = sendCipher ?: return -1L
        val nonce = sendNonce++
        return try {
            cs.setNonce(nonce)
            cs.encryptWithAd(null, plain, plainOff, out, outOff, plainLen)
            nonce
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed: ${t.message}")
            -1L
        }
    }

    /**
     * Decrypt a ciphertext slice into `out` using the datagram's explicit nonce.
     * Rejects packets that fail the sliding replay window or MAC verification.
     *
     * @param nonce Monotonic packet sequence number from wire datagram.
     * @param cipher Source buffer.
     * @param cipherOff Offset into [cipher].
     * @param cipherLen Bytes to decrypt (must be at least 16 — the MAC).
     * @param out Destination.
     * @param outOff Offset into [out].
     * @return Number of plaintext bytes written, or -1 on failure.
     */
    fun decrypt(
        nonce: Long,
        cipher: ByteArray, cipherOff: Int, cipherLen: Int,
        out: ByteArray, outOff: Int
    ): Int {
        val cs = recvCipher ?: return -1
        if (!replayWindow.check(nonce)) {
            return -1 // Replay detected or out-of-window
        }
        return try {
            cs.setNonce(nonce)
            val n = cs.decryptWithAd(null, cipher, cipherOff, out, outOff, cipherLen)
            if (n >= 0) {
                replayWindow.update(nonce)
            }
            n
        } catch (t: Throwable) {
            -1 // Authentication failure — silent drop.
        }
    }

    /** Tear down both cipher states and zero internal key material. */
    fun reset() {
        sendNonce = 0L
        sendCipher?.destroy(); sendCipher = null
        recvCipher?.destroy(); recvCipher = null
    }

    companion object {
        private const val TAG = "NoiseSession"

        // Wire envelope tags (must match server protocol).
        const val WIRE_HANDSHAKE_INIT: Byte = 0xC0.toByte()
        const val WIRE_HANDSHAKE_RESP: Byte = 0xC1.toByte()
        const val WIRE_TRANSPORT: Byte      = 0xC2.toByte()

        /** Sent by the server instead of [WIRE_HANDSHAKE_RESP] when this phone is not paired. */
        const val WIRE_PAIRING_REJECTED: Byte = 0xC7.toByte()

        // Per the Noise IK pattern with X25519/ChaChaPoly/BLAKE2s and no payload:
        //  msg 1 = e(32) + s+tag(32+16) + 0+tag(0+16)  = 96 bytes
        //  msg 2 = e(32) + 0+tag(0+16)                  = 48 bytes
        const val NOISE_IK_MSG1_LEN = 96
        const val NOISE_IK_MSG2_LEN = 48

        const val HANDSHAKE_TIMEOUT_MS = 3000

        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
