package com.omsingh.telepad.testing

import com.omsingh.telepad.core.crypto.NoiseSession
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stand-in for the PC's server, speaking the real protocol over loopback UDP, so
 * that the phone's networking can be tested end to end without a PC.
 *
 * It does the responder half of Noise IK exactly as the Rust server does: a handshake
 * that cannot be decrypted (wrong server key) is dropped without a word, an unpaired
 * client is refused with `0xC7`, and transport packets are decrypted, recorded and, for
 * a host-info query, answered.
 */
class NoiseTestServer(
    private val allowClient: (clientPublicKey: ByteArray) -> Boolean = { true },
    private val hostInfoReply: ByteArray? = byteArrayOf(0x82.toByte(), 3, 0x02, 2, 1, 0),
    private val hostname: String = "Test-PC",
    /**
     * A one-time pairing token, as in the QR code on a real PC's screen. A phone that presents it inside its
     * handshake is admitted even if [allowClient] says no, once; the phone is then remembered, as a real server
     * remembers a paired phone.
     */
    private val requiredToken: ByteArray? = null,
) : AutoCloseable {

    private val privateKey = ByteArray(32)
    val publicKey = ByteArray(32)
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)

    val port: Int get() = socket.localPort
    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(publicKey)

    /** Decrypted transport messages received so far, in arrival order. */
    val received = CopyOnWriteArrayList<ByteArray>()

    /** Handshakes completed so far. */
    val handshakes = AtomicInteger()

    /** The payload of every handshake request, as the phone sent it (a pairing token, or nothing). */
    val handshakePayloads = CopyOnWriteArrayList<ByteArray>()

    /** True once the token has admitted a phone: it works once. */
    @Volatile var tokenSpent = false
        private set

    private val admittedByToken = CopyOnWriteArrayList<ByteArray>()

    /** While true the server swallows everything, as a PC that is switched off would. */
    @Volatile var silent = false

    /** How many of the next handshake requests to ignore (to model lost packets). */
    @Volatile var dropNextHandshakes = 0

    private class Peer(val send: CipherState, val receive: CipherState, var nonce: Long = 0)

    private val peers = java.util.concurrent.ConcurrentHashMap<SocketAddress, Peer>()

    init {
        val dh = Noise.createDH("25519")
        dh.generateKeyPair()
        dh.getPrivateKey(privateKey, 0)
        dh.getPublicKey(publicKey, 0)
        dh.destroy()
        thread(isDaemon = true, name = "noise-test-server") { serve() }
    }

    private fun serve() {
        val buf = ByteArray(2048)
        socket.soTimeout = 100
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            if (silent || packet.length < 1) continue
            try {
                when (buf[0]) {
                    NoiseSession.WIRE_HANDSHAKE_INIT -> onHandshake(buf, packet)
                    NoiseSession.WIRE_TRANSPORT -> onTransport(buf, packet)
                    INTRO_REQUEST -> onIntro(packet)
                    DISCOVERY_PROBE -> onProbe(packet)
                }
            } catch (_: Throwable) {
                // Garbage in, silence out: like the real server.
            }
        }
    }

    private fun onProbe(from: DatagramPacket) {
        val reply = byteArrayOf(0xC4.toByte()) + "TELEPAD_PONG:$hostname".toByteArray()
        socket.send(DatagramPacket(reply, reply.size, from.socketAddress))
    }

    private fun onIntro(from: DatagramPacket) {
        val reply = byteArrayOf(INTRO_REPLY) + publicKey
        socket.send(DatagramPacket(reply, reply.size, from.socketAddress))
    }

    private fun onHandshake(buf: ByteArray, from: DatagramPacket) {
        if (dropNextHandshakes > 0) {
            dropNextHandshakes--
            return
        }
        val hs = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER)
        try {
            hs.localKeyPair.setPrivateKey(privateKey, 0)
            hs.start()
            val payloadBuf = ByteArray(64)
            val payloadLen = hs.readMessage(buf, 1, from.length - 1, payloadBuf, 0)
            val payload = payloadBuf.copyOf(payloadLen)
            handshakePayloads += payload

            val clientKey = ByteArray(32)
            hs.remotePublicKey.getPublicKey(clientKey, 0)
            if (!admits(clientKey, payload)) {
                socket.send(DatagramPacket(byteArrayOf(NoiseSession.WIRE_PAIRING_REJECTED), 1, from.socketAddress))
                return
            }

            val reply = ByteArray(1 + NoiseSession.NOISE_IK_MSG2_LEN + 16)
            reply[0] = NoiseSession.WIRE_HANDSHAKE_RESP
            val n = hs.writeMessage(reply, 1, null, 0, 0)
            val pair = hs.split()
            peers[from.socketAddress] = Peer(send = pair.sender, receive = pair.receiver)
            handshakes.incrementAndGet()
            socket.send(DatagramPacket(reply, 1 + n, from.socketAddress))
        } finally {
            hs.destroy()
        }
    }

    /** Whether this phone may connect: already allowed, admitted earlier by the token, or presenting it now. */
    private fun admits(clientKey: ByteArray, payload: ByteArray): Boolean {
        if (allowClient(clientKey) || admittedByToken.any { it.contentEquals(clientKey) }) return true
        val token = requiredToken ?: return false
        if (tokenSpent || !payload.contentEquals(token)) return false
        tokenSpent = true
        admittedByToken += clientKey
        return true
    }

    private fun onTransport(buf: ByteArray, from: DatagramPacket) {
        val peer = peers[from.socketAddress] ?: return
        if (from.length < 9 + 16) return
        var nonce = 0L
        for (i in 0 until 8) nonce = nonce or ((buf[1 + i].toLong() and 0xFF) shl (8 * i))
        val plain = ByteArray(from.length)
        peer.receive.setNonce(nonce)
        val n = peer.receive.decryptWithAd(null, buf, 9, plain, 0, from.length - 9)
        if (n < 1) return
        val message = plain.copyOf(n)
        received += message
        if (message[0] == HOST_INFO_QUERY && hostInfoReply != null) sendTo(from.socketAddress, hostInfoReply)
    }

    /** Sends an encrypted message to the client at [to]. */
    fun sendTo(to: SocketAddress, plain: ByteArray) {
        val peer = peers[to] ?: return
        val out = ByteArray(9 + plain.size + 16)
        out[0] = NoiseSession.WIRE_TRANSPORT
        // The server thread (answering queries) and a test (broadcasting) both send: the nonce
        // and the cipher state must not be used by two threads at once.
        synchronized(peer) {
            val nonce = peer.nonce++
            for (i in 0 until 8) out[1 + i] = ((nonce ushr (8 * i)) and 0xFF).toByte()
            peer.send.setNonce(nonce)
            peer.send.encryptWithAd(null, plain, 0, out, 9, plain.size)
        }
        socket.send(DatagramPacket(out, out.size, to))
    }

    /** Sends [plain] to every client that has completed a handshake. */
    fun broadcast(plain: ByteArray) {
        for (to in peers.keys.toList()) sendTo(to, plain)
    }

    /** Messages received whose first byte is [type]. */
    fun receivedOfType(type: Byte): List<ByteArray> = received.filter { it[0] == type }

    override fun close() {
        running.set(false)
        socket.close()
    }

    private companion object {
        const val HOST_INFO_QUERY: Byte = 0x12
        const val DISCOVERY_PROBE: Byte = 0xC3.toByte()
        const val INTRO_REQUEST: Byte = 0xC5.toByte()
        const val INTRO_REPLY: Byte = 0xC6.toByte()
    }
}
