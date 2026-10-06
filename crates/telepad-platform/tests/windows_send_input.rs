//! Interactive check that the Windows backend really moves the cursor.
//!
//! Needs a logged-in desktop session, so it is ignored by default:
//! `cargo test -p telepad-platform --test windows_send_input -- --ignored`
#![cfg(windows)]

use telepad_platform::{create_input_backend, BackendOptions};
use windows_sys::Win32::Foundation::POINT;
use windows_sys::Win32::UI::WindowsAndMessaging::GetCursorPos;

fn cursor() -> (i32, i32) {
    let mut point = POINT { x: 0, y: 0 };
    // SAFETY: `point` is a valid out-pointer for the duration of the call.
    unsafe { GetCursorPos(&mut point) };
    (point.x, point.y)
}

#[test]
#[ignore = "requires an interactive desktop and real input injection"]
fn mouse_move_moves_the_cursor() {
    let mut backend = create_input_backend(&BackendOptions::default()).expect("backend");
    let before = cursor();
    backend.mouse_move(50, 50).expect("inject mouse move");
    std::thread::sleep(std::time::Duration::from_millis(100));
    let after = cursor();
    println!("cursor before {before:?}, after {after:?}");
    assert_ne!(before, after);
}
