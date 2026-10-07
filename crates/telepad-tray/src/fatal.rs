//! Telling the person that something went wrong when there is no terminal to say it in.
//!
//! The tray program has no window, and on Windows no console either, so a message that only goes to the
//! error stream would never be seen and the program would simply not start.

use std::panic::PanicHookInfo;
use std::sync::atomic::{AtomicBool, Ordering};

/// Where a bug is reported.
const ISSUES: &str = "https://github.com/omsingh02/telepad/issues";

/// Shows `message` where a person will see it, as well as on the error stream.
pub fn show(message: &str) {
    eprintln!("telepad: {message}");
    platform::show(message);
}

/// Makes a bug visible. A panic would otherwise end the program, or a part of it, without a word: on Windows
/// there is no console for its message, and what is left of the program would look like it is working.
///
/// Call it first thing in `main`, so that the thread it is called from is known as the main one.
pub fn report_panics() {
    static SHOWN: AtomicBool = AtomicBool::new(false);
    let main_thread = std::thread::current().id();
    let usual = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        usual(info);
        // One message is enough: the second panic is usually the first one's echo.
        if SHOWN.swap(true, Ordering::SeqCst) {
            return;
        }
        let message = format!(
            "Telepad ran into a problem it did not expect, and may have stopped working.\n\n{}\n\n\
             Please report it, with what you were doing: {ISSUES}",
            describe(info)
        );
        if std::thread::current().id() == main_thread {
            // The program ends as this thread unwinds, so the message is shown first.
            platform::show(&message);
        } else {
            // The rest of the program goes on, and the thread that broke must not be held up by a message box.
            let _ = std::thread::Builder::new()
                .name("telepad-panic".into())
                .spawn(move || platform::show(&message));
        }
    }));
}

/// What went wrong and where, in a line.
fn describe(info: &PanicHookInfo<'_>) -> String {
    let what = info
        .payload()
        .downcast_ref::<&str>()
        .map(|text| (*text).to_owned())
        .or_else(|| info.payload().downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "no description".to_owned());
    match info.location() {
        Some(at) => format!("{what} ({}:{})", at.file(), at.line()),
        None => what,
    }
}

#[cfg(windows)]
mod platform {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};

    pub fn show(message: &str) {
        let wide =
            |text: &str| -> Vec<u16> { text.encode_utf16().chain(std::iter::once(0)).collect() };
        let (text, title) = (wide(message), wide("Telepad"));
        // SAFETY: both strings are zero-terminated UTF-16 that outlive the call; there is no owner window.
        unsafe {
            MessageBoxW(
                std::ptr::null_mut(),
                text.as_ptr(),
                title.as_ptr(),
                MB_OK | MB_ICONERROR,
            )
        };
    }
}

#[cfg(target_os = "macos")]
mod platform {
    pub fn show(message: &str) {
        let _ = std::process::Command::new("osascript")
            .arg("-e")
            .arg(format!(
                "display alert \"Telepad\" message \"{}\" as critical",
                applescript_text(message)
            ))
            .status();
    }

    /// Text for inside a double-quoted AppleScript string.
    pub fn applescript_text(text: &str) -> String {
        text.replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "\\n")
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn quotes_and_backslashes_cannot_end_the_string() {
            assert_eq!(
                applescript_text("say \"hi\" \\ now"),
                "say \\\"hi\\\" \\\\ now"
            );
            assert_eq!(applescript_text("a\nb"), "a\\nb");
        }
    }
}

#[cfg(not(any(windows, target_os = "macos")))]
mod platform {
    pub fn show(message: &str) {
        // A desktop notification, if there is a way to send one; the error stream has been written to anyway.
        let _ = std::process::Command::new("notify-send")
            .args([
                "--urgency=critical",
                "--app-name=Telepad",
                "Telepad could not start",
                message,
            ])
            .status();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[test]
    fn a_panic_is_described_by_what_it_said_and_where() {
        static SEEN: Mutex<Vec<String>> = Mutex::new(Vec::new());
        let usual = std::panic::take_hook();
        std::panic::set_hook(Box::new(|info| SEEN.lock().unwrap().push(describe(info))));
        let _ = std::panic::catch_unwind(|| panic!("the {} broke", "thing"));
        let _ = std::panic::catch_unwind(|| std::panic::panic_any(42u8));
        std::panic::set_hook(usual);

        let seen = SEEN.lock().unwrap();
        let said = seen
            .iter()
            .find(|line| line.starts_with("the thing broke"))
            .unwrap();
        assert!(said.contains("fatal.rs:"), "the place is named: {said}");
        assert!(
            seen.iter().any(|line| line.starts_with("no description (")),
            "a payload that is not text still gets a line: {seen:?}"
        );
    }
}
