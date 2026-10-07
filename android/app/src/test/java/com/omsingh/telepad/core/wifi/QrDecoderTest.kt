package com.omsingh.telepad.core.wifi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDecoderTest {

    private val decoder = QrDecoder()

    /** The link behind [PRINTED], as the real server wrote it. */
    private val printedLink = "telepad://pair?v=1&k=fnAmD9d6yOTnzAOrejyusK8TBS5RLE2adzJ3PjmRSSU" +
        "&t=Qr0sz9kxtCEBwKtBn9ykkw&p=5057&h=10.10.189.161,100.95.242.28&n=deed"

    /** A frame's brightness: [pixels] one byte each, row after row. */
    private class Frame(val pixels: ByteArray, val width: Int, val height: Int)

    /** Draws [text] as a QR code the usual way: dark squares on a light ground, [scale] pixels to a square. */
    private fun encode(text: String, scale: Int = 6, margin: Int = 4): Frame {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to margin),
        )
        val pixels = ByteArray(matrix.width * matrix.height * scale * scale)
        val width = matrix.width * scale
        for (y in 0 until matrix.height * scale) for (x in 0 until width) {
            pixels[y * width + x] = if (matrix[x / scale, y / scale]) 0 else 255.toByte()
        }
        return Frame(pixels, width, matrix.height * scale)
    }

    private fun Frame.inverted() = Frame(ByteArray(pixels.size) { (255 - (pixels[it].toInt() and 0xFF)).toByte() }, width, height)

    private fun Frame.decode(rowStride: Int = width): String? = decoder.decode(pixels, width, height, rowStride)

    /** Pads every row to [stride] bytes, as a camera does. */
    private fun Frame.padded(stride: Int): ByteArray {
        val out = ByteArray(stride * height) { 0x55 }
        for (y in 0 until height) System.arraycopy(pixels, y * width, out, y * stride, width)
        return out
    }

    // ── Codes ────────────────────────────────────────────────────────

    @Test
    fun `a code drawn the usual way is read`() {
        assertEquals(printedLink, encode(printedLink).decode())
    }

    @Test
    fun `a code drawn the other way round, as a dark terminal shows it, is read too`() {
        assertEquals(printedLink, encode(printedLink).inverted().decode())
    }

    @Test
    fun `a frame whose rows are padded is read`() {
        val frame = encode(printedLink)
        val stride = frame.width + 48
        assertEquals(printedLink, decoder.decode(frame.padded(stride), frame.width, frame.height, stride))
    }

    @Test
    fun `a small image of a code is read`() {
        assertEquals(printedLink, encode(printedLink, scale = 3, margin = 2).decode())
    }

    // ── The code the real server printed ─────────────────────────────

    /** What the real server drew in a terminal (light blocks on a dark ground), turned back into pixels. */
    private fun frameOfTerminalDrawing(drawing: String, scale: Int = 6): Frame {
        val lines = drawing.lines().filter { it.isNotEmpty() }
        val columns = lines.maxOf { it.length }
        val width = columns * scale
        val height = lines.size * 2 * scale
        val pixels = ByteArray(width * height)
        for ((row, line) in lines.withIndex()) {
            for (column in 0 until columns) {
                val glyph = line.getOrElse(column) { ' ' }
                // A block is two rows of the code to a line: the top half and the bottom half.
                val top = glyph == '█' || glyph == '▀'
                val bottom = glyph == '█' || glyph == '▄'
                for (half in 0..1) {
                    val lit = if (half == 0) top else bottom
                    for (dy in 0 until scale) for (dx in 0 until scale) {
                        val y = (row * 2 + half) * scale + dy
                        pixels[y * width + column * scale + dx] = if (lit) 255.toByte() else 0
                    }
                }
            }
        }
        return Frame(pixels, width, height)
    }

    @Test
    fun `the code the real server drew in a terminal is read, and says what the server printed`() {
        val frame = frameOfTerminalDrawing(PRINTED)
        assertEquals(printedLink, frame.decode())
    }

    @Test
    fun `the same drawing for a light terminal, seen the other way round, is read as well`() {
        assertEquals(printedLink, frameOfTerminalDrawing(PRINTED).inverted().decode())
    }

    @Test
    fun `the printed link is a code this app can use`() {
        val invite = (PairingInvite.parse(frameOfTerminalDrawing(PRINTED).decode()!!) as PairingInvite.Read.Valid).invite
        assertEquals(5057, invite.port)
        assertEquals("deed", invite.name)
    }

    // ── No code ──────────────────────────────────────────────────────

    @Test
    fun `a blank frame, or noise, has no code`() {
        assertNull(decoder.decode(ByteArray(300 * 300) { 255.toByte() }, 300, 300))
        assertNull(decoder.decode(ByteArray(300 * 300), 300, 300))
        val noise = java.util.Random(7).let { random -> ByteArray(300 * 300) { random.nextInt(256).toByte() } }
        assertNull(decoder.decode(noise, 300, 300))
    }

    @Test
    fun `a frame that does not add up is refused, not read past its end`() {
        assertNull(decoder.decode(ByteArray(10), 300, 300))
        assertNull(decoder.decode(ByteArray(100), 0, 10))
        assertNull(decoder.decode(ByteArray(100), 10, 10, rowStride = 5))
        assertTrue(true)
    }

    private companion object {
        /** Copied from the server's own output: the terminal drawing of [printedLink]. */
        val PRINTED = """
█████████████████████████████████████████████████
█████████████████████████████████████████████████
████ ▄▄▄▄▄ █▀ ▄ ▄▀█▀▄█▀█ █▀▄██ ▀█▄ ▄▀█ ▄▄▄▄▄ ████
████ █   █ █▀█ ▀▄▀█ ▀▀ █ ▄██▀▀█  ▀██▀█ █   █ ████
████ █▄▄▄█ █▀▄ ██ ▄▀█▀▀▄▀█▄▄ ▀▀▀ ▀▄▄ █ █▄▄▄█ ████
████▄▄▄▄▄▄▄█▄█ ▀ █▄█ █ █▄▀▄█ █▄▀▄█ █▄█▄▄▄▄▄▄▄████
████  ▄▄▄█▄▄   ▀█ ▄▀ █▀▀▄▀ ▀▄▄ █▀ ▀ ▄▄▀ ▀ ▀▄▀████
█████▀▀ █▀▄▄▄▄  ▀██▄▄█ ▀▄ ▀▀ █  ▄▄▄█ ██ █▀█▀█████
█████▀▀ ▀ ▄██ ▀▄  █▄█  ▀▄█▀▀▀ ██  ▀ ▄▀█▀ █▄ ▀████
████▄██▀▀▀▄▄ ███▄█ ▄██▀█▀ ▀█▀█▀▀▄▄▄▀▀ █▄▀ ▄██████
████ ▄▄▄█▀▄█ █▀▀▀█ ▄▄▀▀▄ █ ▀██▄▄▄ ▄ ▄▀▀█▀ ▄█▀████
████▀ █▄▀▀▄█ ███▄▄▄▄█▄▀▀█ █▀█▄▄ ▀▄▄▀▀ ▀ █ █ ▀████
█████▄  █ ▄▀███▀▄▄▄█▄▄▄▀█▀▀ ▀ ▄ ▄ █▀▀█▀▀ ▄  ▀████
████▄▄ █ ▄▄▄▄▄███▄█ ▀███▄  ▀▄▄  ███▀█▄█▀ █▄▀█████
█████▄  ▀ ▄  █ ▀█▀  ▀ ▄█▀█ ▄▄▀  ▀▄▀▀ ▀▀█▀▄▄█▀████
████▀▀  ▀▀▄ ▀█ ▀▄▄▄▄██▄█▀ ▀█▀██  ▄█▀ █▀██▀███████
████▀████▀▄▄▄▄▄▀▄▄▀█▄▄▄▀▄▀█▀ ▄▀▀▄▄ ▄▀▀█ ▀█ ▄▀████
████ ██▀ ▀▄▄█▀███▄▀▄█▄ ▀▀▀▀█ █▄  ▄▀█▀▄▄█▄ █▀█████
████▄███▄▄▄▄▀▀█▀ ▀ ▄ ▄▀█ ▄▄█▄ ▄█▀▄▀▀ ▄▄▄ █▄█▄████
████ ▄▄▄▄▄ █▄ ▄███  ▀████▀▄▀ █  ▄▄▀▄ █▄█  ███████
████ █   █ █ ▀▄▀▄▄▄▄▄▄  ▄█▀ ▀ ▀▄▄ ██ ▄▄ ▄█ █▄████
████ █▄▄▄█ █ ▄▄█▄▄▀▄▀█▄█▄▀▀█▀▄▄ █▄▀▀▀▀ █  ▄▀█████
████▄▄▄▄▄▄▄█▄█████▄██▄██▄████▄▄▄███▄██▄▄▄▄▄██████
█████████████████████████████████████████████████
▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀
"""
    }
}
