//! Where the server keeps its identity key and paired-device list.

use std::io;
use std::path::{Path, PathBuf};

/// Default per-user config directory:
///
/// * Windows: `%APPDATA%\Telepad`
/// * macOS:   `~/Library/Application Support/Telepad`
/// * Linux and other Unix: `$XDG_CONFIG_HOME/telepad` (default `~/.config/telepad`)
///
/// Falls back to `./telepad-data` only when the OS reports no home directory
/// at all (a bare service account), so the server still starts.
pub fn default_config_dir() -> PathBuf {
    resolve_config_dir(dirs::config_dir(), dirs::home_dir())
}

fn resolve_config_dir(config_dir: Option<PathBuf>, home_dir: Option<PathBuf>) -> PathBuf {
    let Some(base) = config_dir else {
        return PathBuf::from("./telepad-data");
    };
    let dir = base.join(app_dir_name());

    // macOS used to follow the Linux convention (`~/.config/telepad`) in early
    // builds. Keep honouring an existing identity there: moving it silently
    // would change the server fingerprint and force every phone to re-pair.
    if cfg!(target_os = "macos") && !dir.exists() {
        if let Some(legacy) = home_dir.map(|h| h.join(".config").join("telepad")) {
            if has_identity(&legacy) {
                return legacy;
            }
        }
    }
    dir
}

/// Creates `dir` and any missing parents. Directories created by this call are
/// private to the current user on Unix (mode 0700); one that already exists is
/// left exactly as it is, since it may be a shared location the user chose.
pub fn ensure_private_dir(dir: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        std::fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)
    }
    #[cfg(not(unix))]
    {
        std::fs::create_dir_all(dir)
    }
}

fn app_dir_name() -> &'static str {
    if cfg!(any(windows, target_os = "macos")) {
        "Telepad"
    } else {
        "telepad"
    }
}

fn has_identity(dir: &Path) -> bool {
    dir.join("identity.key").is_file()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dir_is_namespaced_under_the_platform_config_dir() {
        let dir = resolve_config_dir(Some(PathBuf::from("/cfg")), Some(PathBuf::from("/home/u")));
        assert!(dir.starts_with("/cfg"));
        assert_eq!(dir.file_name().unwrap(), app_dir_name());
        if cfg!(any(windows, target_os = "macos")) {
            assert_eq!(app_dir_name(), "Telepad");
        } else {
            assert_eq!(app_dir_name(), "telepad");
        }
    }

    #[test]
    fn no_home_directory_falls_back_to_a_local_folder() {
        assert_eq!(
            resolve_config_dir(None, None),
            PathBuf::from("./telepad-data")
        );
    }

    #[test]
    fn default_dir_resolves_on_this_machine() {
        let dir = default_config_dir();
        assert!(dir.ends_with(app_dir_name()) || dir == Path::new("./telepad-data"));
    }

    fn scratch(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!("telepad_paths_{tag}_{}", std::process::id()))
    }

    #[test]
    fn ensure_private_dir_creates_nested_directories_and_is_idempotent() {
        let root = scratch("nested");
        let _ = std::fs::remove_dir_all(&root);
        let leaf = root.join("a").join("b");
        ensure_private_dir(&leaf).unwrap();
        assert!(leaf.is_dir());
        ensure_private_dir(&leaf).unwrap();
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn new_directories_are_private_but_existing_ones_are_untouched() {
        use std::os::unix::fs::PermissionsExt;
        let root = scratch("perms");
        let _ = std::fs::remove_dir_all(&root);
        let created = root.join("telepad");
        ensure_private_dir(&created).unwrap();
        let mode = |p: &Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&created), 0o700);

        // A pre-existing, deliberately shared directory keeps its permissions.
        let shared = root.join("shared");
        std::fs::create_dir_all(&shared).unwrap();
        std::fs::set_permissions(&shared, std::fs::Permissions::from_mode(0o755)).unwrap();
        ensure_private_dir(&shared).unwrap();
        assert_eq!(mode(&shared), 0o755);
        std::fs::remove_dir_all(&root).unwrap();
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn legacy_macos_identity_is_still_honoured() {
        let tmp = std::env::temp_dir().join(format!("telepad_paths_{}", std::process::id()));
        let legacy = tmp.join(".config").join("telepad");
        std::fs::create_dir_all(&legacy).unwrap();
        std::fs::write(legacy.join("identity.key"), [0u8; 32]).unwrap();
        let dir = resolve_config_dir(
            Some(tmp.join("Library/Application Support")),
            Some(tmp.clone()),
        );
        assert_eq!(dir, legacy);
        std::fs::remove_dir_all(&tmp).unwrap();
    }
}
