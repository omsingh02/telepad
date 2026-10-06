package com.omsingh.telepad.core.bluetooth

import com.omsingh.telepad.core.input.HidKeyCodes

/**
 * What a USB/Bluetooth boot keyboard reports: which modifier keys are down and up
 * to six other keys. Every change is sent as the *whole* state, which is how HID
 * keyboards work and why a key can never be "stuck" unless this state says so.
 *
 * Two kinds of modifier are tracked separately:
 *
 *  - **held** modifiers, pressed as keys in their own right (the usages 0xE0 to
 *    0xE7), stay down until released explicitly;
 *  - **chord** modifiers ride along with one particular key (Ctrl for the C in
 *    Ctrl+C) and vanish when that key is released.
 *
 * The earlier implementation kept a single modifier byte that each press and
 * release overwrote with whatever the event carried, so typing a capital letter
 * left Shift reported as held until some later key cleared it.
 *
 * Not thread-safe: the owner confines it to the one thread that sends reports.
 */
class HidKeyboardState {

    private var heldModifiers = 0
    private val down = ArrayList<Int>(MAX_TRACKED_KEYS)
    private val chords = HashMap<Int, Int>()

    /** Whether nothing at all is pressed. */
    val isIdle: Boolean get() = heldModifiers == 0 && down.isEmpty()

    /**
     * Presses [usage]. If it is a modifier usage it is simply held; otherwise it
     * is added to the pressed keys together with [chord], the modifier mask (see
     * [com.omsingh.telepad.core.input.HidModifierMask]) that must be down while it is.
     */
    fun press(usage: Int, chord: Int = 0) {
        if (usage in HidKeyCodes.LEFT_CTRL..HidKeyCodes.RIGHT_META) {
            heldModifiers = heldModifiers or modifierBit(usage)
            return
        }
        if (usage == HidKeyCodes.NONE) return
        if (usage !in down) {
            if (down.size >= MAX_TRACKED_KEYS) return
            down += usage
        }
        if (chord != 0) chords[usage] = (chords[usage] ?: 0) or (chord and 0xFF)
    }

    /** Releases [usage] and, with it, any chord modifiers that were riding on it. */
    fun release(usage: Int) {
        if (usage in HidKeyCodes.LEFT_CTRL..HidKeyCodes.RIGHT_META) {
            heldModifiers = heldModifiers and modifierBit(usage).inv()
            return
        }
        down.remove(usage)
        chords.remove(usage)
    }

    fun releaseAll() {
        heldModifiers = 0
        down.clear()
        chords.clear()
    }

    /** The modifier byte currently reported: held modifiers plus every pressed key's chord. */
    fun modifiers(): Int {
        var mods = heldModifiers
        for (key in down) mods = mods or (chords[key] ?: 0)
        return mods and 0xFF
    }

    /**
     * Writes the current state into [out] as an 8-byte boot keyboard report:
     * `[modifiers][0][key 1]...[key 6]`. With more than six keys down, a real
     * keyboard cannot say which, so (per the HID spec) all six slots carry
     * `ErrorRollOver` until enough keys are released.
     */
    fun fillReport(out: ByteArray) {
        require(out.size >= REPORT_SIZE) { "a keyboard report is $REPORT_SIZE bytes" }
        out[0] = modifiers().toByte()
        out[1] = 0
        val rollover = down.size > SLOTS
        for (slot in 0 until SLOTS) {
            out[2 + slot] = when {
                rollover -> HidKeyCodes.ERR_OVF
                slot < down.size -> down[slot]
                else -> 0
            }.toByte()
        }
    }

    private fun modifierBit(usage: Int): Int = 1 shl (usage - HidKeyCodes.LEFT_CTRL)

    companion object {
        const val REPORT_SIZE = 8
        const val SLOTS = 6

        /** Far more than anyone can hold; bounds memory if a caller never releases. */
        private const val MAX_TRACKED_KEYS = 16
    }
}

/**
 * The key presses that type [text] on a US-layout keyboard, in order. Characters
 * a keyboard report cannot express (anything outside ASCII) are skipped and
 * counted in [skipped], so the caller can tell the user.
 */
class TypingPlan private constructor(val strokes: List<Stroke>, val skipped: Int) {

    /** One key tapped, with Shift when [shift] is set. */
    data class Stroke(val usage: Int, val shift: Boolean)

    companion object {
        fun of(text: String): TypingPlan {
            val strokes = ArrayList<Stroke>(text.length)
            var skipped = 0
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                val (usage, shift) = if (codePoint < 0x80) {
                    com.omsingh.telepad.core.input.asciiToHid(codePoint.toChar())
                } else {
                    0 to false
                }
                if (usage == 0) skipped++ else strokes += Stroke(usage, shift)
            }
            return TypingPlan(strokes, skipped)
        }
    }
}
