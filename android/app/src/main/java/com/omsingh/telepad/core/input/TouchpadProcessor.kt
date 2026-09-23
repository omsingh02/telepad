package com.omsingh.telepad.core.input

import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Translates raw [MotionEvent]s from the touchpad Composable into a stream of
 * higher-level [InputEvent]s (mouse move, click, double-click, scroll, drag).
 *
 * Gesture vocabulary:
 *  - **1 finger drag**: relative mouse move.
 *  - **1 finger tap**: left click.
 *  - **1 finger double-tap**: double click.
 *  - **1 finger double-tap-and-hold**: enters drag mode (left button held).
 *  - **2 finger drag**: vertical scroll.
 *  - **2 finger tap**: right click.
 *  - **1 finger long-press**: right click (configurable).
 *
 * Events are emitted **synchronously** from [onTouchEvent] so there is zero
 * batching latency. The hardware sampling rate (120–240 Hz on modern phones)
 * sets the upper bound on dispatch rate. The receiving [InputDispatcher] is
 * responsible for any TX-side throttling.
 *
 * **Thread safety:** Not thread-safe. Called from the single touch input
 * thread chosen by the platform — typically the main looper for
 * `pointerInteropFilter`, never concurrently.
 */
class TouchpadProcessor(
    val sensitivityCurve: SensitivityCurve = SensitivityCurve(),
    private val onEvent: (InputEvent) -> Unit
) {

    // ── Configuration (set externally from UserPreferences) ──────────
    var tapToClick: Boolean = true
    var naturalScrolling: Boolean = true
    var scrollSpeed: Float = 2.2f
    var doubleTapDrag: Boolean = true
    var twoFingerRightClick: Boolean = true
    var longPressRightClick: Boolean = true
    var longPressThresholdMs: Long = 500L

    // ── Internal gesture state ───────────────────────────────────────
    private var lastX = 0f
    private var lastY = 0f
    private var maxFingers = 0                  // Max simultaneous in this gesture
    private var totalMovement = 0f              // Cumulative path length, px
    private var touchDownTime = 0L              // Wall time of the first DOWN
    private var isDragging = false              // Drag mode active (button held)
    private var pendingTap = false              // First tap seen; waiting for a double
    private var pendingTapTime = 0L             // When the pending tap landed
    private var isDoubleTapCandidate = false    // Second DOWN received within window
    private var longPressFired = false          // Don't fire long-press twice
    private var scrollAccumulator = 0f          // Sub-notch scroll carry-over
    private var subpixelX = 0f                  // Sub-pixel fractional accumulator
    private var subpixelY = 0f

    // ── Tap / gesture thresholds ─────────────────────────────────────
    private companion object {
        const val TAP_MAX_MOVEMENT_PX = 15f
        const val TAP_MAX_DURATION_MS = 250L
        const val DOUBLE_TAP_WINDOW_MS = 280L
    }

    /**
     * Process a raw [MotionEvent].
     *
     * Call directly from your `pointerInteropFilter { event -> processor.onTouchEvent(event); true }`.
     * No buffering, no main-thread post — events are dispatched immediately.
     */
    fun onTouchEvent(event: MotionEvent) {
        val now = if (event.eventTime > 0) event.eventTime else SystemClock.uptimeMillis()
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                maxFingers = 1
                totalMovement = 0f
                touchDownTime = now
                longPressFired = false
                scrollAccumulator = 0f
                subpixelX = 0f
                subpixelY = 0f

                // Check if this DOWN falls inside the double-tap window
                if (pendingTap && (now - pendingTapTime) <= DOUBLE_TAP_WINDOW_MS) {
                    isDoubleTapCandidate = true
                } else {
                    isDoubleTapCandidate = false
                    pendingTap = false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > maxFingers) maxFingers = event.pointerCount
                // Multi-touch overrides any pending single-tap.
                pendingTap = false
                isDoubleTapCandidate = false
                scrollAccumulator = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                processMove(event.x, event.y, now)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted; reset the reference point to a remaining
                // finger so we don't jump on the next MOVE.
                val liftedIdx = event.actionIndex
                val remainingIdx = if (liftedIdx == 0) 1 else 0
                if (remainingIdx < event.pointerCount) {
                    lastX = event.getX(remainingIdx)
                    lastY = event.getY(remainingIdx)
                    subpixelX = 0f
                    subpixelY = 0f
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = now - touchDownTime
                val isTap = totalMovement < TAP_MAX_MOVEMENT_PX &&
                            duration < TAP_MAX_DURATION_MS

                if (isDragging) {
                    onEvent(InputEvent.DragEnd(InputEvent.Button.LEFT))
                    isDragging = false
                    isDoubleTapCandidate = false
                } else if (isTap && isDoubleTapCandidate && tapToClick) {
                    onEvent(InputEvent.DoubleClick)
                    isDoubleTapCandidate = false
                    pendingTap = false
                } else if (isTap && tapToClick) {
                    handleTap(now)
                }
                maxFingers = 0
                scrollAccumulator = 0f
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    onEvent(InputEvent.DragEnd(InputEvent.Button.LEFT))
                    isDragging = false
                }
                maxFingers = 0
                pendingTap = false
                isDoubleTapCandidate = false
                scrollAccumulator = 0f
            }
        }
    }

    private fun handleTap(now: Long) {
        when (maxFingers) {
            1 -> {
                if (pendingTap &&
                    (now - pendingTapTime) <= DOUBLE_TAP_WINDOW_MS
                ) {
                    // Second tap inside the window → double click.
                    onEvent(InputEvent.DoubleClick)
                    pendingTap = false
                } else {
                    // First tap → click now, but flag pending in case a second
                    // tap follows soon. (We dispatch the single click eagerly
                    // because waiting 280 ms for "maybe a double" would add
                    // perceptible click latency, and double-click sources
                    // generally tolerate the press/press/release/release
                    // pattern just as well as a true double-click.)
                    onEvent(InputEvent.Click)
                    pendingTap = true
                    pendingTapTime = now
                }
            }
            2 -> if (twoFingerRightClick) onEvent(InputEvent.RightClick)
            // 3+ fingers: reserved for future gestures (e.g. swipe to switch app).
        }
    }

    private fun processMove(x: Float, y: Float, now: Long) {
        val rawDx = x - lastX
        val rawDy = y - lastY
        lastX = x
        lastY = y

        totalMovement += hypot(rawDx, rawDy)

        // Double-tap-and-hold → drag mode activates when movement starts on the second tap
        if (isDoubleTapCandidate && doubleTapDrag && totalMovement >= TAP_MAX_MOVEMENT_PX && !isDragging) {
            isDragging = true
            isDoubleTapCandidate = false
            pendingTap = false
            onEvent(InputEvent.DragStart(InputEvent.Button.LEFT))
        }

        // Long-press right-click: fires once if user holds still past threshold.
        if (longPressRightClick &&
            !longPressFired &&
            maxFingers == 1 &&
            !isDragging &&
            totalMovement < TAP_MAX_MOVEMENT_PX &&
            (now - touchDownTime) >= longPressThresholdMs
        ) {
            longPressFired = true
            onEvent(InputEvent.RightClick)
            // Cancel any pending tap so we don't double-fire on UP.
            pendingTap = false
            return
        }

        if (rawDx == 0f && rawDy == 0f) return

        if (maxFingers == 1 || isDragging) {
            // 1 finger or drag → pointer movement with sub-pixel accumulation.
            val (sx, sy) = sensitivityCurve.apply(rawDx, rawDy)
            val targetX = sx + subpixelX
            val targetY = sy + subpixelY
            val sendX = kotlin.math.round(targetX).toInt()
            val sendY = kotlin.math.round(targetY).toInt()
            subpixelX = targetX - sendX
            subpixelY = targetY - sendY
            if (sendX != 0 || sendY != 0) {
                onEvent(InputEvent.MouseMove(sendX.toFloat(), sendY.toFloat()))
            }
        } else if (maxFingers >= 2) {
            // 2+ fingers → vertical scroll, notched by scrollSpeed.
            scrollAccumulator += rawDy
            val notch = scrollSpeed * 8f
            while (scrollAccumulator >= notch) {
                onEvent(InputEvent.Scroll(if (naturalScrolling) +1f else -1f))
                scrollAccumulator -= notch
            }
            while (scrollAccumulator <= -notch) {
                onEvent(InputEvent.Scroll(if (naturalScrolling) -1f else +1f))
                scrollAccumulator += notch
            }
        }
    }

    /** Reset all gesture state. Call when the surface loses input focus. */
    fun reset() {
        if (isDragging) {
            onEvent(InputEvent.DragEnd(InputEvent.Button.LEFT))
            isDragging = false
        }
        maxFingers = 0
        totalMovement = 0f
        pendingTap = false
        longPressFired = false
        scrollAccumulator = 0f
        subpixelX = 0f
        subpixelY = 0f
    }
}
