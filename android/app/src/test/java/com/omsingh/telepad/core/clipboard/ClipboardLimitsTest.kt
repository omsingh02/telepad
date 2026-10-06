package com.omsingh.telepad.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardLimitsTest {

    private fun utf8Size(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun `text within the limit is returned unchanged`() {
        assertEquals("hello", "hello".clipToUtf8Bytes(10))
        assertEquals("hello", "hello".clipToUtf8Bytes(5))
        assertEquals("", "".clipToUtf8Bytes(5))
    }

    @Test
    fun `ascii is cut at exactly the byte limit`() {
        assertEquals("hel", "hello".clipToUtf8Bytes(3))
        assertEquals("", "hello".clipToUtf8Bytes(0))
    }

    @Test
    fun `a two byte character is never split`() {
        // 'é' is 2 bytes in UTF-8.
        assertEquals("", "é".clipToUtf8Bytes(1))
        assertEquals("a", "aé".clipToUtf8Bytes(2))
        assertEquals("aé", "aé".clipToUtf8Bytes(3))
    }

    @Test
    fun `a surrogate pair is kept or dropped whole`() {
        val rocket = "🚀" // U+1F680, 4 bytes, two Kotlin Chars
        assertEquals("ab", "ab$rocket".clipToUtf8Bytes(4))
        assertEquals("ab", "ab$rocket".clipToUtf8Bytes(5))
        assertEquals("ab$rocket", "ab$rocket".clipToUtf8Bytes(6))
    }

    @Test
    fun `three byte characters are counted correctly`() {
        val korean = "한".repeat(500) // 1500 bytes
        val clipped = korean.clipToUtf8Bytes(MAX_CLIPBOARD_UTF8_BYTES)
        assertEquals(400, clipped.length)
        assertEquals(1200, utf8Size(clipped))
    }

    @Test
    fun `the result always fits and is a prefix for every limit`() {
        val text = "aé한🚀".repeat(40)
        for (max in 0..utf8Size(text) + 2) {
            val clipped = text.clipToUtf8Bytes(max)
            assertTrue("size ${utf8Size(clipped)} > $max", utf8Size(clipped) <= max)
            assertTrue(text.startsWith(clipped))
            // No lone surrogate left behind by the cut.
            assertTrue(clipped.isEmpty() || !Character.isHighSurrogate(clipped.last()))
        }
    }

    @Test
    fun `a clipped message plus protocol overhead fits one datagram`() {
        // 9-byte wire header + 3-byte message header + payload + 16-byte tag.
        val worstCase = "🚀".repeat(1000).clipToUtf8Bytes(MAX_CLIPBOARD_UTF8_BYTES)
        assertTrue(9 + 3 + utf8Size(worstCase) + 16 <= 1472)
    }
}
