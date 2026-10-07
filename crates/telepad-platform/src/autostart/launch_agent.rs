//! The macOS way: a LaunchAgent, which `launchd` starts when the user logs in.

use super::{remove_if_present, write_replacing, Launch};
use crate::Result;
use std::path::{Path, PathBuf};

const LABEL: &str = "io.github.omsingh02.telepad";

/// Where a user's LaunchAgents live.
pub(super) fn directory(home: &Path) -> PathBuf {
    home.join("Library").join("LaunchAgents")
}

fn file(dir: &Path) -> PathBuf {
    dir.join(format!("{LABEL}.plist"))
}

pub(super) fn is_enabled_in(dir: &Path) -> bool {
    file(dir).is_file()
}

pub(super) fn enable_in(dir: &Path, launch: &Launch) -> Result<()> {
    write_replacing(&file(dir), &plist(launch))?;
    Ok(())
}

pub(super) fn disable_in(dir: &Path) -> Result<()> {
    remove_if_present(&file(dir))?;
    Ok(())
}

/// The property list: run it at login, in the user's graphical session, and leave it alone when the person quits it
/// (no `KeepAlive`: quitting the program means quitting it).
pub(super) fn plist(launch: &Launch) -> String {
    let words: String = std::iter::once(launch.exe.to_string_lossy().into_owned())
        .chain(launch.args.iter().cloned())
        .map(|word| format!("        <string>{}</string>\n", escape(&word)))
        .collect();
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
         <!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
         <plist version=\"1.0\">\n\
         <dict>\n\
         \x20   <key>Label</key>\n\
         \x20   <string>{LABEL}</string>\n\
         \x20   <key>ProgramArguments</key>\n\
         \x20   <array>\n\
         {words}\
         \x20   </array>\n\
         \x20   <key>RunAtLoad</key>\n\
         \x20   <true/>\n\
         \x20   <key>ProcessType</key>\n\
         \x20   <string>Interactive</string>\n\
         \x20   <key>LimitLoadToSessionType</key>\n\
         \x20   <string>Aqua</string>\n\
         </dict>\n\
         </plist>\n"
    )
}

/// Text for an XML element.
fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn launch(exe: &str, args: &[&str]) -> Launch {
        Launch {
            exe: PathBuf::from(exe),
            args: args.iter().map(|a| (*a).to_owned()).collect(),
        }
    }

    struct Scratch(PathBuf);
    impl Scratch {
        fn new() -> Self {
            use std::hash::{BuildHasher, Hasher};
            let id = std::collections::hash_map::RandomState::new()
                .build_hasher()
                .finish();
            let dir = std::env::temp_dir().join(format!("telepad_agent_{id:016x}"));
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
    }
    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn the_agent_runs_the_program_at_login_and_is_not_restarted_when_quit() {
        let text = plist(&launch(
            "/Applications/Telepad.app/Contents/MacOS/telepad",
            &["--minimized"],
        ));
        assert!(text.contains("<key>Label</key>\n    <string>io.github.omsingh02.telepad</string>"));
        assert!(text.contains("<string>/Applications/Telepad.app/Contents/MacOS/telepad</string>"));
        assert!(text.contains("<string>--minimized</string>"));
        assert!(text.contains("<key>RunAtLoad</key>\n    <true/>"));
        assert!(!text.contains("KeepAlive"), "quitting must quit");
        assert!(text.trim_end().ends_with("</plist>"));
    }

    #[test]
    fn xml_characters_in_a_path_cannot_break_the_file() {
        let text = plist(&launch("/Users/a&b/<x>/\"q\"/telepad", &[]));
        assert!(text.contains("/Users/a&amp;b/&lt;x&gt;/&quot;q&quot;/telepad"));
        assert!(!text.contains("a&b"));
    }

    #[test]
    fn the_agents_folder_is_the_users_own() {
        assert_eq!(
            directory(Path::new("/Users/me")),
            PathBuf::from("/Users/me/Library/LaunchAgents")
        );
    }

    #[test]
    fn enabling_and_disabling_round_trip_and_enabling_again_replaces() {
        let scratch = Scratch::new();
        let dir = directory(&scratch.0);
        assert!(!is_enabled_in(&dir));

        enable_in(&dir, &launch("/old/telepad", &[])).unwrap();
        enable_in(&dir, &launch("/new/telepad", &[])).unwrap();
        assert!(is_enabled_in(&dir));
        let text = std::fs::read_to_string(file(&dir)).unwrap();
        assert!(text.contains("/new/telepad") && !text.contains("/old/telepad"));

        disable_in(&dir).unwrap();
        assert!(!is_enabled_in(&dir));
        disable_in(&dir).unwrap();
    }
}
