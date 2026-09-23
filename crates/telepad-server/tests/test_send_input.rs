#[test]
#[ignore = "requires interactive desktop and real input injection"]
#[cfg(windows)]
fn test_mouse_move_moves_cursor() {
    use std::mem::size_of;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    use windows_sys::Win32::Foundation::*;

    extern "system" {
        fn OpenInputDesktop(dwflags: u32, finherit: i32, dwdesiredaccess: u32) -> isize;
        fn SetThreadDesktop(hdesktop: isize) -> i32;
        fn CloseDesktop(hdesktop: isize) -> i32;
    }

    unsafe {
        let desk = OpenInputDesktop(0, 0, 0x1FF);
        if desk != 0 {
            SetThreadDesktop(desk);
            CloseDesktop(desk);
        }

        let mut pt_before = POINT { x: 0, y: 0 };
        GetCursorPos(&mut pt_before);

        let mut input = INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                mi: MOUSEINPUT {
                    dx: 50,
                    dy: 50,
                    mouseData: 0,
                    dwFlags: MOUSEEVENTF_MOVE,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        let res = SendInput(1, &mut input, size_of::<INPUT>() as i32);
        let err = GetLastError();
        println!("SendInput res={}, err={}", res, err);

        let mut pt_after = POINT { x: 0, y: 0 };
        GetCursorPos(&mut pt_after);
        println!("Cursor before: ({}, {}), after: ({}, {})", pt_before.x, pt_before.y, pt_after.x, pt_after.y);

        assert_eq!(res, 1);
        assert!(pt_after.x != pt_before.x || pt_after.y != pt_before.y);
    }
}
