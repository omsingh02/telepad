//! The decisions behind the macOS backend, as pure functions.
//!
//! Everything here is plain data in, plain data out, with no CoreGraphics
//! calls. It is compiled on every OS (under `cfg(test)`) so the logic that is
//! easiest to get subtly wrong (modifier flag bookkeeping, click counting, text
//! chunking) is unit-tested in CI regardless of which machine builds it. The
//! thin executor in `macos.rs` only turns these plans into events.

use crate::keymap::macos::{self as mac, hid_to_mac_vk, modifier_flag, ModifierPolicy};
use crate::keymap::modifier_usages;
use std::time::{Duration, Instant};
use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};

/// One keyboard event to post.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct KeyStep {
    pub vk: u16,
    pub down: bool,
    /// `CGEventFlags` modifier bits to attach to this event.
    pub flags: u64,
}

fn flags_of(vks: &[u16]) -> u64 {
    vks.iter().fold(0, |acc, &vk| acc | modifier_flag(vk))
}

/// Plans a key press or release wrapped in the modifiers of `mods`.
///
/// `held` is the set of modifier flags currently held by standalone modifier
/// key presses (the client can send a bare Shift press, for instance); they are
/// attached to every event so a later key sees them. Returns the events to post
/// and the updated `held` mask.
pub(crate) fn plan_key(
    usage: u16,
    mods: u8,
    pressed: bool,
    policy: ModifierPolicy,
    held: u64,
) -> (Vec<KeyStep>, u64) {
    let Some(vk) = hid_to_mac_vk(policy.apply(usage)) else {
        return (Vec::new(), held);
    };
    let mod_vks: Vec<u16> = modifier_usages(mods)
        .filter_map(|u| hid_to_mac_vk(policy.apply(u)))
        .collect();

    let mut steps = Vec::with_capacity(mod_vks.len() + 1);
    if pressed {
        let mut flags = held;
        for &m in &mod_vks {
            flags |= modifier_flag(m);
            steps.push(KeyStep {
                vk: m,
                down: true,
                flags,
            });
        }
        flags |= modifier_flag(vk);
        steps.push(KeyStep {
            vk,
            down: true,
            flags,
        });
    } else {
        // The modifiers are still down while the key itself goes up.
        let mut flags = held | flags_of(&mod_vks);
        steps.push(KeyStep {
            vk,
            down: false,
            flags: flags & !modifier_flag(vk),
        });
        for &m in mod_vks.iter().rev() {
            flags &= !modifier_flag(m);
            steps.push(KeyStep {
                vk: m,
                down: false,
                flags,
            });
        }
    }

    let new_held = if pressed {
        held | modifier_flag(vk)
    } else {
        held & !modifier_flag(vk)
    };
    (steps, new_held)
}

/// Plans a chord: keys go down in order and come up in reverse, with the
/// modifier flags of every key already down attached to each event.
pub(crate) fn plan_chord(vks: &[u16]) -> Vec<KeyStep> {
    let mut steps = Vec::with_capacity(vks.len() * 2);
    let mut flags = 0u64;
    for &vk in vks {
        flags |= modifier_flag(vk);
        steps.push(KeyStep {
            vk,
            down: true,
            flags,
        });
    }
    for &vk in vks.iter().rev() {
        steps.push(KeyStep {
            vk,
            down: false,
            flags,
        });
        flags &= !modifier_flag(vk);
    }
    steps
}

/// A unit of typed text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TextAction {
    /// A real key press, for characters that must not travel as Unicode
    /// strings (applications ignore a newline typed that way).
    Key(u16),
    /// UTF-16 code units posted as the Unicode payload of one key event.
    Chars(Vec<u16>),
}

/// `CGEventKeyboardSetUnicodeString` carries at most this many UTF-16 units per event.
pub(crate) const MAX_UNICODE_UNITS_PER_EVENT: usize = 20;

pub(crate) fn plan_text(text: &str) -> Vec<TextAction> {
    fn flush(chunk: &mut Vec<u16>, actions: &mut Vec<TextAction>) {
        if !chunk.is_empty() {
            actions.push(TextAction::Chars(std::mem::take(chunk)));
        }
    }

    let mut actions = Vec::new();
    let mut chunk: Vec<u16> = Vec::new();
    let mut previous_was_cr = false;
    for c in text.chars() {
        let was_cr = std::mem::replace(&mut previous_was_cr, c == '\r');
        let key = match c {
            '\r' => Some(mac::RETURN),
            // Part of a "\r\n" pair that already produced one Return.
            '\n' if was_cr => continue,
            '\n' => Some(mac::RETURN),
            '\t' => Some(mac::TAB),
            '\u{8}' => Some(mac::DELETE),
            c if c.is_control() => continue,
            _ => None,
        };
        if let Some(vk) = key {
            flush(&mut chunk, &mut actions);
            actions.push(TextAction::Key(vk));
            continue;
        }
        let mut units = [0u16; 2];
        let units = c.encode_utf16(&mut units);
        // Never split a surrogate pair across two events.
        if chunk.len() + units.len() > MAX_UNICODE_UNITS_PER_EVENT {
            flush(&mut chunk, &mut actions);
        }
        chunk.extend_from_slice(units);
    }
    flush(&mut chunk, &mut actions);
    actions
}

/// Counts consecutive clicks the way macOS expects (`kCGMouseEventClickState`):
/// presses of the same button that land close together in time and space form
/// a double or triple click. Without this, applications never see a
/// double-click, because the two presses arrive as unrelated single clicks.
#[derive(Debug, Default)]
pub(crate) struct ClickTracker {
    last: [Option<LastPress>; 3],
}

#[derive(Debug, Clone, Copy)]
struct LastPress {
    at: Instant,
    x: f64,
    y: f64,
    count: i64,
}

impl ClickTracker {
    /// How close together presses must be to chain (the macOS default double-click interval).
    pub const MAX_INTERVAL: Duration = Duration::from_millis(500);
    /// How far the pointer may drift between chained presses, in points.
    pub const MAX_DISTANCE: f64 = 5.0;

    fn slot(button: MouseButtonKind) -> usize {
        match button {
            MouseButtonKind::Left => 0,
            MouseButtonKind::Right => 1,
            MouseButtonKind::Middle => 2,
        }
    }

    /// Registers a press and returns its click count (1 = single, 2 = double, ...).
    pub fn press(&mut self, button: MouseButtonKind, at: Instant, x: f64, y: f64) -> i64 {
        let slot = &mut self.last[Self::slot(button)];
        let count = match *slot {
            Some(prev)
                if at.saturating_duration_since(prev.at) <= Self::MAX_INTERVAL
                    && (x - prev.x).hypot(y - prev.y) <= Self::MAX_DISTANCE =>
            {
                // A fourth rapid click starts over, like a physical mouse's
                // double/triple-click selection cycle.
                if prev.count >= 3 {
                    1
                } else {
                    prev.count + 1
                }
            }
            _ => 1,
        };
        *slot = Some(LastPress { at, x, y, count });
        count
    }

    /// The click count a release should carry: that of the matching press.
    pub fn release(&self, button: MouseButtonKind) -> i64 {
        self.last[Self::slot(button)].map_or(1, |p| p.count)
    }
}

/// Wheel lines per notch. Windows scrolls three lines per notch by default; using
/// the same keeps one flick of the phone feeling alike on every host.
pub(crate) const SCROLL_LINES_PER_NOTCH: i32 = 3;

pub(crate) fn scroll_lines(delta: i16) -> i32 {
    i32::from(delta) * SCROLL_LINES_PER_NOTCH
}

/// A display's bounds as `(x, y, width, height)` in global display coordinates.
pub(crate) type DisplayRect = (f64, f64, f64, f64);

/// Keeps a pointer position inside the bounding box of all displays.
pub(crate) fn clamp_to_displays(x: f64, y: f64, displays: &[DisplayRect]) -> (f64, f64) {
    if displays.is_empty() {
        return (x, y);
    }
    let min_x = displays.iter().map(|d| d.0).fold(f64::INFINITY, f64::min);
    let min_y = displays.iter().map(|d| d.1).fold(f64::INFINITY, f64::min);
    let max_x = displays
        .iter()
        .map(|d| d.0 + d.2)
        .fold(f64::NEG_INFINITY, f64::max);
    let max_y = displays
        .iter()
        .map(|d| d.1 + d.3)
        .fold(f64::NEG_INFINITY, f64::max);
    // A display's far edge is one past its last pixel.
    (
        x.clamp(min_x, (max_x - 1.0).max(min_x)),
        y.clamp(min_y, (max_y - 1.0).max(min_y)),
    )
}

/// `NX_KEYTYPE_*` codes for system-defined media keys (`IOKit/hidsystem/ev_keymap.h`).
pub(crate) mod nx_key {
    pub const SOUND_UP: i64 = 0;
    pub const SOUND_DOWN: i64 = 1;
    pub const MUTE: i64 = 7;
    pub const PLAY: i64 = 16;
    pub const NEXT: i64 = 17;
    pub const PREVIOUS: i64 = 18;
}

/// macOS has no "stop" media key, so `Stop` cannot be honoured.
pub(crate) fn media_key(action: MediaAction) -> Option<i64> {
    match action {
        MediaAction::PlayPause => Some(nx_key::PLAY),
        MediaAction::Next => Some(nx_key::NEXT),
        MediaAction::Prev => Some(nx_key::PREVIOUS),
        MediaAction::Stop => None,
    }
}

pub(crate) fn volume_key(direction: VolumeDirection) -> i64 {
    match direction {
        VolumeDirection::Up => nx_key::SOUND_UP,
        VolumeDirection::Down => nx_key::SOUND_DOWN,
        VolumeDirection::Mute => nx_key::MUTE,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum LaunchPlan {
    Chord(Vec<u16>),
    Open(Vec<String>),
}

pub(crate) fn plan_launch(action: SystemAction, home: &str) -> LaunchPlan {
    let open = |args: &[&str]| {
        let mut argv = vec!["open".to_owned()];
        argv.extend(args.iter().map(|a| (*a).to_owned()));
        LaunchPlan::Open(argv)
    };
    match action {
        // F11 is the default "Show Desktop" shortcut.
        SystemAction::ShowDesktop => LaunchPlan::Chord(vec![mac::F11]),
        SystemAction::TaskView => open(&["-a", "Mission Control"]),
        // Same target the Windows server uses: hand "https://" to the default browser.
        SystemAction::Browser => open(&["https://"]),
        SystemAction::FileManager => open(&[home]),
        SystemAction::TaskManager => open(&["-a", "Activity Monitor"]),
        // Cmd+Shift+5 opens the screenshot toolbar.
        SystemAction::Screenshot => LaunchPlan::Chord(vec![mac::COMMAND, mac::SHIFT, mac::ANSI_5]),
    }
}

/// Ctrl+Cmd+Q locks the screen on macOS 10.13 and later.
pub(crate) fn lock_chord() -> Vec<u16> {
    vec![mac::CONTROL, mac::COMMAND, mac::ANSI_Q]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keymap::macos::flags::{COMMAND, CONTROL, SHIFT};
    use telepad_protocol::modifiers::*;

    fn step(vk: u16, down: bool, flags: u64) -> KeyStep {
        KeyStep { vk, down, flags }
    }

    // ── keys ────────────────────────────────────────────────────────────

    #[test]
    fn plain_key_has_no_flags() {
        let (down, held) = plan_key(0x04, 0, true, ModifierPolicy::Native, 0);
        assert_eq!(down, vec![step(mac::ANSI_A, true, 0)]);
        assert_eq!(held, 0);
        let (up, held) = plan_key(0x04, 0, false, ModifierPolicy::Native, 0);
        assert_eq!(up, vec![step(mac::ANSI_A, false, 0)]);
        assert_eq!(held, 0);
    }

    #[test]
    fn command_c_wraps_the_key_and_carries_the_flag() {
        // Win on the phone is HID left-GUI, which is Command on a Mac.
        let (down, _) = plan_key(0x06, LMETA, true, ModifierPolicy::Native, 0);
        assert_eq!(
            down,
            vec![
                step(mac::COMMAND, true, COMMAND),
                step(mac::ANSI_C, true, COMMAND)
            ]
        );
        let (up, _) = plan_key(0x06, LMETA, false, ModifierPolicy::Native, 0);
        assert_eq!(
            up,
            vec![
                step(mac::ANSI_C, false, COMMAND),
                step(mac::COMMAND, false, 0)
            ]
        );
    }

    #[test]
    fn ctrl_as_command_turns_ctrl_c_into_command_c() {
        let (down, _) = plan_key(0x06, LCTRL, true, ModifierPolicy::CtrlAsCommand, 0);
        assert_eq!(
            down,
            vec![
                step(mac::COMMAND, true, COMMAND),
                step(mac::ANSI_C, true, COMMAND)
            ]
        );
        // ...and Win becomes Control.
        let (down, _) = plan_key(0x06, LMETA, true, ModifierPolicy::CtrlAsCommand, 0);
        assert_eq!(down[0], step(mac::CONTROL, true, CONTROL));
    }

    #[test]
    fn several_modifiers_accumulate_then_unwind_in_reverse() {
        let (down, _) = plan_key(0x1D, LCTRL | LSHIFT, true, ModifierPolicy::Native, 0);
        assert_eq!(
            down,
            vec![
                step(mac::CONTROL, true, CONTROL),
                step(mac::SHIFT, true, CONTROL | SHIFT),
                step(mac::ANSI_Z, true, CONTROL | SHIFT),
            ]
        );
        let (up, _) = plan_key(0x1D, LCTRL | LSHIFT, false, ModifierPolicy::Native, 0);
        assert_eq!(
            up,
            vec![
                step(mac::ANSI_Z, false, CONTROL | SHIFT),
                step(mac::SHIFT, false, CONTROL),
                step(mac::CONTROL, false, 0),
            ]
        );
    }

    #[test]
    fn press_and_release_with_the_same_modifiers_are_balanced() {
        let all = LCTRL | LSHIFT | LALT | LMETA | RCTRL | RSHIFT | RALT | RMETA;
        for mods in [0, LCTRL, LMETA | LALT, all] {
            for policy in [ModifierPolicy::Native, ModifierPolicy::CtrlAsCommand] {
                let mut held = std::collections::HashSet::new();
                let (down, _) = plan_key(0x2C, mods, true, policy, 0);
                let (up, _) = plan_key(0x2C, mods, false, policy, 0);
                for s in down.iter().chain(&up) {
                    if s.down {
                        assert!(held.insert(s.vk), "{:#x} pressed twice", s.vk);
                    } else {
                        assert!(held.remove(&s.vk), "{:#x} released while up", s.vk);
                    }
                }
                assert!(held.is_empty(), "stuck keys: {held:?}");
                // Releasing the last modifier leaves no flags behind.
                assert_eq!(up.last().unwrap().flags, 0);
            }
        }
    }

    #[test]
    fn standalone_modifier_stays_in_effect_for_following_keys() {
        // Press a bare Shift, then 'a': the 'a' must carry the Shift flag.
        let (shift_down, held) = plan_key(0xE1, 0, true, ModifierPolicy::Native, 0);
        assert_eq!(shift_down, vec![step(mac::SHIFT, true, SHIFT)]);
        assert_eq!(held, SHIFT);
        let (a_down, held) = plan_key(0x04, 0, true, ModifierPolicy::Native, held);
        assert_eq!(a_down, vec![step(mac::ANSI_A, true, SHIFT)]);
        assert_eq!(held, SHIFT);
        let (a_up, held) = plan_key(0x04, 0, false, ModifierPolicy::Native, held);
        assert_eq!(a_up, vec![step(mac::ANSI_A, false, SHIFT)]);
        // Releasing Shift clears the flag, including on its own key-up event.
        let (shift_up, held) = plan_key(0xE1, 0, false, ModifierPolicy::Native, held);
        assert_eq!(shift_up, vec![step(mac::SHIFT, false, 0)]);
        assert_eq!(held, 0);
    }

    #[test]
    fn unmapped_usage_does_nothing_and_keeps_held_flags() {
        assert_eq!(
            plan_key(0x00, LCTRL, true, ModifierPolicy::Native, SHIFT),
            (vec![], SHIFT)
        );
        assert_eq!(
            plan_key(0xFFFF, 0, false, ModifierPolicy::Native, 0),
            (vec![], 0)
        );
    }

    #[test]
    fn chords_accumulate_modifier_flags() {
        assert_eq!(
            plan_chord(&[mac::COMMAND, mac::SHIFT, mac::ANSI_5]),
            vec![
                step(mac::COMMAND, true, COMMAND),
                step(mac::SHIFT, true, COMMAND | SHIFT),
                step(mac::ANSI_5, true, COMMAND | SHIFT),
                step(mac::ANSI_5, false, COMMAND | SHIFT),
                step(mac::SHIFT, false, COMMAND | SHIFT),
                step(mac::COMMAND, false, COMMAND),
            ]
        );
        assert_eq!(
            plan_chord(&[mac::F11]),
            vec![step(mac::F11, true, 0), step(mac::F11, false, 0)]
        );
    }

    // ── text ────────────────────────────────────────────────────────────

    fn utf16(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    #[test]
    fn plain_text_is_one_unicode_chunk() {
        assert_eq!(
            plan_text("Hello, wörld"),
            vec![TextAction::Chars(utf16("Hello, wörld"))]
        );
        assert!(plan_text("").is_empty());
    }

    #[test]
    fn newline_tab_and_backspace_are_real_keys() {
        assert_eq!(
            plan_text("a\nb\tc\u{8}"),
            vec![
                TextAction::Chars(utf16("a")),
                TextAction::Key(mac::RETURN),
                TextAction::Chars(utf16("b")),
                TextAction::Key(mac::TAB),
                TextAction::Chars(utf16("c")),
                TextAction::Key(mac::DELETE),
            ]
        );
    }

    #[test]
    fn crlf_is_a_single_return() {
        assert_eq!(plan_text("\r\n"), vec![TextAction::Key(mac::RETURN)]);
        assert_eq!(
            plan_text("\r\n\r\n"),
            vec![TextAction::Key(mac::RETURN), TextAction::Key(mac::RETURN)]
        );
        assert_eq!(
            plan_text("a\n\nb"),
            vec![
                TextAction::Chars(utf16("a")),
                TextAction::Key(mac::RETURN),
                TextAction::Key(mac::RETURN),
                TextAction::Chars(utf16("b")),
            ]
        );
    }

    #[test]
    fn other_control_characters_are_dropped() {
        assert_eq!(
            plan_text("a\u{1b}\u{7f}b"),
            vec![TextAction::Chars(utf16("ab"))]
        );
    }

    #[test]
    fn long_text_is_chunked_at_twenty_utf16_units() {
        let text = "x".repeat(45);
        let actions = plan_text(&text);
        let lens: Vec<usize> = actions
            .iter()
            .map(|a| match a {
                TextAction::Chars(c) => c.len(),
                TextAction::Key(_) => panic!("unexpected key"),
            })
            .collect();
        assert_eq!(lens, vec![20, 20, 5]);
    }

    #[test]
    fn surrogate_pairs_are_never_split_across_chunks() {
        // 19 ASCII units + one emoji (2 units) would overflow a chunk at 21.
        let text = format!("{}🚀", "a".repeat(19));
        let actions = plan_text(&text);
        assert_eq!(actions.len(), 2);
        let TextAction::Chars(first) = &actions[0] else {
            panic!()
        };
        let TextAction::Chars(second) = &actions[1] else {
            panic!()
        };
        assert_eq!(first.len(), 19);
        assert_eq!(*second, utf16("🚀"));
        assert!(second.len() == 2);

        // Round trip: every chunk decodes on its own.
        for a in plan_text(&"🚀".repeat(30)) {
            let TextAction::Chars(units) = a else {
                panic!()
            };
            assert!(units.len() <= MAX_UNICODE_UNITS_PER_EVENT);
            assert!(String::from_utf16(&units).is_ok());
        }
    }

    // ── clicks ──────────────────────────────────────────────────────────

    #[test]
    fn two_quick_presses_make_a_double_click() {
        let mut t = ClickTracker::default();
        let t0 = Instant::now();
        assert_eq!(t.press(MouseButtonKind::Left, t0, 100.0, 100.0), 1);
        assert_eq!(t.release(MouseButtonKind::Left), 1);
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(120),
                100.0,
                100.0
            ),
            2
        );
        assert_eq!(t.release(MouseButtonKind::Left), 2);
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(240),
                101.0,
                99.0
            ),
            3
        );
        // A fourth rapid click starts a new cycle.
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(360),
                100.0,
                100.0
            ),
            1
        );
    }

    #[test]
    fn slow_or_distant_presses_do_not_chain() {
        let mut t = ClickTracker::default();
        let t0 = Instant::now();
        assert_eq!(t.press(MouseButtonKind::Left, t0, 0.0, 0.0), 1);
        // Too slow.
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(900),
                0.0,
                0.0
            ),
            1
        );
        // Too far.
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(1000),
                50.0,
                0.0
            ),
            1
        );
    }

    #[test]
    fn buttons_are_counted_independently() {
        let mut t = ClickTracker::default();
        let t0 = Instant::now();
        assert_eq!(t.press(MouseButtonKind::Left, t0, 0.0, 0.0), 1);
        assert_eq!(t.press(MouseButtonKind::Right, t0, 0.0, 0.0), 1);
        assert_eq!(
            t.press(
                MouseButtonKind::Left,
                t0 + Duration::from_millis(50),
                0.0,
                0.0
            ),
            2
        );
        assert_eq!(t.release(MouseButtonKind::Right), 1);
        assert_eq!(t.release(MouseButtonKind::Middle), 1);
    }

    // ── pointer & scroll ────────────────────────────────────────────────

    #[test]
    fn scroll_scales_notches_to_lines_and_keeps_direction() {
        assert_eq!(scroll_lines(1), 3);
        assert_eq!(scroll_lines(-2), -6);
        assert_eq!(scroll_lines(0), 0);
        assert_eq!(scroll_lines(i16::MAX), 32767 * 3);
    }

    #[test]
    fn pointer_is_clamped_to_the_display_area() {
        let one = [(0.0, 0.0, 1920.0, 1080.0)];
        assert_eq!(clamp_to_displays(500.0, 400.0, &one), (500.0, 400.0));
        assert_eq!(clamp_to_displays(-30.0, -5.0, &one), (0.0, 0.0));
        assert_eq!(clamp_to_displays(5000.0, 5000.0, &one), (1919.0, 1079.0));
        // A second display to the left has negative coordinates.
        let two = [(0.0, 0.0, 1920.0, 1080.0), (-1280.0, 100.0, 1280.0, 720.0)];
        assert_eq!(clamp_to_displays(-2000.0, 0.0, &two), (-1280.0, 0.0));
        assert_eq!(clamp_to_displays(-100.0, 50.0, &two), (-100.0, 50.0));
        assert_eq!(clamp_to_displays(7.0, 8.0, &[]), (7.0, 8.0));
    }

    // ── media & launch ──────────────────────────────────────────────────

    #[test]
    fn media_and_volume_keys() {
        assert_eq!(media_key(MediaAction::PlayPause), Some(16));
        assert_eq!(media_key(MediaAction::Next), Some(17));
        assert_eq!(media_key(MediaAction::Prev), Some(18));
        assert_eq!(media_key(MediaAction::Stop), None);
        assert_eq!(volume_key(VolumeDirection::Up), 0);
        assert_eq!(volume_key(VolumeDirection::Down), 1);
        assert_eq!(volume_key(VolumeDirection::Mute), 7);
    }

    #[test]
    fn launch_plans() {
        assert_eq!(
            plan_launch(SystemAction::TaskView, "/Users/u"),
            LaunchPlan::Open(vec!["open".into(), "-a".into(), "Mission Control".into()])
        );
        assert_eq!(
            plan_launch(SystemAction::FileManager, "/Users/u"),
            LaunchPlan::Open(vec!["open".into(), "/Users/u".into()])
        );
        assert_eq!(
            plan_launch(SystemAction::TaskManager, "/Users/u"),
            LaunchPlan::Open(vec!["open".into(), "-a".into(), "Activity Monitor".into()])
        );
        assert_eq!(
            plan_launch(SystemAction::ShowDesktop, "/h"),
            LaunchPlan::Chord(vec![mac::F11])
        );
        assert_eq!(
            plan_launch(SystemAction::Screenshot, "/h"),
            LaunchPlan::Chord(vec![mac::COMMAND, mac::SHIFT, mac::ANSI_5])
        );
        assert_eq!(lock_chord(), vec![mac::CONTROL, mac::COMMAND, mac::ANSI_Q]);
    }
}
