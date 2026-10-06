//! Windows input injection through `SendInput`.

use crate::backend::{InputBackend, PlatformError, Result};
use crate::keymap::modifier_usages;
use std::mem::size_of;
use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};
use windows_sys::Win32::Foundation::GetLastError;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;

extern "system" {
    fn OpenInputDesktop(dwflags: u32, finherit: i32, dwdesiredaccess: u32) -> isize;
    fn SetThreadDesktop(hdesktop: isize) -> i32;
    fn CloseDesktop(hdesktop: isize) -> i32;
}

const WHEEL_DELTA: i32 = 120;

thread_local! {
    static DESKTOP_ATTACHED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Attaches the calling thread to the currently active input desktop.
///
/// This is strictly required on Windows: threads other than the one that owns
/// the interactive desktop (for example a worker thread) otherwise get
/// `ERROR_ACCESS_DENIED` from `SendInput`. The relatively expensive Win32 calls
/// are made once per OS thread, not on every input event.
fn ensure_input_desktop() {
    DESKTOP_ATTACHED.with(|attached| {
        if attached.get() {
            return;
        }
        // SAFETY: plain Win32 calls with valid arguments; the handle is closed
        // immediately after the thread has been attached to the desktop.
        unsafe {
            let desktop = OpenInputDesktop(0, 0, 0x1FF /* MAXIMUM_ALLOWED / GENERIC_ALL */);
            if desktop != 0 {
                SetThreadDesktop(desktop);
                CloseDesktop(desktop);
            }
        }
        attached.set(true);
    });
}

/// The input desktop changes when the workstation is locked, a UAC prompt
/// appears, or the user switches accounts. Forgetting the attachment makes the
/// next [`ensure_input_desktop`] pick up the new one.
fn reattach_input_desktop() {
    DESKTOP_ATTACHED.with(|attached| attached.set(false));
    ensure_input_desktop();
}

/// Converts USB HID Usage Page 0x07 (Keyboard/Keypad) to Windows Virtual Key codes.
/// Specification: USB HID Usage Tables v1.4, Section 10.
pub(crate) fn hid_to_vk(hid: u16) -> u16 {
    match hid {
        0x04..=0x1D => (b'A' + (hid - 0x04) as u8) as u16,
        0x1E..=0x26 => (b'1' + (hid - 0x1E) as u8) as u16,
        0x27 => b'0' as u16,
        0x28 => VK_RETURN,
        0x29 => VK_ESCAPE,
        0x2A => VK_BACK,
        0x2B => VK_TAB,
        0x2C => VK_SPACE,
        0x2D => VK_OEM_MINUS,
        0x2E => VK_OEM_PLUS,
        0x2F => VK_OEM_4,
        0x30 => VK_OEM_6,
        0x31 => VK_OEM_5,
        0x33 => VK_OEM_1,
        0x34 => VK_OEM_7,
        0x35 => VK_OEM_3,
        0x36 => VK_OEM_COMMA,
        0x37 => VK_OEM_PERIOD,
        0x38 => VK_OEM_2,
        0x39 => VK_CAPITAL,
        0x3A..=0x45 => VK_F1 + (hid - 0x3A),
        0x46 => VK_SNAPSHOT,
        0x47 => VK_SCROLL,
        0x48 => VK_PAUSE,
        0x49 => VK_INSERT,
        0x4A => VK_HOME,
        0x4B => VK_PRIOR,
        0x4C => VK_DELETE,
        0x4D => VK_END,
        0x4E => VK_NEXT,
        0x4F => VK_RIGHT,
        0x50 => VK_LEFT,
        0x51 => VK_DOWN,
        0x52 => VK_UP,
        0x53 => VK_NUMLOCK,
        0x54 => VK_DIVIDE,
        0x55 => VK_MULTIPLY,
        0x56 => VK_SUBTRACT,
        0x57 => VK_ADD,
        0x58 => VK_RETURN,
        0x59..=0x62 => VK_NUMPAD1 + (hid - 0x59),
        0x63 => VK_DECIMAL,
        0x65 => VK_APPS,
        0xE0 => VK_LCONTROL,
        0xE1 => VK_LSHIFT,
        0xE2 => VK_LMENU,
        0xE3 => VK_LWIN,
        0xE4 => VK_RCONTROL,
        0xE5 => VK_RSHIFT,
        0xE6 => VK_RMENU,
        0xE7 => VK_RWIN,
        _ => 0,
    }
}

fn is_extended_key(vk: u16) -> bool {
    matches!(
        vk,
        VK_UP
            | VK_DOWN
            | VK_LEFT
            | VK_RIGHT
            | VK_INSERT
            | VK_DELETE
            | VK_HOME
            | VK_END
            | VK_PRIOR
            | VK_NEXT
            | VK_RCONTROL
            | VK_RMENU
            | VK_LWIN
            | VK_RWIN
            | VK_APPS
            | VK_DIVIDE
            | VK_SNAPSHOT
    )
}

fn key_input(vk: u16, flags: u32) -> INPUT {
    let extended = if is_extended_key(vk) {
        KEYEVENTF_EXTENDEDKEY
    } else {
        0
    };
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: 0,
                dwFlags: flags | extended,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

fn unicode_input(unit: u16, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: 0,
                wScan: unit,
                dwFlags: KEYEVENTF_UNICODE | flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

fn mouse_input(dx: i32, dy: i32, mouse_data: u32, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx,
                dy,
                mouseData: mouse_data,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

/// Injects `inputs` as one atomic batch. `what` names the operation in errors.
fn send_inputs(inputs: &mut [INPUT], what: &str) -> Result<()> {
    if inputs.is_empty() {
        return Ok(());
    }
    ensure_input_desktop();
    let count = inputs.len() as u32;

    let send = |inputs: &mut [INPUT]| -> u32 {
        // SAFETY: `inputs` is a valid, initialised slice of INPUT structures and
        // the size argument is the size of one element.
        unsafe { SendInput(count, inputs.as_mut_ptr(), size_of::<INPUT>() as i32) }
    };

    let mut sent = send(inputs);
    if sent == 0 {
        // The active input desktop may have changed (lock screen, UAC prompt,
        // fast user switching). Re-attach once and retry before giving up.
        reattach_input_desktop();
        sent = send(inputs);
    }
    if sent == count {
        return Ok(());
    }
    // SAFETY: trivially safe; reads the calling thread's last-error value.
    let error = unsafe { GetLastError() };
    Err(PlatformError::Os(format!(
        "{what}: SendInput accepted {sent} of {count} events (error {error})"
    )))
}

/// Presses the keys in order and releases them in reverse, as one batch.
fn send_chord(keys: &[u16], what: &str) -> Result<()> {
    let mut inputs: Vec<INPUT> = Vec::with_capacity(keys.len() * 2);
    inputs.extend(keys.iter().map(|&vk| key_input(vk, 0)));
    inputs.extend(keys.iter().rev().map(|&vk| key_input(vk, KEYEVENTF_KEYUP)));
    send_inputs(&mut inputs, what)
}

pub struct WindowsInput;

impl WindowsInput {
    pub fn new() -> Self {
        Self
    }
}

impl Default for WindowsInput {
    fn default() -> Self {
        Self::new()
    }
}

impl InputBackend for WindowsInput {
    fn name(&self) -> &'static str {
        "Windows SendInput"
    }

    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()> {
        send_inputs(
            &mut [mouse_input(dx.into(), dy.into(), 0, MOUSEEVENTF_MOVE)],
            "mouse move",
        )
    }

    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()> {
        let flags = match (button, pressed) {
            (MouseButtonKind::Left, true) => MOUSEEVENTF_LEFTDOWN,
            (MouseButtonKind::Left, false) => MOUSEEVENTF_LEFTUP,
            (MouseButtonKind::Right, true) => MOUSEEVENTF_RIGHTDOWN,
            (MouseButtonKind::Right, false) => MOUSEEVENTF_RIGHTUP,
            (MouseButtonKind::Middle, true) => MOUSEEVENTF_MIDDLEDOWN,
            (MouseButtonKind::Middle, false) => MOUSEEVENTF_MIDDLEUP,
        };
        send_inputs(&mut [mouse_input(0, 0, 0, flags)], "mouse button")
    }

    fn scroll(&mut self, delta: i16) -> Result<()> {
        // A negative wheel delta is carried as the two's-complement DWORD.
        let data = (i32::from(delta) * WHEEL_DELTA) as u32;
        send_inputs(&mut [mouse_input(0, 0, data, MOUSEEVENTF_WHEEL)], "scroll")
    }

    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()> {
        let vk = hid_to_vk(usage);
        if vk == 0 {
            return Ok(());
        }
        let modifier_vks: Vec<u16> = modifier_usages(mods)
            .map(hid_to_vk)
            .filter(|&vk| vk != 0)
            .collect();

        // One SendInput call for modifiers + key avoids races between the
        // modifier going down and the key arriving.
        let mut inputs: Vec<INPUT> = Vec::with_capacity(modifier_vks.len() + 1);
        if pressed {
            inputs.extend(modifier_vks.iter().map(|&m| key_input(m, 0)));
            inputs.push(key_input(vk, 0));
        } else {
            inputs.push(key_input(vk, KEYEVENTF_KEYUP));
            inputs.extend(
                modifier_vks
                    .iter()
                    .rev()
                    .map(|&m| key_input(m, KEYEVENTF_KEYUP)),
            );
        }
        send_inputs(&mut inputs, "key")
    }

    fn text(&mut self, text: &str) -> Result<()> {
        // Every UTF-16 code unit goes down and up as a Unicode keystroke, all in
        // one call ("SendInput inserts events serially"), which avoids 2N kernel
        // transitions. Surrogate pairs are sent unit by unit, as Windows expects.
        let mut inputs: Vec<INPUT> = Vec::with_capacity(text.len() * 2);
        for unit in text.encode_utf16() {
            inputs.push(unicode_input(unit, 0));
            inputs.push(unicode_input(unit, KEYEVENTF_KEYUP));
        }
        send_inputs(&mut inputs, "text")
    }

    fn media(&mut self, action: MediaAction) -> Result<()> {
        let vk = match action {
            MediaAction::PlayPause => VK_MEDIA_PLAY_PAUSE,
            MediaAction::Next => VK_MEDIA_NEXT_TRACK,
            MediaAction::Prev => VK_MEDIA_PREV_TRACK,
            MediaAction::Stop => VK_MEDIA_STOP,
        };
        send_chord(&[vk], "media key")
    }

    fn volume(&mut self, direction: VolumeDirection) -> Result<()> {
        let vk = match direction {
            VolumeDirection::Up => VK_VOLUME_UP,
            VolumeDirection::Down => VK_VOLUME_DOWN,
            VolumeDirection::Mute => VK_VOLUME_MUTE,
        };
        send_chord(&[vk], "volume key")
    }

    fn launch(&mut self, action: SystemAction) -> Result<()> {
        match action {
            SystemAction::ShowDesktop => send_chord(&[VK_LWIN, b'D' as u16], "show desktop"),
            SystemAction::TaskView => send_chord(&[VK_LWIN, VK_TAB], "task view"),
            SystemAction::FileManager => send_chord(&[VK_LWIN, b'E' as u16], "file manager"),
            SystemAction::TaskManager => {
                send_chord(&[VK_LCONTROL, VK_LSHIFT, VK_ESCAPE], "task manager")
            }
            SystemAction::Screenshot => {
                send_chord(&[VK_LWIN, VK_LSHIFT, b'S' as u16], "screenshot")
            }
            SystemAction::Browser => {
                std::process::Command::new("explorer")
                    .arg("https://")
                    .spawn()?;
                Ok(())
            }
        }
    }

    fn lock_screen(&mut self) -> Result<()> {
        // SAFETY: `LockWorkStation` takes no arguments.
        let ok = unsafe { windows_sys::Win32::System::Shutdown::LockWorkStation() };
        if ok == 0 {
            // SAFETY: trivially safe; reads the calling thread's last-error value.
            let error = unsafe { GetLastError() };
            return Err(PlatformError::Os(format!(
                "LockWorkStation failed (error {error})"
            )));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn letters_digits_and_modifiers_translate() {
        assert_eq!(hid_to_vk(0x04), b'A' as u16);
        assert_eq!(hid_to_vk(0x1D), b'Z' as u16);
        assert_eq!(hid_to_vk(0x1E), b'1' as u16);
        assert_eq!(hid_to_vk(0x26), b'9' as u16);
        assert_eq!(hid_to_vk(0x27), b'0' as u16);
        assert_eq!(hid_to_vk(0xE0), VK_LCONTROL);
        assert_eq!(hid_to_vk(0xE3), VK_LWIN);
        assert_eq!(hid_to_vk(0xE7), VK_RWIN);
    }

    #[test]
    fn function_keys_and_keypad_are_contiguous() {
        assert_eq!(hid_to_vk(0x3A), VK_F1);
        assert_eq!(hid_to_vk(0x45), VK_F12);
        assert_eq!(hid_to_vk(0x59), VK_NUMPAD1);
        assert_eq!(hid_to_vk(0x62), VK_NUMPAD1 + 9);
    }

    #[test]
    fn every_usage_the_android_client_can_send_is_mapped() {
        // HidKeyCodes.kt never sends the two non-US keys (0x32 and 0x64), which
        // sit inside this range and are deliberately left unmapped on Windows.
        for usage in (0x04..=0x65u16)
            .filter(|u| ![0x32, 0x64].contains(u))
            .chain(0xE0..=0xE7)
        {
            assert_ne!(hid_to_vk(usage), 0, "usage {usage:#x} unmapped");
        }
        assert_eq!(hid_to_vk(0x00), 0);
        assert_eq!(hid_to_vk(0xFF), 0);
    }

    #[test]
    fn navigation_keys_are_marked_extended() {
        for vk in [
            VK_UP, VK_DOWN, VK_LEFT, VK_RIGHT, VK_INSERT, VK_DELETE, VK_HOME, VK_END,
        ] {
            assert!(is_extended_key(vk));
            let input = key_input(vk, 0);
            // SAFETY: `ki` is the active union member for keyboard input.
            assert_ne!(
                unsafe { input.Anonymous.ki.dwFlags } & KEYEVENTF_EXTENDEDKEY,
                0
            );
        }
        assert!(!is_extended_key(b'A' as u16));
        assert!(!is_extended_key(VK_RETURN));
    }
}
