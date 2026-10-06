package com.omsingh.telepad.core.input

import com.omsingh.telepad.core.input.InputEvent.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Gesture behaviour, replayed on a scripted timeline. Time is explicit, so every
 * test is deterministic: there are no sleeps and nothing depends on a real clock.
 */
class GestureEngineTest {

    /** A pad plus a clock. All positions are in pixels at the reference density. */
    private class Rig(config: GestureConfig = GestureConfig(sensitivity = 1f)) {
        val events = mutableListOf<InputEvent>()
        val engine = GestureEngine { events += it }.also { it.config = config }
        var now = 1_000L

        fun hold(ms: Long) {
            now += ms
            engine.tick(now)
        }

        fun down(x: Float = 100f, y: Float = 100f) = engine.onDown(now, x, y)
        fun secondFinger(cx: Float, cy: Float, count: Int = 2) = engine.onPointerDown(now, count, cx, cy)
        fun move(x: Float, y: Float, fingers: Int = 1) = engine.onMove(now, fingers, x, y)
        fun lift(remaining: Int, cx: Float, cy: Float) = engine.onPointerUp(now, remaining, cx, cy)
        fun up() = engine.onUp(now)

        /** A quick tap with one finger. */
        fun tap(x: Float = 100f, y: Float = 100f, duration: Long = 60) {
            down(x, y); hold(duration); up()
        }

        /** Moves two fingers vertically, [steps] frames of [stepPx] each [frameMs] apart. */
        fun twoFingerDrag(steps: Int, stepPx: Float, frameMs: Long = 16, startY: Float = 100f) {
            down(100f, startY)
            secondFinger(100f, startY)
            for (i in 1..steps) {
                hold(frameMs)
                move(100f, startY + stepPx * i, fingers = 2)
            }
        }

        fun clear() = events.clear()

        val scrolls get() = events.filterIsInstance<InputEvent.Scroll>()
        val moves get() = events.filterIsInstance<InputEvent.MouseMove>()
        val clicks get() = events.filterIsInstance<InputEvent>().count { it == InputEvent.Click }
    }

    // ── Taps and clicks ────────────────────────────────────────────────

    @Test
    fun `a tap is a left click`() {
        val pad = Rig()
        pad.tap()
        assertEquals(listOf<InputEvent>(InputEvent.Click), pad.events)
    }

    @Test
    fun `taps do nothing when tap to click is off`() {
        val pad = Rig(GestureConfig(tapToClick = false))
        pad.tap()
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `a slow press is not a tap`() {
        val pad = Rig(GestureConfig(longPressRightClick = false))
        pad.down(); pad.hold(400); pad.up()
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `moving beyond the slop moves the pointer instead of clicking`() {
        val pad = Rig()
        pad.down(); pad.hold(16); pad.move(140f, 100f); pad.hold(16); pad.up()
        assertEquals(0, pad.clicks)
        assertEquals(40f, pad.moves.sumOf { it.dx.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `a small wobble still counts as a tap`() {
        val pad = Rig()
        pad.down(); pad.hold(20); pad.move(104f, 102f); pad.hold(20); pad.up()
        assertEquals(1, pad.clicks)
    }

    // ── Double tap ────────────────────────────────────────────────────

    @Test
    fun `a double tap is exactly two clicks, not three`() {
        // The first version sent a Click, then a DoubleClick that expanded to two more.
        val pad = Rig()
        pad.tap(); pad.hold(80); pad.tap()
        assertEquals(listOf<InputEvent>(InputEvent.Click, InputEvent.Click), pad.events)
    }

    @Test
    fun `taps far apart in time are two ordinary clicks`() {
        val pad = Rig()
        pad.tap(); pad.hold(GestureEngine.DOUBLE_TAP_WINDOW_MS + 100); pad.tap()
        assertEquals(2, pad.clicks)
        assertFalse(pad.events.any { it is InputEvent.DragStart })
    }

    // ── Tap, then drag ────────────────────────────────────────────────

    @Test
    fun `tap then touch and move presses the button before the pointer moves`() {
        val pad = Rig()
        pad.tap()
        pad.hold(100)
        pad.down(); pad.hold(16); pad.move(120f, 100f); pad.hold(16); pad.move(150f, 100f)
        pad.up()

        val kinds = pad.events.map { it::class.simpleName }
        assertEquals(listOf("Click", "DragStart", "MouseMove", "MouseMove", "DragEnd"), kinds)
        assertEquals(Button.LEFT, (pad.events[1] as InputEvent.DragStart).button)
        assertEquals(50f, pad.moves.sumOf { it.dx.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `tap then hold grabs the button without needing to move`() {
        val pad = Rig()
        pad.tap()
        pad.hold(100)
        pad.down()
        pad.hold(GestureEngine.DRAG_HOLD_MS + 20)
        assertTrue("grabbed after the hold", pad.engine.isDragging)
        pad.move(130f, 100f)
        pad.up()
        assertEquals(
            listOf("Click", "DragStart", "MouseMove", "DragEnd"),
            pad.events.map { it::class.simpleName }
        )
    }

    @Test
    fun `tap then a second quick tap is a second click, not a drag`() {
        val pad = Rig()
        pad.tap(); pad.hold(100); pad.tap()
        assertEquals(listOf("Click", "Click"), pad.events.map { it::class.simpleName })
    }

    @Test
    fun `drag after a tap can be switched off`() {
        val pad = Rig(GestureConfig(doubleTapDrag = false, sensitivity = 1f))
        pad.tap(); pad.hold(100)
        pad.down(); pad.hold(16); pad.move(150f, 100f); pad.up()
        assertFalse(pad.events.any { it is InputEvent.DragStart })
        assertEquals(1, pad.clicks)
        assertEquals(50f, pad.moves.sumOf { it.dx.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `a drag that follows an old tap does not happen`() {
        val pad = Rig()
        pad.tap(); pad.hold(GestureEngine.DOUBLE_TAP_WINDOW_MS + 50)
        pad.down(); pad.hold(16); pad.move(150f, 100f); pad.up()
        assertFalse(pad.events.any { it is InputEvent.DragStart })
    }

    // ── Press and hold ────────────────────────────────────────────────

    @Test
    fun `pressing and holding still is a right click, once`() {
        val pad = Rig()
        pad.down()
        pad.hold(GestureEngine.LONG_PRESS_MS + 10)
        pad.hold(100)
        pad.hold(100)
        pad.up()
        assertEquals(listOf<InputEvent>(InputEvent.RightClick), pad.events)
    }

    @Test
    fun `a long press can be switched off`() {
        val pad = Rig(GestureConfig(longPressRightClick = false))
        pad.down(); pad.hold(900); pad.up()
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `moving cancels the long press`() {
        val pad = Rig()
        pad.down(); pad.hold(100); pad.move(200f, 100f); pad.hold(GestureEngine.LONG_PRESS_MS)
        assertFalse(pad.events.any { it == InputEvent.RightClick })
    }

    // ── Two fingers ───────────────────────────────────────────────────

    @Test
    fun `a two finger tap is a right click`() {
        val pad = Rig()
        pad.down(); pad.secondFinger(130f, 100f); pad.hold(50)
        pad.lift(1, 100f, 100f); pad.up()
        assertEquals(listOf<InputEvent>(InputEvent.RightClick), pad.events)
    }

    @Test
    fun `a two finger tap can be switched off`() {
        val pad = Rig(GestureConfig(twoFingerRightClick = false))
        pad.down(); pad.secondFinger(130f, 100f); pad.hold(50)
        pad.lift(1, 100f, 100f); pad.up()
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `two fingers scroll and never move the pointer`() {
        val pad = Rig()
        pad.twoFingerDrag(steps = 10, stepPx = 20f)
        assertTrue(pad.scrolls.isNotEmpty())
        assertTrue(pad.moves.isEmpty())
    }

    @Test
    fun `finishing a scroll does not also right click`() {
        val pad = Rig(GestureConfig(momentumScrolling = false))
        pad.twoFingerDrag(steps = 10, stepPx = 20f)
        pad.lift(1, 100f, 300f); pad.up()
        assertFalse(pad.events.any { it == InputEvent.RightClick })
    }

    @Test
    fun `natural scrolling follows the fingers`() {
        val natural = Rig(GestureConfig(naturalScrolling = true, momentumScrolling = false))
        natural.twoFingerDrag(steps = 5, stepPx = 30f) // fingers move down
        assertTrue(natural.scrolls.all { it.delta > 0f })

        val classic = Rig(GestureConfig(naturalScrolling = false, momentumScrolling = false))
        classic.twoFingerDrag(steps = 5, stepPx = 30f)
        assertTrue(classic.scrolls.all { it.delta < 0f })
    }

    @Test
    fun `fingers moving up scroll the other way`() {
        val pad = Rig(GestureConfig(momentumScrolling = false))
        pad.twoFingerDrag(steps = 5, stepPx = -30f, startY = 400f)
        assertTrue(pad.scrolls.isNotEmpty())
        assertTrue(pad.scrolls.all { it.delta < 0f })
    }

    @Test
    fun `a higher scroll speed scrolls further for the same finger movement`() {
        // The first version divided the wrong way round: faster meant slower.
        fun notches(speed: Float): Int {
            val pad = Rig(GestureConfig(scrollSpeed = speed, momentumScrolling = false))
            pad.twoFingerDrag(steps = 10, stepPx = 20f)
            return pad.scrolls.size
        }
        val slow = notches(1.1f)
        val normal = notches(2.2f)
        val fast = notches(4.4f)
        assertTrue("slow=$slow normal=$normal fast=$fast", slow < normal && normal < fast)
        assertEquals(normal * 2.0, fast.toDouble(), 2.0)
    }

    @Test
    fun `scroll notches match the configured distance`() {
        // Default speed 2.2: one notch per 17.6 reference px, so 5 x 20 = 100 px is 5 notches.
        val pad = Rig(GestureConfig(scrollSpeed = 2.2f, momentumScrolling = false))
        pad.twoFingerDrag(steps = 5, stepPx = 20f)
        assertEquals(5, pad.scrolls.size)
    }

    @Test
    fun `a second finger landing does not make the scroll jump`() {
        val pad = Rig()
        pad.down(100f, 100f)
        pad.hold(20)
        // The centre of the two fingers is 150 px away from where the first one was.
        pad.secondFinger(100f, 400f)
        assertTrue(pad.scrolls.isEmpty())
        assertTrue(pad.moves.isEmpty())
    }

    @Test
    fun `one remaining finger does not move the pointer after a two finger gesture`() {
        val pad = Rig(GestureConfig(momentumScrolling = false))
        pad.twoFingerDrag(steps = 3, stepPx = 20f)
        pad.lift(1, 100f, 160f)
        pad.clear()
        pad.hold(16); pad.move(180f, 220f, fingers = 1)
        assertTrue(pad.moves.isEmpty())
        assertTrue(pad.scrolls.isEmpty())
    }

    // ── Three fingers ─────────────────────────────────────────────────

    @Test
    fun `a three finger tap is a middle click`() {
        val pad = Rig()
        pad.down(); pad.secondFinger(120f, 100f, count = 2); pad.secondFinger(140f, 100f, count = 3)
        pad.hold(60)
        pad.lift(2, 110f, 100f); pad.lift(1, 100f, 100f); pad.up()
        assertEquals(
            listOf<InputEvent>(
                InputEvent.MouseButton(Button.MIDDLE, true),
                InputEvent.MouseButton(Button.MIDDLE, false),
            ),
            pad.events
        )
    }

    @Test
    fun `a three finger tap can be switched off`() {
        val pad = Rig(GestureConfig(threeFingerMiddleClick = false))
        pad.down(); pad.secondFinger(120f, 100f, count = 2); pad.secondFinger(140f, 100f, count = 3)
        pad.hold(60)
        pad.lift(2, 110f, 100f); pad.lift(1, 100f, 100f); pad.up()
        assertTrue(pad.events.isEmpty())
    }

    // ── Pointer movement ──────────────────────────────────────────────

    @Test
    fun `one finger moves the pointer one to one at unit sensitivity`() {
        val pad = Rig(GestureConfig(sensitivity = 1f))
        pad.down(); pad.hold(8); pad.move(110f, 105f); pad.hold(8); pad.move(100f, 90f)
        assertEquals(
            listOf(InputEvent.MouseMove(10f, 5f), InputEvent.MouseMove(-10f, -15f)),
            pad.moves
        )
    }

    @Test
    fun `sensitivity scales the motion`() {
        val pad = Rig(GestureConfig(sensitivity = 2f))
        pad.down(); pad.hold(8); pad.move(110f, 100f)
        assertEquals(listOf(InputEvent.MouseMove(20f, 0f)), pad.moves)
    }

    @Test
    fun `slow movement is not rounded away`() {
        val pad = Rig(GestureConfig(sensitivity = 1f))
        pad.down()
        var x = 100f
        repeat(10) { pad.hold(8); x += 0.4f; pad.move(x, 100f) }
        // 10 x 0.4 = 4 px in total, all of which must arrive.
        assertEquals(4f, pad.moves.sumOf { it.dx.toDouble() }.toFloat(), 0.51f)
        assertTrue(pad.moves.all { it.dx == 1f || it.dx == 0f })
    }

    @Test
    fun `the same finger movement gives the same pointer movement on any screen density`() {
        // 40 px on a 2x denser screen is the same physical distance as 20 px here.
        val dense = Rig(GestureConfig(sensitivity = 1f, density = GestureEngine.REFERENCE_DENSITY * 2))
        dense.down(); dense.hold(8); dense.move(140f, 100f)
        val reference = Rig(GestureConfig(sensitivity = 1f))
        reference.down(); reference.hold(8); reference.move(120f, 100f)
        assertEquals(reference.moves, dense.moves)

        val sparse = Rig(GestureConfig(sensitivity = 1f, density = GestureEngine.REFERENCE_DENSITY / 2))
        sparse.down(); sparse.hold(8); sparse.move(110f, 100f)
        assertEquals(reference.moves, sparse.moves)
    }

    @Test
    fun `pointer movement works while dragging with the button held`() {
        val pad = Rig()
        pad.tap(); pad.hold(80)
        pad.down(); pad.hold(10); pad.move(110f, 100f)
        assertTrue(pad.engine.isDragging)
        assertEquals(1, pad.moves.size)
    }

    // ── Cancel and reset ──────────────────────────────────────────────

    @Test
    fun `cancelling a drag releases the button`() {
        val pad = Rig()
        pad.tap(); pad.hold(80)
        pad.down(); pad.hold(10); pad.move(130f, 100f)
        pad.clear()
        pad.engine.onCancel()
        assertEquals(listOf<InputEvent>(InputEvent.DragEnd(Button.LEFT)), pad.events)
        assertFalse(pad.engine.isDragging)
    }

    @Test
    fun `cancelling an ordinary touch clicks nothing`() {
        val pad = Rig()
        pad.down(); pad.hold(30); pad.engine.onCancel()
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `a cancelled touch does not leave a tap behind for the next touch to drag`() {
        val pad = Rig()
        pad.tap(); pad.hold(50)
        pad.down(); pad.engine.onCancel(); pad.hold(50)
        pad.clear()
        pad.down(); pad.hold(10); pad.move(150f, 100f)
        assertFalse(pad.engine.isDragging)
    }

    @Test
    fun `reset releases a held drag`() {
        val pad = Rig()
        pad.tap(); pad.hold(80)
        pad.down(); pad.hold(10); pad.move(130f, 100f)
        pad.clear()
        pad.engine.reset()
        assertEquals(listOf<InputEvent>(InputEvent.DragEnd(Button.LEFT)), pad.events)
    }

    // ── Momentum ──────────────────────────────────────────────────────

    /** A quick flick (2.5 px/ms) that ends with both fingers leaving the pad. */
    private fun flick(pad: Rig) {
        pad.twoFingerDrag(steps = 10, stepPx = 40f)
        pad.lift(1, 100f, 500f)
        pad.up()
    }

    private fun coast(pad: Rig, maxFrames: Int = 400): Int {
        var frames = 0
        while (pad.engine.isCoasting && frames < maxFrames) {
            pad.hold(16)
            frames++
        }
        return frames
    }

    @Test
    fun `a fast flick keeps scrolling after the fingers leave`() {
        val pad = Rig()
        flick(pad)
        val during = pad.scrolls.size
        assertTrue("flick must start momentum", pad.engine.isCoasting)
        assertTrue(pad.engine.needsTicks)
        coast(pad)
        assertTrue("momentum added notches: ${pad.scrolls.size} vs $during", pad.scrolls.size > during)
    }

    @Test
    fun `momentum fades out on its own and never runs away`() {
        val pad = Rig()
        flick(pad)
        val frames = coast(pad)
        assertFalse(pad.engine.isCoasting)
        assertTrue("took $frames frames", frames in 5..300)
        assertTrue(pad.scrolls.size < 200)
        assertFalse(pad.engine.needsTicks)
    }

    @Test
    fun `momentum continues in the direction of the flick`() {
        val up = Rig()
        up.twoFingerDrag(steps = 10, stepPx = -40f, startY = 600f)
        up.lift(1, 100f, 200f); up.up()
        val before = up.scrolls.size
        coast(up)
        val coasted = up.scrolls.drop(before)
        assertTrue(coasted.isNotEmpty())
        assertTrue(coasted.all { it.delta < 0f })
    }

    @Test
    fun `a slow scroll does not coast`() {
        val pad = Rig()
        pad.twoFingerDrag(steps = 10, stepPx = 4f, frameMs = 16) // 0.25 px/ms
        pad.lift(1, 100f, 140f); pad.up()
        assertFalse(pad.engine.isCoasting)
    }

    @Test
    fun `pausing before letting go cancels the flick`() {
        val pad = Rig()
        pad.twoFingerDrag(steps = 10, stepPx = 40f)
        pad.hold(250) // fingers rest on the pad
        pad.lift(1, 100f, 500f); pad.up()
        assertFalse(pad.engine.isCoasting)
    }

    @Test
    fun `momentum can be switched off`() {
        val pad = Rig(GestureConfig(momentumScrolling = false))
        flick(pad)
        assertFalse(pad.engine.isCoasting)
    }

    @Test
    fun `touching the pad stops momentum immediately`() {
        val pad = Rig()
        flick(pad)
        assertTrue(pad.engine.isCoasting)
        pad.hold(50)
        pad.down()
        assertFalse(pad.engine.isCoasting)
        pad.clear()
        pad.hold(16)
        assertTrue("no more scrolling after the touch", pad.scrolls.isEmpty())
    }

    @Test
    fun `a flick that ends with both fingers lifting at once still coasts`() {
        val pad = Rig()
        pad.twoFingerDrag(steps = 10, stepPx = 40f)
        pad.up() // no intermediate single-finger event
        assertTrue(pad.engine.isCoasting)
    }

    // ── Scroll strip and misc ─────────────────────────────────────────

    @Test
    fun `the scroll strip scrolls by distance without any gesture`() {
        val pad = Rig(GestureConfig(scrollSpeed = 2.2f))
        pad.engine.scrollBy(17.6f * 3)
        assertEquals(3, pad.scrolls.size)
        assertTrue(pad.scrolls.all { it.delta > 0f })
    }

    @Test
    fun `the scroll strip honours natural scrolling`() {
        val pad = Rig(GestureConfig(naturalScrolling = false, scrollSpeed = 2.2f))
        pad.engine.scrollBy(35.2f)
        assertTrue(pad.scrolls.isNotEmpty() && pad.scrolls.all { it.delta < 0f })
    }

    @Test
    fun `an idle engine needs no ticks`() {
        val pad = Rig()
        assertFalse(pad.engine.needsTicks)
        pad.down()
        assertTrue(pad.engine.needsTicks)
        pad.up()
        assertFalse(pad.engine.needsTicks)
    }

    @Test
    fun `tick without touches does nothing`() {
        val pad = Rig()
        repeat(50) { pad.hold(16) }
        assertTrue(pad.events.isEmpty())
    }

    @Test
    fun `changing the configuration takes effect immediately`() {
        val pad = Rig(GestureConfig(sensitivity = 1f))
        pad.down(); pad.hold(8); pad.move(110f, 100f)
        pad.engine.config = pad.engine.config.copy(sensitivity = 3f)
        pad.hold(8); pad.move(120f, 100f)
        assertEquals(listOf(10f, 30f), pad.moves.map { it.dx })
    }

    @Test
    fun `no sequence of touches ever emits an unbalanced button state`() {
        // Whatever the user does, every DragStart is eventually matched by a DragEnd.
        val pad = Rig()
        val script = listOf(
            { pad.tap(); pad.hold(60); pad.down(); pad.hold(10); pad.move(150f, 100f) },
            { pad.engine.onCancel() },
            { pad.tap(); pad.hold(60); pad.down(); pad.hold(300); pad.up() },
            { pad.tap(); pad.hold(60); pad.down(); pad.hold(10); pad.move(130f, 90f); pad.up() },
        )
        script.forEach { it() }
        pad.engine.reset()
        val starts = pad.events.count { it is InputEvent.DragStart }
        val ends = pad.events.count { it is InputEvent.DragEnd }
        assertEquals(starts, ends)
        assertTrue(starts >= 2)
        assertFalse(abs(starts - ends) > 0)
    }
}
