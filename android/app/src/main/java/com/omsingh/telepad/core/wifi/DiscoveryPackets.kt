package com.omsingh.telepad.core.wifi

/**
 * The packets of LAN discovery.
 *
 *  - probe (phone to PC or broadcast): `[0xC3]` followed by an 8 byte magic number
 *  - announcement (PC to phone): `[0xC4]"TELEPAD_PONG:<hostname>"`, also sent bare
 *    without the tag, which older clients expect
 *  - public key request and reply: see [PairingIntro]
 *
 * The magic keeps random UDP noise from being mistaken for a probe; there is no
 * readable product name on the wire.
 */
object DiscoveryPackets {
    const val PROBE: Byte = 0xC3.toByte()
    const val ANNOUNCEMENT: Byte = 0xC4.toByte()
    const val PORT = 5000
    const val MULTICAST_GROUP = "239.255.42.67"

    private const val PONG_PREFIX = "TELEPAD_PONG:"
    private const val MAX_NAME_LENGTH = 64

    private val MAGIC = byteArrayOf(
        0x54, 0xE7.toByte(), 0x9A.toByte(), 0x03,
        0x21, 0xC8.toByte(), 0xBE.toByte(), 0xFE.toByte(),
    )

    fun probe(): ByteArray = byteArrayOf(PROBE) + MAGIC

    /** What a received datagram turned out to be. */
    sealed interface Parsed {
        data class Announcement(val name: String) : Parsed
        data class PublicKey(val key: ByteArray) : Parsed {
            override fun equals(other: Any?) = other is PublicKey && key.contentEquals(other.key)
            override fun hashCode() = key.contentHashCode()
        }
    }

    /** Classifies [buf]; null means it is neither (including our own probes echoed back). */
    fun parse(buf: ByteArray, len: Int): Parsed? {
        if (len < 1 || len > buf.size) return null
        PairingIntro.parseReply(buf, len)?.let { return Parsed.PublicKey(it) }

        val text = when {
            buf[0] == ANNOUNCEMENT -> String(buf, 1, len - 1, Charsets.UTF_8)
            else -> String(buf, 0, len, Charsets.UTF_8)
        }
        if (!text.startsWith(PONG_PREFIX)) return null
        return Parsed.Announcement(cleanName(text.removePrefix(PONG_PREFIX)))
    }

    /** A hostname fit to show: no control characters, trimmed, not absurdly long. */
    fun cleanName(raw: String): String =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_NAME_LENGTH)
}
