//! Running other programs, behind a small interface so that what Telepad would run can be checked without
//! running it, and asking this system what it is.

use crate::kind::{self, Facts, Kind, Os, PackageFormat};
use std::ffi::OsStr;
use std::io;
use std::path::Path;
use std::process::{Command, Output, Stdio};

/// What the updater runs.
pub trait Commands: Send + Sync {
    /// Runs `program` and waits for it. Output is captured.
    fn run(&self, program: &str, args: &[&OsStr]) -> io::Result<Output>;

    /// Starts `program` and does not wait. It goes on after this program has quit.
    fn spawn_detached(&self, program: &str, args: &[&OsStr]) -> io::Result<()>;
}

/// The real thing.
pub struct System;

impl Commands for System {
    fn run(&self, program: &str, args: &[&OsStr]) -> io::Result<Output> {
        Command::new(program)
            .args(args)
            .stdin(Stdio::null())
            .output()
    }

    fn spawn_detached(&self, program: &str, args: &[&OsStr]) -> io::Result<()> {
        let mut command = Command::new(program);
        command
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const DETACHED_PROCESS: u32 = 0x0000_0008;
            const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
            command.creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP);
        }
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            // A session of its own, so that a hang-up sent to this program's does not reach it.
            // SAFETY: setsid is async-signal-safe and touches no memory of ours.
            unsafe {
                command.pre_exec(|| {
                    libc::setsid();
                    Ok(())
                });
            }
        }
        command.spawn().map(drop)
    }
}

/// Whether `program` can be run from here.
pub fn exists(commands: &dyn Commands, program: &str) -> bool {
    commands.run(program, &[OsStr::new("--version")]).is_ok()
}

/// Which kind of package owns `file`, asking the system's own package tools.
pub fn package_owning(commands: &dyn Commands, file: &Path) -> Option<PackageFormat> {
    let owns = |program: &str, args: &[&str]| {
        let mut all: Vec<&OsStr> = args.iter().map(OsStr::new).collect();
        all.push(file.as_os_str());
        commands
            .run(program, &all)
            .is_ok_and(|output| output.status.success())
    };
    if owns("dpkg", &["-S"]) {
        Some(PackageFormat::Deb)
    } else if owns("rpm", &["-qf"]) {
        Some(PackageFormat::Rpm)
    } else if owns("pacman", &["-Qo"]) {
        Some(PackageFormat::Pacman)
    } else {
        None
    }
}

/// Whether a file in `folder` can be replaced by this person: tried by making one, since permissions alone
/// (access control lists, read-only mounts) do not tell.
pub fn can_replace_in(folder: &Path) -> bool {
    let probe = folder.join(format!(".telepad-write-test-{}", std::process::id()));
    match std::fs::File::create(&probe) {
        Ok(_) => {
            let _ = std::fs::remove_file(&probe);
            true
        }
        Err(_) => false,
    }
}

/// How the running copy of Telepad was installed.
pub fn detect(commands: &dyn Commands) -> Kind {
    let Some(os) = Os::current() else {
        return Kind::Unsupported("Telepad cannot update itself on this system.".into());
    };
    let exe = match std::env::current_exe() {
        // Linux appends " (deleted)" to the path of a program file that has been replaced while it runs.
        Ok(exe) => exe,
        Err(error) => {
            return Kind::Unsupported(format!("Telepad cannot tell where it is: {error}"))
        }
    };
    // Resolve links (`/usr/bin/telepad` may point elsewhere; `~/.local/bin` may be a link to a folder). Not on
    // Windows, where the resolved path comes back in a form (`\\?\C:\...`) that no one compares against.
    #[cfg(not(windows))]
    let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
    let facts = Facts {
        os,
        arch: std::env::consts::ARCH.to_owned(),
        exe,
        local_app_data: std::env::var_os("LOCALAPPDATA").map(Into::into),
    };
    kind::classify(
        &facts,
        &|file| package_owning(commands, file),
        &can_replace_in,
    )
}

#[cfg(test)]
pub(crate) mod fake {
    use super::*;
    use std::sync::Mutex;

    /// Remembers what it was asked to run, and answers from a script.
    #[derive(Default)]
    pub struct Script {
        pub ran: Mutex<Vec<String>>,
        pub spawned: Mutex<Vec<String>>,
        /// The first rule whose text the command line starts with decides the exit code (default 0).
        pub exit_codes: Mutex<Vec<(String, i32)>>,
        /// What a command line that starts with the text writes to its error output.
        pub stderr: Mutex<Vec<(String, String)>>,
        /// Programs that do not exist.
        pub missing: Mutex<Vec<String>>,
    }

    impl Script {
        pub fn exit(&self, starts_with: &str, code: i32) -> &Self {
            self.exit_codes
                .lock()
                .unwrap()
                .push((starts_with.to_owned(), code));
            self
        }
        #[cfg(unix)]
        pub fn says(&self, starts_with: &str, text: &str) -> &Self {
            self.stderr
                .lock()
                .unwrap()
                .push((starts_with.to_owned(), text.to_owned()));
            self
        }
        pub fn missing(&self, program: &str) -> &Self {
            self.missing.lock().unwrap().push(program.to_owned());
            self
        }
        pub fn ran(&self) -> Vec<String> {
            self.ran.lock().unwrap().clone()
        }
        pub fn spawned(&self) -> Vec<String> {
            self.spawned.lock().unwrap().clone()
        }
    }

    fn line(program: &str, args: &[&OsStr]) -> String {
        let mut line = program.to_owned();
        for arg in args {
            line.push(' ');
            line.push_str(&arg.to_string_lossy());
        }
        line
    }

    #[cfg(unix)]
    fn status(code: i32) -> std::process::ExitStatus {
        use std::os::unix::process::ExitStatusExt;
        std::process::ExitStatus::from_raw(code << 8)
    }

    #[cfg(windows)]
    fn status(code: i32) -> std::process::ExitStatus {
        use std::os::windows::process::ExitStatusExt;
        std::process::ExitStatus::from_raw(code as u32)
    }

    impl Commands for Script {
        fn run(&self, program: &str, args: &[&OsStr]) -> io::Result<Output> {
            let line = line(program, args);
            self.ran.lock().unwrap().push(line.clone());
            if self.missing.lock().unwrap().iter().any(|m| m == program) {
                return Err(io::Error::from(io::ErrorKind::NotFound));
            }
            let code = self
                .exit_codes
                .lock()
                .unwrap()
                .iter()
                .find(|(prefix, _)| line.starts_with(prefix.as_str()))
                .map_or(0, |(_, code)| *code);
            let stderr = self
                .stderr
                .lock()
                .unwrap()
                .iter()
                .find(|(prefix, _)| line.starts_with(prefix.as_str()))
                .map_or_else(Vec::new, |(_, text)| text.clone().into_bytes());
            Ok(Output {
                status: status(code),
                stdout: Vec::new(),
                stderr,
            })
        }

        fn spawn_detached(&self, program: &str, args: &[&OsStr]) -> io::Result<()> {
            self.spawned.lock().unwrap().push(line(program, args));
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fake::Script;
    use super::*;

    #[test]
    fn the_package_that_owns_the_file_is_the_one_whose_tool_says_so() {
        let file = Path::new("/usr/bin/telepad");

        let debian = Script::default();
        debian.exit("rpm", 1).exit("pacman", 1);
        assert_eq!(package_owning(&debian, file), Some(PackageFormat::Deb));

        let fedora = Script::default();
        fedora.missing("dpkg").exit("pacman", 1);
        assert_eq!(package_owning(&fedora, file), Some(PackageFormat::Rpm));

        let arch = Script::default();
        arch.missing("dpkg").missing("rpm");
        assert_eq!(package_owning(&arch, file), Some(PackageFormat::Pacman));

        let none = Script::default();
        none.exit("dpkg", 1).exit("rpm", 1).exit("pacman", 1);
        assert_eq!(package_owning(&none, file), None);
    }

    #[test]
    fn can_replace_in_tries_it() {
        let dir = std::env::temp_dir().join(format!("telepad-replace-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(can_replace_in(&dir));
        assert!(!can_replace_in(&dir.join("not-there")));
        assert_eq!(
            std::fs::read_dir(&dir).unwrap().count(),
            0,
            "it cleans up after itself"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn this_system_can_be_asked_what_it_is() {
        // Under cargo the program is in target/, which is never replaced.
        assert!(matches!(detect(&System), Kind::Unsupported(_)));
    }
}
