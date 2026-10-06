package com.omsingh.telepad.core.input

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Behaviour switches for [GestureEngine], mirrored from the user's preferences.
 *
 * Immutable so the engine can be handed a new configuration at any moment
 * without any chance of observing a half-updated mix of settings.
 */
data class GestureConfig(
    val tapToClick: Boolean = true,
    val naturalScrolling: Boolean = true,
    /** Higher scrolls further per finger movement. 1 is slow, 5 is fast. */
    val scrollSpeed: Float = 2.2f,
    /** Tap, then touch again and move: hold the left button while dragging. */
    val doubleTapDrag: Boolean = true,
    val twoFingerRightClick: Boolean = true,
    val threeFingerMiddleClick: Boolean = true,
    val longPressRightClick: Boolean = true,
    /** Keep scrolling for a moment after a quick two-finger flick, like a laptop trackpad. */
    val momentumScrolling: Boolean = true,
    val sensitivity: Float = 1.6f,
    val accelerationCurve: SensitivityCurve.AccelCurve = SensitivityCurve.AccelCurve.LINEAR,
    /** How far a finger may wobble and still count as a tap, in raw pixels (about 8 dp). */
    val tapSlopPx: Float = 22f,
    /** Device pixels per dp. Motion is normalised by it so the pointer feels the same on every phone. */
    val density: Float = GestureEngine.REFERENCE_DENSITY,
)

/**
 * Turns finger movements on the pad into mouse actions.
 *
 * The vocabulary is the one people know from laptop trackpads:
 *
 *  - **One finger, drag**: move the pointer.
 *  - **One finger, tap**: left click. Two quick taps are two clicks, which the PC
 *    recognises as a double-click, exactly as it does for a real mouse.
 *  - **Tap, then touch again and move (or hold)**: press the left button and drag.
 *  - **One finger, press and hold**: right click.
 *  - **Two fingers, move**: scroll, optionally with momentum after a flick.
 *  - **Two fingers, tap**: right click.
 *  - **Three fingers, tap**: middle click.
 *
 * The engine is pure Kotlin: it knows nothing about Android views, and time only
 * ever comes in through the `timeMs` arguments. That makes every gesture
 * deterministic and unit-testable with a scripted timeline.
 *
 * Events are emitted synchronously from the call that caused them (no batching),
 * so the only latency is the touch hardware's own sampling interval. Call [tick]
 * on every frame while [needsTicks] is true; long-press, hold-to-drag and
 * momentum are driven by it.
 *
 * Not thread-safe: use from a single thread (the UI thread).
 */
class GestureEngine(private val emit: (InputEvent) -> Unit) {

    private val curve = SensitivityCurve()

    var config: GestureConfig = GestureConfig()
        set(value) {
            field = value
            curve.baseSensitivity = value.sensitivity
            curve.curve = value.accelerationCurve
        }

    init {
        config = GestureConfig()
    }

    // ── Per-gesture state ────────────────────────────────────────────
    private var fingers = 0
    private var maxFingers = 0
    private var gestureStartMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var exceededSlop = false
    private var longPressFired = false
    private var dragging = false
    /** This touch began soon after a tap: it may turn into a press-and-drag. */
    private var dragCandidate = false
    private var lastTapUpMs = NO_TAP

    private var carryX = 0f
    private var carryY = 0f

    private var scrollAccumulator = 0f
    private var scrolledThisGesture = false
    private var scrollVelocity = 0f     // reference pixels per millisecond
    private var lastScrollMoveMs = NO_SAMPLE

    private var coasting = false
    private var coastVelocity = 0f
    private var lastTickMs = 0L
    private var coastNotches = 0

    /** True while [tick] has work to do: a finger is down, or momentum is running. */
    val needsTicks: Boolean get() = fingers > 0 || coasting

    val isCoasting: Boolean get() = coasting

    /** Whether the left button is currently held down by a drag. */
    val isDragging: Boolean get() = dragging

    // ── Input ────────────────────────────────────────────────────────

    /** The first finger touched the pad at ([x], [y]). */
    fun onDown(timeMs: Long, x: Float, y: Float) {
        stopCoasting()
        fingers = 1
        maxFingers = 1
        gestureStartMs = timeMs
        startX = x; startY = y
        lastX = x; lastY = y
        exceededSlop = false
        longPressFired = false
        dragging = false
        carryX = 0f; carryY = 0f
        scrollAccumulator = 0f
        scrolledThisGesture = false
        scrollVelocity = 0f
        lastScrollMoveMs = NO_SAMPLE

        dragCandidate = config.doubleTapDrag && lastTapUpMs != NO_TAP &&
            timeMs - lastTapUpMs <= DOUBLE_TAP_WINDOW_MS
        // Whether or not it chains into a drag, this touch uses up the previous tap.
        lastTapUpMs = NO_TAP
    }

    /** Another finger touched down; ([cx], [cy]) is the centre of all fingers now on the pad. */
    fun onPointerDown(timeMs: Long, count: Int, cx: Float, cy: Float) {
        stopCoasting()
        fingers = count
        if (count > maxFingers) maxFingers = count
        dragCandidate = false
        lastTapUpMs = NO_TAP
        // The centre jumps when a finger lands. Re-anchor so that is not mistaken
        // for movement, and so it cannot cancel a tap by exceeding the slop.
        lastX = cx; lastY = cy
        startX = cx; startY = cy
        carryX = 0f; carryY = 0f
        scrollAccumulator = 0f
        scrollVelocity = 0f
        lastScrollMoveMs = NO_SAMPLE
    }

    /** The fingers moved; ([cx], [cy]) is the centre of the [count] fingers on the pad. */
    fun onMove(timeMs: Long, count: Int, cx: Float, cy: Float) {
        fingers = count
        val dx = cx - lastX
        val dy = cy - lastY
        lastX = cx; lastY = cy

        if (!exceededSlop && hypot(cx - startX, cy - startY) > config.tapSlopPx) {
            exceededSlop = true
        }
        if (dx == 0f && dy == 0f) return

        val normalise = REFERENCE_DENSITY / config.density
        val ndx = dx * normalise
        val ndy = dy * normalise

        // Touching again soon after a tap and moving: grab with the left button
        // *before* the pointer moves, so the drag starts exactly where the user aimed.
        if (dragCandidate && !dragging && count == 1) beginDrag()

        when {
            dragging || maxFingers == 1 -> movePointer(ndx, ndy)
            count == 2 && maxFingers == 2 -> scrollWithFingers(timeMs, ndy)
            // Three or more fingers, or one finger left after a multi-finger gesture: ignored.
        }
    }

    /** One finger lifted but [remaining] are still down, centred on ([cx], [cy]). */
    fun onPointerUp(timeMs: Long, remaining: Int, cx: Float, cy: Float) {
        val wasScrolling = fingers == 2 && remaining < 2 && scrolledThisGesture
        fingers = remaining
        lastX = cx; lastY = cy
        startX = cx; startY = cy
        scrollAccumulator = 0f
        carryX = 0f; carryY = 0f
        if (wasScrolling) startCoasting(timeMs)
    }

    /** The last finger lifted. */
    fun onUp(timeMs: Long) {
        val duration = timeMs - gestureStartMs
        val isTap = !exceededSlop && duration <= TAP_MAX_DURATION_MS

        when {
            dragging -> {
                emit(InputEvent.DragEnd(InputEvent.Button.LEFT))
                dragging = false
            }
            longPressFired -> Unit
            isTap -> when (maxFingers) {
                1 -> if (config.tapToClick) {
                    emit(InputEvent.Click)
                    lastTapUpMs = timeMs
                }
                2 -> if (config.twoFingerRightClick) emit(InputEvent.RightClick)
                3 -> if (config.threeFingerMiddleClick) {
                    emit(InputEvent.MouseButton(InputEvent.Button.MIDDLE, true))
                    emit(InputEvent.MouseButton(InputEvent.Button.MIDDLE, false))
                }
            }
        }

        // A flick that ended with the last finger leaving the pad at once.
        if (maxFingers == 2 && scrolledThisGesture && !coasting && fingers == 2) startCoasting(timeMs)

        fingers = 0
        maxFingers = 0
        dragCandidate = false
        scrollAccumulator = 0f
    }

    /** The system took the touch away (a notification, a palm, a gesture). Nothing is clicked. */
    fun onCancel() {
        if (dragging) {
            emit(InputEvent.DragEnd(InputEvent.Button.LEFT))
            dragging = false
        }
        stopCoasting()
        fingers = 0
        maxFingers = 0
        dragCandidate = false
        lastTapUpMs = NO_TAP
        scrollAccumulator = 0f
    }

    /**
     * Advances everything that depends on time passing without a touch event:
     * press-and-hold, hold-to-drag, and momentum scrolling. Call every frame
     * while [needsTicks] is true.
     */
    fun tick(timeMs: Long) {
        if (coasting) tickMomentum(timeMs)

        if (fingers == 1 && maxFingers == 1 && !dragging && !exceededSlop && !longPressFired) {
            val held = timeMs - gestureStartMs
            if (dragCandidate) {
                if (held >= DRAG_HOLD_MS) beginDrag()
            } else if (config.longPressRightClick && held >= LONG_PRESS_MS) {
                longPressFired = true
                emit(InputEvent.RightClick)
            }
        }
    }

    /**
     * Scrolls by a raw pixel distance without any finger gesture, for the scroll
     * strip along the pad's edge. Positive moves the finger down.
     */
    fun scrollBy(dyPx: Float) {
        accumulateScroll(dyPx * (REFERENCE_DENSITY / config.density))
    }

    /** Forget everything, releasing a held drag. Call when the surface loses input. */
    fun reset() {
        onCancel()
        carryX = 0f
        carryY = 0f
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun beginDrag() {
        dragging = true
        dragCandidate = false
        emit(InputEvent.DragStart(InputEvent.Button.LEFT))
    }

    private fun movePointer(dx: Float, dy: Float) {
        val (sx, sy) = curve.apply(dx, dy)
        val targetX = sx + carryX
        val targetY = sy + carryY
        val sendX = targetX.roundToInt()
        val sendY = targetY.roundToInt()
        // Carry the fraction so a slow drag is not rounded away to nothing.
        carryX = targetX - sendX
        carryY = targetY - sendY
        if (sendX != 0 || sendY != 0) {
            emit(InputEvent.MouseMove(sendX.toFloat(), sendY.toFloat()))
        }
    }

    private fun scrollWithFingers(timeMs: Long, dy: Float) {
        scrolledThisGesture = true
        if (lastScrollMoveMs != NO_SAMPLE) {
            // Smooth the velocity so one jittery sample cannot launch a runaway flick.
            val elapsed = (timeMs - lastScrollMoveMs).coerceAtLeast(1L)
            scrollVelocity = VELOCITY_SMOOTHING * scrollVelocity +
                (1f - VELOCITY_SMOOTHING) * (dy / elapsed)
        }
        lastScrollMoveMs = timeMs
        accumulateScroll(dy)
    }

    /** Adds [dy] reference pixels of scrolling and emits the wheel notches it completes. */
    private fun accumulateScroll(dy: Float): Int {
        scrollAccumulator += dy
        val notch = BASE_NOTCH_PX / config.scrollSpeed.coerceAtLeast(MIN_SCROLL_SPEED)
        val towardsPositive = if (config.naturalScrolling) +1f else -1f
        var emitted = 0
        while (scrollAccumulator >= notch) {
            emit(InputEvent.Scroll(towardsPositive))
            scrollAccumulator -= notch
            emitted++
        }
        while (scrollAccumulator <= -notch) {
            emit(InputEvent.Scroll(-towardsPositive))
            scrollAccumulator += notch
            emitted++
        }
        return emitted
    }

    private fun startCoasting(timeMs: Long) {
        // A flick that had stopped before the fingers left is not a flick.
        val stale = lastScrollMoveMs == NO_SAMPLE || timeMs - lastScrollMoveMs > FLICK_MAX_IDLE_MS
        if (!config.momentumScrolling || stale || abs(scrollVelocity) < MIN_FLICK_VELOCITY) {
            scrollVelocity = 0f
            return
        }
        coasting = true
        coastVelocity = scrollVelocity.coerceIn(-MAX_FLICK_VELOCITY, MAX_FLICK_VELOCITY)
        lastTickMs = timeMs
        coastNotches = 0
        scrollVelocity = 0f
    }

    private fun tickMomentum(timeMs: Long) {
        val elapsed = (timeMs - lastTickMs).coerceIn(0L, MAX_TICK_GAP_MS)
        lastTickMs = timeMs
        if (elapsed == 0L) return
        coastNotches += accumulateScroll(coastVelocity * elapsed)
        coastVelocity *= exp(-elapsed.toFloat() / MOMENTUM_DECAY_MS)
        if (abs(coastVelocity) < STOP_VELOCITY || coastNotches >= MAX_COAST_NOTCHES) {
            stopCoasting()
        }
    }

    private fun stopCoasting() {
        coasting = false
        coastVelocity = 0f
    }

    companion object {
        /** A typical modern phone (about 440 dpi). Motion is expressed relative to it. */
        const val REFERENCE_DENSITY = 2.75f

        const val TAP_MAX_DURATION_MS = 280L
        const val DOUBLE_TAP_WINDOW_MS = 300L
        const val LONG_PRESS_MS = 500L
        /** Touching again within the double-tap window and holding this long grabs the button. */
        const val DRAG_HOLD_MS = 220L

        /** Reference pixels of two-finger travel per wheel notch at scroll speed 1. */
        private const val BASE_NOTCH_PX = 17.6f * 2.2f
        private const val MIN_SCROLL_SPEED = 0.2f

        private const val NO_TAP = Long.MIN_VALUE / 2
        private const val NO_SAMPLE = -1L

        private const val VELOCITY_SMOOTHING = 0.6f
        private const val FLICK_MAX_IDLE_MS = 90L
        /** Reference px/ms: 0.4 px/ms is a slow, deliberate drag; flicks are well above it. */
        private const val MIN_FLICK_VELOCITY = 0.5f
        private const val MAX_FLICK_VELOCITY = 6f
        private const val STOP_VELOCITY = 0.04f
        private const val MOMENTUM_DECAY_MS = 350f
        private const val MAX_TICK_GAP_MS = 48L
        private const val MAX_COAST_NOTCHES = 80
    }
}
