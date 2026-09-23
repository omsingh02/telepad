#[cfg(windows)]
pub mod platform {
    use std::mem::size_of;
    use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};
    use tracing::warn;
    use windows_sys::Win32::Foundation::GetLastError;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;

    extern "system" {
        fn OpenInputDesktop(dwflags: u32, finherit: i32, dwdesiredaccess: u32) -> isize;
        fn SetThreadDesktop(hdesktop: isize) -> i32;
        fn CloseDesktop(hdesktop: isize) -> i32;
    }

    /// Attaches the calling thread to the currently active input desktop.
    /// This is strictly required on Windows: background thread pools (e.g. Tokio)
    /// will otherwise fail SendInput calls with ERROR_ACCESS_DENIED (5).
    ///
    /// Uses a thread-local flag so the (relatively expensive) Win32 calls only
    /// happen once per OS thread, not on every input event.
    pub fn ensure_input_desktop() {
        thread_local! {
            static DONE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
        }
        DONE.with(|done| {
            if done.get() {
                return;
            }
            unsafe {
                let desk = OpenInputDesktop(0, 0, 0x1FF /* MAXIMUM_ALLOWED / GENERIC_ALL */);
                if desk != 0 {
                    SetThreadDesktop(desk);
                    CloseDesktop(desk);
                }
            }
            done.set(true);
        });
    }

    /// Converts USB HID Usage Page 0x07 (Keyboard/Keypad) to Windows Virtual Key (VK) codes.
    /// Specification: USB HID Usage Tables v1.4, Section 10.
    pub fn hid_to_vk(hid: u16) -> u16 {
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


    pub fn send_mouse_move(dx: i16, dy: i16) {
        unsafe {
            ensure_input_desktop();
            let mut input = INPUT {
                r#type: INPUT_MOUSE,
                Anonymous: INPUT_0 {
                    mi: MOUSEINPUT {
                        dx: dx as i32,
                        dy: dy as i32,
                        mouseData: 0,
                        dwFlags: MOUSEEVENTF_MOVE,
                        time: 0,
                        dwExtraInfo: 0,
                    },
                },
            };
            let res = SendInput(1, &mut input, size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_mouse_move SendInput failed: {}", GetLastError());
            }
        }
    }

    pub fn send_mouse_button(button: MouseButtonKind, pressed: bool) {
        let flags = match (button, pressed) {
            (MouseButtonKind::Left, true) => MOUSEEVENTF_LEFTDOWN,
            (MouseButtonKind::Left, false) => MOUSEEVENTF_LEFTUP,
            (MouseButtonKind::Right, true) => MOUSEEVENTF_RIGHTDOWN,
            (MouseButtonKind::Right, false) => MOUSEEVENTF_RIGHTUP,
            (MouseButtonKind::Middle, true) => MOUSEEVENTF_MIDDLEDOWN,
            (MouseButtonKind::Middle, false) => MOUSEEVENTF_MIDDLEUP,
        };

        unsafe {
            ensure_input_desktop();
            let mut input = INPUT {
                r#type: INPUT_MOUSE,
                Anonymous: INPUT_0 {
                    mi: MOUSEINPUT {
                        dx: 0,
                        dy: 0,
                        mouseData: 0,
                        dwFlags: flags,
                        time: 0,
                        dwExtraInfo: 0,
                    },
                },
            };
            let res = SendInput(1, &mut input, size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_mouse_button SendInput failed: {}", GetLastError());
            }
        }
    }

    const WHEEL_DELTA: i32 = 120;

    pub fn send_scroll(delta: i16) {
        unsafe {
            ensure_input_desktop();
            let mut input = INPUT {
                r#type: INPUT_MOUSE,
                Anonymous: INPUT_0 {
                    mi: MOUSEINPUT {
                        dx: 0,
                        dy: 0,
                        mouseData: ((delta as i32) * WHEEL_DELTA) as u32,
                        dwFlags: MOUSEEVENTF_WHEEL,
                        time: 0,
                        dwExtraInfo: 0,
                    },
                },
            };
            let res = SendInput(1, &mut input, size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_scroll SendInput failed: {}", GetLastError());
            }
        }
    }

    pub fn send_key(keycode: u16, mods: u8, pressed: bool) {
        let vk = hid_to_vk(keycode);
        if vk == 0 {
            return;
        }
        unsafe {
            ensure_input_desktop();
            // Batch modifier + key into one SendInput call to prevent
            // injection races between modifier-down and key-down.
            let mut inputs: Vec<INPUT> = Vec::with_capacity(10);
            let key_flags = if pressed { 0 } else { KEYEVENTF_KEYUP };
            let mod_flags = if pressed { 0 } else { KEYEVENTF_KEYUP };

            // Press modifiers before key-down, release after key-up
            if pressed && mods != 0 {
                let pairs = [
                    (0x01u8, VK_LCONTROL), (0x02, VK_LSHIFT), (0x04, VK_LMENU),
                    (0x08, VK_LWIN), (0x10, VK_RCONTROL), (0x20, VK_RSHIFT),
                    (0x40, VK_RMENU), (0x80, VK_RWIN),
                ];
                for (mask, mvk) in pairs {
                    if (mods & mask) != 0 {
                        inputs.push(make_key_input(mvk, mod_flags));
                    }
                }
            }

            inputs.push(make_key_input(vk, key_flags));

            if !pressed && mods != 0 {
                let pairs = [
                    (0x01u8, VK_LCONTROL), (0x02, VK_LSHIFT), (0x04, VK_LMENU),
                    (0x08, VK_LWIN), (0x10, VK_RCONTROL), (0x20, VK_RSHIFT),
                    (0x40, VK_RMENU), (0x80, VK_RWIN),
                ];
                for (mask, mvk) in pairs {
                    if (mods & mask) != 0 {
                        inputs.push(make_key_input(mvk, KEYEVENTF_KEYUP));
                    }
                }
            }

            let n = inputs.len() as u32;
            let res = SendInput(n, inputs.as_mut_ptr(), size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_key SendInput failed: {}", GetLastError());
            }
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

    #[inline]
    fn make_key_input(vk: u16, flags: u32) -> INPUT {
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

    fn send_vk_momentary(vk: u16) {
        unsafe {
            ensure_input_desktop();
            let mut inputs = [
                make_key_input(vk, 0),
                make_key_input(vk, KEYEVENTF_KEYUP),
            ];
            let res = SendInput(2, inputs.as_mut_ptr(), size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_vk_momentary SendInput failed: {}", GetLastError());
            }
        }
    }

    pub fn send_media_cmd(action: MediaAction) {
        let vk = match action {
            MediaAction::PlayPause => VK_MEDIA_PLAY_PAUSE,
            MediaAction::Next => VK_MEDIA_NEXT_TRACK,
            MediaAction::Prev => VK_MEDIA_PREV_TRACK,
            MediaAction::Stop => VK_MEDIA_STOP,
        };
        send_vk_momentary(vk);
    }

    pub fn send_volume_cmd(direction: VolumeDirection) {
        let vk = match direction {
            VolumeDirection::Up => VK_VOLUME_UP,
            VolumeDirection::Down => VK_VOLUME_DOWN,
            VolumeDirection::Mute => VK_VOLUME_MUTE,
        };
        send_vk_momentary(vk);
    }

    pub fn send_text(text: &str) {
        unsafe {
            ensure_input_desktop();
            // Batch all characters into one SendInput call (down+up per char).
            // Win32 docs: "SendInput inserts events serially" — one call for
            // the whole string avoids 2N kernel transitions.
            let chars: Vec<u16> = text.encode_utf16().collect();
            let mut inputs: Vec<INPUT> = Vec::with_capacity(chars.len() * 2);
            for ch in &chars {
                inputs.push(INPUT {
                    r#type: INPUT_KEYBOARD,
                    Anonymous: INPUT_0 {
                        ki: KEYBDINPUT {
                            wVk: 0,
                            wScan: *ch,
                            dwFlags: KEYEVENTF_UNICODE,
                            time: 0,
                            dwExtraInfo: 0,
                        },
                    },
                });
                inputs.push(INPUT {
                    r#type: INPUT_KEYBOARD,
                    Anonymous: INPUT_0 {
                        ki: KEYBDINPUT {
                            wVk: 0,
                            wScan: *ch,
                            dwFlags: KEYEVENTF_UNICODE | KEYEVENTF_KEYUP,
                            time: 0,
                            dwExtraInfo: 0,
                        },
                    },
                });
            }
            if !inputs.is_empty() {
                let n = inputs.len() as u32;
                let res = SendInput(n, inputs.as_mut_ptr(), size_of::<INPUT>() as i32);
                if res == 0 {
                    warn!("send_text SendInput failed: {}", GetLastError());
                }
            }
        }
    }

    pub fn send_launch_action(action: SystemAction) {
        ensure_input_desktop();
        match action {
            SystemAction::ShowDesktop => {
                // Win + D
                send_vk_combo(&[VK_LWIN, b'D' as u16]);
            }
            SystemAction::TaskView => {
                // Win + Tab
                send_vk_combo(&[VK_LWIN, VK_TAB]);
            }
            SystemAction::Browser => {
                let _ = std::process::Command::new("explorer").arg("https://").spawn();
            }
            SystemAction::FileManager => {
                // Win + E
                send_vk_combo(&[VK_LWIN, b'E' as u16]);
            }
            SystemAction::TaskManager => {
                // Ctrl + Shift + Esc
                send_vk_combo(&[VK_LCONTROL, VK_LSHIFT, VK_ESCAPE]);
            }
            SystemAction::Screenshot => {
                // Win + Shift + S
                send_vk_combo(&[VK_LWIN, VK_LSHIFT, b'S' as u16]);
            }
        }
    }

    fn send_vk_combo(keys: &[u16]) {
        unsafe {
            ensure_input_desktop();
            // Batch all key presses + releases into one SendInput call.
            // Press in order, release in reverse — all atomic.
            let mut inputs: Vec<INPUT> = Vec::with_capacity(keys.len() * 2);
            for &vk in keys {
                inputs.push(make_key_input(vk, 0));
            }
            for &vk in keys.iter().rev() {
                inputs.push(make_key_input(vk, KEYEVENTF_KEYUP));
            }
            let n = inputs.len() as u32;
            let res = SendInput(n, inputs.as_mut_ptr(), size_of::<INPUT>() as i32);
            if res == 0 {
                warn!("send_vk_combo SendInput failed: {}", GetLastError());
            }
        }
    }

    pub fn lock_workstation() {
        unsafe {
            windows_sys::Win32::System::Shutdown::LockWorkStation();
        }
    }
}

#[cfg(not(windows))]
pub mod platform {
    use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};

    pub fn send_mouse_move(_dx: i16, _dy: i16) {}
    pub fn send_mouse_button(_button: MouseButtonKind, _pressed: bool) {}
    pub fn send_scroll(_delta: i16) {}
    pub fn send_key(_keycode: u16, _mods: u8, _pressed: bool) {}
    pub fn send_media_cmd(_action: MediaAction) {}
    pub fn send_volume_cmd(_direction: VolumeDirection) {}
    pub fn send_text(_text: &str) {}
    pub fn send_launch_action(_action: SystemAction) {}
    pub fn lock_workstation() {}
}
