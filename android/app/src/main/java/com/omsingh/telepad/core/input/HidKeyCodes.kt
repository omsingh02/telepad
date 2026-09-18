package com.omsingh.telepad.core.input

/**
 * USB HID Usage Page 0x07 (Keyboard/Keypad) scan codes.
 *
 * Authoritative source: USB Implementers Forum, "USB HID Usage Tables 1.4"
 * (January 2023), Section 10 "Keyboard/Keypad Page (0x07)".
 *
 * These usage IDs go into the *key slots* of the standard 8-byte boot keyboard
 * HID report (bytes 3..8). Modifier state lives in byte 0 of the same report
 * (see [HidModifierMask]).
 *
 * Naming mirrors the C-style `KEY_*` macros (e.g. in the Linux input subsystem)
 * so cross-referencing with the server/firmware code is one-to-one.
 */
@Suppress("MagicNumber", "unused")
object HidKeyCodes {

    // ── Reserved / errors ────────────────────────────────────────────
    const val NONE      = 0x00
    const val ERR_OVF   = 0x01   // Reported in all 6 slots on rollover
    const val POST_FAIL = 0x02
    const val ERR_UNDEF = 0x03

    // ── Letters ───────────────────────────────────────────────────────
    const val A = 0x04; const val B = 0x05; const val C = 0x06; const val D = 0x07
    const val E = 0x08; const val F = 0x09; const val G = 0x0A; const val H = 0x0B
    const val I = 0x0C; const val J = 0x0D; const val K = 0x0E; const val L = 0x0F
    const val M = 0x10; const val N = 0x11; const val O = 0x12; const val P = 0x13
    const val Q = 0x14; const val R = 0x15; const val S = 0x16; const val T = 0x17
    const val U = 0x18; const val V = 0x19; const val W = 0x1A; const val X = 0x1B
    const val Y = 0x1C; const val Z = 0x1D

    // ── Top-row digits ───────────────────────────────────────────────
    // Note: digits are NOT in display order: 0x1E=1, 0x1F=2, ..., 0x26=9, 0x27=0.
    const val NUM_1 = 0x1E; const val NUM_2 = 0x1F; const val NUM_3 = 0x20
    const val NUM_4 = 0x21; const val NUM_5 = 0x22; const val NUM_6 = 0x23
    const val NUM_7 = 0x24; const val NUM_8 = 0x25; const val NUM_9 = 0x26
    const val NUM_0 = 0x27

    // ── Whitespace / control ─────────────────────────────────────────
    const val ENTER     = 0x28
    const val ESC       = 0x29
    const val BACKSPACE = 0x2A
    const val TAB       = 0x2B
    const val SPACE     = 0x2C

    // ── US-layout punctuation ────────────────────────────────────────
    const val MINUS       = 0x2D   // -  _
    const val EQUAL       = 0x2E   // =  +
    const val LEFT_BRACE  = 0x2F   // [  {
    const val RIGHT_BRACE = 0x30   // ]  }
    const val BACKSLASH   = 0x31   // \  |
    const val SEMICOLON   = 0x33   // ;  :
    const val APOSTROPHE  = 0x34   // '  "
    const val GRAVE       = 0x35   // `  ~
    const val COMMA       = 0x36   // ,  <
    const val DOT         = 0x37   // .  >
    const val SLASH       = 0x38   // /  ?
    const val CAPS_LOCK   = 0x39

    // ── Function row ─────────────────────────────────────────────────
    const val F1  = 0x3A; const val F2  = 0x3B; const val F3  = 0x3C
    const val F4  = 0x3D; const val F5  = 0x3E; const val F6  = 0x3F
    const val F7  = 0x40; const val F8  = 0x41; const val F9  = 0x42
    const val F10 = 0x43; const val F11 = 0x44; const val F12 = 0x45

    // ── Navigation / system cluster ──────────────────────────────────
    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK  = 0x47
    const val PAUSE        = 0x48
    const val INSERT       = 0x49
    const val HOME         = 0x4A
    const val PAGE_UP      = 0x4B
    const val DELETE       = 0x4C
    const val END          = 0x4D
    const val PAGE_DOWN    = 0x4E
    const val RIGHT        = 0x4F
    const val LEFT         = 0x50
    const val DOWN         = 0x51
    const val UP           = 0x52

    // ── Keypad ───────────────────────────────────────────────────────
    const val NUM_LOCK    = 0x53
    const val KP_DIVIDE   = 0x54
    const val KP_MULTIPLY = 0x55
    const val KP_MINUS    = 0x56
    const val KP_PLUS     = 0x57
    const val KP_ENTER    = 0x58
    const val KP_1 = 0x59; const val KP_2 = 0x5A; const val KP_3 = 0x5B
    const val KP_4 = 0x5C; const val KP_5 = 0x5D; const val KP_6 = 0x5E
    const val KP_7 = 0x5F; const val KP_8 = 0x60; const val KP_9 = 0x61
    const val KP_0 = 0x62
    const val KP_DOT = 0x63

    /** "Menu" / application key, between Right GUI and Right Ctrl on most layouts. */
    const val APPLICATION = 0x65

    // ── Modifier keys as standalone usages (vs. the modifier bitmap) ──
    // E0..E7 are *also* valid usage IDs that can appear in the key slots,
    // typically used when a modifier is sent without any companion key.
    const val LEFT_CTRL   = 0xE0
    const val LEFT_SHIFT  = 0xE1
    const val LEFT_ALT    = 0xE2
    const val LEFT_META   = 0xE3   // "Left GUI" / Windows / Command
    const val RIGHT_CTRL  = 0xE4
    const val RIGHT_SHIFT = 0xE5
    const val RIGHT_ALT   = 0xE6
    const val RIGHT_META  = 0xE7
}

/**
 * Bit masks for byte 0 of the HID keyboard report (the modifier byte).
 *
 * **Distinct** from [HidKeyCodes.LEFT_CTRL] etc., which are the standalone
 * usage IDs for the same physical keys.
 */
@Suppress("MagicNumber", "unused")
object HidModifierMask {
    const val LEFT_CTRL   = 0x01
    const val LEFT_SHIFT  = 0x02
    const val LEFT_ALT    = 0x04
    const val LEFT_META   = 0x08
    const val RIGHT_CTRL  = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT   = 0x40
    const val RIGHT_META  = 0x80
}

/**
 * USB HID Usage Page 0x0C (Consumer) selected codes for media controls.
 * Source: USB HID Usage Tables 1.4, Section 15 "Consumer Page (0x0C)".
 */
@Suppress("MagicNumber", "unused")
object HidConsumerCodes {
    const val PLAY_PAUSE     = 0x00CD
    const val SCAN_NEXT      = 0x00B5
    const val SCAN_PREVIOUS  = 0x00B6
    const val STOP           = 0x00B7
    const val MUTE           = 0x00E2
    const val VOLUME_UP      = 0x00E9
    const val VOLUME_DOWN    = 0x00EA

    /** Used by some apps for "show all windows" / Mission Control on macOS. */
    const val AC_DESKTOP_SHOW_ALL_WINDOWS = 0x029F
}

/**
 * Map an ASCII character to (HID usage, needs-shift) using the US-QWERTY layout.
 * Returns (0, false) for characters that can't be typed via standard HID
 * usages (e.g. non-ASCII Unicode — use Wi-Fi's TextInput for those).
 *
 * Pulled out into a top-level helper because both the Bluetooth dispatcher
 * (for HID typing) and unit tests need to reach it.
 */
@Suppress("MagicNumber")
fun asciiToHid(c: Char): Pair<Int, Boolean> = when (c) {
    in 'a'..'z'     -> (c - 'a' + HidKeyCodes.A) to false
    in 'A'..'Z'     -> (c - 'A' + HidKeyCodes.A) to true
    in '1'..'9'     -> (c - '1' + HidKeyCodes.NUM_1) to false
    '0'             -> HidKeyCodes.NUM_0 to false
    ' '             -> HidKeyCodes.SPACE to false
    '\n', '\r'      -> HidKeyCodes.ENTER to false
    '\t'            -> HidKeyCodes.TAB to false
    '\b'            -> HidKeyCodes.BACKSPACE to false
    '-'             -> HidKeyCodes.MINUS to false
    '='             -> HidKeyCodes.EQUAL to false
    '['             -> HidKeyCodes.LEFT_BRACE to false
    ']'             -> HidKeyCodes.RIGHT_BRACE to false
    '\\'            -> HidKeyCodes.BACKSLASH to false
    ';'             -> HidKeyCodes.SEMICOLON to false
    '\''            -> HidKeyCodes.APOSTROPHE to false
    '`'             -> HidKeyCodes.GRAVE to false
    ','             -> HidKeyCodes.COMMA to false
    '.'             -> HidKeyCodes.DOT to false
    '/'             -> HidKeyCodes.SLASH to false
    '!'             -> HidKeyCodes.NUM_1 to true
    '@'             -> HidKeyCodes.NUM_2 to true
    '#'             -> HidKeyCodes.NUM_3 to true
    '$'             -> HidKeyCodes.NUM_4 to true
    '%'             -> HidKeyCodes.NUM_5 to true
    '^'             -> HidKeyCodes.NUM_6 to true
    '&'             -> HidKeyCodes.NUM_7 to true
    '*'             -> HidKeyCodes.NUM_8 to true
    '('             -> HidKeyCodes.NUM_9 to true
    ')'             -> HidKeyCodes.NUM_0 to true
    '_'             -> HidKeyCodes.MINUS to true
    '+'             -> HidKeyCodes.EQUAL to true
    '{'             -> HidKeyCodes.LEFT_BRACE to true
    '}'             -> HidKeyCodes.RIGHT_BRACE to true
    '|'             -> HidKeyCodes.BACKSLASH to true
    ':'             -> HidKeyCodes.SEMICOLON to true
    '"'             -> HidKeyCodes.APOSTROPHE to true
    '~'             -> HidKeyCodes.GRAVE to true
    '<'             -> HidKeyCodes.COMMA to true
    '>'             -> HidKeyCodes.DOT to true
    '?'             -> HidKeyCodes.SLASH to true
    else            -> 0 to false
}
