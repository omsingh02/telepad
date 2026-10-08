//! Whether this computer lets Telepad move the pointer and type, and how to ask it to.
//!
//! Windows lets any program do both. A Mac wants the person to switch the program on in *Accessibility*, and a
//! Linux computer wants a rule that lets the person at the keyboard use `/dev/uinput`. The packages for Linux
//! set that rule up; a program unpacked from a tarball has not, so it has to ask, and this is the part of it
//! that knows how. The words the person reads are the tray program's.

use crate::os;

/// What stands between Telepad and the pointer and keyboard.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Block {
    /// macOS: Telepad is not switched on in System Settings > Privacy & Security > Accessibility.
    MacAccessibility,
    /// Linux: Telepad cannot open `/dev/uinput` (no rule lets this person use it, or the module is not loaded).
    LinuxUinput,
}

/// Why asking did not leave things allowed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AllowError {
    /// The person closed the password prompt. Nothing was changed.
    Declined,
    /// Permission was not given: there was no way to ask here (no `pkexec`, or no password prompt on this
    /// desktop), or the prompt refused the password. `terminal` is what the person can run themselves.
    NotGranted { terminal: String },
    /// It was asked, and it did not work.
    Failed(String),
}

/// What is in the way of input right now, or `None` when nothing is. It is cheap: ask as often as needed.
pub fn input_blocked() -> Option<Block> {
    os::input_blocked()
}

/// Asks for what `block` needs. This can wait on a person (a password prompt), so call it off any thread that
/// has other work to do.
///
/// On a Mac this opens the Accessibility list and returns: it is the person who switches Telepad on, and
/// [`input_blocked`] says when they have.
pub fn allow(block: Block) -> std::result::Result<(), AllowError> {
    os::allow_input(block)
}

/// The commands a person can run in a terminal to do what [`allow`] does, for when it cannot ask.
pub fn terminal_steps(block: Block) -> Option<String> {
    os::terminal_steps(block)
}
