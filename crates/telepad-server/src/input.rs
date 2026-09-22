#[cfg(windows)]
pub mod platform {
    use std::mem::size_of;
    use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;

    pub fn send_mouse_move(dx: i16, dy: i16) {
        unsafe {
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
            SendInput(1, &mut input, size_of::<INPUT>() as i32);
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
            SendInput(1, &mut input, size_of::<INPUT>() as i32);
        }
    }

    const WHEEL_DELTA: i32 = 120;

    pub fn send_scroll(delta: i16) {
        unsafe {
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
            SendInput(1, &mut input, size_of::<INPUT>() as i32);
        }
    }

    pub fn send_key(vk: u16, pressed: bool) {
        unsafe {
            let flags = if pressed { 0 } else { KEYEVENTF_KEYUP };
            let mut input = INPUT {
                r#type: INPUT_KEYBOARD,
                Anonymous: INPUT_0 {
                    ki: KEYBDINPUT {
                        wVk: vk,
                        wScan: 0,
                        dwFlags: flags,
                        time: 0,
                        dwExtraInfo: 0,
                    },
                },
            };
            SendInput(1, &mut input, size_of::<INPUT>() as i32);
        }
    }

    pub fn send_media_cmd(action: MediaAction) {
        let vk = match action {
            MediaAction::PlayPause => VK_MEDIA_PLAY_PAUSE,
            MediaAction::Next => VK_MEDIA_NEXT_TRACK,
            MediaAction::Prev => VK_MEDIA_PREV_TRACK,
            MediaAction::Stop => VK_MEDIA_STOP,
        };
        send_key(vk, true);
        send_key(vk, false);
    }

    pub fn send_volume_cmd(direction: VolumeDirection) {
        let vk = match direction {
            VolumeDirection::Up => VK_VOLUME_UP,
            VolumeDirection::Down => VK_VOLUME_DOWN,
            VolumeDirection::Mute => VK_VOLUME_MUTE,
        };
        send_key(vk, true);
        send_key(vk, false);
    }

    pub fn send_text(text: &str) {
        for ch in text.encode_utf16() {
            unsafe {
                let mut down = INPUT {
                    r#type: INPUT_KEYBOARD,
                    Anonymous: INPUT_0 {
                        ki: KEYBDINPUT {
                            wVk: 0,
                            wScan: ch,
                            dwFlags: KEYEVENTF_UNICODE,
                            time: 0,
                            dwExtraInfo: 0,
                        },
                    },
                };
                let mut up = INPUT {
                    r#type: INPUT_KEYBOARD,
                    Anonymous: INPUT_0 {
                        ki: KEYBDINPUT {
                            wVk: 0,
                            wScan: ch,
                            dwFlags: KEYEVENTF_UNICODE | KEYEVENTF_KEYUP,
                            time: 0,
                            dwExtraInfo: 0,
                        },
                    },
                };
                SendInput(1, &mut down, size_of::<INPUT>() as i32);
                SendInput(1, &mut up, size_of::<INPUT>() as i32);
            }
        }
    }

    pub fn send_launch_action(action: SystemAction) {
        match action {
            SystemAction::ShowDesktop => {
                // Win + D
                send_key(VK_LWIN, true);
                send_key(b'D' as u16, true);
                send_key(b'D' as u16, false);
                send_key(VK_LWIN, false);
            }
            SystemAction::TaskView => {
                // Win + Tab
                send_key(VK_LWIN, true);
                send_key(VK_TAB, true);
                send_key(VK_TAB, false);
                send_key(VK_LWIN, false);
            }
            SystemAction::Browser => {
                let _ = std::process::Command::new("explorer").arg("https://").spawn();
            }
            SystemAction::FileManager => {
                // Win + E
                send_key(VK_LWIN, true);
                send_key(b'E' as u16, true);
                send_key(b'E' as u16, false);
                send_key(VK_LWIN, false);
            }
            SystemAction::TaskManager => {
                // Ctrl + Shift + Esc
                send_key(VK_LCONTROL, true);
                send_key(VK_LSHIFT, true);
                send_key(VK_ESCAPE, true);
                send_key(VK_ESCAPE, false);
                send_key(VK_LSHIFT, false);
                send_key(VK_LCONTROL, false);
            }
            SystemAction::Screenshot => {
                // Win + Shift + S
                send_key(VK_LWIN, true);
                send_key(VK_LSHIFT, true);
                send_key(b'S' as u16, true);
                send_key(b'S' as u16, false);
                send_key(VK_LSHIFT, false);
                send_key(VK_LWIN, false);
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
    pub fn send_key(_vk: u16, _pressed: bool) {}
    pub fn send_media_cmd(_action: MediaAction) {}
    pub fn send_volume_cmd(_direction: VolumeDirection) {}
    pub fn send_text(_text: &str) {}
    pub fn send_launch_action(_action: SystemAction) {}
    pub fn lock_workstation() {}
}
