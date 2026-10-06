package com.omsingh.telepad.core.input

import com.omsingh.telepad.core.host.Chord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A modifier key that can be latched on the on-screen keyboard. */
enum class ModifierKey { CTRL, ALT, SHIFT, META }

/** How a latched modifier will apply. */
enum class ModifierState {
    OFF,

    /** On for the next key only, like tapping Shift on a phone keyboard once. */
    ARMED,

    /** On until switched off, like double-tapping Shift for Caps Lock. */
    LOCKED,
}

/**
 * The on-screen keyboard's behaviour, apart from how it looks.
 *
 * It owns the modifier keys the person has latched (Ctrl, Alt, Shift and the Windows/Command
 * key), and turns taps, typed text and shortcuts into [InputEvent]s that carry them.
 *
 *  - Tap a modifier once and it is on for the next key; tap it twice quickly and it stays on;
 *    tap it again to release.
 *  - Text typed with no modifier latched is sent as text, so it can be anything the PC can
 *    type. With a modifier latched it is sent as real key presses (Ctrl+C is not the text "C").
 *
 * Time is passed in, so every rule is a plain test. Not thread-safe: use from the UI thread.
 */
class KeyboardSession(private val send: (InputEvent) -> Unit) {

    private val states = mutableMapOf<ModifierKey, ModifierState>()
    private val armedAt = mutableMapOf<ModifierKey, Long>()

    private val _version = MutableStateFlow(0)

    /** Changes whenever a modifier's state does, so a screen showing them knows to redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() {
        _version.value += 1
    }

    fun state(key: ModifierKey): ModifierState = states[key] ?: ModifierState.OFF

    /** The person tapped a modifier key. */
    fun tapModifier(key: ModifierKey, nowMs: Long) {
        when (state(key)) {
            ModifierState.OFF -> {
                states[key] = ModifierState.ARMED
                armedAt[key] = nowMs
            }
            ModifierState.ARMED ->
                // A quick second tap locks it; a slow one is a change of mind.
                states[key] = if (nowMs - (armedAt[key] ?: nowMs) <= DOUBLE_TAP_MS) ModifierState.LOCKED else ModifierState.OFF
            ModifierState.LOCKED -> states[key] = ModifierState.OFF
        }
        changed()
    }

    /** The modifiers that apply right now. */
    fun modifiers(): InputEvent.Modifiers = InputEvent.Modifiers(
        leftCtrl = state(ModifierKey.CTRL) != ModifierState.OFF,
        leftAlt = state(ModifierKey.ALT) != ModifierState.OFF,
        leftShift = state(ModifierKey.SHIFT) != ModifierState.OFF,
        leftMeta = state(ModifierKey.META) != ModifierState.OFF,
    )

    /** Whether any modifier is latched. */
    val hasModifiers: Boolean get() = modifiers().hasAny

    /** Switches off everything, locked or not. */
    fun releaseModifiers() {
        states.clear()
        armedAt.clear()
        changed()
    }

    // ── Typing ───────────────────────────────────────────────────────

    /** Text from the phone's keyboard. */
    fun type(text: String) {
        if (text.isEmpty()) return
        val mods = modifiers()
        if (!mods.hasAny) {
            send(InputEvent.TextInput(text))
            return
        }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint >= 0x80) continue // no key combination for these
            val (usage, needsShift) = asciiToHid(codePoint.toChar())
            if (usage == 0) continue
            tap(usage, if (needsShift) mods.copy(leftShift = true) else mods)
        }
        consumeArmed()
    }

    /** Presses and releases one key with the latched modifiers. */
    fun tapKey(usage: Int) {
        tap(usage, modifiers())
        consumeArmed()
    }

    /** Backspace, [count] times. */
    fun backspace(count: Int) {
        repeat(count) { tap(HidKeyCodes.BACKSPACE, modifiers()) }
        if (count > 0) consumeArmed()
    }

    /** A key going down and staying down (it repeats on the PC), until [release]. */
    fun press(usage: Int) {
        send(InputEvent.KeyPress(usage, modifiers()))
    }

    fun release(usage: Int) {
        send(InputEvent.KeyRelease(usage, modifiers()))
        consumeArmed()
    }

    /** A shortcut such as Ctrl+C, combined with whatever is latched. */
    fun shortcut(chord: Chord) {
        val mods = modifiers()
        val merged = InputEvent.Modifiers(
            leftCtrl = mods.leftCtrl || chord.modifiers and HidModifierMask.LEFT_CTRL != 0,
            leftShift = mods.leftShift || chord.modifiers and HidModifierMask.LEFT_SHIFT != 0,
            leftAlt = mods.leftAlt || chord.modifiers and HidModifierMask.LEFT_ALT != 0,
            leftMeta = mods.leftMeta || chord.modifiers and HidModifierMask.LEFT_META != 0,
        )
        tap(chord.keyCode, merged)
        consumeArmed()
    }

    private fun tap(usage: Int, mods: InputEvent.Modifiers) {
        send(InputEvent.KeyPress(usage, mods))
        send(InputEvent.KeyRelease(usage, mods))
    }

    /** One-shot modifiers are used up by the key they applied to. */
    private fun consumeArmed() {
        var any = false
        for (key in ModifierKey.values()) {
            if (state(key) == ModifierState.ARMED) {
                states[key] = ModifierState.OFF
                armedAt.remove(key)
                any = true
            }
        }
        if (any) changed()
    }

    companion object {
        /** Two taps this close together lock a modifier. */
        const val DOUBLE_TAP_MS = 350L
    }
}
