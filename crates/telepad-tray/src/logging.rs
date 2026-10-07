//! Where the program writes down what it does.
//!
//! A program with no window and, on Windows, no console has nowhere to say what happened, and a person who
//! reports a problem has nothing to paste. So the program keeps a log file in its folder: what it did (starting,
//! a phone connecting, an error), never what was typed. It is small (about two megabytes at most) and stays on
//! this computer; nothing sends it anywhere.

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::{fmt, EnvFilter};

/// The name of the log file, in the program's folder.
const FILE_NAME: &str = "telepad.log";

/// How big the file may grow before it is set aside as `telepad.log.1` (replacing the older one) and a new one
/// is started.
const MAX_BYTES: u64 = 1024 * 1024;

/// Starts logging to the error stream and, when `folder` is given, to the log file there. Returns the file's
/// path if it could be opened. A copy that only asks another one to open its page or to quit passes no folder,
/// so that it does not write beside the one that is running.
pub fn init(verbose: bool, folder: Option<&Path>) -> Option<PathBuf> {
    let default = if verbose {
        "telepad=debug,telepad_server=debug,info"
    } else {
        "telepad=info,telepad_server=info,warn"
    };
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default));

    let opened = folder.and_then(|folder| {
        let path = folder.join(FILE_NAME);
        RotatingFile::open(path.clone(), MAX_BYTES)
            .ok()
            .map(|file| (path, file))
    });
    let (path, file_layer) = match opened {
        Some((path, file)) => (
            Some(path),
            // No colour codes in a file.
            Some(fmt::layer().with_ansi(false).with_writer(Mutex::new(file))),
        ),
        None => (None, None),
    };

    // Where there is no error stream (Windows, no console) writing to it does nothing, harmlessly.
    tracing_subscriber::registry()
        .with(filter)
        .with(fmt::layer())
        .with(file_layer)
        .init();
    path
}

/// A file that starts a new one when it gets big.
struct RotatingFile {
    path: PathBuf,
    file: File,
    written: u64,
    max: u64,
}

impl RotatingFile {
    fn open(path: PathBuf, max: u64) -> io::Result<Self> {
        let mut size = std::fs::metadata(&path).map_or(0, |meta| meta.len());
        if size > max {
            set_aside(&path)?;
            size = 0;
        }
        let file = open_append(&path)?;
        Ok(Self {
            path,
            file,
            written: size,
            max,
        })
    }

    fn rotate(&mut self) -> io::Result<()> {
        set_aside(&self.path)?;
        self.file = open_append(&self.path)?;
        self.written = 0;
        Ok(())
    }
}

/// Opens the log for appending, readable by this user only: it holds names and addresses.
fn open_append(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.create(true).append(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

/// Moves `telepad.log` to `telepad.log.1`, replacing what was there.
fn set_aside(path: &Path) -> io::Result<()> {
    let mut old = path.as_os_str().to_owned();
    old.push(".1");
    // Windows will not rename over a file that exists.
    let _ = std::fs::remove_file(&old);
    std::fs::rename(path, old)
}

impl Write for RotatingFile {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.written > self.max {
            // A log that cannot be rotated is not worth stopping the program for: keep writing to the same file.
            let _ = self.rotate();
        }
        let n = self.file.write(bytes)?;
        self.written += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("telepad-log-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn lines_are_appended_and_survive_a_restart() {
        let dir = scratch("append");
        let path = dir.join(FILE_NAME);
        let mut first = RotatingFile::open(path.clone(), 1000).unwrap();
        first.write_all(b"one\n").unwrap();
        drop(first);
        let mut second = RotatingFile::open(path.clone(), 1000).unwrap();
        second.write_all(b"two\n").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "one\ntwo\n");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[cfg(unix)]
    #[test]
    fn the_log_is_for_this_user_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = scratch("private");
        let path = dir.join(FILE_NAME);
        let _file = RotatingFile::open(path.clone(), 1000).unwrap();
        let mode = std::fs::metadata(&path).unwrap().permissions().mode();
        assert_eq!(mode & 0o077, 0, "no access for group or others: {mode:o}");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_big_file_is_set_aside_at_the_start_and_the_new_one_is_empty() {
        let dir = scratch("start");
        let path = dir.join(FILE_NAME);
        std::fs::write(&path, vec![b'x'; 600]).unwrap();
        let mut file = RotatingFile::open(path.clone(), 500).unwrap();
        file.write_all(b"fresh\n").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "fresh\n");
        assert_eq!(std::fs::read(dir.join("telepad.log.1")).unwrap().len(), 600);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_file_that_grows_too_big_while_running_is_rotated_and_only_one_old_copy_is_kept() {
        let dir = scratch("running");
        let path = dir.join(FILE_NAME);
        let mut file = RotatingFile::open(path.clone(), 100).unwrap();
        for round in 0..6 {
            file.write_all(format!("{round}: {}\n", "y".repeat(60)).as_bytes())
                .unwrap();
        }
        let current = std::fs::read_to_string(&path).unwrap();
        let old = std::fs::read_to_string(dir.join("telepad.log.1")).unwrap();
        assert!(
            current.len() <= 200,
            "the current file stays small: {} bytes",
            current.len()
        );
        assert!(old.contains("y"), "the older lines are in the second file");
        assert!(
            current.contains("5: "),
            "the newest line is in the current file"
        );
        let names: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names.len(), 2, "no more than the two files: {names:?}");
        let _ = std::fs::remove_dir_all(dir);
    }
}
