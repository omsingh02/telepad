//! USB HID usage -> macOS virtual key code (Carbon `kVK_*`, `HIToolbox/Events.h`).
//!
//! The codes name *physical key positions* on an ANSI keyboard, which is also
//! what HID usages describe, so the translation is layout independent.

macro_rules! virtual_keys {
    ($($name:ident = $val:expr),* $(,)?) => {
        $( pub const $name: u16 = $val; )*
        /// Every constant above, by name (drives the uniqueness test).
        #[allow(dead_code)]
        pub const ALL: &[(&str, u16)] = &[ $( (stringify!($name), $val) ),* ];
    };
}

virtual_keys! {
    ANSI_A = 0x00, ANSI_S = 0x01, ANSI_D = 0x02, ANSI_F = 0x03, ANSI_H = 0x04, ANSI_G = 0x05,
    ANSI_Z = 0x06, ANSI_X = 0x07, ANSI_C = 0x08, ANSI_V = 0x09, ISO_SECTION = 0x0A,
    ANSI_B = 0x0B, ANSI_Q = 0x0C, ANSI_W = 0x0D, ANSI_E = 0x0E, ANSI_R = 0x0F, ANSI_Y = 0x10,
    ANSI_T = 0x11, ANSI_1 = 0x12, ANSI_2 = 0x13, ANSI_3 = 0x14, ANSI_4 = 0x15, ANSI_6 = 0x16,
    ANSI_5 = 0x17, ANSI_EQUAL = 0x18, ANSI_9 = 0x19, ANSI_7 = 0x1A, ANSI_MINUS = 0x1B,
    ANSI_8 = 0x1C, ANSI_0 = 0x1D, ANSI_RIGHT_BRACKET = 0x1E, ANSI_O = 0x1F, ANSI_U = 0x20,
    ANSI_LEFT_BRACKET = 0x21, ANSI_I = 0x22, ANSI_P = 0x23, RETURN = 0x24, ANSI_L = 0x25,
    ANSI_J = 0x26, ANSI_QUOTE = 0x27, ANSI_K = 0x28, ANSI_SEMICOLON = 0x29,
    ANSI_BACKSLASH = 0x2A, ANSI_COMMA = 0x2B, ANSI_SLASH = 0x2C, ANSI_N = 0x2D, ANSI_M = 0x2E,
    ANSI_PERIOD = 0x2F, TAB = 0x30, SPACE = 0x31, ANSI_GRAVE = 0x32, DELETE = 0x33,
    ESCAPE = 0x35, RIGHT_COMMAND = 0x36, COMMAND = 0x37, SHIFT = 0x38, CAPS_LOCK = 0x39,
    OPTION = 0x3A, CONTROL = 0x3B, RIGHT_SHIFT = 0x3C, RIGHT_OPTION = 0x3D,
    RIGHT_CONTROL = 0x3E, F17 = 0x40, KEYPAD_DECIMAL = 0x41, KEYPAD_MULTIPLY = 0x43,
    KEYPAD_PLUS = 0x45, KEYPAD_CLEAR = 0x47, KEYPAD_DIVIDE = 0x4B, KEYPAD_ENTER = 0x4C,
    KEYPAD_MINUS = 0x4E, F18 = 0x4F, F19 = 0x50, KEYPAD_0 = 0x52, KEYPAD_1 = 0x53,
    KEYPAD_2 = 0x54, KEYPAD_3 = 0x55, KEYPAD_4 = 0x56, KEYPAD_5 = 0x57, KEYPAD_6 = 0x58,
    KEYPAD_7 = 0x59, F20 = 0x5A, KEYPAD_8 = 0x5B, KEYPAD_9 = 0x5C,
    F5 = 0x60, F6 = 0x61, F7 = 0x62, F3 = 0x63, F8 = 0x64, F9 = 0x65, F11 = 0x67, F13 = 0x69,
    F16 = 0x6A, F14 = 0x6B, F10 = 0x6D, CONTEXTUAL_MENU = 0x6E, F12 = 0x6F, F15 = 0x71,
    HELP = 0x72, HOME = 0x73, PAGE_UP = 0x74, FORWARD_DELETE = 0x75, F4 = 0x76, END = 0x77,
    F2 = 0x78, PAGE_DOWN = 0x79, F1 = 0x7A, LEFT_ARROW = 0x7B, RIGHT_ARROW = 0x7C,
    DOWN_ARROW = 0x7D, UP_ARROW = 0x7E,
}

/// Letters a..z in HID order.
const LETTERS: [u16; 26] = [
    ANSI_A, ANSI_B, ANSI_C, ANSI_D, ANSI_E, ANSI_F, ANSI_G, ANSI_H, ANSI_I, ANSI_J, ANSI_K, ANSI_L,
    ANSI_M, ANSI_N, ANSI_O, ANSI_P, ANSI_Q, ANSI_R, ANSI_S, ANSI_T, ANSI_U, ANSI_V, ANSI_W, ANSI_X,
    ANSI_Y, ANSI_Z,
];

/// Digits 1..9, 0 in HID order. (macOS numbers the digit row non-sequentially.)
const DIGITS: [u16; 10] = [
    ANSI_1, ANSI_2, ANSI_3, ANSI_4, ANSI_5, ANSI_6, ANSI_7, ANSI_8, ANSI_9, ANSI_0,
];

/// F1..F12 in HID order.
const F_KEYS: [u16; 12] = [F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12];

/// F13..F20 in HID order (0x68..=0x6F). Apple keyboards stop at F20.
const HIGH_F_KEYS: [u16; 8] = [F13, F14, F15, F16, F17, F18, F19, F20];

/// Keypad 1..9, 0 in HID order (0x59..=0x62).
const KEYPAD_DIGITS: [u16; 10] = [
    KEYPAD_1, KEYPAD_2, KEYPAD_3, KEYPAD_4, KEYPAD_5, KEYPAD_6, KEYPAD_7, KEYPAD_8, KEYPAD_9,
    KEYPAD_0,
];

/// Translates a HID keyboard-page usage into a macOS virtual key code.
///
/// Keys that a Mac keyboard does not have follow Apple's own convention for
/// third-party keyboards: Print Screen / Scroll Lock / Pause become F13 / F14 /
/// F15, Num Lock becomes Clear, and Insert becomes Help.
pub fn hid_to_mac_vk(usage: u16) -> Option<u16> {
    Some(match usage {
        0x04..=0x1D => LETTERS[(usage - 0x04) as usize],
        0x1E..=0x27 => DIGITS[(usage - 0x1E) as usize],
        0x28 => RETURN,
        0x29 => ESCAPE,
        0x2A => DELETE, // Backspace
        0x2B => TAB,
        0x2C => SPACE,
        0x2D => ANSI_MINUS,
        0x2E => ANSI_EQUAL,
        0x2F => ANSI_LEFT_BRACKET,
        0x30 => ANSI_RIGHT_BRACKET,
        0x31 | 0x32 => ANSI_BACKSLASH, // 0x32 = non-US '#'
        0x33 => ANSI_SEMICOLON,
        0x34 => ANSI_QUOTE,
        0x35 => ANSI_GRAVE,
        0x36 => ANSI_COMMA,
        0x37 => ANSI_PERIOD,
        0x38 => ANSI_SLASH,
        0x39 => CAPS_LOCK,
        0x3A..=0x45 => F_KEYS[(usage - 0x3A) as usize],
        0x46 => F13,  // Print Screen
        0x47 => F14,  // Scroll Lock
        0x48 => F15,  // Pause
        0x49 => HELP, // Insert
        0x4A => HOME,
        0x4B => PAGE_UP,
        0x4C => FORWARD_DELETE,
        0x4D => END,
        0x4E => PAGE_DOWN,
        0x4F => RIGHT_ARROW,
        0x50 => LEFT_ARROW,
        0x51 => DOWN_ARROW,
        0x52 => UP_ARROW,
        0x53 => KEYPAD_CLEAR, // Num Lock
        0x54 => KEYPAD_DIVIDE,
        0x55 => KEYPAD_MULTIPLY,
        0x56 => KEYPAD_MINUS,
        0x57 => KEYPAD_PLUS,
        0x58 => KEYPAD_ENTER,
        0x59..=0x62 => KEYPAD_DIGITS[(usage - 0x59) as usize],
        0x63 => KEYPAD_DECIMAL,
        0x64 => ISO_SECTION,     // non-US backslash
        0x65 => CONTEXTUAL_MENU, // Application key
        0x68..=0x6F => HIGH_F_KEYS[(usage - 0x68) as usize],
        0xE0 => CONTROL,
        0xE1 => SHIFT,
        0xE2 => OPTION,
        0xE3 => COMMAND,
        0xE4 => RIGHT_CONTROL,
        0xE5 => RIGHT_SHIFT,
        0xE6 => RIGHT_OPTION,
        0xE7 => RIGHT_COMMAND,
        _ => return None,
    })
}

/// `CGEventFlags` mask bits (`kCGEventFlagMask*`).
pub mod flags {
    pub const SHIFT: u64 = 0x0002_0000;
    pub const CONTROL: u64 = 0x0004_0000;
    pub const ALTERNATE: u64 = 0x0008_0000;
    pub const COMMAND: u64 = 0x0010_0000;
}

/// The `CGEventFlags` bit a modifier key contributes while held, or 0 for
/// non-modifier keys.
pub fn modifier_flag(vk: u16) -> u64 {
    match vk {
        SHIFT | RIGHT_SHIFT => flags::SHIFT,
        CONTROL | RIGHT_CONTROL => flags::CONTROL,
        OPTION | RIGHT_OPTION => flags::ALTERNATE,
        COMMAND | RIGHT_COMMAND => flags::COMMAND,
        _ => 0,
    }
}

/// How the client's modifier keys are interpreted on macOS.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ModifierPolicy {
    /// Exactly what a PC keyboard plugged into a Mac does: Ctrl = Control,
    /// Win = Command, Alt = Option.
    #[default]
    Native,
    /// Ctrl and Win swap roles (Ctrl = Command, Win = Control), so the
    /// Windows-style Ctrl+C / Ctrl+V / Ctrl+Z shortcuts the app sends act as
    /// Copy / Paste / Undo on a Mac.
    CtrlAsCommand,
}

impl ModifierPolicy {
    /// Applies the policy to a HID modifier usage (0xE0..=0xE7); any other
    /// usage is returned unchanged.
    pub fn apply(self, usage: u16) -> u16 {
        match (self, usage) {
            (Self::CtrlAsCommand, 0xE0) => 0xE3,
            (Self::CtrlAsCommand, 0xE3) => 0xE0,
            (Self::CtrlAsCommand, 0xE4) => 0xE7,
            (Self::CtrlAsCommand, 0xE7) => 0xE4,
            _ => usage,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn virtual_key_codes_are_unique() {
        let mut seen = std::collections::HashMap::new();
        for (name, code) in ALL {
            if let Some(prev) = seen.insert(code, name) {
                panic!("{prev} and {name} share virtual key code {code:#x}");
            }
        }
    }

    #[test]
    fn well_known_codes() {
        // Values every macOS developer knows: A=0x00, Return=0x24, Space=0x31,
        // arrows 0x7B..0x7E, Command=0x37, Escape=0x35.
        assert_eq!(hid_to_mac_vk(0x04), Some(0x00)); // a
        assert_eq!(hid_to_mac_vk(0x28), Some(0x24)); // return
        assert_eq!(hid_to_mac_vk(0x2C), Some(0x31)); // space
        assert_eq!(hid_to_mac_vk(0x29), Some(0x35)); // escape
        assert_eq!(hid_to_mac_vk(0x50), Some(0x7B)); // left
        assert_eq!(hid_to_mac_vk(0x4F), Some(0x7C)); // right
        assert_eq!(hid_to_mac_vk(0x51), Some(0x7D)); // down
        assert_eq!(hid_to_mac_vk(0x52), Some(0x7E)); // up
        assert_eq!(hid_to_mac_vk(0xE3), Some(0x37)); // left GUI -> command
        assert_eq!(hid_to_mac_vk(0xE2), Some(0x3A)); // left alt -> option
    }

    #[test]
    fn digit_row_is_not_sequential_on_macos() {
        assert_eq!(hid_to_mac_vk(0x1E), Some(ANSI_1));
        assert_eq!(hid_to_mac_vk(0x22), Some(ANSI_5));
        assert_eq!(hid_to_mac_vk(0x23), Some(ANSI_6));
        assert_eq!(ANSI_5, 0x17);
        assert_eq!(ANSI_6, 0x16);
        assert_eq!(hid_to_mac_vk(0x27), Some(ANSI_0));
    }

    #[test]
    fn function_keys() {
        assert_eq!(hid_to_mac_vk(0x3A), Some(F1));
        assert_eq!(hid_to_mac_vk(0x3C), Some(F3));
        assert_eq!(hid_to_mac_vk(0x45), Some(F12));
        assert_eq!(hid_to_mac_vk(0x68), Some(F13));
        assert_eq!(hid_to_mac_vk(0x6F), Some(F20));
        // Apple keyboards have no F21..F24.
        assert_eq!(hid_to_mac_vk(0x70), None);
    }

    #[test]
    fn pc_only_keys_follow_apples_third_party_keyboard_convention() {
        assert_eq!(hid_to_mac_vk(0x46), Some(F13)); // Print Screen
        assert_eq!(hid_to_mac_vk(0x47), Some(F14)); // Scroll Lock
        assert_eq!(hid_to_mac_vk(0x48), Some(F15)); // Pause
        assert_eq!(hid_to_mac_vk(0x49), Some(HELP)); // Insert
        assert_eq!(hid_to_mac_vk(0x53), Some(KEYPAD_CLEAR)); // Num Lock
        assert_eq!(hid_to_mac_vk(0x4C), Some(FORWARD_DELETE)); // Delete
        assert_eq!(hid_to_mac_vk(0x2A), Some(DELETE)); // Backspace
    }

    #[test]
    fn every_usage_the_android_client_can_send_is_mapped() {
        for usage in (0x04..=0x65u16).chain(0xE0..=0xE7) {
            assert!(hid_to_mac_vk(usage).is_some(), "usage {usage:#x} unmapped");
        }
    }

    /// Pairs of usages that intentionally land on the same Mac key: the two
    /// backslash usages, and the PC-only Print Screen / Scroll Lock / Pause keys,
    /// which Apple maps onto F13 / F14 / F15 (the same keys real F13-F15 produce).
    const INTENTIONAL_ALIASES: [[u16; 2]; 4] =
        [[0x31, 0x32], [0x46, 0x68], [0x47, 0x69], [0x48, 0x6A]];

    #[test]
    fn mapping_is_injective_apart_from_the_documented_aliases() {
        let mut seen = std::collections::HashMap::new();
        for usage in 0u16..=0xFF {
            if let Some(vk) = hid_to_mac_vk(usage) {
                if let Some(prev) = seen.insert(vk, usage) {
                    assert!(
                        INTENTIONAL_ALIASES.contains(&[prev, usage]),
                        "usages {prev:#x} and {usage:#x} both map to vk {vk:#x}"
                    );
                }
            }
        }
    }

    #[test]
    fn unknown_usages_are_rejected() {
        for usage in [0x00, 0x01, 0x03, 0x66, 0x67, 0x74, 0xDF, 0xE8, 0x1234] {
            assert_eq!(hid_to_mac_vk(usage), None, "usage {usage:#x}");
        }
    }

    #[test]
    fn modifier_keys_contribute_event_flags() {
        assert_eq!(modifier_flag(COMMAND), flags::COMMAND);
        assert_eq!(modifier_flag(RIGHT_COMMAND), flags::COMMAND);
        assert_eq!(modifier_flag(OPTION), flags::ALTERNATE);
        assert_eq!(modifier_flag(CONTROL), flags::CONTROL);
        assert_eq!(modifier_flag(RIGHT_SHIFT), flags::SHIFT);
        assert_eq!(modifier_flag(ANSI_A), 0);
        assert_eq!(modifier_flag(CAPS_LOCK), 0);
    }

    #[test]
    fn native_policy_is_the_identity() {
        for usage in 0u16..=0xFF {
            assert_eq!(ModifierPolicy::Native.apply(usage), usage);
        }
    }

    #[test]
    fn ctrl_as_command_swaps_ctrl_and_win_only() {
        let p = ModifierPolicy::CtrlAsCommand;
        assert_eq!(p.apply(0xE0), 0xE3); // left ctrl -> left GUI (command)
        assert_eq!(p.apply(0xE4), 0xE7); // right ctrl -> right GUI
        assert_eq!(p.apply(0xE3), 0xE0);
        assert_eq!(p.apply(0xE7), 0xE4);
        for unchanged in [0xE1, 0xE2, 0xE5, 0xE6, 0x04, 0x2C] {
            assert_eq!(p.apply(unchanged), unchanged);
        }
        // Swapping twice restores the original.
        for usage in 0u16..=0xFF {
            assert_eq!(p.apply(p.apply(usage)), usage);
        }
    }
}
