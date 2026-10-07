package com.omsingh.telepad.core.input

import com.omsingh.telepad.core.input.TypingBridge.Composition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingBridgeTest {

    private val sent = mutableListOf<InputEvent>()
    private val keyboard = KeyboardSession { sent += it }
    private val bridge = TypingBridge(keyboard)

    private val ctrl = InputEvent.Modifiers(leftCtrl = true)
    private val none = InputEvent.Modifiers.EMPTY

    private fun backspace() = listOf(
        InputEvent.KeyPress(HidKeyCodes.BACKSPACE, none),
        InputEvent.KeyRelease(HidKeyCodes.BACKSPACE, none),
    )

    // ── Typing as the phone's keyboard commits it ────────────────────

    @Test
    fun `letters typed without suggestions go to the PC at once`() {
        assertNull(bridge.update("h", null))
        assertNull(bridge.update("he", null))
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("h"), InputEvent.TextInput("e")), sent)
    }

    @Test
    fun `the word still being composed is held back until it is committed`() {
        // The keyboard underlines the word while it is typed: nothing is sent yet.
        assertNull(bridge.update("hel", Composition(0, 3)))
        assertNull(bridge.update("hell", Composition(0, 4)))
        assertTrue(sent.isEmpty())

        // Space commits it.
        assertNull(bridge.update("hello ", null))
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("hello ")), sent)
    }

    @Test
    fun `only the committed words are sent while the next one is composed`() {
        bridge.update("hello ", null)
        sent.clear()

        bridge.update("hello wor", Composition(6, 9))
        assertTrue("the text before the underlined word is already on the PC", sent.isEmpty())

        bridge.update("hello world ", null)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("world ")), sent)
    }

    @Test
    fun `autocorrect never reaches the PC as a typo`() {
        bridge.update("teh", Composition(0, 3))
        bridge.update("the ", null) // the keyboard corrected it on space
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("the ")), sent)
    }

    @Test
    fun `a correction of text already sent is a backspace and the new text`() {
        bridge.update("helo ", null)
        sent.clear()

        // Tapping a suggestion replaces the word that was already committed. "hel" is shared,
        // so only the "o " after it is erased, and "lo " is typed in its place.
        bridge.update("hello ", null)
        assertEquals(backspace() * 2 + InputEvent.TextInput("lo "), sent)
    }

    @Test
    fun `deleting text sends one Backspace for each character`() {
        bridge.update("abc", null)
        sent.clear()

        bridge.update("a", null)
        assertEquals(backspace() * 2, sent)
    }

    @Test
    fun `an emoji is one character to delete`() {
        bridge.update("a🚀", null)
        sent.clear()

        bridge.update("a", null)
        assertEquals(backspace(), sent)
    }

    @Test
    fun `a word being composed in the middle is not held back`() {
        bridge.update("one two", null)
        sent.clear()

        // The PC's cursor is at the end, so only a word at the end can wait.
        bridge.update("one tXwo", Composition(4, 5))
        assertTrue(sent.isNotEmpty())
    }

    // ── Flushing ─────────────────────────────────────────────────────

    @Test
    fun `flush sends the word being composed, so a key from the bar comes after it`() {
        bridge.update("hello", Composition(0, 5))
        assertTrue(sent.isEmpty())

        bridge.flush("hello")
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("hello")), sent)

        // The keyboard going on to commit the same word sends nothing more.
        sent.clear()
        bridge.update("hello ", null)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput(" ")), sent)
    }

    @Test
    fun `a flushed word the keyboard then corrects is fixed on the PC`() {
        bridge.update("teh", Composition(0, 3))
        bridge.flush("teh")
        sent.clear()

        bridge.update("the ", null)
        assertEquals(backspace() * 2 + InputEvent.TextInput("he "), sent)
    }

    @Test
    fun `flush with nothing new sends nothing`() {
        bridge.update("done", null)
        sent.clear()
        bridge.flush("done")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `flush leaves text alone while a modifier is latched`() {
        bridge.update("hello", Composition(0, 5))
        keyboard.tapModifier(ModifierKey.CTRL, nowMs = 0)
        sent.clear()

        // Text typed before the modifier was latched must never be caught up in a chord.
        bridge.flush("hello")
        assertTrue(sent.isEmpty())
    }

    // ── Chords ───────────────────────────────────────────────────────

    @Test
    fun `with Ctrl latched a typed letter is Ctrl and that key, and the field is emptied`() {
        keyboard.tapModifier(ModifierKey.CTRL, nowMs = 0)

        val replacement = bridge.update("c", Composition(0, 1))

        assertEquals("", replacement)
        assertEquals(
            listOf<InputEvent>(
                InputEvent.KeyPress(HidKeyCodes.C, ctrl),
                InputEvent.KeyRelease(HidKeyCodes.C, ctrl),
            ),
            sent,
        )
    }

    @Test
    fun `a chord uses up a one-shot modifier but not a locked one`() {
        keyboard.tapModifier(ModifierKey.CTRL, nowMs = 0)
        keyboard.tapModifier(ModifierKey.CTRL, nowMs = 100) // quick second tap: locked

        bridge.update("c", null)
        bridge.update("v", null)

        assertEquals(
            listOf<InputEvent>(
                InputEvent.KeyPress(HidKeyCodes.C, ctrl), InputEvent.KeyRelease(HidKeyCodes.C, ctrl),
                InputEvent.KeyPress(HidKeyCodes.V, ctrl), InputEvent.KeyRelease(HidKeyCodes.V, ctrl),
            ),
            sent,
        )
    }

    @Test
    fun `after a chord ordinary typing is sent as text again`() {
        keyboard.tapModifier(ModifierKey.CTRL, nowMs = 0)
        bridge.update("c", null)
        sent.clear()

        assertNull(bridge.update("x", null))
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("x")), sent)
    }

    @Test
    fun `a chord is not held back by the keyboard's underlined word`() {
        keyboard.tapModifier(ModifierKey.META, nowMs = 0)
        bridge.update("q", Composition(0, 1))
        assertEquals(
            listOf<InputEvent>(
                InputEvent.KeyPress(HidKeyCodes.Q, InputEvent.Modifiers(leftMeta = true)),
                InputEvent.KeyRelease(HidKeyCodes.Q, InputEvent.Modifiers(leftMeta = true)),
            ),
            sent,
        )
    }

    // ── Lines and length ─────────────────────────────────────────────

    @Test
    fun `Enter sends the line and starts the field afresh`() {
        bridge.update("hi", null)
        sent.clear()

        val replacement = bridge.update("hi\n", null)

        assertEquals("", replacement)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("\n")), sent)

        // The next line is compared with an empty field, not with the old line.
        sent.clear()
        bridge.update("a", null)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("a")), sent)
    }

    @Test
    fun `a very long field is cut to its end, and the PC keeps all of it`() {
        val long = "x".repeat(TYPING_MAX_BUFFER + 1)

        val replacement = bridge.update(long, null)

        assertEquals(120, replacement?.length)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput(long)), sent)
    }

    @Test
    fun `reset forgets what the field held`() {
        bridge.update("abc", null)
        bridge.reset()
        sent.clear()

        bridge.update("d", null)
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("d")), sent)
    }

    @Test
    fun `Backspace with an empty field is still a Backspace on the PC`() {
        bridge.backspaceWithNothingToDelete()
        assertEquals(backspace(), sent)
    }

    private operator fun List<InputEvent>.times(n: Int) = (1..n).flatMap { this }
}
