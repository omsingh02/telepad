//! USB HID usage -> Linux evdev key code (`linux/input-event-codes.h`).
//!
//! Constants keep the kernel's own names so that [`tests::constants_match_kernel_header`]
//! can verify every value against the system header whenever it is installed.

macro_rules! kernel_keys {
    ($($name:ident = $val:expr),* $(,)?) => {
        $( pub const $name: u16 = $val; )*
        /// Every constant above, by kernel name (drives the header cross-check test).
        #[allow(dead_code)]
        pub const ALL: &[(&str, u16)] = &[ $( (stringify!($name), $val) ),* ];
    };
}

kernel_keys! {
    KEY_ESC = 1, KEY_1 = 2, KEY_2 = 3, KEY_3 = 4, KEY_4 = 5, KEY_5 = 6, KEY_6 = 7,
    KEY_7 = 8, KEY_8 = 9, KEY_9 = 10, KEY_0 = 11, KEY_MINUS = 12, KEY_EQUAL = 13,
    KEY_BACKSPACE = 14, KEY_TAB = 15,
    KEY_Q = 16, KEY_W = 17, KEY_E = 18, KEY_R = 19, KEY_T = 20, KEY_Y = 21, KEY_U = 22,
    KEY_I = 23, KEY_O = 24, KEY_P = 25, KEY_LEFTBRACE = 26, KEY_RIGHTBRACE = 27,
    KEY_ENTER = 28, KEY_LEFTCTRL = 29,
    KEY_A = 30, KEY_S = 31, KEY_D = 32, KEY_F = 33, KEY_G = 34, KEY_H = 35, KEY_J = 36,
    KEY_K = 37, KEY_L = 38, KEY_SEMICOLON = 39, KEY_APOSTROPHE = 40, KEY_GRAVE = 41,
    KEY_LEFTSHIFT = 42, KEY_BACKSLASH = 43,
    KEY_Z = 44, KEY_X = 45, KEY_C = 46, KEY_V = 47, KEY_B = 48, KEY_N = 49, KEY_M = 50,
    KEY_COMMA = 51, KEY_DOT = 52, KEY_SLASH = 53, KEY_RIGHTSHIFT = 54, KEY_KPASTERISK = 55,
    KEY_LEFTALT = 56, KEY_SPACE = 57, KEY_CAPSLOCK = 58,
    KEY_F1 = 59, KEY_F2 = 60, KEY_F3 = 61, KEY_F4 = 62, KEY_F5 = 63, KEY_F6 = 64,
    KEY_F7 = 65, KEY_F8 = 66, KEY_F9 = 67, KEY_F10 = 68,
    KEY_NUMLOCK = 69, KEY_SCROLLLOCK = 70,
    KEY_KP7 = 71, KEY_KP8 = 72, KEY_KP9 = 73, KEY_KPMINUS = 74,
    KEY_KP4 = 75, KEY_KP5 = 76, KEY_KP6 = 77, KEY_KPPLUS = 78,
    KEY_KP1 = 79, KEY_KP2 = 80, KEY_KP3 = 81, KEY_KP0 = 82, KEY_KPDOT = 83,
    KEY_102ND = 86, KEY_F11 = 87, KEY_F12 = 88,
    KEY_KPENTER = 96, KEY_RIGHTCTRL = 97, KEY_KPSLASH = 98, KEY_SYSRQ = 99, KEY_RIGHTALT = 100,
    KEY_HOME = 102, KEY_UP = 103, KEY_PAGEUP = 104, KEY_LEFT = 105, KEY_RIGHT = 106,
    KEY_END = 107, KEY_DOWN = 108, KEY_PAGEDOWN = 109, KEY_INSERT = 110, KEY_DELETE = 111,
    KEY_MUTE = 113, KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115, KEY_PAUSE = 119,
    KEY_LEFTMETA = 125, KEY_RIGHTMETA = 126, KEY_COMPOSE = 127,
    KEY_NEXTSONG = 163, KEY_PLAYPAUSE = 164, KEY_PREVIOUSSONG = 165, KEY_STOPCD = 166,
    KEY_F13 = 183, KEY_F14 = 184, KEY_F15 = 185, KEY_F16 = 186, KEY_F17 = 187, KEY_F18 = 188,
    KEY_F19 = 189, KEY_F20 = 190, KEY_F21 = 191, KEY_F22 = 192, KEY_F23 = 193, KEY_F24 = 194,
}

/// Evdev codes of the `BTN_*` mouse buttons.
pub const BTN_LEFT: u16 = 0x110;
pub const BTN_RIGHT: u16 = 0x111;
pub const BTN_MIDDLE: u16 = 0x112;

/// Letters a..z in HID order (evdev letter codes follow the QWERTY layout, not the alphabet).
const LETTERS: [u16; 26] = [
    KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J, KEY_K, KEY_L, KEY_M,
    KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z,
];

/// Digits 1..9, 0 in HID order (0x1E..=0x27).
const DIGITS: [u16; 10] = [
    KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_0,
];

/// F1..F12 in HID order (0x3A..=0x45); the kernel numbers F11/F12 out of sequence.
const F_KEYS: [u16; 12] = [
    KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8, KEY_F9, KEY_F10, KEY_F11,
    KEY_F12,
];

/// Keypad 1..9, 0 in HID order (0x59..=0x62).
const KEYPAD_DIGITS: [u16; 10] = [
    KEY_KP1, KEY_KP2, KEY_KP3, KEY_KP4, KEY_KP5, KEY_KP6, KEY_KP7, KEY_KP8, KEY_KP9, KEY_KP0,
];

/// Translates a HID keyboard-page usage into an evdev key code, or `None` if
/// there is no equivalent. Follows the kernel's own `hid_keyboard[]` table.
pub fn hid_to_evdev(usage: u16) -> Option<u16> {
    Some(match usage {
        0x04..=0x1D => LETTERS[(usage - 0x04) as usize],
        0x1E..=0x27 => DIGITS[(usage - 0x1E) as usize],
        0x28 => KEY_ENTER,
        0x29 => KEY_ESC,
        0x2A => KEY_BACKSPACE,
        0x2B => KEY_TAB,
        0x2C => KEY_SPACE,
        0x2D => KEY_MINUS,
        0x2E => KEY_EQUAL,
        0x2F => KEY_LEFTBRACE,
        0x30 => KEY_RIGHTBRACE,
        0x31 | 0x32 => KEY_BACKSLASH, // 0x32 = non-US '#', same physical position
        0x33 => KEY_SEMICOLON,
        0x34 => KEY_APOSTROPHE,
        0x35 => KEY_GRAVE,
        0x36 => KEY_COMMA,
        0x37 => KEY_DOT,
        0x38 => KEY_SLASH,
        0x39 => KEY_CAPSLOCK,
        0x3A..=0x45 => F_KEYS[(usage - 0x3A) as usize],
        0x46 => KEY_SYSRQ, // Print Screen
        0x47 => KEY_SCROLLLOCK,
        0x48 => KEY_PAUSE,
        0x49 => KEY_INSERT,
        0x4A => KEY_HOME,
        0x4B => KEY_PAGEUP,
        0x4C => KEY_DELETE,
        0x4D => KEY_END,
        0x4E => KEY_PAGEDOWN,
        0x4F => KEY_RIGHT,
        0x50 => KEY_LEFT,
        0x51 => KEY_DOWN,
        0x52 => KEY_UP,
        0x53 => KEY_NUMLOCK,
        0x54 => KEY_KPSLASH,
        0x55 => KEY_KPASTERISK,
        0x56 => KEY_KPMINUS,
        0x57 => KEY_KPPLUS,
        0x58 => KEY_KPENTER,
        0x59..=0x62 => KEYPAD_DIGITS[(usage - 0x59) as usize],
        0x63 => KEY_KPDOT,
        0x64 => KEY_102ND,
        0x65 => KEY_COMPOSE, // Application / context-menu key
        0x68..=0x73 => KEY_F13 + (usage - 0x68),
        0xE0 => KEY_LEFTCTRL,
        0xE1 => KEY_LEFTSHIFT,
        0xE2 => KEY_LEFTALT,
        0xE3 => KEY_LEFTMETA,
        0xE4 => KEY_RIGHTCTRL,
        0xE5 => KEY_RIGHTSHIFT,
        0xE6 => KEY_RIGHTALT,
        0xE7 => KEY_RIGHTMETA,
        _ => return None,
    })
}

/// Every evdev key code the keyboard device must advertise: all translatable
/// HID usages plus the media/volume keys (which have no keyboard-page usage).
pub fn all_supported_keys() -> Vec<u16> {
    let mut keys: Vec<u16> = (0u16..=0xFF).filter_map(hid_to_evdev).collect();
    keys.extend([
        KEY_MUTE,
        KEY_VOLUMEDOWN,
        KEY_VOLUMEUP,
        KEY_NEXTSONG,
        KEY_PLAYPAUSE,
    ]);
    keys.extend([KEY_PREVIOUSSONG, KEY_STOPCD]);
    keys.sort_unstable();
    keys.dedup();
    keys
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn letters_follow_the_kernel_qwerty_layout() {
        // Spot checks against the well-known kernel table.
        assert_eq!(hid_to_evdev(0x04), Some(30)); // a
        assert_eq!(hid_to_evdev(0x05), Some(48)); // b
        assert_eq!(hid_to_evdev(0x06), Some(46)); // c
        assert_eq!(hid_to_evdev(0x14), Some(16)); // q
        assert_eq!(hid_to_evdev(0x1D), Some(44)); // z
    }

    #[test]
    fn digits_and_function_keys() {
        assert_eq!(hid_to_evdev(0x1E), Some(2)); // 1
        assert_eq!(hid_to_evdev(0x26), Some(10)); // 9
        assert_eq!(hid_to_evdev(0x27), Some(11)); // 0
        assert_eq!(hid_to_evdev(0x3A), Some(59)); // F1
        assert_eq!(hid_to_evdev(0x43), Some(68)); // F10
        assert_eq!(hid_to_evdev(0x44), Some(87)); // F11 (out of sequence in the kernel)
        assert_eq!(hid_to_evdev(0x45), Some(88)); // F12
        assert_eq!(hid_to_evdev(0x68), Some(183)); // F13
        assert_eq!(hid_to_evdev(0x73), Some(194)); // F24
    }

    #[test]
    fn navigation_keypad_and_modifiers() {
        assert_eq!(hid_to_evdev(0x4F), Some(KEY_RIGHT));
        assert_eq!(hid_to_evdev(0x50), Some(KEY_LEFT));
        assert_eq!(hid_to_evdev(0x51), Some(KEY_DOWN));
        assert_eq!(hid_to_evdev(0x52), Some(KEY_UP));
        assert_eq!(hid_to_evdev(0x59), Some(KEY_KP1));
        assert_eq!(hid_to_evdev(0x61), Some(KEY_KP9));
        assert_eq!(hid_to_evdev(0x62), Some(KEY_KP0));
        assert_eq!(hid_to_evdev(0xE0), Some(KEY_LEFTCTRL));
        assert_eq!(hid_to_evdev(0xE3), Some(KEY_LEFTMETA));
        assert_eq!(hid_to_evdev(0xE7), Some(KEY_RIGHTMETA));
    }

    #[test]
    fn unknown_usages_are_rejected() {
        for usage in [
            0x00, 0x01, 0x02, 0x03, 0x66, 0x67, 0x74, 0xA0, 0xDF, 0xE8, 0x1234,
        ] {
            assert_eq!(hid_to_evdev(usage), None, "usage {usage:#x}");
        }
    }

    #[test]
    fn every_usage_the_android_client_can_send_is_mapped() {
        // HidKeyCodes.kt covers 0x04..=0x65 (letters through the Application
        // key) plus the modifiers 0xE0..=0xE7.
        for usage in (0x04..=0x65u16).chain(0xE0..=0xE7) {
            assert!(hid_to_evdev(usage).is_some(), "usage {usage:#x} unmapped");
        }
    }

    #[test]
    fn mapping_is_injective_apart_from_the_two_backslash_usages() {
        use std::collections::HashMap;
        let mut seen: HashMap<u16, u16> = HashMap::new();
        for usage in 0u16..=0xFF {
            if let Some(code) = hid_to_evdev(usage) {
                if let Some(prev) = seen.insert(code, usage) {
                    assert!(
                        code == KEY_BACKSLASH && [prev, usage] == [0x31, 0x32],
                        "usages {prev:#x} and {usage:#x} both map to evdev {code}"
                    );
                }
            }
        }
    }

    #[test]
    fn advertised_key_set_covers_media_keys_and_has_no_duplicates() {
        let keys = all_supported_keys();
        let mut sorted = keys.clone();
        sorted.dedup();
        assert_eq!(keys, sorted);
        for k in [
            KEY_PLAYPAUSE,
            KEY_NEXTSONG,
            KEY_PREVIOUSSONG,
            KEY_STOPCD,
            KEY_MUTE,
        ] {
            assert!(keys.contains(&k));
        }
        assert!(
            keys.iter().all(|&k| k < 0x100),
            "BTN_* codes must not leak into the keyboard"
        );
    }

    /// Cross-checks every constant against the kernel's own header when it is
    /// installed (it is on any Linux box with linux-libc-dev / linux-api-headers).
    #[test]
    fn constants_match_kernel_header() {
        let Ok(header) = std::fs::read_to_string("/usr/include/linux/input-event-codes.h") else {
            eprintln!("skipping: /usr/include/linux/input-event-codes.h not present");
            return;
        };
        let mut checked = 0;
        for line in header.lines() {
            let mut parts = line.split_whitespace();
            if parts.next() != Some("#define") {
                continue;
            }
            let (Some(name), Some(value)) = (parts.next(), parts.next()) else {
                continue;
            };
            let Some(mine) = ALL.iter().find(|(n, _)| *n == name).map(|(_, v)| *v) else {
                continue;
            };
            let parsed = match value.strip_prefix("0x") {
                Some(hex) => u16::from_str_radix(hex, 16),
                None => value.parse(),
            };
            if let Ok(expected) = parsed {
                assert_eq!(mine, expected, "{name} differs from the kernel header");
                checked += 1;
            }
        }
        assert_eq!(
            checked,
            ALL.len(),
            "some constants were not found in the header"
        );
        for (name, value) in [
            ("BTN_LEFT", BTN_LEFT),
            ("BTN_RIGHT", BTN_RIGHT),
            ("BTN_MIDDLE", BTN_MIDDLE),
        ] {
            let line = header
                .lines()
                .find(|l| l.split_whitespace().nth(1) == Some(name))
                .unwrap_or_else(|| panic!("{name} missing from header"));
            let hex = line
                .split_whitespace()
                .nth(2)
                .unwrap()
                .trim_start_matches("0x");
            assert_eq!(value, u16::from_str_radix(hex, 16).unwrap(), "{name}");
        }
    }
}
