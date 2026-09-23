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

        assertEquals(listOf(InputEvent.Click, InputEvent.DoubleClick), collected)
    }

    @Test
    fun `double tap window expiry emits two separate clicks`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 10))
        // Beyond 280ms DOUBLE_TAP_WINDOW_MS
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 350))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 360))

        assertEquals(listOf(InputEvent.Click, InputEvent.Click), collected)
    }

    @Test
    fun `tap exceeding duration limit does not emit click`() {
        // Exceeds 250ms TAP_MAX_DURATION_MS
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   100f, 100f, 300))

        assertTrue(collected.isEmpty())
    }

    @Test
    fun `movement exceeding touch slop does not emit click`() {
        // Exceeds 15px TAP_MAX_MOVEMENT_PX
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 125f, 100f, 10))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP,   125f, 100f, 20))

        assertTrue(collected.none { it is InputEvent.Click })
        assertTrue(collected.any { it is InputEvent.MouseMove })
    }

    @Test
    fun `action cancel resets pending gestures without click`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        processor.onTouchEvent(motion(MotionEvent.ACTION_CANCEL, 100f, 100f, 10))

        assertTrue(collected.isEmpty())
    }

    @Test
    fun `two finger scroll emits scroll event`() {
        processor.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 100f, 0, pointers = 1))
        processor.onTouchEvent(motion(MotionEvent.ACTION_POINTER_DOWN, 100f, 100f, 5, pointers = 2))
        processor.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 100f, 150f, 16, pointers = 2))
        processor.onTouchEvent(motion(MotionEvent.ACTION_POINTER_UP, 100f, 150f, 30, pointers = 2))
        processor.onTouchEvent(motion(MotionEvent.ACTION_UP, 100f, 150f, 40, pointers = 1))

        assertTrue(collected.any { it is InputEvent.Scroll })
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
        coords: List<Pair<Float, Float>>? = null,
    ): MotionEvent = mockk {
        every { actionMasked } returns action
        every { actionIndex } returns 0
        every { this@mockk.x } returns x
        every { this@mockk.y } returns y
        every { getX(any()) } answers {
            val idx = firstArg<Int>()
            coords?.getOrNull(idx)?.first ?: x
        }
        every { getY(any()) } answers {
            val idx = firstArg<Int>()
            coords?.getOrNull(idx)?.second ?: y
        }
        every { pointerCount } returns (coords?.size ?: pointers)
        every { eventTime } returns time
    }
}
