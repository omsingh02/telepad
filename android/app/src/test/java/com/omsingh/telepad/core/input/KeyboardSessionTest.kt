package com.omsingh.telepad.core.input

import com.omsingh.telepad.core.host.Chord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSessionTest {

    private val sent = mutableListOf<InputEvent>()
    private val keyboard = KeyboardSession { sent += it }

    private val ctrl = InputEvent.Modifiers(leftCtrl = true)
    private val shift = InputEvent.Modifiers(leftShift = true)

    private fun tapOf(usage: Int, mods: InputEvent.Modifiers) =
        listOf(InputEvent.KeyPress(usage, mods), InputEvent.KeyRelease(usage, mods))

    // ── Plain typing ─────────────────────────────────────────────────

    @Test
    fun `plain text is sent as text`() {
        keyboard.type("héllo 🚀")
        assertEquals(listOf(InputEvent.TextInput("héllo 🚀")), sent)
    }

    @Test
    fun `empty text sends nothing`() {
        keyboard.type("")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `backspaces are key taps`() {
        keyboard.backspace(2)
        assertEquals(tapOf(HidKeyCodes.BACKSPACE, InputEvent.Modifiers.EMPTY) * 2, sent)
    }

    private operator fun List<InputEvent>.times(n: Int) = (1..n).flatMap { this }

    @Test
    fun `zero backspaces send nothing`() {
        keyboard.backspace(0)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a key tap is a press then a release`() {
        keyboard.tapKey(HidKeyCodes.ESC)
        assertEquals(tapOf(HidKeyCodes.ESC, InputEvent.Modifiers.EMPTY), sent)
    }

    @Test
    fun `a held key presses now and releases later`() {
        keyboard.press(HidKeyCodes.LEFT)
        assertEquals(listOf<InputEvent>(InputEvent.KeyPress(HidKeyCodes.LEFT, InputEvent.Modifiers.EMPTY)), sent)
        keyboard.release(HidKeyCodes.LEFT)
        assertEquals(InputEvent.KeyRelease(HidKeyCodes.LEFT, InputEvent.Modifiers.EMPTY), sent.last())
    }

    // ── Modifiers ────────────────────────────────────────────────────

    @Test
    fun `a modifier tapped once applies to the next key only`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        assertEquals(ModifierState.ARMED, keyboard.state(ModifierKey.CTRL))

        keyboard.tapKey(HidKeyCodes.C)
        assertEquals(tapOf(HidKeyCodes.C, ctrl), sent)
        assertEquals(ModifierState.OFF, keyboard.state(ModifierKey.CTRL))

        sent.clear()
        keyboard.tapKey(HidKeyCodes.C)
        assertEquals(tapOf(HidKeyCodes.C, InputEvent.Modifiers.EMPTY), sent)
    }

    @Test
    fun `tapping a modifier twice quickly locks it`() {
        keyboard.tapModifier(ModifierKey.SHIFT, 1_000)
        keyboard.tapModifier(ModifierKey.SHIFT, 1_200)
        assertEquals(ModifierState.LOCKED, keyboard.state(ModifierKey.SHIFT))

        keyboard.tapKey(HidKeyCodes.A)
        keyboard.tapKey(HidKeyCodes.B)
        assertEquals(tapOf(HidKeyCodes.A, shift) + tapOf(HidKeyCodes.B, shift), sent)
        assertEquals("a locked modifier survives its key", ModifierState.LOCKED, keyboard.state(ModifierKey.SHIFT))
    }

    @Test
    fun `tapping a locked modifier releases it`() {
        keyboard.tapModifier(ModifierKey.ALT, 0)
        keyboard.tapModifier(ModifierKey.ALT, 100)
        keyboard.tapModifier(ModifierKey.ALT, 5_000)
        assertEquals(ModifierState.OFF, keyboard.state(ModifierKey.ALT))
    }

    @Test
    fun `a slow second tap on an armed modifier switches it off`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.tapModifier(ModifierKey.CTRL, 2_000)
        assertEquals(ModifierState.OFF, keyboard.state(ModifierKey.CTRL))
    }

    @Test
    fun `several modifiers combine`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.tapModifier(ModifierKey.SHIFT, 10)
        keyboard.tapKey(HidKeyCodes.Z)
        assertEquals(tapOf(HidKeyCodes.Z, InputEvent.Modifiers(leftCtrl = true, leftShift = true)), sent)
    }

    @Test
    fun `release modifiers clears everything`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.tapModifier(ModifierKey.SHIFT, 0)
        keyboard.tapModifier(ModifierKey.SHIFT, 50)
        keyboard.releaseModifiers()
        assertFalse(keyboard.hasModifiers)
    }

    // ── Typing with modifiers ────────────────────────────────────────

    @Test
    fun `text typed with a modifier becomes key presses`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.type("c")
        assertEquals(tapOf(HidKeyCodes.C, ctrl), sent)
        assertEquals(ModifierState.OFF, keyboard.state(ModifierKey.CTRL))
    }

    @Test
    fun `capital letters add shift to the modifiers`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.type("C")
        assertEquals(tapOf(HidKeyCodes.C, InputEvent.Modifiers(leftCtrl = true, leftShift = true)), sent)
    }

    @Test
    fun `an armed modifier is used up by the text it was typed with`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.type("ab")
        // Both characters arrived in one call, so both are inside the same latched state.
        assertEquals(tapOf(HidKeyCodes.A, ctrl) + tapOf(HidKeyCodes.B, ctrl), sent)
        sent.clear()
        keyboard.type("c")
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("c")), sent)
    }

    @Test
    fun `characters with no key combination are skipped while a modifier is latched`() {
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        keyboard.type("é😀a")
        assertEquals(tapOf(HidKeyCodes.A, ctrl), sent)
    }

    // ── Shortcuts ────────────────────────────────────────────────────

    @Test
    fun `a shortcut sends its chord`() {
        keyboard.shortcut(Chord(HidKeyCodes.C, HidModifierMask.LEFT_CTRL))
        assertEquals(tapOf(HidKeyCodes.C, ctrl), sent)
    }

    @Test
    fun `a shortcut merges with a latched modifier`() {
        keyboard.tapModifier(ModifierKey.SHIFT, 0)
        keyboard.shortcut(Chord(HidKeyCodes.V, HidModifierMask.LEFT_CTRL))
        assertEquals(tapOf(HidKeyCodes.V, InputEvent.Modifiers(leftCtrl = true, leftShift = true)), sent)
        assertEquals(ModifierState.OFF, keyboard.state(ModifierKey.SHIFT))
    }

    @Test
    fun `a shortcut with every modifier bit sends them all`() {
        val all = HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT or HidModifierMask.LEFT_ALT or HidModifierMask.LEFT_META
        keyboard.shortcut(Chord(HidKeyCodes.TAB, all))
        assertEquals(
            tapOf(HidKeyCodes.TAB, InputEvent.Modifiers(leftCtrl = true, leftShift = true, leftAlt = true, leftMeta = true)),
            sent,
        )
    }

    @Test
    fun `the version changes when a modifier does, so screens can redraw`() {
        val start = keyboard.version.value
        keyboard.tapModifier(ModifierKey.CTRL, 0)
        val armed = keyboard.version.value
        assertTrue(armed > start)

        keyboard.tapKey(HidKeyCodes.A) // uses up the armed Ctrl
        assertTrue(keyboard.version.value > armed)

        val idle = keyboard.version.value
        keyboard.tapKey(HidKeyCodes.A) // nothing latched, nothing changes
        assertEquals(idle, keyboard.version.value)
    }
}
