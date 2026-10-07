//! The Windows way: a value in the current user's `Run` key, which Windows runs at login.

use super::Launch;

/// The name of the value, so that it can be found again.
#[cfg_attr(not(windows), allow(dead_code))]
const VALUE_NAME: &str = "Telepad";

/// The command line Windows runs at login: the program in quotes, then the arguments.
#[cfg_attr(not(windows), allow(dead_code))]
pub(super) fn command_line(launch: &Launch) -> String {
    let mut line = format!("\"{}\"", launch.exe.to_string_lossy());
    for arg in &launch.args {
        line.push(' ');
        line.push_str(&quote_argument(arg));
    }
    line
}

/// One argument, quoted so that `CommandLineToArgvW` reads back exactly what was given.
///
/// The rules are Windows' own and easy to get wrong: backslashes only count as escapes when they come
/// before a double quote (or the closing one), where each one is doubled and the quote gets one more.
#[cfg_attr(not(windows), allow(dead_code))]
pub(super) fn quote_argument(arg: &str) -> String {
    if !arg.is_empty()
        && !arg
            .chars()
            .any(|c| matches!(c, ' ' | '\t' | '\n' | '\x0B' | '"'))
    {
        return arg.to_owned();
    }
    let mut quoted = String::with_capacity(arg.len() + 2);
    quoted.push('"');
    let mut backslashes = 0usize;
    for c in arg.chars() {
        match c {
            '\\' => backslashes += 1,
            '"' => {
                quoted.extend(std::iter::repeat_n('\\', backslashes * 2 + 1));
                quoted.push('"');
                backslashes = 0;
            }
            other => {
                quoted.extend(std::iter::repeat_n('\\', backslashes));
                quoted.push(other);
                backslashes = 0;
            }
        }
    }
    quoted.extend(std::iter::repeat_n('\\', backslashes * 2));
    quoted.push('"');
    quoted
}

#[cfg(windows)]
pub(super) use registry::{disable, enable, is_enabled};

#[cfg(windows)]
mod registry {
    use super::{command_line, Launch, VALUE_NAME};
    use crate::{PlatformError, Result};
    use windows_sys::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS};
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW,
        RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE,
        REG_OPTION_NON_VOLATILE, REG_SZ,
    };

    const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";

    /// UTF-16 with the terminating zero the registry calls want.
    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// An open registry key, closed when dropped.
    struct Key(HKEY);

    impl Drop for Key {
        fn drop(&mut self) {
            // SAFETY: the handle was opened by this module and is closed exactly once, here.
            unsafe { RegCloseKey(self.0) };
        }
    }

    fn open(access: u32) -> std::result::Result<Key, u32> {
        let mut key: HKEY = std::ptr::null_mut();
        let subkey = wide(RUN_KEY);
        // SAFETY: `subkey` is a valid zero-terminated UTF-16 string and `key` a valid out pointer.
        let status =
            unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, subkey.as_ptr(), 0, access, &mut key) };
        if status == ERROR_SUCCESS {
            Ok(Key(key))
        } else {
            Err(status)
        }
    }

    fn failure(what: &str, status: u32) -> PlatformError {
        PlatformError::Os(format!(
            "could not {what} the startup entry (Windows error {status})"
        ))
    }

    pub fn is_enabled() -> bool {
        let Ok(key) = open(KEY_QUERY_VALUE) else {
            return false;
        };
        let name = wide(VALUE_NAME);
        let mut size = 0u32;
        // SAFETY: a null data pointer asks only for the value's size, which is the way to ask whether it exists.
        let status = unsafe {
            RegQueryValueExW(
                key.0,
                name.as_ptr(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                &mut size,
            )
        };
        status == ERROR_SUCCESS
    }

    pub fn enable(launch: &Launch) -> Result<()> {
        // The Run key exists on every Windows, but creating it is harmless if some account lacks it.
        let mut key: HKEY = std::ptr::null_mut();
        let subkey = wide(RUN_KEY);
        // SAFETY: valid zero-terminated strings and out pointers; no class and no security attributes are given.
        let status = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                std::ptr::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                std::ptr::null(),
                &mut key,
                std::ptr::null_mut(),
            )
        };
        if status != ERROR_SUCCESS {
            return Err(failure("write", status));
        }
        let key = Key(key);

        let name = wide(VALUE_NAME);
        let data = wide(&command_line(launch));
        let bytes = u32::try_from(data.len() * 2)
            .map_err(|_| PlatformError::Os("the startup command is too long".into()))?;
        // SAFETY: `data` holds `bytes` bytes of zero-terminated UTF-16, which is what REG_SZ is.
        let status = unsafe {
            RegSetValueExW(
                key.0,
                name.as_ptr(),
                0,
                REG_SZ,
                data.as_ptr().cast::<u8>(),
                bytes,
            )
        };
        if status != ERROR_SUCCESS {
            return Err(failure("write", status));
        }
        Ok(())
    }

    pub fn disable() -> Result<()> {
        let key = match open(KEY_SET_VALUE) {
            Ok(key) => key,
            Err(status) if status == ERROR_FILE_NOT_FOUND => return Ok(()),
            Err(status) => return Err(failure("remove", status)),
        };
        let name = wide(VALUE_NAME);
        // SAFETY: valid key and zero-terminated name.
        let status = unsafe { RegDeleteValueW(key.0, name.as_ptr()) };
        if status == ERROR_SUCCESS || status == ERROR_FILE_NOT_FOUND {
            Ok(())
        } else {
            Err(failure("remove", status))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn launch(exe: &str, args: &[&str]) -> Launch {
        Launch {
            exe: PathBuf::from(exe),
            args: args.iter().map(|a| (*a).to_owned()).collect(),
        }
    }

    #[test]
    fn the_program_is_always_quoted_because_its_path_may_have_spaces() {
        assert_eq!(
            command_line(&launch(r"C:\Program Files\Telepad\telepad.exe", &[])),
            r#""C:\Program Files\Telepad\telepad.exe""#
        );
        assert_eq!(
            command_line(&launch(r"C:\Telepad\telepad.exe", &["--minimized"])),
            r#""C:\Telepad\telepad.exe" --minimized"#
        );
    }

    #[test]
    fn plain_arguments_are_left_alone_and_empty_ones_are_kept() {
        assert_eq!(quote_argument("--port=5000"), "--port=5000");
        assert_eq!(
            quote_argument(r"C:\Users\me\x"),
            r"C:\Users\me\x",
            "backslashes are plain unless a quote follows"
        );
        assert_eq!(quote_argument(""), "\"\"");
    }

    #[test]
    fn arguments_with_spaces_or_quotes_follow_the_rules_of_commandlinetoargvw() {
        // The vectors are the ones in Microsoft's description of the rules.
        assert_eq!(quote_argument("a b"), "\"a b\"");
        assert_eq!(quote_argument("say \"hi\""), "\"say \\\"hi\\\"\"");
        // A backslash before a quote is doubled, and the quote gets one more.
        assert_eq!(quote_argument("a\\\"b"), "\"a\\\\\\\"b\"");
        // Backslashes at the end are doubled so that they do not escape the closing quote.
        assert_eq!(quote_argument("C:\\My Dir\\"), "\"C:\\My Dir\\\\\"");
        assert_eq!(
            quote_argument("\\\\server share\\"),
            "\"\\\\server share\\\\\""
        );
    }
}
