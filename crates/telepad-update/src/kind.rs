//! How this copy of Telepad was installed, which decides how it is updated and which file of a release it takes.
//!
//! A program cannot update itself the same way everywhere: the Windows installer replaces the program, a Mac
//! replaces the app in `/Applications`, a Linux package is the package manager's to change, and a program
//! unpacked from the tarball into a person's home can simply be swapped. Anything else (a build from source, a
//! Homebrew install) is left alone: it says there is a new version, and where to get it.

use std::path::{Path, PathBuf};

/// A kind of Linux package.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PackageFormat {
    Deb,
    Rpm,
    Pacman,
}

/// How this copy was installed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    /// Windows, by the installer (it lives in the person's own program folder).
    WindowsInstaller,
    /// macOS, an app bundle: `app` is the `Telepad.app` folder.
    MacApp { app: PathBuf },
    /// Linux, from a system package.
    LinuxPackage(PackageFormat),
    /// Linux, unpacked from the tarball by `install.sh`: a program file the person can replace.
    LinuxUser { exe: PathBuf },
    /// Anything that is not updated by Telepad itself, and why not, in words for the person.
    Unsupported(String),
}

/// The facts [`classify`] decides from, so that it can be tried without being on every system.
#[derive(Debug, Clone)]
pub struct Facts {
    pub os: Os,
    /// The processor, as Rust names it (`x86_64`, `aarch64`).
    pub arch: String,
    /// Where the running program file is.
    pub exe: PathBuf,
    /// `%LOCALAPPDATA%` on Windows.
    pub local_app_data: Option<PathBuf>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Os {
    Windows,
    MacOs,
    Linux,
}

impl Os {
    pub fn current() -> Option<Os> {
        match std::env::consts::OS {
            "windows" => Some(Os::Windows),
            "macos" => Some(Os::MacOs),
            "linux" => Some(Os::Linux),
            _ => None,
        }
    }
}

/// Works out how the program in `facts` was installed. `package_owning` says which kind of package (if any)
/// owns a file, and `can_replace` whether the person can overwrite files in a folder: both look at the system.
pub fn classify(
    facts: &Facts,
    package_owning: &dyn Fn(&Path) -> Option<PackageFormat>,
    can_replace: &dyn Fn(&Path) -> bool,
) -> Kind {
    let exe = &facts.exe;
    let unsupported = |why: &str| Kind::Unsupported(why.to_owned());

    if is_build_folder(exe) {
        return unsupported("This is a build from source, which a build from source updates.");
    }
    match facts.os {
        Os::Windows => {
            if facts.arch != "x86_64" {
                return unsupported("There is no Telepad for this kind of processor yet.");
            }
            let installed = facts
                .local_app_data
                .as_ref()
                .map(|base| base.join("Programs").join("Telepad"));
            match installed {
                Some(folder) if starts_with_ignoring_case(exe, &folder) => Kind::WindowsInstaller,
                _ => unsupported(
                    "This copy of Telepad was not put here by its installer, so it cannot replace itself. \
                     Run the new installer.",
                ),
            }
        }
        Os::MacOs => {
            let text = exe.to_string_lossy();
            if text.contains("/Cellar/") || text.contains("/Caskroom/") {
                return unsupported("Homebrew installed Telepad: update it with `brew upgrade`.");
            }
            match app_folder(exe) {
                Some(app) => Kind::MacApp { app },
                None => unsupported("This is not the Telepad app, so it cannot replace itself."),
            }
        }
        Os::Linux => {
            if facts.arch != "x86_64" {
                return unsupported("There is no Telepad for this kind of processor yet.");
            }
            if exe.starts_with("/usr/bin") || exe.starts_with("/usr/sbin") {
                return match package_owning(exe) {
                    Some(format) => Kind::LinuxPackage(format),
                    None => unsupported(
                        "Telepad is in a system folder but no package owns it, so it cannot replace itself.",
                    ),
                };
            }
            match exe.parent() {
                Some(folder) if can_replace(folder) => Kind::LinuxUser { exe: exe.clone() },
                _ => unsupported(
                    "Telepad is in a folder that only an administrator can change. Update it the way it was installed.",
                ),
            }
        }
    }
}

/// `cargo run` and `cargo build` leave the program here; replacing it would only confuse a developer.
fn is_build_folder(exe: &Path) -> bool {
    let parts: Vec<_> = exe
        .components()
        .map(|c| c.as_os_str().to_string_lossy().into_owned())
        .collect();
    let profile = |name: &str| matches!(name, "debug" | "release");
    // target/release/, and target/<platform>/release/ for a build for another system.
    parts.windows(2).any(|p| p[0] == "target" && profile(&p[1]))
        || parts.windows(3).any(|p| p[0] == "target" && profile(&p[2]))
}

/// `…/Telepad.app` from `…/Telepad.app/Contents/MacOS/telepad`.
fn app_folder(exe: &Path) -> Option<PathBuf> {
    let macos = exe.parent()?;
    let contents = macos.parent()?;
    let app = contents.parent()?;
    let named = |path: &Path, name: &str| path.file_name().is_some_and(|n| n == name);
    (named(macos, "MacOS")
        && named(contents, "Contents")
        && app.extension().is_some_and(|e| e == "app"))
    .then(|| app.to_path_buf())
}

fn starts_with_ignoring_case(path: &Path, folder: &Path) -> bool {
    // `\\?\C:\...` is the same place as `C:\...`.
    let lower = |p: &Path| {
        let text = p.to_string_lossy().replace('/', "\\").to_lowercase();
        text.strip_prefix("\\\\?\\")
            .map_or(text.clone(), str::to_owned)
    };
    let (path, folder) = (lower(path), lower(folder));
    path.strip_prefix(&folder)
        .is_some_and(|rest| rest.starts_with('\\'))
}

impl Kind {
    /// The file of release `version` (such as `2.0.0-alpha.4`) that updates this kind of install.
    pub fn asset_name(&self, version: &str) -> Option<String> {
        let tail = match self {
            Kind::WindowsInstaller => "windows-x86_64-setup.exe",
            Kind::MacApp { .. } => "macos-universal.dmg",
            Kind::LinuxPackage(PackageFormat::Deb) => "linux-x86_64.deb",
            Kind::LinuxPackage(PackageFormat::Rpm) => "linux-x86_64.rpm",
            Kind::LinuxPackage(PackageFormat::Pacman) => "linux-x86_64.pkg.tar.zst",
            Kind::LinuxUser { .. } => "linux-x86_64.tar.gz",
            Kind::Unsupported(_) => return None,
        };
        Some(format!("telepad-v{version}-{tail}"))
    }

    /// Whether Telepad can install an update itself.
    pub fn can_update_itself(&self) -> bool {
        !matches!(self, Kind::Unsupported(_))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts(os: Os, exe: &str) -> Facts {
        Facts {
            os,
            arch: "x86_64".into(),
            exe: PathBuf::from(exe),
            local_app_data: Some(PathBuf::from(r"C:\Users\Me\AppData\Local")),
        }
    }

    fn owned_by(format: Option<PackageFormat>) -> impl Fn(&Path) -> Option<PackageFormat> {
        move |_| format
    }

    fn classify_with(facts: &Facts, owner: Option<PackageFormat>, writable: bool) -> Kind {
        classify(facts, &owned_by(owner), &|_| writable)
    }

    #[test]
    fn the_windows_installer_puts_the_program_in_the_persons_program_folder() {
        let kind = classify_with(
            &facts(
                Os::Windows,
                r"C:\Users\Me\AppData\Local\Programs\Telepad\telepad.exe",
            ),
            None,
            true,
        );
        assert_eq!(kind, Kind::WindowsInstaller);
        // Windows does not care about capitals in a path.
        let kind = classify_with(
            &facts(
                Os::Windows,
                r"c:\users\me\appdata\local\programs\TELEPAD\Telepad.exe",
            ),
            None,
            true,
        );
        assert_eq!(kind, Kind::WindowsInstaller);
    }

    #[test]
    fn a_windows_path_in_the_long_form_is_the_same_place() {
        let kind = classify_with(
            &facts(
                Os::Windows,
                r"\\?\C:\Users\Me\AppData\Local\Programs\Telepad\telepad.exe",
            ),
            None,
            true,
        );
        assert_eq!(kind, Kind::WindowsInstaller);
    }

    #[test]
    fn a_windows_program_run_from_the_downloads_folder_is_left_alone() {
        for exe in [
            r"C:\Users\Me\Downloads\telepad-windows-x86_64.exe",
            r"C:\Users\Me\AppData\Local\Programs\TelepadEvil\telepad.exe",
            r"C:\Users\Me\AppData\Local\Programs\Telepad",
        ] {
            assert!(
                matches!(
                    classify_with(&facts(Os::Windows, exe), None, true),
                    Kind::Unsupported(_)
                ),
                "{exe}"
            );
        }
    }

    #[test]
    fn a_mac_app_is_found_by_its_folder() {
        let kind = classify_with(
            &facts(
                Os::MacOs,
                "/Applications/Telepad.app/Contents/MacOS/telepad",
            ),
            None,
            true,
        );
        assert_eq!(
            kind,
            Kind::MacApp {
                app: PathBuf::from("/Applications/Telepad.app")
            }
        );
        let kind = classify_with(
            &facts(
                Os::MacOs,
                "/Users/me/Applications/Telepad.app/Contents/MacOS/telepad",
            ),
            None,
            true,
        );
        assert_eq!(
            kind,
            Kind::MacApp {
                app: PathBuf::from("/Users/me/Applications/Telepad.app")
            }
        );
    }

    #[test]
    fn a_mac_program_that_is_not_the_app_or_that_homebrew_owns_is_left_alone() {
        for exe in [
            "/usr/local/bin/telepad",
            "/Users/me/Downloads/telepad",
            "/opt/homebrew/Caskroom/telepad/2.0.0/Telepad.app/Contents/MacOS/telepad",
            "/opt/homebrew/Cellar/telepad/2.0.0/bin/telepad",
        ] {
            assert!(
                matches!(
                    classify_with(&facts(Os::MacOs, exe), None, true),
                    Kind::Unsupported(_)
                ),
                "{exe}"
            );
        }
    }

    #[test]
    fn a_linux_package_is_known_by_who_owns_the_file() {
        for format in [
            PackageFormat::Deb,
            PackageFormat::Rpm,
            PackageFormat::Pacman,
        ] {
            let kind = classify_with(&facts(Os::Linux, "/usr/bin/telepad"), Some(format), false);
            assert_eq!(kind, Kind::LinuxPackage(format));
        }
        assert!(
            matches!(
                classify_with(&facts(Os::Linux, "/usr/bin/telepad"), None, false),
                Kind::Unsupported(_)
            ),
            "no package owns it"
        );
    }

    #[test]
    fn a_linux_program_in_the_home_folder_is_swapped_in_place() {
        let kind = classify_with(&facts(Os::Linux, "/home/me/.local/bin/telepad"), None, true);
        assert_eq!(
            kind,
            Kind::LinuxUser {
                exe: PathBuf::from("/home/me/.local/bin/telepad")
            }
        );
        assert!(
            matches!(
                classify_with(&facts(Os::Linux, "/opt/telepad/telepad"), None, false),
                Kind::Unsupported(_)
            ),
            "a folder only an administrator can change"
        );
    }

    #[test]
    fn a_program_that_cargo_built_is_never_replaced() {
        for exe in [
            "/home/me/telepad/target/debug/telepad",
            "/home/me/telepad/target/release/telepad",
            "/home/me/telepad/target/x86_64-pc-windows-gnu/release/telepad",
        ] {
            assert!(
                matches!(
                    classify_with(&facts(Os::Linux, exe), None, true),
                    Kind::Unsupported(_)
                ),
                "{exe}"
            );
        }
    }

    #[test]
    fn there_is_no_build_for_other_processors_yet() {
        let mut arm = facts(Os::Linux, "/home/me/.local/bin/telepad");
        arm.arch = "aarch64".into();
        assert!(matches!(
            classify_with(&arm, None, true),
            Kind::Unsupported(_)
        ));
        // The Mac download is universal.
        let mut mac = facts(
            Os::MacOs,
            "/Applications/Telepad.app/Contents/MacOS/telepad",
        );
        mac.arch = "aarch64".into();
        assert!(matches!(
            classify_with(&mac, None, true),
            Kind::MacApp { .. }
        ));
    }

    #[test]
    fn each_kind_takes_its_own_file_of_the_release() {
        let name = |kind: Kind| kind.asset_name("2.0.0-alpha.4");
        assert_eq!(
            name(Kind::WindowsInstaller).unwrap(),
            "telepad-v2.0.0-alpha.4-windows-x86_64-setup.exe"
        );
        assert_eq!(
            name(Kind::MacApp {
                app: "/Applications/Telepad.app".into()
            })
            .unwrap(),
            "telepad-v2.0.0-alpha.4-macos-universal.dmg"
        );
        assert_eq!(
            name(Kind::LinuxPackage(PackageFormat::Deb)).unwrap(),
            "telepad-v2.0.0-alpha.4-linux-x86_64.deb"
        );
        assert_eq!(
            name(Kind::LinuxPackage(PackageFormat::Rpm)).unwrap(),
            "telepad-v2.0.0-alpha.4-linux-x86_64.rpm"
        );
        assert_eq!(
            name(Kind::LinuxPackage(PackageFormat::Pacman)).unwrap(),
            "telepad-v2.0.0-alpha.4-linux-x86_64.pkg.tar.zst"
        );
        assert_eq!(
            name(Kind::LinuxUser { exe: "/x".into() }).unwrap(),
            "telepad-v2.0.0-alpha.4-linux-x86_64.tar.gz"
        );
        assert_eq!(name(Kind::Unsupported("x".into())), None);
    }
}
