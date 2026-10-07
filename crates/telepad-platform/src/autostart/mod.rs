//! Starting Telepad when the person logs in.
//!
//! Every system has its own place for this, and each is small:
//!
//! | system | what is written |
//! | :--- | :--- |
//! | Windows | a value under `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run` |
//! | macOS | a LaunchAgent, `~/Library/LaunchAgents/io.github.omsingh02.telepad.plist` |
//! | Linux | an XDG autostart entry, `~/.config/autostart/telepad.desktop` |
//!
//! Only the current user's own startup is touched, so nothing needs administrator rights, and
//! turning it off puts everything back as it was.

// Each way is compiled where it is used, and in the tests everywhere: what they write is plain text, which
// the tests can check on any system.
#[cfg(any(target_os = "macos", test))]
mod launch_agent;
#[cfg(any(target_os = "windows", test))]
mod windows;
#[cfg(any(not(any(target_os = "windows", target_os = "macos")), test))]
mod xdg;

use crate::Result;
use std::path::PathBuf;

/// What starts at login: the program and the arguments to give it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Launch {
    pub exe: PathBuf,
    pub args: Vec<String>,
}

impl Launch {
    /// The running program itself, with these arguments.
    pub fn current(args: &[&str]) -> std::io::Result<Self> {
        Ok(Self {
            exe: std::env::current_exe()?,
            args: args.iter().map(|arg| (*arg).to_owned()).collect(),
        })
    }
}

/// Whether Telepad starts at login.
pub fn is_enabled() -> bool {
    platform::is_enabled()
}

/// Makes Telepad start at login, replacing an entry that is already there (which is how an entry
/// that points at an old location is put right).
pub fn enable(launch: &Launch) -> Result<()> {
    platform::enable(launch)
}

/// Stops Telepad starting at login. Does nothing if it did not.
pub fn disable() -> Result<()> {
    platform::disable()
}

/// If Telepad starts at login, makes sure the entry points at `launch`: a program that was moved or
/// updated in place keeps starting. Does nothing when starting at login is off.
pub fn refresh(launch: &Launch) -> Result<()> {
    if is_enabled() {
        enable(launch)?;
    }
    Ok(())
}

#[cfg(target_os = "windows")]
mod platform {
    use super::*;
    pub fn is_enabled() -> bool {
        super::windows::is_enabled()
    }
    pub fn enable(launch: &Launch) -> Result<()> {
        super::windows::enable(launch)
    }
    pub fn disable() -> Result<()> {
        super::windows::disable()
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use super::*;
    fn dir() -> Option<PathBuf> {
        dirs::home_dir().map(|home| launch_agent::directory(&home))
    }
    pub fn is_enabled() -> bool {
        dir().is_some_and(|dir| launch_agent::is_enabled_in(&dir))
    }
    pub fn enable(launch: &Launch) -> Result<()> {
        launch_agent::enable_in(&dir().ok_or_else(no_home)?, launch)
    }
    pub fn disable() -> Result<()> {
        dir().map_or(Ok(()), |dir| launch_agent::disable_in(&dir))
    }
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
mod platform {
    use super::*;
    fn dir() -> Option<PathBuf> {
        dirs::config_dir().map(|config| xdg::directory(&config))
    }
    pub fn is_enabled() -> bool {
        dir().is_some_and(|dir| xdg::is_enabled_in(&dir))
    }
    pub fn enable(launch: &Launch) -> Result<()> {
        xdg::enable_in(&dir().ok_or_else(no_home)?, launch)
    }
    pub fn disable() -> Result<()> {
        dir().map_or(Ok(()), |dir| xdg::disable_in(&dir))
    }
}

#[cfg(not(target_os = "windows"))]
fn no_home() -> crate::PlatformError {
    crate::PlatformError::Os("this account has no home folder to keep a startup entry in".into())
}

/// Writes `contents` to `path` without ever leaving half of it there: to a neighbour first, then renamed over it.
#[cfg(any(not(target_os = "windows"), test))]
pub(crate) fn write_replacing(path: &std::path::Path, contents: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, contents)?;
    std::fs::rename(&temporary, path)
}

/// Removes `path`, and is content if it was not there.
#[cfg(any(not(target_os = "windows"), test))]
pub(crate) fn remove_if_present(path: &std::path::Path) -> std::io::Result<()> {
    match std::fs::remove_file(path) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}
