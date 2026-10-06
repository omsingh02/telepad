package com.omsingh.telepad.core.bluetooth

import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidKeyboardStateTest {

    private val state = HidKeyboardState()

    private fun report(): ByteArray = ByteArray(HidKeyboardState.REPORT_SIZE).also { state.fillReport(it) }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an idle keyboard reports all zeroes`() {
        assertTrue(state.isIdle)
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0), report())
    }

    @Test
    fun `a plain key press and release`() {
        state.press(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.A, 0, 0, 0, 0, 0), report())
        state.release(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0), report())
        assertTrue(state.isIdle)
    }

    @Test
    fun `a chord modifier is reported only while its key is down`() {
        // This is the regression: Shift used to stay reported after the letter came up.
        state.press(HidKeyCodes.A, HidModifierMask.LEFT_SHIFT)
        assertArrayEquals(bytes(HidModifierMask.LEFT_SHIFT, 0, HidKeyCodes.A, 0, 0, 0, 0, 0), report())

        state.release(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0), report())
        assertEquals(0, state.modifiers())
    }

    @Test
    fun `typing a capital letter twice leaves nothing held`() {
        repeat(2) {
            state.press(HidKeyCodes.B, HidModifierMask.LEFT_SHIFT)
            state.release(HidKeyCodes.B)
        }
        assertTrue(state.isIdle)
        assertEquals(0, state.modifiers())
    }

    @Test
    fun `a held modifier key stays down until it is released`() {
        state.press(HidKeyCodes.LEFT_CTRL)
        assertEquals(HidModifierMask.LEFT_CTRL, state.modifiers())

        state.press(HidKeyCodes.C)
        assertArrayEquals(bytes(HidModifierMask.LEFT_CTRL, 0, HidKeyCodes.C, 0, 0, 0, 0, 0), report())
        state.release(HidKeyCodes.C)
        assertEquals("Ctrl must survive the C coming up", HidModifierMask.LEFT_CTRL, state.modifiers())

        state.release(HidKeyCodes.LEFT_CTRL)
        assertTrue(state.isIdle)
    }

    @Test
    fun `held and chord modifiers combine and release independently`() {
        state.press(HidKeyCodes.LEFT_ALT)
        state.press(HidKeyCodes.TAB, HidModifierMask.LEFT_SHIFT)
        assertEquals(HidModifierMask.LEFT_ALT or HidModifierMask.LEFT_SHIFT, state.modifiers())

        state.release(HidKeyCodes.TAB)
        assertEquals(HidModifierMask.LEFT_ALT, state.modifiers())
        state.release(HidKeyCodes.LEFT_ALT)
        assertEquals(0, state.modifiers())
    }

    @Test
    fun `every modifier usage maps to its bit`() {
        val usages = listOf(
            HidKeyCodes.LEFT_CTRL to HidModifierMask.LEFT_CTRL,
            HidKeyCodes.LEFT_SHIFT to HidModifierMask.LEFT_SHIFT,
            HidKeyCodes.LEFT_ALT to HidModifierMask.LEFT_ALT,
            HidKeyCodes.LEFT_META to HidModifierMask.LEFT_META,
            HidKeyCodes.RIGHT_CTRL to HidModifierMask.RIGHT_CTRL,
            HidKeyCodes.RIGHT_SHIFT to HidModifierMask.RIGHT_SHIFT,
            HidKeyCodes.RIGHT_ALT to HidModifierMask.RIGHT_ALT,
            HidKeyCodes.RIGHT_META to HidModifierMask.RIGHT_META,
        )
        for ((usage, mask) in usages) {
            state.press(usage)
            assertEquals("press $usage", mask, state.modifiers())
            state.release(usage)
            assertEquals("release $usage", 0, state.modifiers())
        }
    }

    @Test
    fun `pressing a key twice does not use two slots`() {
        state.press(HidKeyCodes.A)
        state.press(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.A, 0, 0, 0, 0, 0), report())
        state.release(HidKeyCodes.A)
        assertTrue(state.isIdle)
    }

    @Test
    fun `keys are reported in the order they were pressed`() {
        state.press(HidKeyCodes.C)
        state.press(HidKeyCodes.A)
        state.press(HidKeyCodes.B)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.C, HidKeyCodes.A, HidKeyCodes.B, 0, 0, 0), report())
        state.release(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.C, HidKeyCodes.B, 0, 0, 0, 0), report())
    }

    @Test
    fun `releasing a key that is not down changes nothing`() {
        state.press(HidKeyCodes.A)
        state.release(HidKeyCodes.Z)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.A, 0, 0, 0, 0, 0), report())
    }

    @Test
    fun `seven keys down report rollover until one comes up`() {
        val keys = listOf(HidKeyCodes.A, HidKeyCodes.B, HidKeyCodes.C, HidKeyCodes.D, HidKeyCodes.E, HidKeyCodes.F, HidKeyCodes.G)
        keys.forEach { state.press(it) }
        assertArrayEquals(bytes(0, 0, 1, 1, 1, 1, 1, 1), report())

        state.release(HidKeyCodes.G)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.A, HidKeyCodes.B, HidKeyCodes.C, HidKeyCodes.D, HidKeyCodes.E, HidKeyCodes.F), report())
    }

    @Test
    fun `a key pressed during rollover is not lost when a slot frees up`() {
        val keys = listOf(HidKeyCodes.A, HidKeyCodes.B, HidKeyCodes.C, HidKeyCodes.D, HidKeyCodes.E, HidKeyCodes.F, HidKeyCodes.G)
        keys.forEach { state.press(it) }
        state.release(HidKeyCodes.A)
        assertArrayEquals(bytes(0, 0, HidKeyCodes.B, HidKeyCodes.C, HidKeyCodes.D, HidKeyCodes.E, HidKeyCodes.F, HidKeyCodes.G), report())
    }

    @Test
    fun `release all clears keys modifiers and chords`() {
        state.press(HidKeyCodes.LEFT_META)
        state.press(HidKeyCodes.L, HidModifierMask.LEFT_SHIFT)
        state.press(HidKeyCodes.A)
        state.releaseAll()
        assertTrue(state.isIdle)
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0), report())
    }

    @Test
    fun `the none usage is ignored`() {
        state.press(HidKeyCodes.NONE)
        assertTrue(state.isIdle)
    }

    @Test
    fun `a chord pressed again adds to the one already on the key`() {
        state.press(HidKeyCodes.A, HidModifierMask.LEFT_CTRL)
        state.press(HidKeyCodes.A, HidModifierMask.LEFT_SHIFT)
        assertEquals(HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT, state.modifiers())
        state.release(HidKeyCodes.A)
        assertEquals(0, state.modifiers())
    }

    @Test
    fun `a report buffer that is too small is refused`() {
        val failed = runCatching { state.fillReport(ByteArray(4)) }.isFailure
        assertTrue(failed)
    }

    // ── Typing plans ────────────────────────────────────────────────────

    @Test
    fun `plain ASCII becomes one stroke per character`() {
        val plan = TypingPlan.of("Hi 5!")
        assertEquals(0, plan.skipped)
        assertEquals(
            listOf(
                TypingPlan.Stroke(HidKeyCodes.H, true),
                TypingPlan.Stroke(HidKeyCodes.I, false),
                TypingPlan.Stroke(HidKeyCodes.SPACE, false),
                TypingPlan.Stroke(HidKeyCodes.NUM_5, false),
                TypingPlan.Stroke(HidKeyCodes.NUM_1, true),
            ),
            plan.strokes,
        )
    }

    @Test
    fun `characters a keyboard cannot type are counted rather than silently lost`() {
        val plan = TypingPlan.of("aéb😀c")
        assertEquals(3, plan.strokes.size)
        assertEquals("é and the emoji (one code point) are skipped", 2, plan.skipped)
    }

    @Test
    fun `an empty string has an empty plan`() {
        val plan = TypingPlan.of("")
        assertTrue(plan.strokes.isEmpty())
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `typing a whole plan through the state machine leaves it idle`() {
        val plan = TypingPlan.of("Hello, World! 123")
        for (stroke in plan.strokes) {
            state.press(stroke.usage, if (stroke.shift) HidModifierMask.LEFT_SHIFT else 0)
            state.release(stroke.usage)
            assertTrue(state.isIdle)
        }
    }
}
