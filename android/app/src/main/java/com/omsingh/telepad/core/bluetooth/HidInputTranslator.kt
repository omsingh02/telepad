package com.omsingh.telepad.core.bluetooth

import com.omsingh.telepad.core.host.Chord
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputEvent
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Where HID reports are delivered. Only ever called on the translator's executor thread. */
interface HidReportSink {
    /** Whether a host is connected; reports are dropped while it is not. */
    val isConnected: Boolean

    /** Sends one report. The array is reused afterwards, so copy it to keep it. */
    fun send(reportId: Int, data: ByteArray)
}

/**
 * Turns [InputEvent]s into USB-HID reports, the language a Bluetooth keyboard and mouse speak.
 *
 * Everything that touches state runs on one thread, [executor], and the callers (touch
 * handlers on the UI thread) only post work to it. That makes the keyboard and button
 * state free of races, and stops the Bluetooth stack's blocking calls from ever running
 * on the main thread.
 *
 *  - **Pointer movement** is coalesced: while the Bluetooth link is busy sending, newer
 *    movement piles up into one report instead of a queue of stale ones, so the
 *    pointer never lags behind the finger.
 *  - **Keys** go through [HidKeyboardState], so chords like Ctrl+C or a capital letter
 *    release their modifiers when the key comes up.
 *  - **Typed text** is played back one key at a time with a short pause, strictly in
 *    order even when several pieces of text arrive at once.
 *  - **System actions and screen lock** are sent as the host OS's own shortcut.
 */
class HidInputTranslator(
    private val sink: HidReportSink,
    private val executor: ScheduledExecutorService,
    private val host: () -> HostProfile,
    private val timing: Timing = Timing(),
    /** Told how many characters of a piece of text a keyboard cannot type (anything but ASCII). */
    private val onUntypeable: (Int) -> Unit = {},
) {

    data class Timing(
        /** How long a typed key stays down. */
        val keyDownMs: Long = 8L,
        /** The pause after releasing a typed key, before the next. */
        val betweenKeysMs: Long = 4L,
        /** How long a shortcut or media key is held. */
        val tapMs: Long = 40L,
    )

    // Confined to the executor thread.
    private val keyboard = HidKeyboardState()
    private var buttons = 0
    private val keyboardReport = ByteArray(HidReportDescriptor.KEYBOARD_REPORT_SIZE)
    private val mouseReport = ByteArray(HidReportDescriptor.MOUSE_REPORT_SIZE)
    private val consumerReport = ByteArray(HidReportDescriptor.CONSUMER_REPORT_SIZE)
    private val typingQueue = ArrayDeque<TypingStep>()
    private var typing = false

    private class TypingStep(val usage: Int, val modifiers: Int)

    // Shared with the threads that produce touch events.
    private val pendingDx = AtomicInteger()
    private val pendingDy = AtomicInteger()
    private val moveFlushQueued = AtomicBoolean()

    /** Accepts an event from any thread. Returns immediately. */
    fun handle(event: InputEvent) {
        if (event is InputEvent.MouseMove) {
            pendingDx.addAndGet(event.dx.roundToInt())
            pendingDy.addAndGet(event.dy.roundToInt())
            if (moveFlushQueued.compareAndSet(false, true)) post(::flushMove)
            return
        }
        post { process(event) }
    }

    /** Lets go of every key, button and media key. Call when the connection ends. */
    fun releaseAll() = post {
        typingQueue.clear()
        typing = false
        keyboard.releaseAll()
        sendKeyboard()
        buttons = 0
        sendMouse(0, 0, 0, 0)
        sendConsumer(0)
    }

    private fun post(task: () -> Unit) {
        try {
            executor.execute { runCatching(task) }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun later(delayMs: Long, task: () -> Unit) {
        try {
            executor.schedule({ runCatching(task) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    // ── Events (executor thread) ─────────────────────────────────────

    private fun process(event: InputEvent) {
        when (event) {
            is InputEvent.MouseMove -> Unit // handled before it is queued

            is InputEvent.MouseButton -> {
                flushMove()
                setButton(event.button, event.pressed)
            }
            is InputEvent.DragStart -> {
                flushMove()
                setButton(event.button, true)
            }
            is InputEvent.DragEnd -> {
                flushMove()
                setButton(event.button, false)
            }
            InputEvent.Click -> clickButton(InputEvent.Button.LEFT)
            InputEvent.RightClick -> clickButton(InputEvent.Button.RIGHT)

            is InputEvent.Scroll -> {
                flushMove()
                sendMouse(buttons, 0, 0, event.delta.roundToInt())
            }

            is InputEvent.KeyPress -> {
                keyboard.press(event.keyCode, event.modifiers.toHidByte())
                sendKeyboard()
            }
            is InputEvent.KeyRelease -> {
                keyboard.release(event.keyCode)
                sendKeyboard()
            }
            is InputEvent.TextInput -> typeText(event)

            is InputEvent.MediaCommand -> tapConsumer(
                when (event.action) {
                    InputEvent.MediaAction.PLAY_PAUSE -> HidReportDescriptor.CONSUMER_PLAY_PAUSE
                    InputEvent.MediaAction.NEXT -> HidReportDescriptor.CONSUMER_NEXT_TRACK
                    InputEvent.MediaAction.PREV -> HidReportDescriptor.CONSUMER_PREV_TRACK
                    InputEvent.MediaAction.STOP -> HidReportDescriptor.CONSUMER_STOP
                }
            )
            is InputEvent.VolumeCommand -> tapConsumer(
                when (event.direction) {
                    InputEvent.VolumeDirection.UP -> HidReportDescriptor.CONSUMER_VOLUME_UP
                    InputEvent.VolumeDirection.DOWN -> HidReportDescriptor.CONSUMER_VOLUME_DOWN
                    InputEvent.VolumeDirection.MUTE -> HidReportDescriptor.CONSUMER_MUTE
                }
            )

            InputEvent.LockScreen -> tapChord(host().lockChord)
            is InputEvent.LaunchAction -> host().systemChord(event.action)?.let(::tapChord)

            // A keyboard and mouse cannot carry the clipboard.
            InputEvent.ClipboardGet, is InputEvent.ClipboardSet -> Unit
        }
    }

    // ── Pointer ──────────────────────────────────────────────────────

    private fun flushMove() {
        moveFlushQueued.set(false)
        val dx = pendingDx.getAndSet(0)
        val dy = pendingDy.getAndSet(0)
        if (dx != 0 || dy != 0) sendMouse(buttons, dx, dy, 0)
    }

    private fun setButton(button: InputEvent.Button, pressed: Boolean) {
        val bit = buttonBit(button)
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        sendMouse(buttons, 0, 0, 0)
    }

    private fun clickButton(button: InputEvent.Button) {
        flushMove()
        val bit = buttonBit(button)
        sendMouse(buttons or bit, 0, 0, 0)
        sendMouse(buttons and bit.inv(), 0, 0, 0)
    }

    private fun buttonBit(button: InputEvent.Button): Int = when (button) {
        InputEvent.Button.LEFT -> 0x01
        InputEvent.Button.RIGHT -> 0x02
        InputEvent.Button.MIDDLE -> 0x04
    }

    private fun sendMouse(buttonBits: Int, dx: Int, dy: Int, wheel: Int) {
        if (!sink.isConnected) return
        val r = mouseReport
        r[0] = (buttonBits and 0x07).toByte()
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        r[1] = (x and 0xFF).toByte()
        r[2] = ((x ushr 8) and 0xFF).toByte()
        r[3] = (y and 0xFF).toByte()
        r[4] = ((y ushr 8) and 0xFF).toByte()
        r[5] = wheel.coerceIn(-127, 127).toByte()
        sink.send(HidReportDescriptor.REPORT_ID_MOUSE, r)
    }

    // ── Keyboard ─────────────────────────────────────────────────────

    private fun sendKeyboard() {
        if (!sink.isConnected) return
        keyboard.fillReport(keyboardReport)
        sink.send(HidReportDescriptor.REPORT_ID_KEYBOARD, keyboardReport)
    }

    /** Presses a shortcut, then lets go of it a moment later. */
    private fun tapChord(chord: Chord) {
        keyboard.press(chord.keyCode, chord.modifiers)
        sendKeyboard()
        later(timing.tapMs) {
            keyboard.release(chord.keyCode)
            sendKeyboard()
        }
    }

    private fun typeText(event: InputEvent.TextInput) {
        val plan = TypingPlan.of(event.text)
        if (plan.skipped > 0) onUntypeable(plan.skipped)
        val base = event.modifiers.toHidByte()
        for (stroke in plan.strokes) {
            typingQueue += TypingStep(stroke.usage, base or if (stroke.shift) HidModifierMask.LEFT_SHIFT else 0)
        }
        if (!typing && typingQueue.isNotEmpty()) {
            typing = true
            typeNext()
        }
    }

    /**
     * Presses the next queued key. Each key's release, and the pause after it, are
     * scheduled from the previous step, so keys can never overlap or reorder.
     */
    private fun typeNext() {
        val step = typingQueue.removeFirstOrNull()
        if (step == null) {
            typing = false
            return
        }
        keyboard.press(step.usage, step.modifiers)
        sendKeyboard()
        later(timing.keyDownMs) {
            keyboard.release(step.usage)
            sendKeyboard()
            later(timing.betweenKeysMs, ::typeNext)
        }
    }

    // ── Media keys ───────────────────────────────────────────────────

    private fun tapConsumer(usage: Int) {
        sendConsumer(usage)
        later(timing.tapMs) { sendConsumer(0) }
    }

    private fun sendConsumer(usage: Int) {
        if (!sink.isConnected) return
        consumerReport[0] = (usage and 0xFF).toByte()
        consumerReport[1] = ((usage ushr 8) and 0xFF).toByte()
        sink.send(HidReportDescriptor.REPORT_ID_CONSUMER, consumerReport)
    }
}
