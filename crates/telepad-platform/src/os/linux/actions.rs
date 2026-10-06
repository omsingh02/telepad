//! Linux implementations of the "launch action" and "lock screen" commands.
//!
//! Desktop environments agree on very little here, so each action is either a
//! key chord that the common shells bind by default, or a list of helper
//! programs of which the first installed one is used.

use crate::keymap::linux::*;
use crate::os::process::argv;
use telepad_protocol::SystemAction;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Desktop {
    Kde,
    /// GNOME and everything else; the defaults below follow GNOME.
    Other,
}

/// Classifies `$XDG_CURRENT_DESKTOP`, a colon-separated list such as
/// `ubuntu:GNOME` or `KDE`.
pub fn detect_desktop(xdg_current_desktop: Option<&str>) -> Desktop {
    let is_kde = xdg_current_desktop
        .map(|v| v.split(':').any(|part| part.eq_ignore_ascii_case("kde")))
        .unwrap_or(false);
    if is_kde {
        Desktop::Kde
    } else {
        Desktop::Other
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LaunchPlan {
    /// Press these keys in order, then release them in reverse order.
    Chord(Vec<u16>),
    /// Run the first of these commands whose program is installed.
    Run(Vec<Vec<String>>),
}

pub fn plan_launch(action: SystemAction, desktop: Desktop, home: &str) -> LaunchPlan {
    match action {
        // Super+D: "show desktop" on KDE, XFCE, Cinnamon, MATE and Ubuntu's GNOME.
        SystemAction::ShowDesktop => LaunchPlan::Chord(vec![KEY_LEFTMETA, KEY_D]),
        SystemAction::TaskView => match desktop {
            // Plasma's window overview.
            Desktop::Kde => LaunchPlan::Chord(vec![KEY_LEFTMETA, KEY_W]),
            // Tapping Super opens the GNOME Activities overview.
            Desktop::Other => LaunchPlan::Chord(vec![KEY_LEFTMETA]),
        },
        // Same target the Windows server uses: hand "https://" to the default browser.
        SystemAction::Browser => LaunchPlan::Run(vec![argv(&["xdg-open", "https://"])]),
        SystemAction::FileManager => LaunchPlan::Run(vec![argv(&["xdg-open", home])]),
        SystemAction::TaskManager => LaunchPlan::Run(
            [
                "gnome-system-monitor",
                "plasma-systemmonitor",
                "ksysguard",
                "xfce4-taskmanager",
                "mate-system-monitor",
                "lxtask",
                "deepin-system-monitor",
            ]
            .iter()
            .map(|program| argv(&[program]))
            .collect(),
        ),
        // The Print key opens the screenshot UI on GNOME, KDE (Spectacle) and XFCE.
        SystemAction::Screenshot => LaunchPlan::Chord(vec![KEY_SYSRQ]),
    }
}

/// Commands that lock the current session, most reliable first.
pub fn lock_commands() -> Vec<Vec<String>> {
    vec![
        argv(&["loginctl", "lock-session"]),
        argv(&["xdg-screensaver", "lock"]),
        argv(&["gnome-screensaver-command", "--lock"]),
        argv(&["cinnamon-screensaver-command", "--lock"]),
        argv(&["mate-screensaver-command", "--lock"]),
        argv(&["xflock4"]),
        argv(&[
            "dbus-send",
            "--session",
            "--dest=org.freedesktop.ScreenSaver",
            "/ScreenSaver",
            "org.freedesktop.ScreenSaver.Lock",
        ]),
        argv(&[
            "qdbus",
            "org.freedesktop.ScreenSaver",
            "/ScreenSaver",
            "Lock",
        ]),
        argv(&["xscreensaver-command", "-lock"]),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_detection() {
        assert_eq!(detect_desktop(Some("KDE")), Desktop::Kde);
        assert_eq!(detect_desktop(Some("kde")), Desktop::Kde);
        assert_eq!(detect_desktop(Some("plasma:KDE")), Desktop::Kde);
        assert_eq!(detect_desktop(Some("ubuntu:GNOME")), Desktop::Other);
        assert_eq!(detect_desktop(Some("sway")), Desktop::Other);
        assert_eq!(detect_desktop(Some("")), Desktop::Other);
        assert_eq!(detect_desktop(None), Desktop::Other);
    }

    #[test]
    fn key_chord_actions() {
        assert_eq!(
            plan_launch(SystemAction::ShowDesktop, Desktop::Other, "/h"),
            LaunchPlan::Chord(vec![KEY_LEFTMETA, KEY_D])
        );
        assert_eq!(
            plan_launch(SystemAction::TaskView, Desktop::Kde, "/h"),
            LaunchPlan::Chord(vec![KEY_LEFTMETA, KEY_W])
        );
        assert_eq!(
            plan_launch(SystemAction::TaskView, Desktop::Other, "/h"),
            LaunchPlan::Chord(vec![KEY_LEFTMETA])
        );
        assert_eq!(
            plan_launch(SystemAction::Screenshot, Desktop::Other, "/h"),
            LaunchPlan::Chord(vec![KEY_SYSRQ])
        );
    }

    #[test]
    fn program_actions() {
        assert_eq!(
            plan_launch(SystemAction::FileManager, Desktop::Other, "/home/u"),
            LaunchPlan::Run(vec![argv(&["xdg-open", "/home/u"])])
        );
        assert_eq!(
            plan_launch(SystemAction::Browser, Desktop::Other, "/home/u"),
            LaunchPlan::Run(vec![argv(&["xdg-open", "https://"])])
        );
        let LaunchPlan::Run(candidates) =
            plan_launch(SystemAction::TaskManager, Desktop::Other, "/h")
        else {
            panic!("task manager must be a program launch");
        };
        assert!(candidates.len() >= 5);
        assert!(candidates.iter().all(|c| c.len() == 1));
    }

    #[test]
    fn every_action_has_a_plan() {
        use SystemAction::*;
        for action in [
            ShowDesktop,
            TaskView,
            Browser,
            FileManager,
            TaskManager,
            Screenshot,
        ] {
            for desktop in [Desktop::Kde, Desktop::Other] {
                match plan_launch(action, desktop, "/h") {
                    LaunchPlan::Chord(keys) => assert!(!keys.is_empty()),
                    LaunchPlan::Run(cmds) => assert!(cmds.iter().all(|c| !c.is_empty())),
                }
            }
        }
    }

    #[test]
    fn lock_prefers_logind_and_has_fallbacks() {
        let cmds = lock_commands();
        assert_eq!(cmds[0], argv(&["loginctl", "lock-session"]));
        assert!(cmds.len() >= 5);
    }
}
