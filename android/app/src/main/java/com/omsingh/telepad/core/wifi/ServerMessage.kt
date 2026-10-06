package com.omsingh.telepad.core.wifi

import com.omsingh.telepad.core.host.HostInfo
import com.omsingh.telepad.core.host.HostInfoCodec
import com.omsingh.telepad.core.media.NowPlayingState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A message the PC sends to the phone, once decrypted. */
sealed interface ServerMessage {
    /** The PC's clipboard text, answering a clipboard request. */
    data class Clipboard(val text: String) : ServerMessage

    data class NowPlaying(val state: NowPlayingState) : ServerMessage

    data class Host(val info: HostInfo) : ServerMessage
}

/**
 * Reads the PC's replies.
 *
 *  - `[0x80][u16 length][utf-8 text]` clipboard
 *  - `[0x81][flags][i64 position][i64 duration]{[u8 length][utf-8]}x4` now playing
 *  - `[0x82]...` host info (see [HostInfoCodec])
 *
 * Anything malformed, truncated or unknown yields null and is ignored: a packet
 * that fails to parse must never be able to crash the receive loop.
 */
object ServerMessages {
    const val CLIPBOARD_DATA: Byte = 0x80.toByte()
    const val NOW_PLAYING: Byte = 0x81.toByte()

    private const val NOW_PLAYING_FIXED = 1 + 1 + 8 + 8
    private const val FLAG_PLAYING = 0x01

    fun parse(buf: ByteArray, len: Int, nowMs: Long): ServerMessage? {
        if (len < 1 || len > buf.size) return null
        return when (buf[0]) {
            CLIPBOARD_DATA -> parseClipboard(buf, len)
            NOW_PLAYING -> parseNowPlaying(buf, len, nowMs)
            HostInfoCodec.MSG_HOST_INFO -> HostInfoCodec.parse(buf, len)?.let { ServerMessage.Host(it) }
            else -> null
        }
    }

    private fun parseClipboard(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 3) return null
        val textLen = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)
        if (3 + textLen > len) return null
        return ServerMessage.Clipboard(String(buf, 3, textLen, Charsets.UTF_8))
    }

    private fun parseNowPlaying(buf: ByteArray, len: Int, nowMs: Long): ServerMessage? {
        if (len < NOW_PLAYING_FIXED) return null
        val bb = ByteBuffer.wrap(buf, 1, len - 1).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.get().toInt() and 0xFF
        val position = bb.long
        val duration = bb.long

        // Four strings follow; an absent one is a zero length (or the packet simply ends).
        fun readString(): String? {
            if (bb.remaining() < 1) return null
            val n = bb.get().toInt() and 0xFF
            if (n == 0 || bb.remaining() < n) return null
            val bytes = ByteArray(n)
            bb.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        return ServerMessage.NowPlaying(
            NowPlayingState(
                title = readString(),
                artist = readString(),
                album = readString(),
                sourceApp = readString(),
                isPlaying = flags and FLAG_PLAYING != 0,
                positionMs = position.takeIf { it >= 0 },
                durationMs = duration.takeIf { it >= 0 },
                sampledAtMs = nowMs,
            ),
        )
    }
}
