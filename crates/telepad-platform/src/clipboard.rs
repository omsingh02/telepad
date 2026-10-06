//! Text clipboard access.

use crate::backend::{PlatformError, Result};

/// Reads and writes the system text clipboard.
///
/// Implementations are created and used on one dedicated thread (clipboard
/// handles are not `Send` on every platform), so no thread-safety is required.
pub trait Clipboard {
    /// Current clipboard text; `None` when it is empty or holds something
    /// other than text.
    fn get_text(&mut self) -> Result<Option<String>>;
    fn set_text(&mut self, text: &str) -> Result<()>;
}

/// The operating system's clipboard, via `arboard`.
///
/// One instance is meant to live for the whole life of the server: on X11 and
/// Wayland the application that set the clipboard also serves it to whoever
/// pastes, so dropping the handle right after `set_text` makes the contents
/// vanish.
pub struct SystemClipboard {
    inner: arboard::Clipboard,
}

impl SystemClipboard {
    pub fn new() -> Result<Self> {
        let inner = arboard::Clipboard::new().map_err(clipboard_error)?;
        Ok(Self { inner })
    }
}

impl Clipboard for SystemClipboard {
    fn get_text(&mut self) -> Result<Option<String>> {
        match self.inner.get_text() {
            Ok(text) => Ok(Some(text)),
            Err(arboard::Error::ContentNotAvailable) => Ok(None),
            Err(err) => Err(clipboard_error(err)),
        }
    }

    fn set_text(&mut self, text: &str) -> Result<()> {
        self.inner
            .set_text(text.to_owned())
            .map_err(clipboard_error)
    }
}

fn clipboard_error(err: arboard::Error) -> PlatformError {
    PlatformError::Os(format!("clipboard: {err}"))
}
