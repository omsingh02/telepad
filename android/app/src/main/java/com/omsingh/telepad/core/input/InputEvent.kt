package com.omsingh.telepad.core.input

/**
 * Unified input event model. Every UI gesture, key, and command is represented
 * as a single [InputEvent] regardless of transport (Bluetooth HID or Wi-Fi UDP).
 *
 * The active [InputDispatcher] converts these to the appropriate wire format:
 *  - Bluetooth HID: report bytes per USB HID Usage Tables 1.4.
 *  - Wi-Fi UDP: Noise-encrypted protocol messages defined in protocol.h.
 */
sealed interface InputEvent {

    // ── Mouse ──────────────────────────────────────────────────────────

    /** Relative pointer movement, in device-independent units. */
    data class MouseMove(val dx: Float, val dy: Float) : InputEvent

    /** Mouse button press or release. */
    data class MouseButton(val button: Button, val pressed: Boolean) : InputEvent

    /** Vertical scroll delta. Positive = scroll up (content moves down). */
    data class Scroll(val delta: Float) : InputEvent

    /** Single left click (press + release as one logical event). */
    data object Click : InputEvent

    /** Double left click. */
    data object DoubleClick : InputEvent

    /** Single right click. */
    data object RightClick : InputEvent

    // ── Drag ───────────────────────────────────────────────────────────

    /** Begin a drag — button held down until [DragEnd]. */
    data class DragStart(val button: Button = Button.LEFT) : InputEvent

    /** End a drag — button released. */
    data class DragEnd(val button: Button = Button.LEFT) : InputEvent

    // ── Keyboard ───────────────────────────────────────────────────────

    /** Single key press with optional modifiers. [keyCode] is a HID usage code. */
    data class KeyPress(
        val keyCode: Int,
        val modifiers: Modifiers = Modifiers()
    ) : InputEvent

    /** Single key release. */
    data class KeyRelease(
        val keyCode: Int,
        val modifiers: Modifiers = Modifiers()
    ) : InputEvent

    /** Direct UTF-8 text input. Bypasses keycodes — used for IME/composing. */
    data class TextInput(
        val text: String,
        val modifiers: Modifiers = Modifiers()
    ) : InputEvent

    // ── Media / system ─────────────────────────────────────────────────

    data class MediaCommand(val action: MediaAction) : InputEvent

    data class VolumeCommand(val direction: VolumeDirection) : InputEvent

    data object LockScreen : InputEvent

    // ── Clipboard sync (Wi-Fi only) ────────────────────────────────────

    /** Request the PC's current clipboard contents. */
    data object ClipboardGet : InputEvent

    /** Set the PC's clipboard to the given UTF-8 string. */
    data class ClipboardSet(val text: String) : InputEvent

    // ── Quick launchers / system actions ───────────────────────────────

    data class LaunchAction(val action: SystemAction) : InputEvent

    // ── Enums ──────────────────────────────────────────────────────────

    enum class Button { LEFT, RIGHT, MIDDLE }
    enum class MediaAction { PLAY_PAUSE, NEXT, PREV, STOP }
    enum class VolumeDirection { UP, DOWN, MUTE }
    enum class SystemAction {
        SHOW_DESKTOP,
        TASK_VIEW,
        BROWSER,
        FILE_MANAGER,
        TASK_MANAGER,
        SCREENSHOT
    }

    /**
     * Modifier key state. Sent alongside key events.
     * For Bluetooth HID, these map directly to byte 0 of the keyboard report
     * (see USB HID Usage Tables 1.4, Section 10).
     */
    data class Modifiers(
        val leftCtrl: Boolean = false,
        val leftShift: Boolean = false,
        val leftAlt: Boolean = false,
        val leftMeta: Boolean = false,
        val rightCtrl: Boolean = false,
        val rightShift: Boolean = false,
        val rightAlt: Boolean = false,
        val rightMeta: Boolean = false,
    ) {
        /** Pack into the HID modifier byte (byte 0 of the keyboard report). */
        fun toHidByte(): Int {
            var b = 0
            if (leftCtrl)   b = b or 0x01
            if (leftShift)  b = b or 0x02
            if (leftAlt)    b = b or 0x04
            if (leftMeta)   b = b or 0x08
            if (rightCtrl)  b = b or 0x10
            if (rightShift) b = b or 0x20
            if (rightAlt)   b = b or 0x40
            if (rightMeta)  b = b or 0x80
            return b
        }

        val hasAny: Boolean
            get() = leftCtrl || leftShift || leftAlt || leftMeta ||
                    rightCtrl || rightShift || rightAlt || rightMeta

        companion object {
            val EMPTY = Modifiers()
        }
    }
}
