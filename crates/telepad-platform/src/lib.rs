//! OS integration for the Telepad desktop server.
//!
//! Everything that differs between Windows, Linux and macOS lives behind the
//! small interfaces in this crate, so the server itself is plain portable
//! networking code:
//!
//! * [`InputBackend`]: inject mouse, keyboard, media and system actions.
//! * [`clipboard`]: read and write the text clipboard.
//! * [`netif`]: enumerate local IPv4 networks for discovery.
//! * [`paths`]: where per-user configuration lives.
//! * [`autostart`]: starting at login.
//! * [`access`]: whether the system lets Telepad type, and asking it to.

pub mod access;
pub mod autostart;
pub mod backend;
pub mod clipboard;
pub mod keymap;
pub mod netif;
mod os;
pub mod paths;

pub use backend::{
    DeferredInput, InputBackend, InputCall, PlatformError, RecordingBackend, Result,
};

/// How typed text reaches applications on Linux.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TextEntry {
    /// ASCII characters are typed as key presses on a US-style layout (fast and
    /// works everywhere), anything else through the Unicode sequence. Correct
    /// only when the desktop uses a US-compatible layout.
    #[default]
    Keys,
    /// Every character except whitespace goes through the Ctrl+Shift+U Unicode
    /// sequence. Independent of the keyboard layout, but only applications that
    /// support that sequence (GTK, IBus) accept it.
    Unicode,
}

/// Settings for [`create_input_backend`] that only some platforms use.
#[derive(Debug, Clone, Copy, Default)]
pub struct BackendOptions {
    /// macOS only: how the client's Ctrl/Win modifiers map to Control/Command.
    pub macos_modifiers: keymap::macos::ModifierPolicy,
    /// Linux only: how typed text is entered.
    pub text_entry: TextEntry,
}

/// Creates the input backend for the operating system this binary was built for.
///
/// Fails with [`PlatformError::Unavailable`], carrying instructions the user
/// can act on, when the OS refuses (for example missing `/dev/uinput` access
/// on Linux).
pub fn create_input_backend(options: &BackendOptions) -> Result<Box<dyn InputBackend>> {
    os::create(options)
}

/// Like [`create_input_backend`], but a computer that has not allowed input yet does not make it fail: the
/// backend that comes back keeps asking the system, and works as soon as the person has allowed it (see
/// [`access`]). Anything other than "not allowed" is still an error.
pub fn create_input_backend_when_allowed(
    options: &BackendOptions,
) -> Result<Box<dyn InputBackend>> {
    let options = *options;
    match os::create(&options) {
        Err(error @ PlatformError::Unavailable(_)) => {
            tracing::warn!("input is not allowed yet: {error}");
            Ok(Box::new(DeferredInput::waiting(
                move || os::create(&options),
                &error,
            )))
        }
        other => other,
    }
}
