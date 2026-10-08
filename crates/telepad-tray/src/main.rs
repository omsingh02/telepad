//! Telepad for the desktop: the server, with a tray icon, a QR code to pair with, and starting at login.
//!
//! It is the same server as `telepad-server` (which stays for people who like a console), made for double-clicking:
//! there is no window, only the icon, and the page with the QR code opens when it is needed.

// A program with no console on Windows: a console window beside the tray icon would be a mistake.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod app;
mod fatal;
mod icon;
mod instance;
mod logging;
mod panel;
mod setup;
mod status;
mod tray;

use clap::Parser;
use std::process::ExitCode;

fn main() -> ExitCode {
    fatal::report_panics();
    match app::run(app::Cli::parse()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            fatal::show(&message);
            ExitCode::FAILURE
        }
    }
}
