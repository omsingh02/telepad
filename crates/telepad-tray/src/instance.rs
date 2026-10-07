//! One Telepad at a time.
//!
//! The program has no window, so the way to open it again (from the Start menu, Spotlight or the launcher) is
//! to start it again. Two copies cannot share the port, so instead of failing the second one finds the first
//! and shows its page with the code, then ends.
//!
//! The running copy leaves the address of its page in a file in its folder. A copy that was killed leaves the
//! file behind, which is why an address is tried before it is trusted. And since the file is read back and
//! opened in the browser, only an address of exactly the shape this program writes is ever opened.

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::Path;
use std::time::{Duration, Instant};

/// The file, in the program's folder, that holds the address of the running page.
const FILE_NAME: &str = "panel.url";

/// How long a copy that may be dead has to answer.
const PATIENCE: Duration = Duration::from_millis(1500);

/// How long a copy that was asked to quit has to be gone.
const QUIT_PATIENCE: Duration = Duration::from_secs(8);

/// Leaves `url` where the next copy of the program will look for it.
pub fn announce(dir: &Path, url: &str) -> io::Result<()> {
    // Written beside the file and moved over it, so that nobody reads half an address.
    let staging = dir.join(format!("{FILE_NAME}.new"));
    write_private(&staging, url.as_bytes())?;
    std::fs::rename(&staging, dir.join(FILE_NAME))
}

/// Takes the address away when this copy stops, unless another copy has put its own there since.
pub fn withdraw(dir: &Path, url: &str) {
    let file = dir.join(FILE_NAME);
    if std::fs::read_to_string(&file).is_ok_and(|text| text.trim() == url) {
        let _ = std::fs::remove_file(file);
    }
}

/// The address of the page of another copy that is running, if there is one.
pub fn running_page(dir: &Path) -> Option<String> {
    let text = std::fs::read_to_string(dir.join(FILE_NAME)).ok()?;
    let url = text.trim();
    let (port, secret) = parse(url)?;
    answers(port, secret).then(|| url.to_owned())
}

/// What came of asking the running copy to quit.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Quit {
    /// There was none.
    NotRunning,
    /// It said yes and is gone.
    Done,
    /// It is still there.
    Stuck,
}

/// Asks the copy that is running to quit, and waits until it has, so that its files are free. For installers and
/// scripts, which cannot choose "Quit" from a menu.
pub fn quit_running(dir: &Path) -> Quit {
    quit_running_within(dir, QUIT_PATIENCE)
}

fn quit_running_within(dir: &Path, patience: Duration) -> Quit {
    let Some(url) = running_page(dir) else {
        return Quit::NotRunning;
    };
    let Some((port, secret)) = parse(&url) else {
        return Quit::NotRunning;
    };
    if !send_quit(port, secret) {
        return Quit::Stuck;
    }
    // It stops a moment after it has answered.
    let deadline = Instant::now() + patience;
    while answers(port, secret) {
        if Instant::now() >= deadline {
            return Quit::Stuck;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    Quit::Done
}

/// The port and the secret of an address `http://127.0.0.1:<port>/<secret>/`, and nothing else.
fn parse(url: &str) -> Option<(u16, &str)> {
    let rest = url.strip_prefix("http://127.0.0.1:")?;
    let (port, path) = rest.split_once('/')?;
    if port.is_empty() || port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let port: u16 = port.parse().ok().filter(|port| *port != 0)?;
    let secret = path.strip_suffix('/')?;
    let plain = |b: u8| b.is_ascii_alphanumeric() || b == b'-' || b == b'_';
    ((16..=64).contains(&secret.len()) && secret.bytes().all(plain)).then_some((port, secret))
}

/// Whether the page at this address is really there. It asks for the status, which, unlike the page itself,
/// changes nothing (the page makes a new code every time it is opened).
fn answers(port: u16, secret: &str) -> bool {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let Ok(mut stream) = TcpStream::connect_timeout(&address, PATIENCE) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(PATIENCE));
    let _ = stream.set_write_timeout(Some(PATIENCE));
    let request = format!(
        "GET /{secret}/status.json HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut reply = Vec::new();
    let _ = stream.take(2048).read_to_end(&mut reply);
    reply.starts_with(b"HTTP/1.1 200") && reply.windows(8).any(|window| window == b"\"paired\"")
}

/// Presses the page's "Quit" button, the way the page itself does.
fn send_quit(port: u16, secret: &str) -> bool {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let Ok(mut stream) = TcpStream::connect_timeout(&address, PATIENCE) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(PATIENCE));
    let _ = stream.set_write_timeout(Some(PATIENCE));
    let request = format!(
        "POST /{secret}/quit HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut reply = Vec::new();
    let _ = stream.take(2048).read_to_end(&mut reply);
    reply.starts_with(b"HTTP/1.1 200")
}

/// Writes a file that only this user can read, since what it holds is the way into the page.
fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)?;
        file.write_all(bytes)
    }
    #[cfg(not(unix))]
    {
        // The folder is under the user's own profile, which other accounts cannot read.
        std::fs::write(path, bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    const SECRET: &str = "AbCdEfGhIjKlMnOpQrStUv";

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("telepad-instance-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// A stand-in for the page that answers every request with `reply`, and the address it is at.
    fn page_that_says(reply: &'static str) -> (String, std::thread::JoinHandle<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut asked = vec![0u8; 1024];
            let n = stream.read(&mut asked).unwrap();
            stream.write_all(reply.as_bytes()).unwrap();
            String::from_utf8_lossy(&asked[..n]).into_owned()
        });
        (format!("http://127.0.0.1:{port}/{SECRET}/"), handle)
    }

    const JSON_OK: &str =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"paired\":1,\"connected\":0}";

    #[test]
    fn an_address_like_the_one_the_program_writes_is_read() {
        assert_eq!(
            parse(&format!("http://127.0.0.1:41234/{SECRET}/")),
            Some((41234, SECRET))
        );
        assert_eq!(
            parse(&format!("http://127.0.0.1:1/{SECRET}-_9/")),
            Some((1, "AbCdEfGhIjKlMnOpQrStUv-_9"))
        );
    }

    #[test]
    fn nothing_else_is_ever_opened() {
        let bad = [
            "".to_owned(),
            "https://127.0.0.1:41234/AbCdEfGhIjKlMnOpQrStUv/".to_owned(),
            format!("http://localhost:41234/{SECRET}/"),
            format!("http://127.0.0.1.evil.example:41234/{SECRET}/"),
            format!("http://127.0.0.1@evil.example:41234/{SECRET}/"),
            format!("http://evil.example/http://127.0.0.1:41234/{SECRET}/"),
            format!("http://127.0.0.1:0/{SECRET}/"),
            format!("http://127.0.0.1:65536/{SECRET}/"),
            format!("http://127.0.0.1:+80/{SECRET}/"),
            format!("http://127.0.0.1:/{SECRET}/"),
            format!("http://127.0.0.1:41234/{SECRET}"),
            "http://127.0.0.1:41234/short/".to_owned(),
            format!("http://127.0.0.1:41234/{}/", "a".repeat(65)),
            format!("http://127.0.0.1:41234/{SECRET}/../../x/"),
            format!("http://127.0.0.1:41234/{SECRET}/?next=http://evil.example"),
            format!("http://127.0.0.1:41234/{SECRET}%2F/"),
            format!("javascript:alert(1)//http://127.0.0.1:41234/{SECRET}/"),
            format!("http://127.0.0.1:41234/{SECRET}/\nhttp://evil.example/"),
        ];
        for url in &bad {
            assert_eq!(parse(url), None, "{url:?} must not be accepted");
        }
    }

    #[test]
    fn a_page_that_answers_is_a_running_copy() {
        let dir = scratch("alive");
        let (url, page) = page_that_says(JSON_OK);
        announce(&dir, &url).unwrap();

        assert_eq!(running_page(&dir), Some(url.clone()));

        // What it asked for must be the status, not the page (which would use up the code on show).
        let asked = page.join().unwrap();
        assert!(
            asked.starts_with(&format!("GET /{SECRET}/status.json HTTP/1.1\r\n")),
            "{asked}"
        );
        assert!(asked.contains("Host: 127.0.0.1:"), "{asked}");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn an_address_left_by_a_copy_that_died_is_not_a_running_copy() {
        let dir = scratch("dead");
        // Nothing listens on the port any more.
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        announce(&dir, &format!("http://127.0.0.1:{port}/{SECRET}/")).unwrap();
        assert_eq!(running_page(&dir), None);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn something_else_on_that_port_is_not_a_running_copy() {
        let dir = scratch("other");
        let (url, _page) = page_that_says("HTTP/1.1 404 Not Found\r\n\r\nNot found.");
        announce(&dir, &url).unwrap();
        assert_eq!(running_page(&dir), None);

        let (url, _page) =
            page_that_says("HTTP/1.1 200 OK\r\n\r\n<html>a different program</html>");
        announce(&dir, &url).unwrap();
        assert_eq!(running_page(&dir), None, "a 200 alone proves nothing");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn no_file_and_a_damaged_file_are_no_running_copy() {
        let dir = scratch("none");
        assert_eq!(running_page(&dir), None);
        std::fs::write(dir.join(FILE_NAME), b"\xff\xfe not an address").unwrap();
        assert_eq!(running_page(&dir), None);
        std::fs::write(dir.join(FILE_NAME), b"").unwrap();
        assert_eq!(running_page(&dir), None);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn withdrawing_takes_only_its_own_address_away() {
        let dir = scratch("withdraw");
        let mine = format!("http://127.0.0.1:1111/{SECRET}/");
        let theirs = format!("http://127.0.0.1:2222/{SECRET}/");

        announce(&dir, &mine).unwrap();
        withdraw(&dir, &mine);
        assert!(!dir.join(FILE_NAME).exists());

        // A newer copy has taken the file over: this one must not delete the newer one's address.
        announce(&dir, &theirs).unwrap();
        withdraw(&dir, &mine);
        assert!(dir.join(FILE_NAME).exists());

        // Withdrawing what is not there is not an error.
        withdraw(&dir.join("missing"), &mine);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn announcing_replaces_the_old_address_and_leaves_no_litter() {
        let dir = scratch("replace");
        announce(&dir, "http://127.0.0.1:1/old-old-old-old-old-old/").unwrap();
        announce(&dir, "http://127.0.0.1:2/new-new-new-new-new-new/").unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.join(FILE_NAME)).unwrap(),
            "http://127.0.0.1:2/new-new-new-new-new-new/"
        );
        let files: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(files, vec![std::ffi::OsString::from(FILE_NAME)]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_for_this_user_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = scratch("private");
        announce(&dir, &format!("http://127.0.0.1:1/{SECRET}/")).unwrap();
        let mode = std::fs::metadata(dir.join(FILE_NAME))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o077, 0, "group and others have no access: {mode:o}");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// A stand-in for the running copy: it answers the status, and when it is told to quit it says yes and goes
    /// away (or, if `stays`, says yes and carries on). Gives the requests it saw.
    fn page_that_can_be_told_to_quit(
        stays: bool,
    ) -> (String, std::thread::JoinHandle<Vec<String>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let mut seen = Vec::new();
            // The copy that stays serves until the test has seen what it needed, and a little longer.
            let end = Instant::now() + Duration::from_secs(3);
            listener.set_nonblocking(true).unwrap();
            while Instant::now() < end {
                let Ok((mut stream, _)) = listener.accept() else {
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                };
                stream.set_nonblocking(false).unwrap();
                let mut asked = vec![0u8; 1024];
                let n = stream.read(&mut asked).unwrap_or(0);
                let first_line = String::from_utf8_lossy(&asked[..n])
                    .lines()
                    .next()
                    .unwrap_or("")
                    .to_owned();
                let quit = first_line.starts_with("POST");
                seen.push(first_line);
                let reply = if quit {
                    "HTTP/1.1 200 OK\r\n\r\nTelepad has quit."
                } else {
                    JSON_OK
                };
                let _ = stream.write_all(reply.as_bytes());
                if quit && !stays {
                    break;
                }
            }
            seen
        });
        (format!("http://127.0.0.1:{port}/{SECRET}/"), handle)
    }

    #[test]
    fn a_copy_that_is_asked_to_quit_is_waited_for() {
        let dir = scratch("quit");
        let (url, page) = page_that_can_be_told_to_quit(false);
        announce(&dir, &url).unwrap();

        assert_eq!(quit_running(&dir), Quit::Done);

        let seen = page.join().unwrap();
        assert!(
            seen.contains(&format!("POST /{SECRET}/quit HTTP/1.1")),
            "the Quit button was pressed: {seen:?}"
        );
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_copy_that_stays_is_reported_as_staying() {
        let dir = scratch("stuck");
        let (url, _page) = page_that_can_be_told_to_quit(true);
        announce(&dir, &url).unwrap();
        assert_eq!(
            quit_running_within(&dir, Duration::from_millis(400)),
            Quit::Stuck
        );
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn with_nothing_running_there_is_nothing_to_quit() {
        let dir = scratch("nothing-to-quit");
        assert_eq!(quit_running(&dir), Quit::NotRunning);
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        announce(&dir, &format!("http://127.0.0.1:{port}/{SECRET}/")).unwrap();
        assert_eq!(quit_running(&dir), Quit::NotRunning, "a stale address too");
        let _ = std::fs::remove_dir_all(dir);
    }
}
