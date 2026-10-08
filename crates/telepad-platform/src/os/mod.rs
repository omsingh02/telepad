#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
// Pure decision logic for the macOS backend; compiled for tests on every OS so
// it is exercised by CI everywhere, not only on a Mac.
#[cfg(any(target_os = "macos", test))]
mod macos_plan;
#[cfg(unix)]
pub(crate) mod process;
#[cfg(windows)]
mod windows;

use crate::access::{AllowError, Block};
use crate::backend::{InputBackend, Result};
use crate::BackendOptions;

pub(crate) fn create(options: &BackendOptions) -> Result<Box<dyn InputBackend>> {
    #[cfg(target_os = "linux")]
    {
        Ok(Box::new(linux::LinuxInput::new(options.text_entry)?))
    }
    #[cfg(target_os = "macos")]
    {
        Ok(Box::new(macos::MacInput::new(options.macos_modifiers)?))
    }
    #[cfg(windows)]
    {
        let _ = options;
        Ok(Box::new(windows::WindowsInput::new()))
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
    {
        let _ = options;
        Err(crate::backend::PlatformError::Unavailable(
            "input injection is only implemented for Windows, Linux and macOS".into(),
        ))
    }
}

/// What is in the way of input, if anything.
pub(crate) fn input_blocked() -> Option<Block> {
    #[cfg(target_os = "linux")]
    {
        linux::setup::blocked()
    }
    #[cfg(target_os = "macos")]
    {
        macos::input_blocked()
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        None
    }
}

/// Asks for what `block` needs.
pub(crate) fn allow_input(block: Block) -> std::result::Result<(), AllowError> {
    match block {
        #[cfg(target_os = "linux")]
        Block::LinuxUinput => linux::setup::allow(),
        #[cfg(target_os = "macos")]
        Block::MacAccessibility => macos::allow_input(),
        other => Err(AllowError::Failed(format!(
            "{other:?} does not apply to this system"
        ))),
    }
}

/// What a person can run in a terminal instead of being asked.
pub(crate) fn terminal_steps(block: Block) -> Option<String> {
    match block {
        #[cfg(target_os = "linux")]
        Block::LinuxUinput => Some(linux::setup::terminal_steps()),
        _ => None,
    }
}
