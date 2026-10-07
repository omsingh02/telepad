//! What the tray says about the server, in a few words.

use telepad_server::Status;

/// One line for the top of the menu and the icon's tooltip.
pub fn describe(status: &Status) -> String {
    let phones = |count: usize| {
        if count == 1 {
            "1 phone".to_owned()
        } else {
            format!("{count} phones")
        }
    };

    let mut line = if status.sessions > 0 {
        format!("{} connected", phones(status.sessions))
    } else if status.paired_devices == 0 {
        "No phone paired yet".to_owned()
    } else {
        format!("Ready ({} paired)", phones(status.paired_devices))
    };
    if status.accepts_any_device {
        line.push_str(" · open to every phone");
    } else if status.invitation.is_some() || status.pairing_window.is_some() {
        line.push_str(" · waiting for a phone");
    }
    line
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn status(sessions: usize, paired: usize) -> Status {
        Status {
            sessions,
            paired_devices: paired,
            pairing_window: None,
            invitation: None,
            accepts_any_device: false,
        }
    }

    #[test]
    fn a_connected_phone_is_the_news() {
        assert_eq!(describe(&status(1, 1)), "1 phone connected");
        assert_eq!(describe(&status(2, 3)), "2 phones connected");
    }

    #[test]
    fn without_a_connection_it_says_whether_anything_is_paired() {
        assert_eq!(describe(&status(0, 0)), "No phone paired yet");
        assert_eq!(describe(&status(0, 1)), "Ready (1 phone paired)");
        assert_eq!(describe(&status(0, 4)), "Ready (4 phones paired)");
    }

    #[test]
    fn it_says_when_a_phone_can_pair_now() {
        let mut waiting = status(0, 0);
        waiting.invitation = Some(Duration::from_secs(120));
        assert_eq!(
            describe(&waiting),
            "No phone paired yet · waiting for a phone"
        );

        let mut window = status(0, 2);
        window.pairing_window = Some(Duration::from_secs(30));
        assert_eq!(
            describe(&window),
            "Ready (2 phones paired) · waiting for a phone"
        );
    }

    #[test]
    fn open_to_everyone_is_never_left_unsaid() {
        let mut open = status(1, 1);
        open.accepts_any_device = true;
        assert_eq!(describe(&open), "1 phone connected · open to every phone");
    }
}
