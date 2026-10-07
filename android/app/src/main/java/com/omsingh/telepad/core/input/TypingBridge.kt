package com.omsingh.telepad.core.input

/** The most text kept in the typing field. Older text is dropped from the field (never from the PC). */
const val TYPING_MAX_BUFFER = 400
private const val KEEP_AFTER_TRIM = 120

/**
 * Turns what the phone's keyboard does to a text field into what the PC should receive.
 *
 * The PC cannot see the field, only what is sent to it, and its cursor is always at the end of
 * what was sent. So the bridge remembers what the PC has been sent ([sent]) and, whenever the
 * field changes, sends the Backspaces and text that make the PC's text match ([TextDiff]).
 *
 * Three things make that feel right:
 *
 *  - **Words are sent when the phone's keyboard has finished with them.** A keyboard with
 *    suggestions on underlines the word you are typing and may replace it with a correction when
 *    you press space. Sending each letter and then erasing it would flicker on the PC and
 *    trigger its autocomplete, so the underlined word at the end is held back until it is
 *    committed. With suggestions off there is no underlined word, and every letter goes at once.
 *  - **A latched modifier turns typing into a chord.** With Ctrl on, typing "c" on the phone's
 *    keyboard is Ctrl+C. The field is emptied afterwards, since the PC did not receive the letter
 *    as text, and anything typed before the modifier was latched has already been sent
 *    ([flush]), so it cannot be caught up in the chord.
 *  - **Enter sends the line.** When the text ends with a newline the field starts afresh.
 *
 * Not thread-safe: use from the UI thread.
 */
class TypingBridge(private val keyboard: KeyboardSession) {

    /** Where the phone's keyboard is still working on the text: the word it underlines. */
    data class Composition(val start: Int, val end: Int)

    /** The text the PC has been sent for what the field holds now. */
    private var sent: String = ""

    /**
     * The field changed to [text]. Sends what the PC is missing.
     *
     * Returns the text the field should be replaced with when it should not keep what it shows
     * (a chord was typed, a line was sent, or it grew too long), or null to leave it alone.
     */
    fun update(text: String, composition: Composition?): String? {
        val chord = keyboard.hasModifiers
        // Only a word at the very end can be held back: the PC's cursor is there too.
        val holdBack = composition != null && !chord && composition.end >= text.length &&
            composition.start in 0..text.length
        val visible = if (holdBack) text.substring(0, composition!!.start) else text

        val edit = TextDiff.diff(sent, visible)
        if (!edit.isEmpty) {
            keyboard.backspace(edit.backspaces)
            keyboard.type(edit.insert)
        }

        if (chord && !edit.isEmpty) {
            sent = ""
            return ""
        }
        sent = visible
        if (composition == null && visible.endsWith("\n")) {
            sent = ""
            return ""
        }
        if (composition == null && visible.length > TYPING_MAX_BUFFER) {
            val tail = visible.takeLast(KEEP_AFTER_TRIM)
            sent = tail
            return tail
        }
        return null
    }

    /**
     * Sends everything the field holds, including the word still being worked on. Call it before
     * a key from the bar is used, so that the key does not arrive ahead of the text typed before it.
     */
    fun flush(text: String) {
        // With a modifier latched, the text was sent as it was typed and the field was emptied.
        if (keyboard.hasModifiers) return
        val edit = TextDiff.diff(sent, text)
        if (!edit.isEmpty) {
            keyboard.backspace(edit.backspaces)
            keyboard.type(edit.insert)
        }
        sent = text
    }

    /** The phone's keyboard sent Backspace while the field was empty: the PC may well have something there. */
    fun backspaceWithNothingToDelete() {
        keyboard.backspace(1)
    }

    /** The field was emptied by the person (not by typing). */
    fun reset() {
        sent = ""
    }
}
