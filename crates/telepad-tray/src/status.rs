//! What the tray says about the server, in a few words.

use crate::setup;
use telepad_platform::access::Block;
use telepad_server::Status;
use telepad_update::updater::State as UpdateState;

/// The words for the tray menu's item about updates.
pub fn update_label(state: &UpdateState) -> String {
    match state {
        UpdateState::Available(update) | UpdateState::InstallFailed { update, .. } => {
            format!("Update to {}…", update.release.version)
        }
        UpdateState::Downloading { .. } => "Downloading the update…".to_owned(),
        UpdateState::Installing(_) => "Installing the update…".to_owned(),
        UpdateState::Restarting(_) => "Restarting…".to_owned(),
        UpdateState::Checking => "Checking for updates…".to_owned(),
        UpdateState::Unknown | UpdateState::UpToDate | UpdateState::CheckFailed(_) => {
            "Check for updates…".to_owned()
        }
    }
}

/// One line for the top of the menu and the icon's tooltip. While the system is not letting Telepad type and
/// click, that comes first: nothing else matters until it is put right.
pub fn describe(status: &Status, blocked: Option<Block>) -> String {
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
    if let Some(block) = blocked {
        line = format!("{} · {line}", setup::words(block).status);
    }
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
        assert_eq!(describe(&status(1, 1), None), "1 phone connected");
        assert_eq!(describe(&status(2, 3), None), "2 phones connected");
    }

    #[test]
    fn without_a_connection_it_says_whether_anything_is_paired() {
        assert_eq!(describe(&status(0, 0), None), "No phone paired yet");
        assert_eq!(describe(&status(0, 1), None), "Ready (1 phone paired)");
        assert_eq!(describe(&status(0, 4), None), "Ready (4 phones paired)");
    }

    #[test]
    fn it_says_when_a_phone_can_pair_now() {
        let mut waiting = status(0, 0);
        waiting.invitation = Some(Duration::from_secs(120));
        assert_eq!(
            describe(&waiting, None),
            "No phone paired yet · waiting for a phone"
        );

        let mut window = status(0, 2);
        window.pairing_window = Some(Duration::from_secs(30));
        assert_eq!(
            describe(&window, None),
            "Ready (2 phones paired) · waiting for a phone"
        );
    }

    #[test]
    fn open_to_everyone_is_never_left_unsaid() {
        let mut open = status(1, 1);
        open.accepts_any_device = true;
        assert_eq!(
            describe(&open, None),
            "1 phone connected · open to every phone"
        );
    }

    #[test]
    fn what_is_missing_comes_first_and_the_rest_is_still_said() {
        assert_eq!(
            describe(&status(0, 0), Some(Block::LinuxUinput)),
            "Needs your permission to type and click · No phone paired yet"
        );
        assert_eq!(
            describe(&status(1, 1), Some(Block::MacAccessibility)),
            "Needs your permission to control this Mac · 1 phone connected"
        );
    }

    // ── The update item ──────────────────────────────────────────────

    fn found(version: &str) -> telepad_update::fetch::Available {
        use telepad_update::release::{Release, Source};
        let release = Release::for_tag(&format!("v{version}"), &Source::github()).unwrap();
        telepad_update::fetch::Available {
            release,
            install: None,
            by_hand: None,
        }
    }

    #[test]
    fn the_update_item_says_what_choosing_it_does() {
        assert_eq!(update_label(&UpdateState::Unknown), "Check for updates…");
        assert_eq!(update_label(&UpdateState::UpToDate), "Check for updates…");
        assert_eq!(
            update_label(&UpdateState::CheckFailed("x".into())),
            "Check for updates…"
        );
        assert_eq!(
            update_label(&UpdateState::Checking),
            "Checking for updates…"
        );
        let update = found("2.0.0-alpha.4");
        assert_eq!(
            update_label(&UpdateState::Available(update.clone())),
            "Update to 2.0.0-alpha.4…"
        );
        assert_eq!(
            update_label(&UpdateState::Downloading {
                update: update.clone(),
                done: 1,
                total: None
            }),
            "Downloading the update…"
        );
        assert_eq!(
            update_label(&UpdateState::Installing(update.clone())),
            "Installing the update…"
        );
        assert_eq!(
            update_label(&UpdateState::Restarting(update.clone())),
            "Restarting…"
        );
        let failed = UpdateState::InstallFailed {
            update,
            message: "m".into(),
            how: None,
            declined: false,
        };
        assert_eq!(
            update_label(&failed),
            "Update to 2.0.0-alpha.4…",
            "a failed update can be tried again"
        );
    }
}
