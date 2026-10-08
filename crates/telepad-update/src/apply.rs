//! Putting a downloaded, checked update in place, and starting the new Telepad when the old one has quit.
//!
//! Each way of installing has its own way of being updated (see [`Kind`]). All of them end the same way: this
//! function returns, the caller quits, and a small helper that was started first waits for that and then starts
//! the new program. Nothing here runs before the file's checksum has been checked (see [`crate::fetch`]).

use crate::kind::{Kind, PackageFormat};
use crate::system::{self, Commands};
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

/// Why an update was not put in place.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Error {
    /// The person closed the password prompt. Nothing was changed.
    #[error("The update was not installed: the password prompt was closed.")]
    Declined,
    /// It has to be done by hand, which `how` explains. The file stays where it is for that.
    #[error("{why}")]
    ByHand { why: String, how: Option<String> },
    #[error("The update did not install: {0}")]
    Failed(String),
}

/// What the update is installed into.
pub struct Plan<'a> {
    pub kind: &'a Kind,
    /// The downloaded file, already checked.
    pub file: &'a Path,
    /// Its SHA-256 (64 hex digits), for a step that runs with more rights than this program to check again.
    pub sha256: &'a str,
    /// A folder for unpacking, which the caller makes and removes.
    pub work: &'a Path,
    /// Whether Telepad starts at login now: an update leaves that as it is.
    pub autostart: bool,
    /// The running program's process id, whose end the helper waits for.
    pub pid: u32,
}

/// Installs the update. On `Ok` the new program is in place and a helper will start it as soon as this
/// process has gone: the caller should quit now.
pub fn apply(commands: &dyn Commands, plan: &Plan<'_>) -> Result<(), Error> {
    match plan.kind {
        Kind::WindowsInstaller => windows(commands, plan),
        Kind::MacApp { app } => macos(commands, plan, app),
        Kind::LinuxPackage(format) => linux_package(commands, plan, *format),
        Kind::LinuxUser { exe } => linux_user(commands, plan, exe),
        Kind::Unsupported(why) => Err(Error::ByHand {
            why: why.clone(),
            how: None,
        }),
    }
}

fn os(text: &str) -> &OsStr {
    OsStr::new(text)
}

fn failed(what: &str, error: impl std::fmt::Display) -> Error {
    Error::Failed(format!("{what}: {error}"))
}

// ── Windows ──────────────────────────────────────────────────────────────

/// The installer is Inno Setup's: `/VERYSILENT` shows nothing, `/RELAUNCH=1` makes it start the program when it
/// is done (the installer asks the running one to quit first, as it always does), and `/MERGETASKS` keeps
/// starting at login the way the person has it.
fn windows(commands: &dyn Commands, plan: &Plan<'_>) -> Result<(), Error> {
    let tasks = if plan.autostart {
        "/MERGETASKS=autostart"
    } else {
        "/MERGETASKS=!autostart"
    };
    commands
        .spawn_detached(
            &plan.file.to_string_lossy(),
            &[
                os("/VERYSILENT"),
                os("/SUPPRESSMSGBOXES"),
                os("/NORESTART"),
                os("/RELAUNCH=1"),
                os(tasks),
            ],
        )
        .map_err(|error| failed("The installer could not be started", error))
}

// ── macOS ────────────────────────────────────────────────────────────────

/// Opens the disk image, copies the app in beside the old one, swaps them, and starts the new one.
fn macos(commands: &dyn Commands, plan: &Plan<'_>, app: &Path) -> Result<(), Error> {
    let parent = app
        .parent()
        .ok_or_else(|| Error::Failed("Telepad cannot tell where its app is.".into()))?;
    if !system::can_replace_in(parent) {
        return Err(Error::ByHand {
            why: format!(
                "Telepad cannot change {}, which is yours to change only with an administrator's password.",
                parent.display()
            ),
            how: Some("Open the disk image and drag Telepad onto Applications.".into()),
        });
    }

    let mount = plan.work.join("image");
    std::fs::create_dir_all(&mount).map_err(|e| failed("Telepad could not make a folder", e))?;
    let attach = commands
        .run(
            "hdiutil",
            &[
                os("attach"),
                os("-nobrowse"),
                os("-readonly"),
                os("-noautoopen"),
                os("-mountpoint"),
                mount.as_os_str(),
                plan.file.as_os_str(),
            ],
        )
        .map_err(|e| failed("The disk image could not be opened", e))?;
    if !attach.status.success() {
        return Err(Error::Failed(format!(
            "The disk image could not be opened: {}",
            String::from_utf8_lossy(&attach.stderr).trim()
        )));
    }

    let swapped = swap_in(commands, &mount.join("Telepad.app"), app);
    // Whatever happened, the image is closed again.
    let detach = |force: bool| {
        let mut args = vec![os("detach"), mount.as_os_str()];
        if force {
            args.push(os("-force"));
        }
        commands
            .run("hdiutil", &args)
            .is_ok_and(|o| o.status.success())
    };
    if !detach(false) {
        detach(true);
    }
    swapped?;

    commands
        .spawn_detached(
            "sh",
            &relaunch(
                plan.pid,
                &[
                    "/usr/bin/open",
                    "-n",
                    &app.to_string_lossy(),
                    "--args",
                    "--background",
                ],
            )
            .iter()
            .map(|s| os(s))
            .collect::<Vec<_>>(),
        )
        .map_err(|e| failed("The new Telepad could not be started", e))
}

/// Copies `new` next to `app` and trades places with it, so that at no moment is there no app.
fn swap_in(commands: &dyn Commands, new: &Path, app: &Path) -> Result<(), Error> {
    if !new.is_dir() {
        return Err(Error::Failed(
            "The disk image has no Telepad app in it.".into(),
        ));
    }
    let beside = |suffix: &str| {
        let mut name = app.file_name().unwrap_or_default().to_os_string();
        name.push(suffix);
        app.with_file_name(name)
    };
    let (staged, old) = (beside(".new"), beside(".old"));
    remove_folder(&staged);
    remove_folder(&old);

    // `ditto` keeps what a Mac app is made of: permissions, links and the signature's extended attributes.
    let copy = commands
        .run("ditto", &[new.as_os_str(), staged.as_os_str()])
        .map_err(|e| failed("The new app could not be copied", e))?;
    if !copy.status.success() {
        remove_folder(&staged);
        return Err(Error::Failed(format!(
            "The new app could not be copied: {}",
            String::from_utf8_lossy(&copy.stderr).trim()
        )));
    }
    std::fs::rename(app, &old).map_err(|e| {
        remove_folder(&staged);
        failed("The old app could not be moved aside", e)
    })?;
    if let Err(error) = std::fs::rename(&staged, app) {
        // Put things back as they were.
        let _ = std::fs::rename(&old, app);
        remove_folder(&staged);
        return Err(failed("The new app could not be put in place", error));
    }
    remove_folder(&old);
    Ok(())
}

fn remove_folder(path: &Path) {
    if path.exists() {
        let _ = std::fs::remove_dir_all(path);
    }
}

// ── Linux ────────────────────────────────────────────────────────────────

/// What runs as root to install a system package: it copies the file to a folder only root can enter, checks
/// the copy against the SHA-256 that was published, and only then hands it to the package manager. The check is
/// made here, at the last moment, on the copy that is installed, so that nothing the person's own programs could
/// do to the downloaded file between the check in this program and the install changes what root installs.
/// Arguments: the file, its checksum, its name, and then the package manager's command (without the file).
const VERIFY_AND_INSTALL: &str = "set -eu\n\
file=$1 sum=$2 name=$3\n\
shift 3\n\
dir=$(mktemp -d)\n\
trap 'rm -rf \"$dir\"' EXIT\n\
cp -- \"$file\" \"$dir/$name\"\n\
printf '%s  %s\\n' \"$sum\" \"$dir/$name\" | sha256sum -c --quiet -\n\
\"$@\" \"$dir/$name\"\n";

/// A system package is the package manager's to install, and that needs an administrator: `pkexec` shows the
/// system's own password prompt.
fn linux_package(
    commands: &dyn Commands,
    plan: &Plan<'_>,
    format: PackageFormat,
) -> Result<(), Error> {
    let file = plan.file.to_string_lossy().into_owned();
    let name = plan
        .file
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or_else(|| Error::Failed("The download has no name.".into()))?
        .to_owned();
    // The package manager's command, without the file it installs.
    let manager: Vec<String> = match format {
        PackageFormat::Deb => [
            "env",
            "DEBIAN_FRONTEND=noninteractive",
            "apt-get",
            "install",
            "-y",
        ]
        .map(String::from)
        .to_vec(),
        PackageFormat::Pacman => ["pacman", "-U", "--noconfirm"].map(String::from).to_vec(),
        PackageFormat::Rpm => {
            if system::exists(commands, "dnf") {
                ["dnf", "install", "-y"].map(String::from).to_vec()
            } else if system::exists(commands, "zypper") {
                [
                    "zypper",
                    "--non-interactive",
                    "install",
                    "--allow-unsigned-rpm",
                ]
                .map(String::from)
                .to_vec()
            } else {
                ["yum", "install", "-y"].map(String::from).to_vec()
            }
        }
    };
    // For a person to run themselves when this program cannot ask for the password.
    let by_hand = |why: &str| Error::ByHand {
        why: why.to_owned(),
        how: Some(format!(
            "sudo {} {file}",
            manager
                .join(" ")
                .replace("env DEBIAN_FRONTEND=noninteractive ", "")
        )),
    };

    if !system::exists(commands, "pkexec") {
        return Err(by_hand(
            "Installing a system package needs an administrator's password, and this system has no way to ask for it here.",
        ));
    }
    let mut args: Vec<&OsStr> = vec![
        os("/bin/sh"),
        os("-c"),
        os(VERIFY_AND_INSTALL),
        os("telepad-update"),
        plan.file.as_os_str(),
        os(plan.sha256),
        os(&name),
    ];
    args.extend(manager.iter().map(|part| os(part)));
    let output = commands
        .run("pkexec", &args)
        .map_err(|e| failed("The password prompt could not be shown", e))?;
    match output.status.code() {
        Some(0) => {}
        // pkexec: 126 is a prompt that was closed, 127 is one that could not be shown or a password refused.
        Some(126) => return Err(Error::Declined),
        Some(127) => {
            return Err(by_hand(
                "The administrator's password was not given, so the update was not installed.",
            ))
        }
        _ => {
            let said = String::from_utf8_lossy(&output.stderr);
            let last = said
                .trim()
                .lines()
                .last()
                .unwrap_or("the package manager failed");
            return Err(Error::Failed(last.to_owned()));
        }
    }

    // The package replaced the program file; the next start is the new one.
    let exe = PathBuf::from("/usr/bin/telepad");
    commands
        .spawn_detached(
            "sh",
            &relaunch(plan.pid, &[&exe.to_string_lossy(), "--background"])
                .iter()
                .map(|s| os(s))
                .collect::<Vec<_>>(),
        )
        .map_err(|e| failed("The new Telepad could not be started", e))
}

/// A tarball install is the person's own file: unpack the new program and put it where the old one is.
fn linux_user(commands: &dyn Commands, plan: &Plan<'_>, exe: &Path) -> Result<(), Error> {
    let name = plan
        .file
        .file_name()
        .and_then(OsStr::to_str)
        .and_then(|name| name.strip_suffix(".tar.gz"))
        .ok_or_else(|| Error::Failed("The download is not the tarball Telepad expected.".into()))?;
    let unpacked = plan.work.join("unpacked");
    std::fs::create_dir_all(&unpacked).map_err(|e| failed("Telepad could not make a folder", e))?;
    let member = format!("{name}/telepad");
    let output = commands
        .run(
            "tar",
            &[
                os("-xzf"),
                plan.file.as_os_str(),
                os("-C"),
                unpacked.as_os_str(),
                os("--no-same-owner"),
                os(&member),
            ],
        )
        .map_err(|e| failed("The download could not be unpacked", e))?;
    if !output.status.success() {
        return Err(Error::Failed(format!(
            "The download could not be unpacked: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )));
    }

    let new = unpacked.join(&member);
    let mut magic = [0u8; 4];
    let is_program = std::fs::File::open(&new)
        .and_then(|mut file| std::io::Read::read_exact(&mut file, &mut magic))
        .is_ok()
        && &magic == b"\x7fELF";
    if !is_program {
        return Err(Error::Failed(
            "The download does not hold the Telepad program.".into(),
        ));
    }
    replace_file(&new, exe).map_err(|e| failed("The program could not be replaced", e))?;

    commands
        .spawn_detached(
            "sh",
            &relaunch(plan.pid, &[&exe.to_string_lossy(), "--background"])
                .iter()
                .map(|s| os(s))
                .collect::<Vec<_>>(),
        )
        .map_err(|e| failed("The new Telepad could not be started", e))
}

/// Puts a copy of `new` at `target` in one step: a program that is running keeps its old file until it quits.
fn replace_file(new: &Path, target: &Path) -> std::io::Result<()> {
    let mut name = target.file_name().unwrap_or_default().to_os_string();
    name.push(".new");
    let staged = target.with_file_name(name);
    let _ = std::fs::remove_file(&staged);
    std::fs::copy(new, &staged)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&staged, std::fs::Permissions::from_mode(0o755))?;
    }
    std::fs::rename(&staged, target).inspect_err(|_| {
        let _ = std::fs::remove_file(&staged);
    })
}

// ── Starting the new one ─────────────────────────────────────────────────

/// The arguments for `sh`: wait (for up to half a minute) for process `pid` to end, then run `command`.
fn relaunch(pid: u32, command: &[&str]) -> Vec<String> {
    let mut args = vec![
        "-c".to_owned(),
        "i=0; while kill -0 \"$1\" 2>/dev/null && [ \"$i\" -lt 150 ]; do sleep 0.2; i=$((i+1)); done; shift; exec \"$@\""
            .to_owned(),
        "telepad-relaunch".to_owned(),
        pid.to_string(),
    ];
    args.extend(command.iter().map(|s| (*s).to_owned()));
    args
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::system::fake::Script;
    #[cfg(unix)]
    use crate::system::System;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("telepad-apply-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    const SUM: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    fn plan<'a>(kind: &'a Kind, file: &'a Path, work: &'a Path) -> Plan<'a> {
        Plan {
            kind,
            file,
            sha256: SUM,
            work,
            autostart: true,
            pid: 4242,
        }
    }

    // ── Windows ──────────────────────────────────────────────────────

    #[test]
    fn windows_runs_the_installer_quietly_and_keeps_start_at_login_as_it_is() {
        let script = Script::default();
        let file = Path::new(r"C:\tmp\telepad-v2.0.0-alpha.4-windows-x86_64-setup.exe");
        let mut p = plan(&Kind::WindowsInstaller, file, Path::new("."));
        apply(&script, &p).unwrap();
        p.autostart = false;
        apply(&script, &p).unwrap();
        let spawned = script.spawned();
        assert_eq!(
            spawned[0],
            r"C:\tmp\telepad-v2.0.0-alpha.4-windows-x86_64-setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /RELAUNCH=1 /MERGETASKS=autostart"
        );
        assert!(
            spawned[1].ends_with("/MERGETASKS=!autostart"),
            "{}",
            spawned[1]
        );
        assert!(script.ran().is_empty());
    }

    // ── Linux packages ───────────────────────────────────────────────

    fn package(format: PackageFormat, script: &dyn Commands) -> Result<(), Error> {
        let kind = Kind::LinuxPackage(format);
        apply(
            script,
            &plan(&kind, Path::new("/tmp/t/telepad.pkg"), Path::new("/tmp/t")),
        )
    }

    /// The command line that was run as root, without the script: the file, its checksum and name, then the manager.
    fn root_arguments(script: &Script) -> String {
        let ran = script.ran();
        let line = ran
            .iter()
            .find(|l| l.starts_with("pkexec /bin/sh -c "))
            .expect("pkexec ran");
        line.rsplit_once("\ntelepad-update ")
            .or_else(|| line.rsplit_once(" telepad-update "))
            .expect("the script is followed by its arguments")
            .1
            .to_owned()
    }

    #[test]
    fn each_package_manager_is_asked_to_install_the_file_as_the_one_command_installer_does() {
        let at = |manager: &str| format!("/tmp/t/telepad.pkg {SUM} telepad.pkg {manager}");

        let deb = Script::default();
        package(PackageFormat::Deb, &deb).unwrap();
        assert_eq!(
            root_arguments(&deb),
            at("env DEBIAN_FRONTEND=noninteractive apt-get install -y")
        );

        let arch = Script::default();
        package(PackageFormat::Pacman, &arch).unwrap();
        assert_eq!(root_arguments(&arch), at("pacman -U --noconfirm"));

        let fedora = Script::default();
        package(PackageFormat::Rpm, &fedora).unwrap();
        assert_eq!(root_arguments(&fedora), at("dnf install -y"));

        let suse = Script::default();
        suse.missing("dnf");
        package(PackageFormat::Rpm, &suse).unwrap();
        assert_eq!(
            root_arguments(&suse),
            at("zypper --non-interactive install --allow-unsigned-rpm")
        );

        let old = Script::default();
        old.missing("dnf").missing("zypper");
        package(PackageFormat::Rpm, &old).unwrap();
        assert_eq!(root_arguments(&old), at("yum install -y"));
    }

    /// Runs the real script that root runs, with a stand-in for the package manager that records what it was given.
    #[cfg(unix)]
    fn run_as_root_would(
        dir: &Path,
        file: &Path,
        sum: &str,
    ) -> (std::process::Output, Option<(String, Vec<u8>)>) {
        use std::os::unix::fs::PermissionsExt;
        let manager = dir.join("fake-manager");
        let log = dir.join("manager.log");
        std::fs::write(
            &manager,
            format!("#!/bin/sh\nfor last; do :; done\nprintf '%s\\n' \"$last\" > '{}'\ncat \"$last\" > '{}.content'\n", log.display(), log.display()),
        )
        .unwrap();
        std::fs::set_permissions(&manager, std::fs::Permissions::from_mode(0o755)).unwrap();
        let name = file.file_name().unwrap();
        let output = std::process::Command::new("/bin/sh")
            .args(["-c", VERIFY_AND_INSTALL, "telepad-update"])
            .arg(file)
            .arg(sum)
            .arg(name)
            .arg(&manager)
            .arg("install")
            .output()
            .unwrap();
        let installed = std::fs::read_to_string(&log).ok().map(|path| {
            (
                path.trim().to_owned(),
                std::fs::read(format!("{}.content", log.display())).unwrap(),
            )
        });
        (output, installed)
    }

    #[cfg(unix)]
    #[test]
    fn what_root_installs_is_a_private_copy_that_matches_the_published_checksum() {
        let dir = temp("root-ok");
        let file = dir.join("telepad.deb");
        std::fs::write(&file, b"abc").unwrap();
        let (output, installed) = run_as_root_would(&dir, &file, SUM);
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let (path, content) = installed.expect("the manager ran");
        assert_eq!(content, b"abc");
        assert_ne!(
            Path::new(&path),
            file,
            "it installs a copy, not the person's file"
        );
        assert!(
            path.ends_with("/telepad.deb"),
            "the name is kept, for managers that look at it: {path}"
        );
        assert!(!Path::new(&path).exists(), "the copy is gone afterwards");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn a_file_that_changed_after_the_check_is_caught_by_the_check_root_makes() {
        let dir = temp("root-bad");
        let file = dir.join("telepad.deb");
        std::fs::write(&file, b"abd").unwrap(); // not what SUM is the checksum of
        let (output, installed) = run_as_root_would(&dir, &file, SUM);
        assert!(!output.status.success());
        assert!(installed.is_none(), "the package manager was never run");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn after_a_package_the_installed_program_is_started_once_this_one_has_gone() {
        let script = Script::default();
        package(PackageFormat::Deb, &script).unwrap();
        let spawned = script.spawned();
        assert_eq!(spawned.len(), 1);
        assert!(spawned[0].starts_with("sh -c "), "{}", spawned[0]);
        assert!(
            spawned[0].ends_with("telepad-relaunch 4242 /usr/bin/telepad --background"),
            "{}",
            spawned[0]
        );
    }

    #[test]
    fn a_closed_password_prompt_changes_nothing_and_says_so() {
        let script = Script::default();
        script.exit("pkexec", 126);
        assert_eq!(
            package(PackageFormat::Deb, &script).unwrap_err(),
            Error::Declined
        );
        assert!(script.spawned().is_empty(), "nothing is restarted");
    }

    #[test]
    fn without_a_way_to_ask_for_the_password_the_command_is_given_to_run_by_hand() {
        let script = Script::default();
        script.missing("pkexec");
        let Error::ByHand { how, .. } = package(PackageFormat::Deb, &script).unwrap_err() else {
            panic!("by hand");
        };
        assert_eq!(
            how.as_deref(),
            Some("sudo apt-get install -y /tmp/t/telepad.pkg")
        );

        let refused = Script::default();
        refused.exit("pkexec", 127);
        assert!(
            matches!(package(PackageFormat::Pacman, &refused).unwrap_err(), Error::ByHand { how: Some(how), .. } if how == "sudo pacman -U --noconfirm /tmp/t/telepad.pkg")
        );
    }

    #[test]
    #[cfg(unix)]
    fn a_package_manager_that_fails_says_its_last_word() {
        let script = Script::default();
        script.exit("pkexec", 100).says(
            "pkexec",
            "Reading package lists...\nE: Unable to locate package\n",
        );
        assert_eq!(
            package(PackageFormat::Deb, &script).unwrap_err(),
            Error::Failed("E: Unable to locate package".into())
        );
    }

    // ── Linux, from the tarball ──────────────────────────────────────

    #[cfg(unix)]
    fn make_tarball(dir: &Path, name: &str, program: &[u8]) -> PathBuf {
        let stage = dir.join("stage").join(name);
        std::fs::create_dir_all(&stage).unwrap();
        std::fs::write(stage.join("telepad"), program).unwrap();
        std::fs::write(stage.join("README.txt"), "hello").unwrap();
        let tarball = dir.join(format!("{name}.tar.gz"));
        let status = std::process::Command::new("tar")
            .args(["-czf"])
            .arg(&tarball)
            .arg("-C")
            .arg(dir.join("stage"))
            .arg(name)
            .status()
            .unwrap();
        assert!(status.success());
        tarball
    }

    #[cfg(unix)]
    #[test]
    fn a_tarball_install_swaps_the_program_and_starts_the_new_one() {
        use std::os::unix::fs::PermissionsExt;
        let dir = temp("user");
        let name = "telepad-v2.0.0-alpha.4-linux-x86_64";
        let tarball = make_tarball(&dir, name, b"\x7fELF the new program");
        let bin = dir.join("home/.local/bin");
        std::fs::create_dir_all(&bin).unwrap();
        let exe = bin.join("telepad");
        std::fs::write(&exe, b"\x7fELF the old program").unwrap();
        std::fs::set_permissions(&exe, std::fs::Permissions::from_mode(0o755)).unwrap();
        let work = dir.join("work");
        std::fs::create_dir_all(&work).unwrap();

        let kind = Kind::LinuxUser { exe: exe.clone() };
        let script = RealTar::default();
        apply(&script, &plan(&kind, &tarball, &work)).unwrap();

        assert_eq!(std::fs::read(&exe).unwrap(), b"\x7fELF the new program");
        assert_eq!(
            std::fs::metadata(&exe).unwrap().permissions().mode() & 0o777,
            0o755
        );
        let leftovers: Vec<_> = std::fs::read_dir(&bin)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(
            leftovers,
            ["telepad"],
            "no staging file is left in the folder"
        );
        let spawned = script.spawned.lock().unwrap().clone();
        assert_eq!(spawned.len(), 1);
        assert!(
            spawned[0].ends_with(&format!(
                "telepad-relaunch 4242 {} --background",
                exe.display()
            )),
            "{}",
            spawned[0]
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Runs real programs (`tar`), and remembers what would have been started.
    #[cfg(unix)]
    #[derive(Default)]
    struct RealTar {
        spawned: std::sync::Mutex<Vec<String>>,
    }
    #[cfg(unix)]
    impl Commands for RealTar {
        fn run(&self, program: &str, args: &[&OsStr]) -> std::io::Result<std::process::Output> {
            System.run(program, args)
        }
        fn spawn_detached(&self, program: &str, args: &[&OsStr]) -> std::io::Result<()> {
            let mut line = program.to_owned();
            for arg in args {
                line.push(' ');
                line.push_str(&arg.to_string_lossy());
            }
            self.spawned.lock().unwrap().push(line);
            Ok(())
        }
    }

    #[cfg(unix)]
    #[test]
    fn a_tarball_without_the_program_in_it_changes_nothing() {
        let dir = temp("user-bad");
        let name = "telepad-v2.0.0-alpha.4-linux-x86_64";
        let work = dir.join("work");
        std::fs::create_dir_all(&work).unwrap();
        let exe = dir.join("telepad");
        std::fs::write(&exe, b"\x7fELF old").unwrap();
        let kind = Kind::LinuxUser { exe: exe.clone() };

        // Not a program (no ELF header): refused, and the old one stays.
        let tarball = make_tarball(&dir, name, b"#!/bin/sh\necho not telepad\n");
        let error = apply(&RealTar::default(), &plan(&kind, &tarball, &work)).unwrap_err();
        assert_eq!(
            error,
            Error::Failed("The download does not hold the Telepad program.".into())
        );
        assert_eq!(std::fs::read(&exe).unwrap(), b"\x7fELF old");

        // The tarball's own name decides the folder inside it: a different one holds nothing to take.
        let other = make_tarball(&dir, "something-else", b"\x7fELF x");
        let renamed = dir.join(format!("{name}.tar.gz"));
        std::fs::rename(&other, &renamed).unwrap();
        assert!(matches!(
            apply(&RealTar::default(), &plan(&kind, &renamed, &work)).unwrap_err(),
            Error::Failed(_)
        ));
        assert_eq!(std::fs::read(&exe).unwrap(), b"\x7fELF old");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn a_folder_that_cannot_be_written_to_leaves_the_old_program_and_says_so() {
        use std::os::unix::fs::PermissionsExt;
        if unsafe { libc::geteuid() } == 0 {
            return; // root can write anywhere
        }
        let dir = temp("user-ro");
        let name = "telepad-v2.0.0-alpha.4-linux-x86_64";
        let tarball = make_tarball(&dir, name, b"\x7fELF new");
        let bin = dir.join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let exe = bin.join("telepad");
        std::fs::write(&exe, b"\x7fELF old").unwrap();
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o555)).unwrap();
        let work = dir.join("work");
        std::fs::create_dir_all(&work).unwrap();

        let kind = Kind::LinuxUser { exe: exe.clone() };
        let error = apply(&RealTar::default(), &plan(&kind, &tarball, &work)).unwrap_err();
        assert!(
            matches!(error, Error::Failed(ref m) if m.starts_with("The program could not be replaced")),
            "{error:?}"
        );
        assert_eq!(std::fs::read(&exe).unwrap(), b"\x7fELF old");
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ── macOS ────────────────────────────────────────────────────────

    /// Plays the part of `hdiutil` and `ditto` with plain file copies: the "image" is a folder.
    struct FakeMac {
        image: PathBuf,
        ran: std::sync::Mutex<Vec<String>>,
        spawned: std::sync::Mutex<Vec<String>>,
        fail_attach: bool,
    }
    impl Commands for FakeMac {
        fn run(&self, program: &str, args: &[&OsStr]) -> std::io::Result<std::process::Output> {
            let line = args
                .iter()
                .map(|a| a.to_string_lossy())
                .collect::<Vec<_>>()
                .join(" ");
            self.ran.lock().unwrap().push(format!("{program} {line}"));
            let script = Script::default();
            if program == "hdiutil" && args[0] == "attach" && self.fail_attach {
                script.exit("hdiutil", 1);
            }
            if program == "hdiutil" && args[0] == "attach" && !self.fail_attach {
                // Mount: the mountpoint becomes the image's contents.
                let mount = Path::new(args[args.len() - 2]);
                copy_dir(&self.image, mount);
            }
            if program == "ditto" {
                copy_dir(Path::new(args[0]), Path::new(args[1]));
            }
            script.run(program, args)
        }
        fn spawn_detached(&self, program: &str, args: &[&OsStr]) -> std::io::Result<()> {
            let line = args
                .iter()
                .map(|a| a.to_string_lossy())
                .collect::<Vec<_>>()
                .join(" ");
            self.spawned
                .lock()
                .unwrap()
                .push(format!("{program} {line}"));
            Ok(())
        }
    }

    fn copy_dir(from: &Path, to: &Path) {
        std::fs::create_dir_all(to).unwrap();
        for entry in std::fs::read_dir(from).unwrap() {
            let entry = entry.unwrap();
            let target = to.join(entry.file_name());
            if entry.file_type().unwrap().is_dir() {
                copy_dir(&entry.path(), &target);
            } else {
                std::fs::copy(entry.path(), target).unwrap();
            }
        }
    }

    fn mac_fixture(name: &str) -> (PathBuf, PathBuf, PathBuf, PathBuf) {
        let dir = temp(name);
        let image = dir.join("dmg-contents");
        std::fs::create_dir_all(image.join("Telepad.app/Contents/MacOS")).unwrap();
        std::fs::write(image.join("Telepad.app/Contents/MacOS/telepad"), "new").unwrap();
        let apps = dir.join("Applications");
        std::fs::create_dir_all(apps.join("Telepad.app/Contents/MacOS")).unwrap();
        std::fs::write(apps.join("Telepad.app/Contents/MacOS/telepad"), "old").unwrap();
        let work = dir.join("work");
        std::fs::create_dir_all(&work).unwrap();
        (dir, image, apps, work)
    }

    #[test]
    fn a_mac_app_is_swapped_for_the_one_in_the_disk_image_and_the_image_is_closed() {
        let (dir, image, apps, work) = mac_fixture("mac");
        let app = apps.join("Telepad.app");
        let kind = Kind::MacApp { app: app.clone() };
        let mac = FakeMac {
            image,
            ran: Default::default(),
            spawned: Default::default(),
            fail_attach: false,
        };

        apply(&mac, &plan(&kind, Path::new("/tmp/Telepad.dmg"), &work)).unwrap();

        assert_eq!(
            std::fs::read_to_string(app.join("Contents/MacOS/telepad")).unwrap(),
            "new"
        );
        let names: Vec<_> = std::fs::read_dir(&apps)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            names,
            ["Telepad.app"],
            "neither the staged copy nor the old app is left: {names:?}"
        );
        let ran = mac.ran.lock().unwrap().clone();
        assert!(
            ran[0].starts_with("hdiutil attach -nobrowse -readonly -noautoopen -mountpoint"),
            "{ran:?}"
        );
        assert!(
            ran.last().unwrap().starts_with("hdiutil detach "),
            "{ran:?}"
        );
        let spawned = mac.spawned.lock().unwrap().clone();
        assert!(
            spawned[0].ends_with(&format!(
                "telepad-relaunch 4242 /usr/bin/open -n {} --args --background",
                app.display()
            )),
            "{spawned:?}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_disk_image_that_will_not_open_changes_nothing() {
        let (dir, image, apps, work) = mac_fixture("mac-bad");
        let app = apps.join("Telepad.app");
        let kind = Kind::MacApp { app: app.clone() };
        let mac = FakeMac {
            image,
            ran: Default::default(),
            spawned: Default::default(),
            fail_attach: true,
        };
        assert!(matches!(
            apply(&mac, &plan(&kind, Path::new("/tmp/Telepad.dmg"), &work)).unwrap_err(),
            Error::Failed(_)
        ));
        assert_eq!(
            std::fs::read_to_string(app.join("Contents/MacOS/telepad")).unwrap(),
            "old"
        );
        assert!(mac.spawned.lock().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_image_without_the_app_changes_nothing_and_is_still_closed() {
        let (dir, image, apps, work) = mac_fixture("mac-empty");
        std::fs::remove_dir_all(image.join("Telepad.app")).unwrap();
        let app = apps.join("Telepad.app");
        let kind = Kind::MacApp { app: app.clone() };
        let mac = FakeMac {
            image,
            ran: Default::default(),
            spawned: Default::default(),
            fail_attach: false,
        };
        let error = apply(&mac, &plan(&kind, Path::new("/tmp/Telepad.dmg"), &work)).unwrap_err();
        assert_eq!(
            error,
            Error::Failed("The disk image has no Telepad app in it.".into())
        );
        assert_eq!(
            std::fs::read_to_string(app.join("Contents/MacOS/telepad")).unwrap(),
            "old"
        );
        assert!(mac
            .ran
            .lock()
            .unwrap()
            .last()
            .unwrap()
            .starts_with("hdiutil detach"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_applications_folder_the_person_cannot_change_gets_a_way_to_do_it_by_hand() {
        let kind = Kind::MacApp {
            app: PathBuf::from("/nonexistent-applications/Telepad.app"),
        };
        let error = apply(
            &Script::default(),
            &plan(&kind, Path::new("/tmp/Telepad.dmg"), Path::new("/tmp")),
        )
        .unwrap_err();
        assert!(
            matches!(error, Error::ByHand { how: Some(_), .. }),
            "{error:?}"
        );
    }

    // ── The rest ─────────────────────────────────────────────────────

    #[test]
    fn a_copy_that_cannot_update_itself_says_why() {
        let kind = Kind::Unsupported("Homebrew installed Telepad.".into());
        let error = apply(
            &Script::default(),
            &plan(&kind, Path::new("x"), Path::new(".")),
        )
        .unwrap_err();
        assert_eq!(
            error,
            Error::ByHand {
                why: "Homebrew installed Telepad.".into(),
                how: None
            }
        );
    }

    #[test]
    #[cfg(unix)]
    fn the_helper_waits_for_the_old_program_to_go_then_runs_the_new_one() {
        // Run the real helper script: the "old program" is a process that ends after a moment.
        let dir = temp("helper");
        let marker = dir.join("started");
        let mut old = std::process::Command::new("sleep")
            .arg("1")
            .spawn()
            .unwrap();
        let args = relaunch(old.id(), &["touch", &marker.to_string_lossy()]);
        let started = std::time::Instant::now();
        let mut helper = std::process::Command::new("sh")
            .args(&args)
            .spawn()
            .unwrap();
        std::thread::sleep(std::time::Duration::from_millis(300));
        assert!(!marker.exists(), "it waits while the old program runs");
        old.wait().unwrap();
        helper.wait().unwrap();
        assert!(marker.exists(), "and then starts the new one");
        assert!(started.elapsed() >= std::time::Duration::from_millis(900));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
