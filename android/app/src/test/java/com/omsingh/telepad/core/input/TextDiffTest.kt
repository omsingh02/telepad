package com.omsingh.telepad.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {

    private fun edit(old: String, new: String) = TextDiff.diff(old, new)

    /** Replays an edit on a model of the PC's text, the way key presses would. */
    private fun apply(pc: String, edit: TextEdit): String {
        var text = pc
        repeat(edit.backspaces) {
            if (text.isNotEmpty()) text = text.substring(0, text.offsetByCodePoints(text.length, -1))
        }
        return text + edit.insert
    }

    @Test
    fun `identical text needs no keys`() {
        assertTrue(edit("hello", "hello").isEmpty)
        assertTrue(edit("", "").isEmpty)
    }

    @Test
    fun `typing appends`() {
        assertEquals(TextEdit(0, "o"), edit("hell", "hello"))
        assertEquals(TextEdit(0, "h"), edit("", "h"))
        assertEquals(TextEdit(0, " world"), edit("hello", "hello world"))
    }

    @Test
    fun `deleting backspaces`() {
        assertEquals(TextEdit(1, ""), edit("hello", "hell"))
        assertEquals(TextEdit(5, ""), edit("hello", ""))
        assertEquals(TextEdit(2, ""), edit("hello", "hel"))
    }

    @Test
    fun `autocorrect replaces the end of the word instead of corrupting it`() {
        // The old length-only logic typed "o " here and left "heloo " on the PC.
        val e = edit("helo", "hello ")
        assertEquals(TextEdit(1, "lo "), e)
        assertEquals("hello ", apply("helo", e))
    }

    @Test
    fun `a same-length replacement is not silently dropped`() {
        // Old logic saw equal lengths and sent nothing at all.
        val e = edit("teh", "the")
        assertEquals(TextEdit(2, "he"), e)
        assertEquals("the", apply("teh", e))
    }

    @Test
    fun `a suggestion that replaces the whole word`() {
        val e = edit("recieve", "receive")
        assertEquals("receive", apply("recieve", e))
        assertEquals(4, e.backspaces)
    }

    @Test
    fun `an edit in the middle rewrites from the change onward`() {
        val e = edit("hello world", "help world")
        assertEquals("help world", apply("hello world", e))
        assertEquals("\"lo world\" (8 chars) follows the common prefix \"hel\"", TextEdit(8, "p world"), e)
    }

    @Test
    fun `emoji count as one character`() {
        assertEquals(TextEdit(1, ""), edit("hi 🚀", "hi "))
        assertEquals(TextEdit(0, "🚀"), edit("hi ", "hi 🚀"))
        val e = edit("go 🚀", "go 🎉")
        assertEquals("go 🎉", apply("go 🚀", e))
        assertEquals(1, e.backspaces)
    }

    @Test
    fun `a common prefix never splits a surrogate pair`() {
        // U+1F680 (🚀) and U+1F681 (🚁) share their high surrogate.
        val rocket = "🚀"
        val helicopter = "🚁"
        assertEquals(rocket[0], helicopter[0])
        val e = edit("a$rocket", "a$helicopter")
        assertEquals("a$helicopter", apply("a$rocket", e))
        assertEquals(1, e.backspaces)
        assertEquals(helicopter, e.insert)
    }

    @Test
    fun `accented and non-latin text is handled`() {
        assertEquals("café", apply("cafe", edit("cafe", "café")))
        assertEquals("안녕하세요", apply("안녕하세", edit("안녕하세", "안녕하세요")))
        assertEquals(TextEdit(0, "요"), edit("안녕하세", "안녕하세요"))
    }

    @Test
    fun `replaying the diff always reproduces the new text`() {
        val samples = listOf(
            "", "a", "ab", "hello", "hello world", "héllo", "🚀", "a🚀b", "🚀🚁", "the quick brown fox",
            "teh quick", "the quick", "日本語", "日本", "abc 🚀 def", "abc 🚁 def"
        )
        for (old in samples) for (new in samples) {
            val e = edit(old, new)
            assertEquals("'$old' -> '$new' via $e", new, apply(old, e))
        }
    }

    @Test
    fun `the diff is minimal when only the tail changed`() {
        val e = edit("the quick brown fox", "the quick brown cat")
        assertEquals(TextEdit(3, "cat"), e)
    }
}
