package com.omsingh.telepad.core.input

import android.view.MotionEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the gesture state machine.
 *
 * We mock MotionEvent (the Android class is non-trivial to construct in unit
 * tests without Robolectric) and emit canned action sequences, asserting on
 * the InputEvents that the processor produces.
 */
class TouchpadProcessorTest {

    private val collected = mutableListOf<InputEvent>()
    private val processor = TouchpadProcessor(
        sensitivityCurve = SensitivityCurve(
            baseSensitivity = 1f,
            curve = SensitivityCurve.AccelCurve.LINEAR,
        ),
        onEvent = { collected += it }
    )

    @Test
    fun `single tap emits Click`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 10))

        assertEquals(listOf(InputEvent.Click), collected)
    }

    @Test
    fun `two-finger tap emits RightClick`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0, pointers = 1))
        processor.onTouchEvent(motion(MotionEvent.ACTION_POINTER_DOWN, 200f, 200f, 5, pointers = 2))
        processor.onTouchEvent(motion(MotionEvent.ACTION_POINTER_UP, 200f, 200f, 30, pointers = 2))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP, 100f, 100f, 40, pointers = 1))

        assertEquals(listOf(InputEvent.RightClick), collected)
    }

    @Test
    fun `drag emits MouseMove`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 110f, 105f, 16))

        assertEquals(1, collected.size)
        val move = collected.first() as InputEvent.MouseMove
        assertEquals(10f, move.dx, 0.01f)
        assertEquals(5f, move.dy, 0.01f)
    }

    @Test
    fun `tap-then-tap emits double click`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 10))
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 50))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 60))

        assertTrue(collected.contains(InputEvent.DoubleClick))
    }

    @Test
    fun `drag mode after double-tap holds button`() {
        processor.doubleTapDrag = true
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 10))
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 50))  // 2nd down inside window
        processor.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 130f, 100f, 60))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   130f, 100f, 100))

        assertTrue(collected.any { it is InputEvent.DragStart })
        assertTrue(collected.any { it is InputEvent.DragEnd })
    }

    private fun motion(
        action: Int,
        x: Float,
        y: Float,
        time: Long,
        pointers: Int = 1,
    ): MotionEvent = mockk {
        every { actionMasked } returns action
        every { actionIndex } returns 0
        every { this@mockk.x } returns x
        every { this@mockk.y } returns y
        every { getX(any()) } returns x
        every { getY(any()) } returns y
        every { pointerCount } returns pointers
        every { eventTime } returns time
    }
}
