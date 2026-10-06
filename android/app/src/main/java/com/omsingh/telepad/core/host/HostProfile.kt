package com.omsingh.telepad.core.host

import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputEvent

/** One key pressed together with modifier keys ([HidModifierMask] bits). */
data class Chord(val keyCode: Int, val modifiers: Int = 0)

/** Shortcuts every desktop has, whose key combination differs by operating system. */
enum class ShortcutId {
    COPY, PASTE, CUT, UNDO, REDO, SELECT_ALL, FIND, SAVE,
    NEW_TAB, CLOSE_TAB, REFRESH, SWITCH_APP,
}

/** How a modifier key is written on screen and spoken by a screen reader. */
data class ModifierLabel(val symbol: String, val spokenName: String)

/**
 * Everything the UI needs to speak the host's language: what the modifier keys
 * are called (Win, Command, Super) and which key combination performs a common
 * shortcut. The phone sends plain USB-HID codes; the server maps the "Win" key
 * to Command on a Mac, so choosing the right chord here is all it takes.
 */
class HostProfile(os: HostOs) {

    /** [HostOs.UNKNOWN] behaves as Windows, which every server was before hosts reported an OS. */
    val os: HostOs = if (os == HostOs.UNKNOWN) HostOs.WINDOWS else os

    val isMac: Boolean get() = os == HostOs.MACOS

    val ctrl: ModifierLabel = if (isMac) ModifierLabel("⌃", "Control") else ModifierLabel("Ctrl", "Control")
    val alt: ModifierLabel = if (isMac) ModifierLabel("⌥", "Option") else ModifierLabel("Alt", "Alt")
    val shift: ModifierLabel = if (isMac) ModifierLabel("⇧", "Shift") else ModifierLabel("Shift", "Shift")
    val meta: ModifierLabel = when (os) {
        HostOs.MACOS -> ModifierLabel("⌘", "Command")
        HostOs.LINUX -> ModifierLabel("Super", "Super")
        else -> ModifierLabel("Win", "Windows")
    }

    /** The modifier that most shortcuts use: Command on a Mac, Control elsewhere. */
    val primaryModifier: Int = if (isMac) HidModifierMask.LEFT_META else HidModifierMask.LEFT_CTRL

    fun chord(id: ShortcutId): Chord {
        val primary = primaryModifier
        val shift = HidModifierMask.LEFT_SHIFT
        return when (id) {
            ShortcutId.COPY -> Chord(HidKeyCodes.C, primary)
            ShortcutId.PASTE -> Chord(HidKeyCodes.V, primary)
            ShortcutId.CUT -> Chord(HidKeyCodes.X, primary)
            ShortcutId.UNDO -> Chord(HidKeyCodes.Z, primary)
            // Windows uses Ctrl+Y; macOS and most Linux applications use Shift+Undo.
            ShortcutId.REDO ->
                if (os == HostOs.WINDOWS) Chord(HidKeyCodes.Y, primary)
                else Chord(HidKeyCodes.Z, primary or shift)
            ShortcutId.SELECT_ALL -> Chord(HidKeyCodes.A, primary)
            ShortcutId.FIND -> Chord(HidKeyCodes.F, primary)
            ShortcutId.SAVE -> Chord(HidKeyCodes.S, primary)
            ShortcutId.NEW_TAB -> Chord(HidKeyCodes.T, primary)
            ShortcutId.CLOSE_TAB -> Chord(HidKeyCodes.W, primary)
            ShortcutId.REFRESH ->
                if (isMac) Chord(HidKeyCodes.R, primary) else Chord(HidKeyCodes.F5)
            ShortcutId.SWITCH_APP ->
                if (isMac) Chord(HidKeyCodes.TAB, HidModifierMask.LEFT_META)
                else Chord(HidKeyCodes.TAB, HidModifierMask.LEFT_ALT)
        }
    }

    /** The key combination that locks the screen. */
    val lockChord: Chord = when (os) {
        HostOs.MACOS -> Chord(HidKeyCodes.Q, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_META)
        else -> Chord(HidKeyCodes.L, HidModifierMask.LEFT_META)
    }

    /**
     * The key combination that performs [action] on this OS, or null if the OS has no
     * standard one. Only a Bluetooth connection needs this (it can send nothing but key
     * presses); over Wi-Fi the server performs the action itself.
     */
    fun systemChord(action: InputEvent.SystemAction): Chord? {
        val meta = HidModifierMask.LEFT_META
        val ctrl = HidModifierMask.LEFT_CTRL
        val alt = HidModifierMask.LEFT_ALT
        val shift = HidModifierMask.LEFT_SHIFT
        return when (os) {
            HostOs.MACOS -> when (action) {
                InputEvent.SystemAction.SHOW_DESKTOP -> Chord(HidKeyCodes.F11)
                InputEvent.SystemAction.TASK_VIEW -> Chord(HidKeyCodes.UP, ctrl)
                InputEvent.SystemAction.TASK_MANAGER -> Chord(HidKeyCodes.ESC, meta or alt)
                InputEvent.SystemAction.SCREENSHOT -> Chord(HidKeyCodes.NUM_4, meta or shift)
                InputEvent.SystemAction.BROWSER, InputEvent.SystemAction.FILE_MANAGER -> null
            }
            HostOs.LINUX -> when (action) {
                InputEvent.SystemAction.SHOW_DESKTOP -> Chord(HidKeyCodes.D, meta)
                // Tapping Super opens the overview in GNOME and KDE.
                InputEvent.SystemAction.TASK_VIEW -> Chord(HidKeyCodes.LEFT_META)
                InputEvent.SystemAction.SCREENSHOT -> Chord(HidKeyCodes.PRINT_SCREEN)
                InputEvent.SystemAction.TASK_MANAGER,
                InputEvent.SystemAction.BROWSER,
                InputEvent.SystemAction.FILE_MANAGER -> null
            }
            else -> when (action) {
                InputEvent.SystemAction.SHOW_DESKTOP -> Chord(HidKeyCodes.D, meta)
                InputEvent.SystemAction.TASK_VIEW -> Chord(HidKeyCodes.TAB, meta)
                InputEvent.SystemAction.TASK_MANAGER -> Chord(HidKeyCodes.ESC, ctrl or shift)
                InputEvent.SystemAction.SCREENSHOT -> Chord(HidKeyCodes.S, meta or shift)
                InputEvent.SystemAction.FILE_MANAGER -> Chord(HidKeyCodes.E, meta)
                InputEvent.SystemAction.BROWSER -> null
            }
        }
    }

    /**
     * Human-readable form of a chord, e.g. `⌘C` on a Mac or `Ctrl+Shift+Z` elsewhere.
     * Modifiers are written the way each OS writes them: ⌃⌥⇧⌘ on a Mac, and with the
     * Windows or Super key first elsewhere (`Win+Shift+S`, `Alt+Tab`).
     */
    fun describe(chord: Chord): String {
        val mods = buildList {
            if (!isMac && chord.modifiers hasBit HidModifierMask.LEFT_META) add(meta.symbol)
            if (chord.modifiers hasBit HidModifierMask.LEFT_CTRL) add(ctrl.symbol)
            if (chord.modifiers hasBit HidModifierMask.LEFT_ALT) add(alt.symbol)
            if (chord.modifiers hasBit HidModifierMask.LEFT_SHIFT) add(shift.symbol)
            if (isMac && chord.modifiers hasBit HidModifierMask.LEFT_META) add(meta.symbol)
        }
        val key = keyName(chord.keyCode)
        return if (isMac) mods.joinToString("") + key else (mods + key).joinToString("+")
    }

    private infix fun Int.hasBit(mask: Int) = this and mask != 0

    companion object {
        /** Name of a key as printed on a keycap, for the keys shortcuts use. */
        fun keyName(code: Int): String = when (code) {
            in HidKeyCodes.A..HidKeyCodes.Z -> ('A' + (code - HidKeyCodes.A)).toString()
            in HidKeyCodes.NUM_1..HidKeyCodes.NUM_9 -> ('1' + (code - HidKeyCodes.NUM_1)).toString()
            HidKeyCodes.NUM_0 -> "0"
            in HidKeyCodes.F1..HidKeyCodes.F12 -> "F${code - HidKeyCodes.F1 + 1}"
            HidKeyCodes.TAB -> "Tab"
            HidKeyCodes.ESC -> "Esc"
            HidKeyCodes.ENTER -> "Enter"
            HidKeyCodes.SPACE -> "Space"
            HidKeyCodes.BACKSPACE -> "Backspace"
            HidKeyCodes.DELETE -> "Del"
            HidKeyCodes.PRINT_SCREEN -> "PrtSc"
            HidKeyCodes.UP -> "↑"
            HidKeyCodes.DOWN -> "↓"
            HidKeyCodes.LEFT -> "←"
            HidKeyCodes.RIGHT -> "→"
            HidKeyCodes.LEFT_META, HidKeyCodes.RIGHT_META -> "Super"
            else -> "0x" + code.toString(16).uppercase()
        }
    }
}
