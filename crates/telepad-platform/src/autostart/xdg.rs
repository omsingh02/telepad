//! The Linux way: an XDG autostart entry, which GNOME, KDE, Xfce and most others run at login.

use super::{remove_if_present, write_replacing, Launch};
use crate::Result;
use std::path::{Path, PathBuf};

const FILE: &str = "telepad.desktop";

/// Where the entries live, inside the user's config folder.
pub(super) fn directory(config_dir: &Path) -> PathBuf {
    config_dir.join("autostart")
}

pub(super) fn is_enabled_in(dir: &Path) -> bool {
    std::fs::read_to_string(dir.join(FILE))
        .is_ok_and(|text| !text.lines().any(|line| line.trim() == "Hidden=true"))
}

pub(super) fn enable_in(dir: &Path, launch: &Launch) -> Result<()> {
    write_replacing(&dir.join(FILE), &entry(launch))?;
    Ok(())
}

pub(super) fn disable_in(dir: &Path) -> Result<()> {
    remove_if_present(&dir.join(FILE))?;
    Ok(())
}

/// The text of the entry.
pub(super) fn entry(launch: &Launch) -> String {
    let command = std::iter::once(launch.exe.to_string_lossy().into_owned())
        .chain(launch.args.iter().cloned())
        .map(|word| quote(&word))
        .collect::<Vec<_>>()
        .join(" ");
    format!(
        "[Desktop Entry]\n\
         Type=Application\n\
         Name=Telepad\n\
         Comment=Use your phone as a trackpad and keyboard for this computer\n\
         Exec={command}\n\
         Terminal=false\n\
         Categories=Utility;\n\
         X-GNOME-Autostart-enabled=true\n"
    )
}

/// One word of an `Exec` line.
///
/// The desktop entry specification has two layers of escaping. A word with a space, a quote or another
/// character the shell would treat specially is put in double quotes, with `"`, `` ` ``, `$` and `\`
/// backslash-escaped inside them. The whole line is then a string value, in which a backslash is written
/// as two. And `%` starts a field code, so a literal one is doubled.
pub(super) fn quote(word: &str) -> String {
    let needs_quotes =
        word.is_empty() || word.chars().any(|c| " \t\n\"'\\><~|&;$*?#()`".contains(c));
    let word = word.replace('%', "%%");
    if !needs_quotes {
        return word;
    }
    let mut quoted = String::with_capacity(word.len() + 2);
    quoted.push('"');
    for c in word.chars() {
        if matches!(c, '"' | '`' | '$' | '\\') {
            quoted.push('\\');
        }
        quoted.push(c);
    }
    quoted.push('"');
    // The string-value layer: every backslash is doubled.
    quoted.replace('\\', "\\\\")
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

    /// A folder for one test, removed when the test ends.
    struct Scratch(PathBuf);
    impl Scratch {
        fn new() -> Self {
            let dir = std::env::temp_dir().join(format!("telepad_autostart_{:016x}", unique()));
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
    }
    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn unique() -> u64 {
        use std::hash::{BuildHasher, Hasher};
        std::collections::hash_map::RandomState::new()
            .build_hasher()
            .finish()
    }

    #[test]
    fn an_entry_names_the_program_and_runs_without_a_terminal() {
        let text = entry(&launch("/home/me/bin/telepad", &["--minimized"]));
        assert!(text.starts_with("[Desktop Entry]\n"));
        assert!(text.contains("\nType=Application\n"));
        assert!(text.contains("\nExec=/home/me/bin/telepad --minimized\n"));
        assert!(text.contains("\nTerminal=false\n"));
        assert!(text.contains("\nName=Telepad\n"));
    }

    #[test]
    fn a_path_with_spaces_is_quoted() {
        assert_eq!(
            quote("/home/me/My Apps/telepad"),
            "\"/home/me/My Apps/telepad\""
        );
        assert_eq!(
            entry(&launch("/opt/My Apps/telepad", &[]))
                .lines()
                .find(|l| l.starts_with("Exec=")),
            Some("Exec=\"/opt/My Apps/telepad\"")
        );
    }

    #[test]
    fn characters_the_shell_would_act_on_are_escaped_in_both_layers() {
        // Inside quotes: \" \` \$ \\ . Then each backslash is doubled for the string value.
        assert_eq!(quote("a b\"c"), "\"a b\\\\\"c\"");
        assert_eq!(quote("cost$5 now"), "\"cost\\\\$5 now\"");
        assert_eq!(quote("tick`tock now"), "\"tick\\\\`tock now\"");
        assert_eq!(quote("back\\slash"), "\"back\\\\\\\\slash\"");
    }

    #[test]
    fn a_percent_sign_is_doubled_so_it_is_not_a_field_code() {
        assert_eq!(quote("100%"), "100%%");
        assert_eq!(quote("50% off"), "\"50%% off\"");
    }

    #[test]
    fn plain_words_are_left_alone() {
        assert_eq!(quote("/usr/bin/telepad"), "/usr/bin/telepad");
        assert_eq!(quote("--key-dir=/tmp/x"), "--key-dir=/tmp/x");
        assert_eq!(
            quote(""),
            "\"\"",
            "an empty argument must still be an argument"
        );
    }

    #[test]
    fn enabling_writes_the_entry_and_disabling_removes_it() {
        let scratch = Scratch::new();
        let dir = directory(&scratch.0);
        assert!(!is_enabled_in(&dir));

        enable_in(&dir, &launch("/usr/bin/telepad", &[])).unwrap();
        assert!(is_enabled_in(&dir));
        assert!(dir.join("telepad.desktop").is_file());

        disable_in(&dir).unwrap();
        assert!(!is_enabled_in(&dir));
        assert!(!dir.join("telepad.desktop").exists(), "put back as it was");
        assert!(
            std::fs::read_dir(&dir).unwrap().next().is_none(),
            "no stray temporary file"
        );
    }

    #[test]
    fn enabling_again_replaces_a_stale_entry() {
        let scratch = Scratch::new();
        let dir = directory(&scratch.0);
        enable_in(&dir, &launch("/old/place/telepad", &[])).unwrap();
        enable_in(&dir, &launch("/new/place/telepad", &[])).unwrap();

        let text = std::fs::read_to_string(dir.join("telepad.desktop")).unwrap();
        assert!(text.contains("Exec=/new/place/telepad"));
        assert!(!text.contains("/old/place"));
    }

    #[test]
    fn disabling_what_was_never_enabled_is_fine() {
        let scratch = Scratch::new();
        disable_in(&directory(&scratch.0)).unwrap();
    }

    #[test]
    fn an_entry_a_desktop_has_hidden_counts_as_off() {
        let scratch = Scratch::new();
        let dir = directory(&scratch.0);
        enable_in(&dir, &launch("/usr/bin/telepad", &[])).unwrap();
        let path = dir.join("telepad.desktop");
        let text = std::fs::read_to_string(&path).unwrap();
        std::fs::write(&path, format!("{text}Hidden=true\n")).unwrap();
        assert!(
            !is_enabled_in(&dir),
            "a settings app that switches an entry off writes Hidden=true"
        );
    }
}
