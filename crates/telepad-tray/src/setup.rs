//! What the person is told, and what happens, while the computer has not let Telepad type and click yet.
//!
//! A Mac needs Telepad switched on in Accessibility; a Linux computer needs a rule that lets the person at the
//! keyboard use `/dev/uinput` (the packages add it; a program from the tarball asks for it). Either way the
//! program is running and can be paired, and says what to do rather than failing to start. The facts and the
//! asking are the platform crate's; the words and the memory of how the asking went are here.

use telepad_platform::access::{self, AllowError, Block};

/// The words for one kind of block.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Words {
    /// The heading on the page.
    pub title: &'static str,
    /// What is wrong and what the button does.
    pub body: &'static str,
    /// The page's button.
    pub button: &'static str,
    /// The tray menu's item.
    pub menu: &'static str,
    /// The start of the status line.
    pub status: &'static str,
}

pub fn words(block: Block) -> Words {
    match block {
        Block::MacAccessibility => Words {
            title: "Allow Telepad to control this Mac",
            body: "macOS only lets a program move the pointer and type once you allow it. Choose the button, \
                   then switch Telepad on in the Accessibility list. This message goes away by itself when you have.",
            button: "Open Accessibility settings",
            menu: "Allow Telepad to control this Mac…",
            status: "Needs your permission to control this Mac",
        },
        Block::LinuxUinput => Words {
            title: "Allow Telepad to type and click",
            body: "Telepad needs your permission, once, to create a virtual keyboard and mouse. Choose the button \
                   and enter your password when the system asks. (Installing the .deb, .rpm or Arch package \
                   does this for you.)",
            button: "Allow…",
            menu: "Allow Telepad to type and click…",
            status: "Needs your permission to type and click",
        },
    }
}

/// How the last attempt to get permission went, for as long as the permission is still missing.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Progress {
    /// Asking right now: a password prompt may be open.
    pub working: bool,
    /// What happened, in words for the person.
    pub note: Option<String>,
    /// What the person can run in a terminal instead.
    pub terminal: Option<String>,
}

impl Progress {
    /// The memory after an attempt ended with `outcome`.
    pub fn after(block: Block, outcome: Result<(), AllowError>) -> Self {
        let (note, terminal) = match outcome {
            // On a Mac that only means that the list is open: switching Telepad on is the person's part.
            Ok(()) => match block {
                Block::MacAccessibility => (
                    Some("System Settings is open: switch Telepad on in the list.".to_owned()),
                    None,
                ),
                Block::LinuxUinput => (None, None),
            },
            Err(AllowError::Declined) => (
                Some("Nothing was changed. Choose the button again when you are ready.".to_owned()),
                None,
            ),
            Err(AllowError::NotGranted { terminal }) => (
                Some(
                    "The system did not give Telepad that permission here: no password prompt could be shown, \
                     or the password was not accepted. You can do it yourself in a terminal:"
                        .to_owned(),
                ),
                Some(terminal),
            ),
            Err(AllowError::Failed(reason)) => {
                let terminal = access::terminal_steps(block);
                (Some(format!("That did not work: {reason}")), terminal)
            }
        };
        Self {
            working: false,
            note,
            terminal,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_block_has_every_word() {
        for block in [Block::MacAccessibility, Block::LinuxUinput] {
            let words = words(block);
            for text in [
                words.title,
                words.body,
                words.button,
                words.menu,
                words.status,
            ] {
                assert!(!text.trim().is_empty(), "{block:?}");
            }
        }
    }

    #[test]
    fn the_package_is_the_way_to_never_see_the_linux_prompt() {
        assert!(words(Block::LinuxUinput)
            .body
            .contains(".deb, .rpm or Arch package"));
    }

    #[test]
    fn a_person_who_closes_the_password_prompt_is_not_told_it_failed() {
        let progress = Progress::after(Block::LinuxUinput, Err(AllowError::Declined));
        assert!(!progress.working);
        assert!(progress.note.unwrap().starts_with("Nothing was changed"));
        assert_eq!(progress.terminal, None);
    }

    #[test]
    fn when_the_system_cannot_ask_the_steps_to_run_by_hand_come_with_it() {
        let outcome = Err(AllowError::NotGranted {
            terminal: "sudo sh -c 'true'".into(),
        });
        let progress = Progress::after(Block::LinuxUinput, outcome);
        assert!(progress.note.unwrap().contains("in a terminal"));
        assert_eq!(progress.terminal.as_deref(), Some("sudo sh -c 'true'"));
    }

    #[test]
    fn a_failure_says_why_and_still_offers_the_terminal() {
        let progress = Progress::after(
            Block::LinuxUinput,
            Err(AllowError::Failed("no udev".into())),
        );
        assert_eq!(progress.note.as_deref(), Some("That did not work: no udev"));
        #[cfg(target_os = "linux")]
        assert!(progress.terminal.unwrap().contains("sudo udevadm"));
    }

    #[test]
    fn on_a_mac_opening_the_list_is_only_half_of_it() {
        let progress = Progress::after(Block::MacAccessibility, Ok(()));
        assert!(progress.note.unwrap().contains("switch Telepad on"));
        // On Linux, success means it is allowed, and there is nothing more to say.
        assert_eq!(
            Progress::after(Block::LinuxUinput, Ok(())),
            Progress::default()
        );
    }
}
