package com.omsingh.telepad.core.input

/** What to do on the PC so that its text matches what the phone's text field now shows. */
data class TextEdit(
    /** Characters to delete first (Backspace presses). */
    val backspaces: Int,
    /** Text to type afterwards. */
    val insert: String,
) {
    val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()

    companion object {
        val NONE = TextEdit(0, "")
    }
}

/**
 * Translates edits made in the phone's text field into key presses on the PC.
 *
 * The PC cannot see the phone's text field, only what is typed into it, and its
 * cursor is always at the end of what was sent. So when the field changes from
 * `old` to `new`, the characters after their common prefix in `old` must be
 * erased and the rest of `new` typed.
 *
 * This is what keeps autocorrect and word suggestions honest. Replacing "helo"
 * with "hello " is one Backspace and "lo ": comparing only the lengths, as the
 * first version did, typed "o " after "helo" and left "heloo " on the PC.
 *
 * Counts are in Unicode code points, because that is what one Backspace removes:
 * an emoji (two UTF-16 units) is one character.
 */
object TextDiff {

    fun diff(old: String, new: String): TextEdit {
        if (old == new) return TextEdit.NONE

        var prefix = commonPrefixLength(old, new)
        // The shared prefix must end on a code point boundary in both strings. If its
        // last char is a high surrogate, the matching low surrogates differ (or one
        // string ended), so that half-emoji belongs to the part being replaced.
        if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--

        val removed = old.substring(prefix)
        return TextEdit(
            backspaces = removed.codePointCount(0, removed.length),
            insert = new.substring(prefix),
        )
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}
