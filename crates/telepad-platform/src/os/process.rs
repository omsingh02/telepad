//! Small helpers for the actions that are implemented by running a desktop
//! helper program (`xdg-open`, `loginctl`, `open`, ...).

use std::io;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Searches `PATH` for an executable called `program`.
pub fn find_in_path(program: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(program))
        .find(|candidate| is_executable(candidate))
}

fn is_executable(path: &std::path::Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    path.metadata()
        .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
        .unwrap_or(false)
}

fn command(argv: &[String]) -> Command {
    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    cmd
}

/// Starts `argv` without waiting for it (GUI programs run for as long as the
/// user keeps them open). A background thread reaps the child so it never
/// lingers as a zombie.
pub fn spawn_detached(argv: &[String]) -> io::Result<()> {
    let mut child = command(argv).spawn()?;
    std::thread::Builder::new()
        .name("telepad-reaper".into())
        .spawn(move || {
            let _ = child.wait();
        })?;
    Ok(())
}

/// Runs `argv` to completion, giving up (and killing it) after `timeout`.
/// Returns whether it exited successfully.
pub fn run_to_completion(argv: &[String], timeout: Duration) -> io::Result<bool> {
    let mut child = command(argv).spawn()?;
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(status.success());
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "helper program timed out",
            ));
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// How [`run_first_available`] decides a candidate "worked".
#[derive(Clone, Copy)]
pub enum Completion {
    /// Success means the program started (launching GUI applications).
    Spawned,
    /// Success means the program ran and exited 0 (one-shot control commands).
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    // only Linux locks the screen this way
    ExitedOk(Duration),
}

/// Tries each candidate command in order and stops at the first one that
/// works. Candidates whose program is not installed are skipped silently.
/// Returns the program that succeeded, or `None`.
pub fn run_first_available(candidates: &[Vec<String>], how: Completion) -> Option<String> {
    for argv in candidates {
        let Some(program) = argv.first() else {
            continue;
        };
        if find_in_path(program).is_none() {
            continue;
        }
        let ok = match how {
            Completion::Spawned => spawn_detached(argv).is_ok(),
            Completion::ExitedOk(timeout) => run_to_completion(argv, timeout).unwrap_or(false),
        };
        if ok {
            return Some(program.clone());
        }
    }
    None
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))] // macOS builds its argv from a plan
pub fn argv(parts: &[&str]) -> Vec<String> {
    parts.iter().map(|p| (*p).to_owned()).collect()
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn finds_standard_programs_and_rejects_missing_ones() {
        assert!(find_in_path("sh").is_some());
        assert!(find_in_path("definitely-not-a-real-program-telepad").is_none());
    }

    #[test]
    fn run_to_completion_reports_exit_status() {
        assert!(run_to_completion(&argv(&["true"]), Duration::from_secs(5)).unwrap());
        assert!(!run_to_completion(&argv(&["false"]), Duration::from_secs(5)).unwrap());
    }

    #[test]
    fn run_to_completion_kills_hung_programs() {
        let start = Instant::now();
        let err =
            run_to_completion(&argv(&["sleep", "30"]), Duration::from_millis(150)).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn first_available_skips_missing_and_failing_candidates() {
        let candidates = vec![
            argv(&["definitely-not-a-real-program-telepad"]),
            argv(&["false"]),
            argv(&["true"]),
            argv(&["sh", "-c", "exit 9"]),
        ];
        let how = Completion::ExitedOk(Duration::from_secs(5));
        assert_eq!(
            run_first_available(&candidates, how).as_deref(),
            Some("true")
        );
        assert_eq!(run_first_available(&candidates[..2], how), None);
    }

    #[test]
    fn spawn_detached_does_not_wait() {
        let start = Instant::now();
        spawn_detached(&argv(&["sleep", "2"])).unwrap();
        assert!(start.elapsed() < Duration::from_millis(500));
    }
}
