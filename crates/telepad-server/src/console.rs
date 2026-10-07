//! The small command line the server offers when run in a terminal.
//!
//! It exists mainly so the owner can open the pairing window to add a phone
//! without restarting the server.

use crate::qr::Style;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    /// Let a new device pair for this long, and show a QR code that pairs one phone.
    Pair(Option<Duration>),
    /// Show a QR code that pairs one phone, whether or not pairing is open.
    Qr(Style),
    /// Stop accepting new devices right now.
    Close,
    List,
    Forget,
    Status,
    Help,
    Quit,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    Empty,
    Unknown(String),
    BadDuration(String),
    /// An option the command does not have.
    BadArgument(String),
}

/// The longest window the console will open (a day), so a typo cannot leave
/// the server open indefinitely by accident.
pub const MAX_PAIRING_SECONDS: u64 = 24 * 60 * 60;

pub const HELP: &str = "\
Commands:
  pair [seconds]   let one new phone connect (default 300 s) and show its QR code
  qr [light]       show a QR code that pairs one phone (scan it in the app);
                   say 'light' if the terminal has a white background
  close            stop accepting new phones now
  list             show paired phones
  forget           unpair every phone
  status           show what the server is doing
  help             show this text
  quit             stop the server";

pub fn parse(line: &str) -> Result<Command, ParseError> {
    let mut words = line.split_whitespace();
    let Some(verb) = words.next() else {
        return Err(ParseError::Empty);
    };
    match verb.to_ascii_lowercase().as_str() {
        "pair" | "p" => match words.next() {
            None => Ok(Command::Pair(None)),
            Some(arg) => match arg.parse::<u64>() {
                Ok(seconds) if (1..=MAX_PAIRING_SECONDS).contains(&seconds) => {
                    Ok(Command::Pair(Some(Duration::from_secs(seconds))))
                }
                _ => Err(ParseError::BadDuration(arg.to_owned())),
            },
        },
        "qr" | "code" => match words.next().map(str::to_ascii_lowercase).as_deref() {
            None | Some("dark") => Ok(Command::Qr(Style::DarkTerminal)),
            Some("light" | "white") => Ok(Command::Qr(Style::LightTerminal)),
            Some(other) => Err(ParseError::BadArgument(other.to_owned())),
        },
        "close" | "c" => Ok(Command::Close),
        "list" | "l" | "ls" => Ok(Command::List),
        "forget" | "f" => Ok(Command::Forget),
        "status" | "s" => Ok(Command::Status),
        "help" | "h" | "?" => Ok(Command::Help),
        "quit" | "q" | "exit" => Ok(Command::Quit),
        other => Err(ParseError::Unknown(other.to_owned())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pair_with_and_without_a_duration() {
        assert_eq!(parse("pair"), Ok(Command::Pair(None)));
        assert_eq!(parse("p"), Ok(Command::Pair(None)));
        assert_eq!(
            parse("pair 30"),
            Ok(Command::Pair(Some(Duration::from_secs(30))))
        );
        assert_eq!(
            parse("  PAIR   600  "),
            Ok(Command::Pair(Some(Duration::from_secs(600))))
        );
    }

    #[test]
    fn bad_durations_are_rejected_not_guessed() {
        for bad in [
            "pair 0",
            "pair -5",
            "pair abc",
            "pair 1.5",
            "pair 86401",
            "pair 99999999999999999999",
        ] {
            assert!(
                matches!(parse(bad), Err(ParseError::BadDuration(_))),
                "{bad}"
            );
        }
        assert_eq!(
            parse("pair 86400"),
            Ok(Command::Pair(Some(Duration::from_secs(86_400))))
        );
    }

    #[test]
    fn simple_commands_and_aliases() {
        assert_eq!(parse("qr"), Ok(Command::Qr(Style::DarkTerminal)));
        assert_eq!(parse("QR"), Ok(Command::Qr(Style::DarkTerminal)));
        assert_eq!(parse("code"), Ok(Command::Qr(Style::DarkTerminal)));
        assert_eq!(parse("qr light"), Ok(Command::Qr(Style::LightTerminal)));
        assert_eq!(parse("qr white"), Ok(Command::Qr(Style::LightTerminal)));
        assert_eq!(
            parse("qr purple"),
            Err(ParseError::BadArgument("purple".into()))
        );
        assert_eq!(parse("close"), Ok(Command::Close));
        assert_eq!(parse("c"), Ok(Command::Close));
        assert_eq!(parse("list"), Ok(Command::List));
        assert_eq!(parse("ls"), Ok(Command::List));
        assert_eq!(parse("forget"), Ok(Command::Forget));
        assert_eq!(parse("status"), Ok(Command::Status));
        assert_eq!(parse("help"), Ok(Command::Help));
        assert_eq!(parse("?"), Ok(Command::Help));
        assert_eq!(parse("quit"), Ok(Command::Quit));
        assert_eq!(parse("exit"), Ok(Command::Quit));
        assert_eq!(parse("Q"), Ok(Command::Quit));
    }

    #[test]
    fn empty_and_unknown_input() {
        assert_eq!(parse(""), Err(ParseError::Empty));
        assert_eq!(parse("   \t "), Err(ParseError::Empty));
        assert_eq!(parse("reboot"), Err(ParseError::Unknown("reboot".into())));
    }

    #[test]
    fn help_mentions_every_command() {
        for word in [
            "pair", "qr", "close", "list", "forget", "status", "help", "quit",
        ] {
            assert!(HELP.contains(word), "{word}");
        }
    }
}
