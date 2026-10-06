//! Key translation tables.
//!
//! These are plain data and pure functions, deliberately *not* gated on the
//! target OS: that way the Linux and macOS mappings are unit-tested on every CI
//! platform instead of only on the machine that happens to use them.

pub mod ascii;
pub mod linux;
pub mod macos;

/// USB HID Usage Page 0x07 (Keyboard/Keypad) codes the backends refer to by name.
pub mod usage {
    pub const A: u16 = 0x04;
    pub const D: u16 = 0x07;
    pub const E: u16 = 0x08;
    pub const Q: u16 = 0x14;
    pub const S: u16 = 0x16;
    pub const U: u16 = 0x18;
    pub const W: u16 = 0x1A;
    pub const ENTER: u16 = 0x28;
    pub const ESC: u16 = 0x29;
    pub const BACKSPACE: u16 = 0x2A;
    pub const TAB: u16 = 0x2B;
    pub const SPACE: u16 = 0x2C;
    pub const F11: u16 = 0x44;
    pub const PRINT_SCREEN: u16 = 0x46;
    pub const UP: u16 = 0x52;
    pub const DOWN: u16 = 0x51;

    pub const LEFT_CTRL: u16 = 0xE0;
    pub const LEFT_SHIFT: u16 = 0xE1;
    pub const LEFT_ALT: u16 = 0xE2;
    pub const LEFT_META: u16 = 0xE3;
    pub const RIGHT_CTRL: u16 = 0xE4;
    pub const RIGHT_SHIFT: u16 = 0xE5;
    pub const RIGHT_ALT: u16 = 0xE6;
    pub const RIGHT_META: u16 = 0xE7;
}

/// HID usages (0xE0..=0xE7) for every bit set in a HID modifier bitmap.
///
/// The wire format reuses byte 0 of the HID boot-keyboard report, in which bit
/// *n* is the modifier whose usage is `0xE0 + n` (LCtrl, LShift, LAlt, LGui,
/// RCtrl, RShift, RAlt, RGui).
pub fn modifier_usages(mods: u8) -> impl Iterator<Item = u16> {
    (0..8u16)
        .filter(move |bit| mods & (1 << bit) != 0)
        .map(|bit| 0xE0 + bit)
}

#[cfg(test)]
mod tests {
    use super::*;
    use telepad_protocol::modifiers;

    #[test]
    fn modifier_bitmap_maps_to_hid_modifier_usages() {
        assert_eq!(modifier_usages(0).count(), 0);
        assert_eq!(
            modifier_usages(modifiers::LCTRL).collect::<Vec<_>>(),
            [usage::LEFT_CTRL]
        );
        assert_eq!(
            modifier_usages(modifiers::RMETA).collect::<Vec<_>>(),
            [usage::RIGHT_META]
        );
        assert_eq!(
            modifier_usages(modifiers::LCTRL | modifiers::LSHIFT | modifiers::RALT)
                .collect::<Vec<_>>(),
            [usage::LEFT_CTRL, usage::LEFT_SHIFT, usage::RIGHT_ALT]
        );
        assert_eq!(modifier_usages(0xFF).count(), 8);
    }

    #[test]
    fn modifier_constants_agree_with_protocol_bitmap() {
        let pairs = [
            (modifiers::LCTRL, usage::LEFT_CTRL),
            (modifiers::LSHIFT, usage::LEFT_SHIFT),
            (modifiers::LALT, usage::LEFT_ALT),
            (modifiers::LMETA, usage::LEFT_META),
            (modifiers::RCTRL, usage::RIGHT_CTRL),
            (modifiers::RSHIFT, usage::RIGHT_SHIFT),
            (modifiers::RALT, usage::RIGHT_ALT),
            (modifiers::RMETA, usage::RIGHT_META),
        ];
        for (mask, usage) in pairs {
            assert_eq!(modifier_usages(mask).collect::<Vec<_>>(), [usage]);
        }
    }
}
