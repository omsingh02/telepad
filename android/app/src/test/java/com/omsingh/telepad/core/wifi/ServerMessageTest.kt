package com.omsingh.telepad.core.wifi

import com.omsingh.telepad.core.host.HostOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServerMessageTest {

    private fun parse(bytes: ByteArray, len: Int = bytes.size) = ServerMessages.parse(bytes, len, nowMs = 1_000L)

    private fun clipboard(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x80.toByte(), (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte()) + body
    }

    private fun nowPlaying(
        flags: Int, position: Long, duration: Long, vararg strings: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x81)
        out.write(flags)
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(position).putLong(duration).array())
        for (s in strings) {
            if (s == null) {
                out.write(0)
            } else {
                val b = s.toByteArray(Charsets.UTF_8)
                out.write(b.size)
                out.write(b)
            }
        }
        return out.toByteArray()
    }

    // ── Clipboard ────────────────────────────────────────────────────────

    @Test
    fun `a clipboard reply carries its text`() {
        val message = parse(clipboard("héllo 📋"))
        assertEquals(ServerMessage.Clipboard("héllo 📋"), message)
    }

    @Test
    fun `an empty clipboard is valid`() {
        assertEquals(ServerMessage.Clipboard(""), parse(clipboard("")))
    }

    @Test
    fun `a clipboard reply claiming more text than it has is rejected`() {
        val bytes = clipboard("hello")
        assertNull(parse(bytes, bytes.size - 1))
    }

    @Test
    fun `a clipboard reply too short for its header is rejected`() {
        assertNull(parse(byteArrayOf(0x80.toByte(), 1)))
    }

    @Test
    fun `trailing bytes after the clipboard text are ignored`() {
        val bytes = clipboard("hi") + byteArrayOf(9, 9, 9)
        assertEquals(ServerMessage.Clipboard("hi"), parse(bytes))
    }

    // ── Now playing ──────────────────────────────────────────────────────

    @Test
    fun `a now playing reply carries every field`() {
        val message = parse(nowPlaying(0x01 or 0x02 or 0x04 or 0x08 or 0x10, 12_000, 240_000, "Song", "Artist", "Album", "Spotify"))
        val state = (message as ServerMessage.NowPlaying).state
        assertEquals("Song", state.title)
        assertEquals("Artist", state.artist)
        assertEquals("Album", state.album)
        assertEquals("Spotify", state.sourceApp)
        assertTrue(state.isPlaying)
        assertEquals(12_000L, state.positionMs)
        assertEquals(240_000L, state.durationMs)
        assertEquals("sample time is when we received it", 1_000L, state.sampledAtMs)
    }

    @Test
    fun `absent strings and unknown positions come out as null`() {
        val message = parse(nowPlaying(0x00, -1, -1, "Only title", null, null, null))
        val state = (message as ServerMessage.NowPlaying).state
        assertEquals("Only title", state.title)
        assertNull(state.artist)
        assertNull(state.album)
        assertNull(state.sourceApp)
        assertFalse(state.isPlaying)
        assertNull(state.positionMs)
        assertNull(state.durationMs)
    }

    @Test
    fun `a now playing reply may end before the strings`() {
        val bytes = nowPlaying(0x01, 5, 10)
        val state = (parse(bytes) as ServerMessage.NowPlaying).state
        assertNull(state.title)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `a string running past the end of the packet is dropped without crashing`() {
        val bytes = nowPlaying(0x02, 0, 0, "A long title that gets cut")
        val truncated = bytes.copyOf(bytes.size - 5)
        val state = (parse(truncated) as ServerMessage.NowPlaying).state
        assertNull(state.title)
    }

    @Test
    fun `a now playing reply shorter than its fixed part is rejected`() {
        assertNull(parse(byteArrayOf(0x81.toByte(), 0, 1, 2, 3)))
    }

    // ── Host info and the rest ───────────────────────────────────────────

    @Test
    fun `a host info reply is passed through`() {
        val message = parse(byteArrayOf(0x82.toByte(), 3, 0x02, 2, 1, 7))
        val info = (message as ServerMessage.Host).info
        assertEquals(HostOs.LINUX, info.os)
        assertEquals("2.1.7", info.version)
        assertTrue(info.capabilities.clipboard)
    }

    @Test
    fun `a truncated host info reply is rejected`() {
        assertNull(parse(byteArrayOf(0x82.toByte(), 3)))
    }

    @Test
    fun `unknown types and empty packets are ignored`() {
        assertNull(parse(byteArrayOf(0x7F, 1, 2, 3)))
        assertNull(parse(ByteArray(0)))
        assertNull(ServerMessages.parse(ByteArray(4), 99, 0))
    }
}
