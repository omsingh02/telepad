//! The one choice the person makes about updates: whether Telepad looks for them by itself.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const FILE_NAME: &str = "updates.json";

/// Whether Telepad checks for a new version when it starts and once a day after that. Whether it does by
/// default is a question of privacy, so it is written down here, where it is easy to see and to change: the
/// check is one request for GitHub's public list of releases, and it can be switched off from the page.
pub const CHECK_BY_DEFAULT: bool = true;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct Stored {
    auto_check: bool,
}

/// The choice, kept in a small file in Telepad's folder.
#[derive(Debug, Clone)]
pub struct Settings {
    path: PathBuf,
}

impl Settings {
    pub fn in_folder(folder: &Path) -> Self {
        Self {
            path: folder.join(FILE_NAME),
        }
    }

    /// Whether to check by itself. A missing or unreadable file means the default.
    pub fn auto_check(&self) -> bool {
        std::fs::read_to_string(&self.path)
            .ok()
            .and_then(|text| serde_json::from_str::<Stored>(&text).ok())
            .map_or(CHECK_BY_DEFAULT, |stored| stored.auto_check)
    }

    pub fn set_auto_check(&self, on: bool) -> Result<(), String> {
        let text = serde_json::to_string(&Stored { auto_check: on }).map_err(|e| e.to_string())?;
        let staging = self.path.with_extension("json.new");
        std::fs::write(&staging, text + "\n")
            .and_then(|()| std::fs::rename(&staging, &self.path))
            .map_err(|error| format!("Telepad could not save the choice: {error}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn folder(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("telepad-settings-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn without_a_file_it_is_the_default_and_a_choice_is_remembered() {
        let dir = folder("a");
        let settings = Settings::in_folder(&dir);
        assert_eq!(settings.auto_check(), CHECK_BY_DEFAULT);
        settings.set_auto_check(false).unwrap();
        assert!(
            !Settings::in_folder(&dir).auto_check(),
            "a new reader sees it"
        );
        settings.set_auto_check(true).unwrap();
        assert!(Settings::in_folder(&dir).auto_check());
        assert_eq!(
            std::fs::read_dir(&dir).unwrap().count(),
            1,
            "no staging file is left"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_that_is_garbage_means_the_default_not_a_crash() {
        let dir = folder("b");
        std::fs::write(dir.join(FILE_NAME), "not json").unwrap();
        assert_eq!(Settings::in_folder(&dir).auto_check(), CHECK_BY_DEFAULT);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
