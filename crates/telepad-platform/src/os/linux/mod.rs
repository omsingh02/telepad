//! Linux input injection through `/dev/uinput`.
//!
//! uinput creates virtual evdev devices in the kernel, so injected input is
//! indistinguishable from real hardware and works the same under Wayland, X11
//! and the text console. It needs write access to `/dev/uinput`.

mod actions;
mod uinput;

use crate::backend::{InputBackend, PlatformError, Result};
use crate::keymap::ascii::ascii_to_hid;
use crate::keymap::linux::*;
use crate::keymap::modifier_usages;
use crate::os::process::{self, Completion};
use crate::TextEntry;
use actions::{detect_desktop, lock_commands, plan_launch, Desktop, LaunchPlan};
use std::time::Duration;
use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};
use uinput::*;

/// Keyboard keys have to reach the application before the next one is pressed
/// for the IBus/GTK Unicode-entry sequence to be reliable.
const UNICODE_SETTLE: Duration = Duration::from_millis(3);
const LOCK_TIMEOUT: Duration = Duration::from_secs(3);

const WHEEL_NOTCH_HI_RES: i32 = 120;

// ── Pure event planning ──────────────────────────────────────────────────
//
// Each function returns the exact evdev events to write. Keeping them free of
// I/O lets the translation logic be tested without a virtual device.

pub(crate) fn mouse_move_events(dx: i16, dy: i16) -> Vec<RawEvent> {
    let mut events = Vec::with_capacity(3);
    if dx != 0 {
        events.push((EV_REL, REL_X, i32::from(dx)));
    }
    if dy != 0 {
        events.push((EV_REL, REL_Y, i32::from(dy)));
    }
    if !events.is_empty() {
        events.push(syn());
    }
    events
}

pub(crate) fn button_events(button: MouseButtonKind, pressed: bool) -> Vec<RawEvent> {
    let code = match button {
        MouseButtonKind::Left => BTN_LEFT,
        MouseButtonKind::Right => BTN_RIGHT,
        MouseButtonKind::Middle => BTN_MIDDLE,
    };
    vec![(EV_KEY, code, i32::from(pressed)), syn()]
}

/// One wheel notch is reported the way the kernel's own HID driver does it:
/// a high-resolution value (120 per notch) and the legacy whole-notch value in
/// the same report, so old and new consumers both see exactly one notch.
pub(crate) fn scroll_events(delta: i16) -> Vec<RawEvent> {
    if delta == 0 {
        return Vec::new();
    }
    let notches = i32::from(delta);
    vec![
        (EV_REL, REL_WHEEL_HI_RES, notches * WHEEL_NOTCH_HI_RES),
        (EV_REL, REL_WHEEL, notches),
        syn(),
    ]
}

/// A key state change, reported on its own like real hardware does.
fn key_event(code: u16, down: bool) -> [RawEvent; 2] {
    [(EV_KEY, code, i32::from(down)), syn()]
}

/// Press (or release) of a HID `usage` wrapped in the modifiers of `mods`:
/// modifiers go down before the key and come up after it.
pub(crate) fn key_events(usage: u16, mods: u8, pressed: bool) -> Vec<RawEvent> {
    let Some(code) = hid_to_evdev(usage) else {
        return Vec::new();
    };
    let modifier_codes: Vec<u16> = modifier_usages(mods).filter_map(hid_to_evdev).collect();
    let mut events = Vec::with_capacity(2 * (modifier_codes.len() + 1));
    if pressed {
        for &m in &modifier_codes {
            events.extend(key_event(m, true));
        }
        events.extend(key_event(code, true));
    } else {
        events.extend(key_event(code, false));
        for &m in modifier_codes.iter().rev() {
            events.extend(key_event(m, false));
        }
    }
    events
}

/// Press all `codes` in order, then release them in reverse (a key chord).
pub(crate) fn chord_events(codes: &[u16]) -> Vec<RawEvent> {
    let mut events = Vec::with_capacity(codes.len() * 4);
    for &c in codes {
        events.extend(key_event(c, true));
    }
    for &c in codes.iter().rev() {
        events.extend(key_event(c, false));
    }
    events
}

/// How a single character is typed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TextStep {
    /// A key on a US-QWERTY keyboard, with or without Shift.
    Key { usage: u16, shift: bool },
    /// Anything else, entered with the Ctrl+Shift+U unicode sequence.
    Unicode(u32),
}

pub(crate) fn plan_text(text: &str, entry: TextEntry) -> Vec<TextStep> {
    text.chars()
        .filter_map(|c| match ascii_to_hid(c) {
            // Whitespace keys sit in the same place on every layout, so they are
            // typed as keys in either mode. Everything else follows the mode.
            Some((usage, shift)) if entry == TextEntry::Keys || !is_layout_dependent(c) => {
                Some(TextStep::Key { usage, shift })
            }
            Some(_) => Some(TextStep::Unicode(u32::from(c))),
            // ASCII control characters without a key (ESC, DEL, ...) are dropped
            // rather than being "typed" as invisible code points.
            None if c.is_ascii() => None,
            None => Some(TextStep::Unicode(u32::from(c))),
        })
        .collect()
}

/// Whether the key that types `c` depends on the active keyboard layout.
fn is_layout_dependent(c: char) -> bool {
    !matches!(c, ' ' | '\n' | '\r' | '\t' | '\u{8}')
}

fn tap_codes(codes: &[u16]) -> Vec<RawEvent> {
    let mut events = Vec::with_capacity(codes.len() * 4);
    for &c in codes {
        events.extend(key_event(c, true));
        events.extend(key_event(c, false));
    }
    events
}

/// Events for one planned step, and whether the application needs a moment to
/// digest it before the next step.
pub(crate) fn step_events(step: TextStep) -> (Vec<RawEvent>, bool) {
    match step {
        TextStep::Key { usage, shift } => {
            let code = hid_to_evdev(usage).expect("ASCII table only yields mapped usages");
            let mut events = Vec::with_capacity(8);
            if shift {
                events.extend(key_event(KEY_LEFTSHIFT, true));
            }
            events.extend(key_event(code, true));
            events.extend(key_event(code, false));
            if shift {
                events.extend(key_event(KEY_LEFTSHIFT, false));
            }
            (events, false)
        }
        TextStep::Unicode(code_point) => {
            // Ctrl+Shift+U, release, hex digits, Space: GTK's and IBus's
            // standard way of entering an arbitrary code point.
            let mut events = chord_events(&[KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_U]);
            for digit in format!("{code_point:x}").chars() {
                let (usage, _) = ascii_to_hid(digit).expect("hex digits are ASCII");
                events.extend(tap_codes(&[
                    hid_to_evdev(usage).expect("digit usage is mapped")
                ]));
            }
            events.extend(tap_codes(&[KEY_SPACE]));
            (events, true)
        }
    }
}

// ── Backend ──────────────────────────────────────────────────────────────

pub struct LinuxInput {
    mouse: VirtualDevice,
    keyboard: VirtualDevice,
    desktop: Desktop,
    home: String,
    text_entry: TextEntry,
}

impl LinuxInput {
    pub fn new(text_entry: TextEntry) -> Result<Self> {
        Self::with_device_names(
            "Telepad Virtual Mouse",
            "Telepad Virtual Keyboard",
            text_entry,
        )
    }

    pub(crate) fn with_device_names(
        mouse_name: &str,
        keyboard_name: &str,
        text_entry: TextEntry,
    ) -> Result<Self> {
        let mouse = VirtualDevice::create(
            mouse_name,
            &[BTN_LEFT, BTN_RIGHT, BTN_MIDDLE],
            &[REL_X, REL_Y, REL_WHEEL, REL_WHEEL_HI_RES],
        )
        .map_err(explain_uinput_error)?;
        let keyboard = VirtualDevice::create(keyboard_name, &all_supported_keys(), &[])
            .map_err(explain_uinput_error)?;

        Ok(Self {
            mouse,
            keyboard,
            desktop: detect_desktop(std::env::var("XDG_CURRENT_DESKTOP").ok().as_deref()),
            home: dirs::home_dir().map_or_else(|| "/".into(), |h| h.to_string_lossy().into_owned()),
            text_entry,
        })
    }

    fn keyboard_events(&self, events: &[RawEvent]) -> Result<()> {
        self.keyboard.emit(events).map_err(PlatformError::from)
    }
}

fn explain_uinput_error(err: std::io::Error) -> PlatformError {
    use std::io::ErrorKind;
    match err.kind() {
        ErrorKind::NotFound => PlatformError::Unavailable(
            "/dev/uinput does not exist, so Telepad cannot create a virtual mouse and keyboard.\n\
             Load the kernel module with `sudo modprobe uinput`, and keep it loaded across reboots \
             with `echo uinput | sudo tee /etc/modules-load.d/uinput.conf`."
                .into(),
        ),
        ErrorKind::PermissionDenied => PlatformError::Unavailable(
            "Permission denied opening /dev/uinput, which Telepad needs to create a virtual mouse \
             and keyboard.\nGrant your user access once:\n  \
             echo 'KERNEL==\"uinput\", GROUP=\"input\", MODE=\"0660\", OPTIONS+=\"static_node=uinput\"' \
             | sudo tee /etc/udev/rules.d/60-telepad-uinput.rules\n  \
             sudo udevadm control --reload && sudo udevadm trigger\n  \
             sudo usermod -aG input \"$USER\"   # then log out and back in\n\
             (or run the server as root, which is not recommended)."
                .into(),
        ),
        _ => PlatformError::Unavailable(format!("could not create uinput devices: {err}")),
    }
}

impl InputBackend for LinuxInput {
    fn name(&self) -> &'static str {
        "Linux uinput"
    }

    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()> {
        Ok(self.mouse.emit(&mouse_move_events(dx, dy))?)
    }

    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()> {
        Ok(self.mouse.emit(&button_events(button, pressed))?)
    }

    fn scroll(&mut self, delta: i16) -> Result<()> {
        Ok(self.mouse.emit(&scroll_events(delta))?)
    }

    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()> {
        self.keyboard_events(&key_events(usage, mods, pressed))
    }

    fn text(&mut self, text: &str) -> Result<()> {
        for step in plan_text(text, self.text_entry) {
            let (events, settle) = step_events(step);
            self.keyboard_events(&events)?;
            if settle {
                std::thread::sleep(UNICODE_SETTLE);
            }
        }
        Ok(())
    }

    fn media(&mut self, action: MediaAction) -> Result<()> {
        let code = match action {
            MediaAction::PlayPause => KEY_PLAYPAUSE,
            MediaAction::Next => KEY_NEXTSONG,
            MediaAction::Prev => KEY_PREVIOUSSONG,
            MediaAction::Stop => KEY_STOPCD,
        };
        self.keyboard_events(&tap_codes(&[code]))
    }

    fn volume(&mut self, direction: VolumeDirection) -> Result<()> {
        let code = match direction {
            VolumeDirection::Up => KEY_VOLUMEUP,
            VolumeDirection::Down => KEY_VOLUMEDOWN,
            VolumeDirection::Mute => KEY_MUTE,
        };
        self.keyboard_events(&tap_codes(&[code]))
    }

    fn launch(&mut self, action: SystemAction) -> Result<()> {
        match plan_launch(action, self.desktop, &self.home) {
            LaunchPlan::Chord(codes) => self.keyboard_events(&chord_events(&codes)),
            LaunchPlan::Run(candidates) => {
                process::run_first_available(&candidates, Completion::Spawned)
                    .map(|_| ())
                    .ok_or_else(|| {
                        PlatformError::Os(format!("no program available for {action:?}"))
                    })
            }
        }
    }

    fn lock_screen(&mut self) -> Result<()> {
        process::run_first_available(&lock_commands(), Completion::ExitedOk(LOCK_TIMEOUT))
            .map(|_| ())
            .ok_or_else(|| {
                PlatformError::Os(
                    "no working screen-lock command found (tried loginctl, xdg-screensaver, ...)"
                        .into(),
                )
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keymap::usage;

    const DOWN: i32 = 1;
    const UP: i32 = 0;

    fn key(code: u16, v: i32) -> [RawEvent; 2] {
        [(EV_KEY, code, v), syn()]
    }

    #[test]
    fn mouse_move_emits_only_nonzero_axes_in_one_report() {
        assert_eq!(
            mouse_move_events(5, -3),
            vec![(EV_REL, REL_X, 5), (EV_REL, REL_Y, -3), syn()]
        );
        assert_eq!(mouse_move_events(0, 7), vec![(EV_REL, REL_Y, 7), syn()]);
        assert_eq!(mouse_move_events(-9, 0), vec![(EV_REL, REL_X, -9), syn()]);
        assert!(mouse_move_events(0, 0).is_empty());
    }

    #[test]
    fn mouse_move_handles_the_extremes_of_the_wire_range() {
        assert_eq!(
            mouse_move_events(i16::MIN, i16::MAX)[0],
            (EV_REL, REL_X, -32768)
        );
        assert_eq!(
            mouse_move_events(i16::MIN, i16::MAX)[1],
            (EV_REL, REL_Y, 32767)
        );
    }

    #[test]
    fn buttons() {
        assert_eq!(
            button_events(MouseButtonKind::Left, true),
            vec![(EV_KEY, BTN_LEFT, DOWN), syn()]
        );
        assert_eq!(
            button_events(MouseButtonKind::Right, false),
            vec![(EV_KEY, BTN_RIGHT, UP), syn()]
        );
        assert_eq!(
            button_events(MouseButtonKind::Middle, true),
            vec![(EV_KEY, BTN_MIDDLE, DOWN), syn()]
        );
    }

    #[test]
    fn scroll_reports_hi_res_and_legacy_values_together() {
        assert_eq!(
            scroll_events(1),
            vec![
                (EV_REL, REL_WHEEL_HI_RES, 120),
                (EV_REL, REL_WHEEL, 1),
                syn()
            ]
        );
        assert_eq!(
            scroll_events(-3),
            vec![
                (EV_REL, REL_WHEEL_HI_RES, -360),
                (EV_REL, REL_WHEEL, -3),
                syn()
            ]
        );
        assert!(scroll_events(0).is_empty());
        // The largest wire value must not overflow the i32 event value.
        assert_eq!(scroll_events(i16::MAX)[0].2, 32767 * 120);
    }

    #[test]
    fn plain_key_press_and_release() {
        assert_eq!(key_events(0x04, 0, true), key(KEY_A, DOWN).to_vec());
        assert_eq!(key_events(0x04, 0, false), key(KEY_A, UP).to_vec());
    }

    #[test]
    fn modifiers_wrap_the_key_in_the_right_order() {
        use telepad_protocol::modifiers::*;
        // Ctrl+Shift+C: modifiers down, key down ... key up, modifiers up (reversed).
        let down = key_events(0x06, LCTRL | LSHIFT, true);
        let expected_down: Vec<RawEvent> = [
            key(KEY_LEFTCTRL, DOWN),
            key(KEY_LEFTSHIFT, DOWN),
            key(KEY_C, DOWN),
        ]
        .concat();
        assert_eq!(down, expected_down);

        let up = key_events(0x06, LCTRL | LSHIFT, false);
        let expected_up: Vec<RawEvent> = [
            key(KEY_C, UP),
            key(KEY_LEFTSHIFT, UP),
            key(KEY_LEFTCTRL, UP),
        ]
        .concat();
        assert_eq!(up, expected_up);
    }

    #[test]
    fn press_and_release_with_same_modifiers_are_balanced() {
        use telepad_protocol::modifiers::*;
        let all = LCTRL | LSHIFT | LALT | LMETA | RCTRL | RSHIFT | RALT | RMETA;
        for mods in [0, LCTRL, LMETA | LALT, all] {
            let mut held = std::collections::HashSet::new();
            for (ty, code, v) in key_events(0x2C, mods, true)
                .into_iter()
                .chain(key_events(0x2C, mods, false))
            {
                if ty != EV_KEY {
                    continue;
                }
                if v == DOWN {
                    assert!(held.insert(code), "{code} pressed twice");
                } else {
                    assert!(held.remove(&code), "{code} released while up");
                }
            }
            assert!(held.is_empty(), "mods {mods:#x} left keys stuck: {held:?}");
        }
    }

    #[test]
    fn unmapped_usage_emits_nothing() {
        assert!(key_events(0x00, 0, true).is_empty());
        assert!(key_events(0xFFFF, 0xFF, false).is_empty());
    }

    #[test]
    fn every_report_ends_with_syn() {
        let all: Vec<Vec<RawEvent>> = vec![
            mouse_move_events(1, 1),
            button_events(MouseButtonKind::Left, true),
            scroll_events(1),
            key_events(0x04, 0xFF, true),
            key_events(0x04, 0xFF, false),
            chord_events(&[KEY_LEFTMETA, KEY_D]),
        ];
        for events in all {
            assert_eq!(events.last(), Some(&syn()));
        }
    }

    #[test]
    fn chord_presses_in_order_and_releases_in_reverse() {
        assert_eq!(
            chord_events(&[KEY_LEFTMETA, KEY_D]),
            [
                key(KEY_LEFTMETA, DOWN),
                key(KEY_D, DOWN),
                key(KEY_D, UP),
                key(KEY_LEFTMETA, UP)
            ]
            .concat()
        );
    }

    #[test]
    fn text_plan_uses_keys_for_ascii_and_unicode_entry_otherwise() {
        assert_eq!(
            plan_text("Hi!", TextEntry::Keys),
            vec![
                TextStep::Key {
                    usage: 0x0B,
                    shift: true
                },
                TextStep::Key {
                    usage: 0x0C,
                    shift: false
                },
                TextStep::Key {
                    usage: 0x1E,
                    shift: true
                },
            ]
        );
        assert_eq!(
            plan_text("é", TextEntry::Keys),
            vec![TextStep::Unicode(0xE9)]
        );
        assert_eq!(
            plan_text("🚀", TextEntry::Keys),
            vec![TextStep::Unicode(0x1F680)]
        );
        assert_eq!(
            plan_text("a한", TextEntry::Keys),
            vec![
                TextStep::Key {
                    usage: 0x04,
                    shift: false
                },
                TextStep::Unicode(0xD55C)
            ]
        );
        // Control characters with no key are skipped, not typed.
        assert!(plan_text("\u{1b}\u{7f}", TextEntry::Keys).is_empty());
        assert_eq!(
            plan_text("\n", TextEntry::Keys),
            vec![TextStep::Key {
                usage: usage::ENTER,
                shift: false
            }]
        );
    }

    #[test]
    fn unicode_mode_types_letters_digits_and_punctuation_by_code_point() {
        // Independent of the layout: no ASCII letter or symbol relies on a key position.
        assert_eq!(
            plan_text("yz1!", TextEntry::Unicode),
            vec![
                TextStep::Unicode(u32::from('y')),
                TextStep::Unicode(u32::from('z')),
                TextStep::Unicode(u32::from('1')),
                TextStep::Unicode(u32::from('!')),
            ]
        );
    }

    #[test]
    fn unicode_mode_still_uses_keys_for_whitespace_and_editing() {
        assert_eq!(
            plan_text("a b\n\t\u{8}", TextEntry::Unicode),
            vec![
                TextStep::Unicode(u32::from('a')),
                TextStep::Key {
                    usage: usage::SPACE,
                    shift: false
                },
                TextStep::Unicode(u32::from('b')),
                TextStep::Key {
                    usage: usage::ENTER,
                    shift: false
                },
                TextStep::Key {
                    usage: usage::TAB,
                    shift: false
                },
                TextStep::Key {
                    usage: usage::BACKSPACE,
                    shift: false
                },
            ]
        );
    }

    #[test]
    fn both_modes_agree_on_non_ascii_and_dropped_controls() {
        for mode in [TextEntry::Keys, TextEntry::Unicode] {
            assert_eq!(
                plan_text("é🚀", mode),
                vec![TextStep::Unicode(0xE9), TextStep::Unicode(0x1F680)]
            );
            assert!(plan_text("\u{1b}\u{7f}", mode).is_empty());
        }
    }

    #[test]
    fn the_default_text_entry_is_the_fast_key_path() {
        assert_eq!(TextEntry::default(), TextEntry::Keys);
    }

    #[test]
    fn shifted_character_wraps_the_key_in_shift() {
        let (events, settle) = step_events(TextStep::Key {
            usage: 0x04,
            shift: true,
        });
        assert!(!settle);
        assert_eq!(
            events,
            [
                key(KEY_LEFTSHIFT, DOWN),
                key(KEY_A, DOWN),
                key(KEY_A, UP),
                key(KEY_LEFTSHIFT, UP)
            ]
            .concat()
        );
    }

    #[test]
    fn unicode_entry_sequence() {
        // é = U+00E9 -> Ctrl+Shift+U, "e9", Space
        let (events, settle) = step_events(TextStep::Unicode(0xE9));
        assert!(settle);
        let presses: Vec<u16> = events
            .iter()
            .filter(|&&(ty, _, v)| ty == EV_KEY && v == DOWN)
            .map(|&(_, code, _)| code)
            .collect();
        assert_eq!(
            presses,
            vec![KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_U, KEY_E, KEY_9, KEY_SPACE]
        );
        // Everything pressed is released again.
        let downs = events
            .iter()
            .filter(|&&(ty, _, v)| ty == EV_KEY && v == DOWN)
            .count();
        let ups = events
            .iter()
            .filter(|&&(ty, _, v)| ty == EV_KEY && v == UP)
            .count();
        assert_eq!(downs, ups);
        // Ctrl and Shift are released before the hex digits are typed.
        let first_digit = events
            .iter()
            .position(|&(_, c, v)| c == KEY_E && v == DOWN)
            .unwrap();
        let ctrl_up = events
            .iter()
            .position(|&(_, c, v)| c == KEY_LEFTCTRL && v == UP)
            .unwrap();
        assert!(ctrl_up < first_digit);
    }

    #[test]
    fn emoji_code_point_uses_all_its_hex_digits() {
        let (events, _) = step_events(TextStep::Unicode(0x1F680));
        let presses: Vec<u16> = events
            .iter()
            .filter(|&&(ty, _, v)| ty == EV_KEY && v == DOWN)
            .map(|&(_, code, _)| code)
            .collect();
        assert_eq!(
            presses,
            vec![
                KEY_LEFTCTRL,
                KEY_LEFTSHIFT,
                KEY_U,
                KEY_1,
                KEY_F,
                KEY_6,
                KEY_8,
                KEY_0,
                KEY_SPACE
            ]
        );
    }

    #[test]
    fn media_and_volume_keys_are_advertised_by_the_keyboard() {
        let keys = all_supported_keys();
        for code in [
            KEY_PLAYPAUSE,
            KEY_NEXTSONG,
            KEY_PREVIOUSSONG,
            KEY_STOPCD,
            KEY_VOLUMEUP,
            KEY_VOLUMEDOWN,
            KEY_MUTE,
        ] {
            assert!(keys.contains(&code));
        }
        // Every key this module can emit must be in the advertised set, or the
        // kernel silently drops it.
        for step_code in [
            KEY_LEFTSHIFT,
            KEY_LEFTCTRL,
            KEY_U,
            KEY_SPACE,
            KEY_LEFTMETA,
            KEY_D,
            KEY_W,
            KEY_SYSRQ,
        ] {
            assert!(keys.contains(&step_code), "{step_code} is not advertised");
        }
    }

    #[test]
    fn permission_error_explains_how_to_fix_it() {
        let msg = explain_uinput_error(std::io::Error::from(std::io::ErrorKind::PermissionDenied))
            .to_string();
        assert!(msg.contains("/dev/uinput"));
        assert!(msg.contains("udev"));
        assert!(msg.contains("input"));
        let missing =
            explain_uinput_error(std::io::Error::from(std::io::ErrorKind::NotFound)).to_string();
        assert!(missing.contains("modprobe uinput"));
    }
}

/// Tests that talk to the real kernel. They create *virtual* devices, take an
/// exclusive grab on them (so no compositor ever sees the events and your real
/// cursor and focused window are untouched), then read the events back.
///
/// They skip themselves when `/dev/uinput` or the new device node is not
/// accessible, which is the normal situation on CI.
#[cfg(test)]
mod device_tests {
    use super::*;
    use nix::ioctl_write_int;
    use std::fs::File;
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;
    use std::time::Instant;

    ioctl_write_int!(eviocgrab, b'E', 0x90);

    struct Probe {
        file: File,
    }

    impl Probe {
        /// Opens and exclusively grabs the event node of `device`, or returns
        /// why that was not possible.
        fn grab(device: &VirtualDevice) -> std::result::Result<Self, String> {
            let sysname = device.sysname().map_err(|e| format!("sysname: {e}"))?;
            let sysdir = std::path::PathBuf::from("/sys/devices/virtual/input").join(&sysname);
            let deadline = Instant::now() + Duration::from_secs(5);
            let node = loop {
                let found = std::fs::read_dir(&sysdir).ok().and_then(|entries| {
                    entries
                        .flatten()
                        .map(|e| e.file_name().to_string_lossy().into_owned())
                        .find(|n| n.starts_with("event"))
                });
                if let Some(name) = found {
                    break std::path::PathBuf::from("/dev/input").join(name);
                }
                if Instant::now() > deadline {
                    return Err(format!("no event node appeared under {}", sysdir.display()));
                }
                std::thread::sleep(Duration::from_millis(20));
            };
            // udev applies the access ACL to a fresh node a moment after it appears.
            let file = loop {
                match std::fs::OpenOptions::new()
                    .read(true)
                    .custom_flags(libc::O_NONBLOCK)
                    .open(&node)
                {
                    Ok(f) => break f,
                    Err(e) if Instant::now() > deadline => {
                        return Err(format!("cannot open {}: {e}", node.display()))
                    }
                    Err(_) => std::thread::sleep(Duration::from_millis(20)),
                }
            };
            // SAFETY: valid fd; EVIOCGRAB takes an int by value.
            unsafe { eviocgrab(file.as_raw_fd(), 1) }.map_err(|e| format!("EVIOCGRAB: {e}"))?;
            Ok(Self { file })
        }

        /// Reads exactly `count` events (or fewer after a timeout).
        fn read(&mut self, count: usize) -> Vec<RawEvent> {
            let size = std::mem::size_of::<libc::input_event>();
            let mut events = Vec::new();
            let mut buf = vec![0u8; size];
            let deadline = Instant::now() + Duration::from_secs(3);
            while events.len() < count && Instant::now() < deadline {
                match self.file.read(&mut buf) {
                    Ok(n) if n == size => {
                        // SAFETY: `buf` holds one complete input_event.
                        let ev: libc::input_event =
                            unsafe { std::ptr::read_unaligned(buf.as_ptr().cast()) };
                        events.push((ev.type_, ev.code, ev.value));
                    }
                    Ok(_) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    Err(e) => panic!("read failed: {e}"),
                }
            }
            events
        }
    }

    fn backend_and_probes() -> Option<(LinuxInput, Probe, Probe)> {
        let tag = std::process::id();
        let input = match LinuxInput::with_device_names(
            &format!("Telepad Test Mouse {tag}"),
            &format!("Telepad Test Keyboard {tag}"),
            TextEntry::Keys,
        ) {
            Ok(input) => input,
            Err(e) => {
                eprintln!("skipping device test: {e}");
                return None;
            }
        };
        let mouse = Probe::grab(&input.mouse);
        let keyboard = Probe::grab(&input.keyboard);
        match (mouse, keyboard) {
            (Ok(m), Ok(k)) => Some((input, m, k)),
            (Err(e), _) | (_, Err(e)) => {
                eprintln!("skipping device test (cannot isolate the virtual device): {e}");
                None
            }
        }
    }

    /// Parses a sysfs capability bitmap (`capabilities/key`, `capabilities/rel`, ...):
    /// space-separated hex words of `unsigned long` width, most significant first.
    fn parse_capability_bitmap(text: &str) -> std::collections::BTreeSet<u16> {
        let bits_per_word = usize::BITS as usize;
        let mut set = std::collections::BTreeSet::new();
        for (index, word) in text.split_whitespace().rev().enumerate() {
            let value = u64::from_str_radix(word, 16).expect("hex word in sysfs bitmap");
            for bit in 0..bits_per_word {
                if value >> bit & 1 == 1 {
                    set.insert((index * bits_per_word + bit) as u16);
                }
            }
        }
        set
    }

    fn sysfs(device: &VirtualDevice, file: &str) -> String {
        let path = std::path::Path::new("/sys/devices/virtual/input")
            .join(device.sysname().expect("sysname"))
            .join(file);
        std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("reading {}: {e}", path.display()))
            .trim()
            .to_owned()
    }

    #[test]
    fn kernel_registers_exactly_the_capabilities_we_asked_for() {
        // No privileges needed: sysfs describes the device the kernel created
        // from our ioctls, and creating it emits no events.
        let tag = std::process::id();
        let input = match LinuxInput::with_device_names(
            &format!("Telepad Caps Mouse {tag}"),
            &format!("Telepad Caps Keyboard {tag}"),
            TextEntry::Keys,
        ) {
            Ok(input) => input,
            Err(e) => {
                eprintln!("skipping device test: {e}");
                return;
            }
        };

        assert_eq!(
            sysfs(&input.mouse, "name"),
            format!("Telepad Caps Mouse {tag}")
        );
        assert_eq!(
            sysfs(&input.keyboard, "name"),
            format!("Telepad Caps Keyboard {tag}")
        );
        // BUS_VIRTUAL
        assert_eq!(sysfs(&input.mouse, "id/bustype"), "0006");

        let mouse_keys = parse_capability_bitmap(&sysfs(&input.mouse, "capabilities/key"));
        assert_eq!(mouse_keys, [BTN_LEFT, BTN_RIGHT, BTN_MIDDLE].into());
        let mouse_rel = parse_capability_bitmap(&sysfs(&input.mouse, "capabilities/rel"));
        assert_eq!(
            mouse_rel,
            [REL_X, REL_Y, REL_WHEEL, REL_WHEEL_HI_RES].into()
        );

        let kbd_keys = parse_capability_bitmap(&sysfs(&input.keyboard, "capabilities/key"));
        let wanted: std::collections::BTreeSet<u16> = all_supported_keys().into_iter().collect();
        assert_eq!(kbd_keys, wanted);
        let kbd_rel = parse_capability_bitmap(&sysfs(&input.keyboard, "capabilities/rel"));
        assert!(
            kbd_rel.is_empty(),
            "keyboard must not look like a pointer: {kbd_rel:?}"
        );

        // Event types: SYN (0), KEY (1) and, for the mouse only, REL (2).
        let mouse_ev = parse_capability_bitmap(&sysfs(&input.mouse, "capabilities/ev"));
        assert!(mouse_ev.is_superset(&[0, 1, 2].into()), "{mouse_ev:?}");
        let kbd_ev = parse_capability_bitmap(&sysfs(&input.keyboard, "capabilities/ev"));
        assert!(
            kbd_ev.is_superset(&[0, 1].into()) && !kbd_ev.contains(&2),
            "{kbd_ev:?}"
        );
    }

    /// Exercises the real write path with a device the desktop has no use for:
    /// it advertises only `REL_DIAL`, an axis no compositor treats as a mouse or
    /// keyboard, so nothing visible can happen. (Content read-back needs `input`
    /// group access and is covered by the privileged test below; this proves the
    /// kernel accepts our event framing, single events and large bursts alike.)
    #[test]
    fn the_kernel_accepts_event_batches_written_to_a_device() {
        const REL_DIAL: u16 = 0x07;
        let name = format!("Telepad write probe {}", std::process::id());
        let device = match VirtualDevice::create(&name, &[], &[REL_DIAL]) {
            Ok(d) => d,
            Err(e) => {
                eprintln!("skipping device test: {e}");
                return;
            }
        };
        device
            .emit(&[(EV_REL, REL_DIAL, 1), syn()])
            .expect("a well-formed batch is accepted");
        device.emit(&[]).expect("an empty batch is a no-op");
        let burst: Vec<RawEvent> = (0..500)
            .flat_map(|i| [(EV_REL, REL_DIAL, if i % 2 == 0 { 1 } else { -1 }), syn()])
            .collect();
        device
            .emit(&burst)
            .expect("a 1000-event burst is accepted in one write");
    }

    #[test]
    fn long_device_names_are_truncated_not_rejected() {
        let long = "x".repeat(200);
        let device = match VirtualDevice::create(&long, &[BTN_LEFT], &[]) {
            Ok(d) => d,
            Err(e) => {
                eprintln!("skipping device test: {e}");
                return;
            }
        };
        assert_eq!(sysfs(&device, "name").len(), UINPUT_MAX_NAME_SIZE - 1);
    }

    /// Full read-back test. Needs read access to `/dev/input/eventN` (member of
    /// the `input` group, or root), so it skips itself everywhere else.
    #[test]
    fn events_written_to_the_virtual_devices_come_out_of_the_kernel_unchanged() {
        let Some((mut input, mut mouse, mut keyboard)) = backend_and_probes() else {
            return;
        };

        input.mouse_move(12, -7).unwrap();
        input.mouse_button(MouseButtonKind::Left, true).unwrap();
        input.mouse_button(MouseButtonKind::Left, false).unwrap();
        input.scroll(-2).unwrap();
        let mut expected = Vec::new();
        expected.extend(mouse_move_events(12, -7));
        expected.extend(button_events(MouseButtonKind::Left, true));
        expected.extend(button_events(MouseButtonKind::Left, false));
        expected.extend(scroll_events(-2));
        let got = mouse.read(expected.len());
        assert_eq!(got, expected);

        input
            .key(0x06, telepad_protocol::modifiers::LCTRL, true)
            .unwrap();
        input
            .key(0x06, telepad_protocol::modifiers::LCTRL, false)
            .unwrap();
        input.volume(VolumeDirection::Mute).unwrap();
        input.media(MediaAction::PlayPause).unwrap();
        let mut expected = Vec::new();
        expected.extend(key_events(0x06, telepad_protocol::modifiers::LCTRL, true));
        expected.extend(key_events(0x06, telepad_protocol::modifiers::LCTRL, false));
        expected.extend(tap_codes(&[KEY_MUTE]));
        expected.extend(tap_codes(&[KEY_PLAYPAUSE]));
        let got = keyboard.read(expected.len());
        assert_eq!(got, expected);

        input.text("Hi é").unwrap();
        let mut expected = Vec::new();
        for step in plan_text("Hi é", TextEntry::Keys) {
            expected.extend(step_events(step).0);
        }
        let got = keyboard.read(expected.len());
        assert_eq!(got, expected);
    }
}
