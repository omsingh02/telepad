//! The icon in the system tray (the menu bar on a Mac) and its menu.
//!
//! Each system has its own way, so the interface is small and the three ways sit behind it: a status
//! line, "Pair a phone", "Start at login" and "Quit". The rest of the program only knows [`run`].

use std::sync::Arc;

#[cfg(any(windows, target_os = "macos"))]
mod desktop;
#[cfg(target_os = "linux")]
mod linux;

#[cfg(any(windows, target_os = "macos"))]
use desktop as platform;
#[cfg(target_os = "linux")]
use linux as platform;
#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
mod platform {
    use super::*;
    pub fn run(
        _runtime: &tokio::runtime::Handle,
        _menu: Menu,
        _commands: Commands,
        _ready: impl FnOnce(Handle) + Send + 'static,
        unavailable: impl FnOnce(String) + Send + 'static,
    ) {
        unavailable("this system has no tray icon support".into());
    }
}

/// What a choice in the menu asks for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    /// Show the page with the QR code.
    Pair,
    /// Turn starting at login on or off.
    ToggleAutostart,
    Quit,
}

/// What the menu shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Menu {
    pub status: String,
    pub autostart: bool,
}

/// Called, from any thread, when the person chooses something.
pub type Commands = Arc<dyn Fn(Command) + Send + Sync>;

/// A hold on the running tray, for changing its menu or ending it, from any thread.
#[derive(Clone)]
pub struct Handle {
    update: Arc<dyn Fn(Menu) + Send + Sync>,
    stop: Arc<dyn Fn() + Send + Sync>,
}

impl Handle {
    fn new(
        update: impl Fn(Menu) + Send + Sync + 'static,
        stop: impl Fn() + Send + Sync + 'static,
    ) -> Self {
        Self {
            update: Arc::new(update),
            stop: Arc::new(stop),
        }
    }

    /// Changes what the menu shows.
    pub fn update(&self, menu: Menu) {
        (self.update)(menu);
    }

    /// Removes the icon and makes [`run`] finish.
    pub fn stop(&self) {
        (self.stop)();
    }
}

/// Shows the icon and runs until [`Handle::stop`] is called, which only the caller decides: choosing "Quit"
/// asks the caller through `commands`, and the caller shuts the server down first.
///
/// `ready` is given the [`Handle`] once the icon is up. If there is nowhere to show an icon (a desktop with no
/// tray, say), `unavailable` is told why and this still runs until stopped, with nothing to see: the program
/// then has to offer another way to reach it.
///
/// On Windows and macOS this takes over the calling thread, which must be the program's main thread, and ends
/// the program when stopped.
pub fn run(
    runtime: &tokio::runtime::Handle,
    menu: Menu,
    commands: Commands,
    ready: impl FnOnce(Handle) + Send + 'static,
    unavailable: impl FnOnce(String) + Send + 'static,
) {
    platform::run(runtime, menu, commands, ready, unavailable);
}
