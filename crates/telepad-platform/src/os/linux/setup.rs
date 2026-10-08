//! Letting the person at this computer use `/dev/uinput`, for a Telepad that did not come in a package.
//!
//! The packages do this when they are installed (the same rule and module list, from the same files). A
//! program unpacked from the tarball cannot, since it never ran as root, so it asks: `pkexec` shows the
//! system's own password prompt, and what it then runs as root is a few lines that are shown in that prompt.

use crate::access::{AllowError, Block};
use crate::os::linux::uinput::can_open_uinput;
use std::process::{Command, Stdio};

/// The udev rule that lets whoever is at the computer use `/dev/uinput` (`uaccess`), as the packages install it.
const RULE_FILE: &str = include_str!("../../../../../packaging/linux/60-telepad-uinput.rules");
/// The list of kernel modules to load at every boot.
const MODULES_FILE: &str = include_str!("../../../../../packaging/linux/telepad-modules-load.conf");

const RULE_PATH: &str = "/etc/udev/rules.d/60-telepad-uinput.rules";
const MODULES_PATH: &str = "/etc/modules-load.d/telepad.conf";

/// `pkexec` ends with 126 when the person dismisses the password prompt.
const PKEXEC_DISMISSED: i32 = 126;
/// ...and with 127 when it could not ask, or the password was not accepted.
const PKEXEC_NOT_AUTHORIZED: i32 = 127;

pub(crate) fn blocked() -> Option<Block> {
    match can_open_uinput() {
        Ok(()) => None,
        Err(error) => {
            tracing::debug!("cannot open /dev/uinput yet: {error}");
            Some(Block::LinuxUinput)
        }
    }
}

pub(crate) fn allow() -> std::result::Result<(), AllowError> {
    let output = Command::new("pkexec")
        .args(["/bin/sh", "-c", &script("")])
        .arg("telepad-setup")
        .stdin(Stdio::null())
        .output();
    let output = match output {
        Ok(output) => output,
        Err(error) => {
            tracing::warn!("could not run pkexec: {error}");
            return Err(AllowError::NotGranted {
                terminal: terminal_steps(),
            });
        }
    };
    if output.status.success() {
        return match blocked() {
            None => Ok(()),
            Some(_) => Err(AllowError::Failed(
                "The system accepted the change, but Telepad still cannot open /dev/uinput. \
                 Signing out and in again may be needed."
                    .into(),
            )),
        };
    }
    match output.status.code() {
        Some(PKEXEC_DISMISSED) => Err(AllowError::Declined),
        Some(PKEXEC_NOT_AUTHORIZED) => Err(AllowError::NotGranted {
            terminal: terminal_steps(),
        }),
        _ => {
            let said = String::from_utf8_lossy(&output.stderr);
            tracing::warn!("the setup did not finish: {}", said.trim());
            Err(AllowError::Failed(
                said.trim()
                    .lines()
                    .last()
                    .unwrap_or("the setup did not finish")
                    .to_owned(),
            ))
        }
    }
}

/// The lines of a file that are not comments or blank: what the system reads.
fn content(file: &str) -> impl Iterator<Item = &str> {
    file.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
}

/// `text` as one word for a shell: in single quotes, which nothing inside can break out of.
fn quoted(text: &str) -> String {
    format!("'{}'", text.replace('\'', "'\\''"))
}

/// What is run as root, a line at a time. `root` is "" for the real system; a test gives it a folder of its own.
fn script(root: &str) -> String {
    let write = |text: &str, path: &str| {
        let lines = content(text).map(quoted).collect::<Vec<_>>().join(" ");
        format!(
            "printf '%s\\n' {lines} > {}\n",
            quoted(&format!("{root}{path}"))
        )
    };
    let dir = |path: &str| quoted(&format!("{root}{path}"));
    format!(
        "set -eu\n\
         export PATH=\"$PATH:/usr/sbin:/sbin\"\n\
         umask 022\n\
         mkdir -p {rules} {modules}\n\
         {rule}\
         {module}\
         modprobe uinput || true\n\
         udevadm control --reload-rules\n\
         udevadm trigger --subsystem-match=misc --sysname-match=uinput\n\
         udevadm settle --timeout=5\n",
        rules = dir("/etc/udev/rules.d"),
        modules = dir("/etc/modules-load.d"),
        rule = write(RULE_FILE, RULE_PATH),
        module = write(MODULES_FILE, MODULES_PATH),
    )
}

/// The same, for a person to run in a terminal: a line at a time, each of which they can read and paste.
pub(crate) fn terminal_steps() -> String {
    terminal_lines("")
}

fn terminal_lines(root: &str) -> String {
    let write = |text: &str, path: &str| {
        let lines = content(text).collect::<Vec<_>>().join("\n");
        format!(
            "echo {} | sudo tee {}\n",
            quoted(&lines),
            quoted(&format!("{root}{path}"))
        )
    };
    format!(
        "{rule}{module}sudo modprobe uinput\n\
         sudo udevadm control --reload-rules\n\
         sudo udevadm trigger --subsystem-match=misc --sysname-match=uinput\n",
        rule = write(RULE_FILE, RULE_PATH),
        module = write(MODULES_FILE, MODULES_PATH),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::path::Path;

    #[test]
    fn it_installs_the_rule_the_packages_install_and_nothing_with_a_comment_in() {
        let rule = content(RULE_FILE).collect::<Vec<_>>();
        assert_eq!(rule.len(), 1, "one rule: {rule:?}");
        assert!(rule[0].contains("uinput") && rule[0].contains("uaccess"));
        assert_eq!(content(MODULES_FILE).collect::<Vec<_>>(), ["uinput"]);
    }

    #[test]
    fn quoting_keeps_every_character_as_it_is() {
        assert_eq!(quoted("a b"), "'a b'");
        assert_eq!(quoted("it's"), "'it'\\''s'");
        assert_eq!(quoted("$(x) `y` \"z\""), "'$(x) `y` \"z\"'");
    }

    fn write_stub(dir: &Path, name: &str, exit: i32) {
        let path = dir.join(name);
        std::fs::write(
            &path,
            format!("#!/bin/sh\necho \"{name} $*\" >> \"$STUB_LOG\"\nexit {exit}\n"),
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    }

    fn run(root: &Path, stubs: &Path, log: &Path) -> std::process::Output {
        Command::new("/bin/sh")
            .arg("-c")
            .arg(script(root.to_str().unwrap()))
            .env("PATH", format!("{}:/usr/bin:/bin", stubs.display()))
            .env("STUB_LOG", log)
            .output()
            .unwrap()
    }

    #[test]
    fn the_script_writes_both_files_and_tells_udev_in_order() {
        let dir = std::env::temp_dir().join(format!("telepad-setup-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let (root, stubs, log) = (dir.join("root"), dir.join("bin"), dir.join("log"));
        std::fs::create_dir_all(&stubs).unwrap();
        for name in ["modprobe", "udevadm"] {
            write_stub(&stubs, name, 0);
        }

        let output = run(&root, &stubs, &log);
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );

        let rule =
            std::fs::read_to_string(root.join("etc/udev/rules.d/60-telepad-uinput.rules")).unwrap();
        assert_eq!(
            rule.lines().collect::<Vec<_>>(),
            content(RULE_FILE).collect::<Vec<_>>()
        );
        let modules =
            std::fs::read_to_string(root.join("etc/modules-load.d/telepad.conf")).unwrap();
        assert_eq!(modules, "uinput\n");
        assert_eq!(
            std::fs::read_to_string(&log)
                .unwrap()
                .lines()
                .collect::<Vec<_>>(),
            [
                "modprobe uinput",
                "udevadm control --reload-rules",
                "udevadm trigger --subsystem-match=misc --sysname-match=uinput",
                "udevadm settle --timeout=5",
            ]
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_module_that_is_built_in_does_not_stop_the_setup_but_udev_failing_does() {
        let dir = std::env::temp_dir().join(format!("telepad-setup-test-b-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let (root, stubs, log) = (dir.join("root"), dir.join("bin"), dir.join("log"));
        std::fs::create_dir_all(&stubs).unwrap();
        write_stub(&stubs, "modprobe", 1);
        write_stub(&stubs, "udevadm", 0);
        assert!(
            run(&root, &stubs, &log).status.success(),
            "no module to load is fine"
        );

        write_stub(&stubs, "udevadm", 1);
        assert!(
            !run(&root, &stubs, &log).status.success(),
            "udev failing is not"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_terminal_steps_do_what_the_script_does() {
        let dir = std::env::temp_dir().join(format!("telepad-setup-test-c-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let (stubs, log) = (dir.join("bin"), dir.join("log"));
        std::fs::create_dir_all(&stubs).unwrap();
        // `sudo` runs what it is given, as the person already is the one running it.
        std::fs::write(stubs.join("sudo"), "#!/bin/sh\nexec \"$@\"\n").unwrap();
        std::fs::set_permissions(stubs.join("sudo"), std::fs::Permissions::from_mode(0o755))
            .unwrap();
        for name in ["modprobe", "udevadm"] {
            write_stub(&stubs, name, 0);
        }

        let by_script = dir.join("by-script");
        let by_hand = dir.join("by-hand");
        std::fs::create_dir_all(by_hand.join("etc/udev/rules.d")).unwrap();
        std::fs::create_dir_all(by_hand.join("etc/modules-load.d")).unwrap();
        assert!(run(&by_script, &stubs, &log).status.success());
        let script_calls = std::fs::read_to_string(&log).unwrap();
        std::fs::remove_file(&log).unwrap();

        let steps = terminal_lines(by_hand.to_str().unwrap());
        let output = Command::new("/bin/sh")
            .args(["-eu", "-c", &steps])
            .env("PATH", format!("{}:/usr/bin:/bin", stubs.display()))
            .env("STUB_LOG", &log)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );

        for file in [
            "etc/udev/rules.d/60-telepad-uinput.rules",
            "etc/modules-load.d/telepad.conf",
        ] {
            assert_eq!(
                std::fs::read_to_string(by_hand.join(file)).unwrap(),
                std::fs::read_to_string(by_script.join(file)).unwrap(),
                "{file}"
            );
        }
        // By hand there is no need to wait for udev to finish: the person is slower than it is.
        let hand_calls = std::fs::read_to_string(&log).unwrap();
        assert_eq!(
            hand_calls,
            script_calls.replace("udevadm settle --timeout=5\n", "")
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
