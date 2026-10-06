package com.omsingh.telepad.core.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Asks a PC for its public key.
 *
 *  - phone to PC: `[0xC5]`
 *  - PC to phone: `[0xC6][32 bytes: the PC's X25519 public key]`
 *
 * The answer is not authenticated, and does not need to be: it is only ever *shown*
 * to the user as a fingerprint to compare with the PC's own screen before it is
 * trusted, or compared with a key that is already trusted. Anyone on the network can
 * send such a request, so the PC reveals nothing it does not already publish.
 */
object PairingIntro {
    const val REQUEST: Byte = 0xC5.toByte()
    const val REPLY: Byte = 0xC6.toByte()
    const val KEY_LENGTH = 32

    fun requestPacket(): ByteArray = byteArrayOf(REQUEST)

    /** The public key in an intro reply, or null if [buf] is not one. */
    fun parseReply(buf: ByteArray, len: Int): ByteArray? {
        if (len < 1 + KEY_LENGTH || len > buf.size || buf[0] != REPLY) return null
        return buf.copyOfRange(1, 1 + KEY_LENGTH)
    }

    /** What a PC reveals about itself to anyone who asks: its name and its public key. */
    data class ServerIdentity(val name: String?, val publicKeyBase64: String)

    /**
     * Asks the Telepad server at [host]:[port] who it is: sends a discovery probe (which
     * the PC answers with its hostname) and a key request together. For an address the
     * user typed in, where there was no announcement to take the name from.
     * Returns null if the PC never answers with its key.
     */
    suspend fun identify(
        host: String,
        port: Int,
        attempts: Int = 3,
        timeoutPerAttemptMs: Int = 600,
        prepare: (DatagramSocket) -> Unit = {},
    ): ServerIdentity? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                prepare(socket)
                socket.connect(InetSocketAddress(host, port))
                val probe = DiscoveryPackets.probe()
                val request = requestPacket()
                val rx = ByteArray(512)
                var name: String? = null
                var key: ByteArray? = null
                repeat(attempts) {
                    socket.send(DatagramPacket(probe, probe.size))
                    socket.send(DatagramPacket(request, request.size))
                    val deadline = System.nanoTime() + timeoutPerAttemptMs * 1_000_000L
                    while (true) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0) break
                        socket.soTimeout = remainingMs.toInt()
                        try {
                            val packet = DatagramPacket(rx, rx.size)
                            socket.receive(packet)
                            when (val parsed = DiscoveryPackets.parse(rx, packet.length)) {
                                is DiscoveryPackets.Parsed.Announcement -> name = parsed.name.ifBlank { null }
                                is DiscoveryPackets.Parsed.PublicKey -> key = parsed.key
                                null -> Unit
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        if (key != null && name != null) break
                    }
                    key?.let { found ->
                        return@withContext ServerIdentity(name, java.util.Base64.getEncoder().encodeToString(found))
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches the public key of the Telepad server at [host]:[port]. UDP can lose the
     * request, so it is sent up to [attempts] times, [timeoutPerAttemptMs] apart.
     * Returns null if the PC never answers.
     *
     * @param prepare called with the socket before it is used, e.g. to bind it to the
     *        Wi-Fi network when mobile data is the phone's default.
     */
    suspend fun fetchPublicKey(
        host: String,
        port: Int,
        attempts: Int = 3,
        timeoutPerAttemptMs: Int = 500,
        prepare: (DatagramSocket) -> Unit = {},
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                prepare(socket)
                // A connected socket only accepts datagrams from that address.
                socket.connect(InetSocketAddress(host, port))
                socket.soTimeout = timeoutPerAttemptMs
                val request = requestPacket()
                val rx = ByteArray(64)
                repeat(attempts) {
                    socket.send(DatagramPacket(request, request.size))
                    try {
                        val packet = DatagramPacket(rx, rx.size)
                        socket.receive(packet)
                        parseReply(rx, packet.length)?.let { return@withContext it }
                    } catch (_: SocketTimeoutException) {
                        // Try again.
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
