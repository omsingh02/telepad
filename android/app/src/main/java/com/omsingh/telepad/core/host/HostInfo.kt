package com.omsingh.telepad.core.host

/** Operating system of the PC the phone is controlling. */
enum class HostOs {
    /**
     * The server did not say (an older version) or runs on something this app
     * does not know. Treated like Windows, which is what every server was
     * before hosts reported anything.
     */
    UNKNOWN,
    WINDOWS,
    MACOS,
    LINUX,
}

/** Optional features a server offers, so the app can hide what is not there. */
data class HostCapabilities(
    /** `NowPlaying` replies carry real data (otherwise the card would stay empty). */
    val nowPlaying: Boolean = false,
    val clipboard: Boolean = true,
)

/** What the server told the phone about itself. */
data class HostInfo(
    val os: HostOs = HostOs.UNKNOWN,
    val capabilities: HostCapabilities = HostCapabilities(),
    /** Server version such as `"2.1.0"`, or null if the server did not report one. */
    val version: String? = null,
) {
    companion object {
        /** What to assume until (or unless) the server answers a host-info query. */
        val UNKNOWN = HostInfo()
    }
}

/**
 * Wire format of the host-info exchange (all inside the encrypted transport):
 *
 *  - phone to PC: `[0x12]`
 *  - PC to phone: `[0x82][os: u8][capabilities: u8][major: u8][minor: u8][patch: u8]`
 *
 * Capability bits: `0x01` now-playing, `0x02` clipboard. Trailing bytes a newer
 * server may append are ignored, and an unrecognised OS byte decodes as
 * [HostOs.UNKNOWN], so a newer server never breaks an older app.
 */
object HostInfoCodec {
    const val MSG_HOST_INFO_QUERY: Byte = 0x12
    const val MSG_HOST_INFO: Byte = 0x82.toByte()

    private const val MIN_LENGTH = 6
    private const val CAP_NOW_PLAYING = 0x01
    private const val CAP_CLIPBOARD = 0x02

    /** Parses a decrypted server message, or null if it is not a valid host-info reply. */
    fun parse(buf: ByteArray, len: Int): HostInfo? {
        if (len < MIN_LENGTH || len > buf.size || buf[0] != MSG_HOST_INFO) return null
        val os = when (buf[1].toInt() and 0xFF) {
            1 -> HostOs.WINDOWS
            2 -> HostOs.MACOS
            3 -> HostOs.LINUX
            else -> HostOs.UNKNOWN
        }
        val caps = buf[2].toInt() and 0xFF
        val version = "${buf[3].toInt() and 0xFF}.${buf[4].toInt() and 0xFF}.${buf[5].toInt() and 0xFF}"
        return HostInfo(
            os = os,
            capabilities = HostCapabilities(
                nowPlaying = caps and CAP_NOW_PLAYING != 0,
                clipboard = caps and CAP_CLIPBOARD != 0,
            ),
            version = version,
        )
    }
}
