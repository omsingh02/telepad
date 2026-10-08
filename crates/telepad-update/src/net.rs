//! The connection to GitHub, and the one rule about it: only this project's pages and files.
//!
//! [`Http`] is what the rest of the crate asks for, so that everything above it can be tried without a network;
//! [`Github`] is the real one.

use crate::release::Source;
use std::io::{Read, Write};
use std::time::Duration;

/// A download is stopped after this long without finishing, so that a stalled connection does not hold the
/// update (and the person) forever.
const TIMEOUT: Duration = Duration::from_secs(600);
/// A connection that cannot be made within this is not going to be.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// Why something could not be fetched, in words for the person.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum NetError {
    #[error("Telepad could not reach GitHub. Check the internet connection and try again.")]
    Unreachable,
    #[error("GitHub is limiting how often Telepad may ask. Try again in an hour.")]
    RateLimited,
    #[error("GitHub does not have that file (it answered {0}).")]
    Status(u16),
    #[error("The file is larger than any Telepad download should be, so it was not fetched.")]
    TooLarge,
    #[error("The download was interrupted: {0}")]
    Interrupted(String),
    #[error("Telepad only fetches from this project's releases on GitHub, and was asked for {0}.")]
    Refused(String),
}

/// What the crate needs of the network.
pub trait Http: Send + Sync {
    /// The text at `url`, which is at most `limit` bytes long.
    fn get_text(&self, url: &str, limit: u64) -> Result<String, NetError>;

    /// Writes the file at `url` to `sink`, calling `progress(done, total)` as it comes. The file is at most
    /// `limit` bytes long. `total` is `None` when the server did not say.
    fn download(
        &self,
        url: &str,
        limit: u64,
        sink: &mut dyn Write,
        progress: &mut dyn FnMut(u64, Option<u64>),
    ) -> Result<(), NetError>;
}

/// GitHub, over HTTPS.
pub struct Github {
    agent: ureq::Agent,
    /// Which addresses may be fetched at all: this project's feed of releases and their files.
    allowed: Box<dyn Fn(&str) -> bool + Send + Sync>,
}

impl Github {
    /// `user_agent` is what GitHub is told this is, such as `Telepad/2.0.0`.
    pub fn new(user_agent: &str) -> Self {
        Self::build(user_agent, true, Box::new(|url| Source::github().owns(url)))
    }

    /// A client for a server of a development build's own, on this computer, over plain HTTP. It is never what
    /// a release does, and only a development build can ask for it.
    #[cfg(debug_assertions)]
    pub fn local(user_agent: &str, source: Source) -> Self {
        Self::build(user_agent, false, Box::new(move |url| source.owns(url)))
    }

    fn build(
        user_agent: &str,
        https_only: bool,
        allowed: Box<dyn Fn(&str) -> bool + Send + Sync>,
    ) -> Self {
        let config = ureq::Agent::config_builder()
            .https_only(https_only)
            .max_redirects(5)
            .timeout_global(Some(TIMEOUT))
            .timeout_connect(Some(CONNECT_TIMEOUT))
            .user_agent(user_agent)
            .build();
        Self {
            agent: config.into(),
            allowed,
        }
    }

    /// A client for a test server on a port of this computer, over plain HTTP.
    #[cfg(test)]
    pub(crate) fn for_tests() -> Self {
        Self::build(
            "Telepad/test",
            false,
            Box::new(|url| url.starts_with("http://127.0.0.1:")),
        )
    }

    fn get(&self, url: &str) -> Result<ureq::http::Response<ureq::Body>, NetError> {
        if !(self.allowed)(url) {
            return Err(NetError::Refused(url.to_owned()));
        }
        self.agent
            .get(url)
            .header(
                "Accept",
                "application/atom+xml, text/plain, application/octet-stream",
            )
            .call()
            .map_err(|error| match error {
                ureq::Error::StatusCode(403 | 429) => NetError::RateLimited,
                ureq::Error::StatusCode(code) => NetError::Status(code),
                ureq::Error::Io(_)
                | ureq::Error::HostNotFound
                | ureq::Error::ConnectionFailed
                | ureq::Error::Timeout(_) => NetError::Unreachable,
                other => NetError::Interrupted(other.to_string()),
            })
    }
}

impl Http for Github {
    fn get_text(&self, url: &str, limit: u64) -> Result<String, NetError> {
        let mut response = self.get(url)?;
        response
            .body_mut()
            .with_config()
            .limit(limit)
            .read_to_string()
            .map_err(|error| match error {
                ureq::Error::BodyExceedsLimit(_) => NetError::TooLarge,
                other => NetError::Interrupted(other.to_string()),
            })
    }

    fn download(
        &self,
        url: &str,
        limit: u64,
        sink: &mut dyn Write,
        progress: &mut dyn FnMut(u64, Option<u64>),
    ) -> Result<(), NetError> {
        let mut response = self.get(url)?;
        let total = response.body().content_length();
        if total.is_some_and(|total| total > limit) {
            return Err(NetError::TooLarge);
        }
        // One more byte than allowed, so that a file that is exactly too long is noticed rather than cut off.
        let mut reader = response.body_mut().with_config().limit(limit + 1).reader();
        let mut buffer = vec![0u8; 64 * 1024];
        let mut done = 0u64;
        loop {
            let n = reader
                .read(&mut buffer)
                .map_err(|error| NetError::Interrupted(error.to_string()))?;
            if n == 0 {
                break;
            }
            done += n as u64;
            if done > limit {
                return Err(NetError::TooLarge);
            }
            sink.write_all(&buffer[..n])
                .map_err(|error| NetError::Interrupted(format!("writing the file: {error}")))?;
            progress(done, total);
        }
        if total.is_some_and(|total| done != total) {
            return Err(NetError::Interrupted(
                "the connection closed before the whole file arrived".into(),
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod fake {
    //! A server on the loopback address that answers from a script, and a fake [`Http`] for the code above.

    use super::*;
    use std::collections::HashMap;
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};

    type Reply = Result<Vec<u8>, NetError>;

    /// A fake network: pages and files by address.
    #[derive(Default, Clone)]
    pub struct FakeHttp {
        pub files: Arc<Mutex<HashMap<String, Reply>>>,
        pub asked: Arc<Mutex<Vec<String>>>,
    }

    impl FakeHttp {
        pub fn with(self, url: &str, body: impl Into<Vec<u8>>) -> Self {
            self.files
                .lock()
                .unwrap()
                .insert(url.to_owned(), Ok(body.into()));
            self
        }
        pub fn failing(self, url: &str, error: NetError) -> Self {
            self.files
                .lock()
                .unwrap()
                .insert(url.to_owned(), Err(error));
            self
        }
        fn fetch(&self, url: &str) -> Result<Vec<u8>, NetError> {
            self.asked.lock().unwrap().push(url.to_owned());
            self.files
                .lock()
                .unwrap()
                .get(url)
                .cloned()
                .unwrap_or(Err(NetError::Status(404)))
        }
    }

    impl Http for FakeHttp {
        fn get_text(&self, url: &str, limit: u64) -> Result<String, NetError> {
            let body = self.fetch(url)?;
            if body.len() as u64 > limit {
                return Err(NetError::TooLarge);
            }
            Ok(String::from_utf8_lossy(&body).into_owned())
        }
        fn download(
            &self,
            url: &str,
            limit: u64,
            sink: &mut dyn Write,
            progress: &mut dyn FnMut(u64, Option<u64>),
        ) -> Result<(), NetError> {
            let body = self.fetch(url)?;
            if body.len() as u64 > limit {
                return Err(NetError::TooLarge);
            }
            for chunk in body.chunks(1000) {
                sink.write_all(chunk)
                    .map_err(|e| NetError::Interrupted(e.to_string()))?;
                progress(chunk.len() as u64, Some(body.len() as u64));
            }
            Ok(())
        }
    }

    /// Serves `responses` in turn, one connection each, on a port of its own; returns the base address.
    pub fn serve(responses: Vec<Vec<u8>>) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
        std::thread::spawn(move || {
            for response in responses {
                let Ok((mut stream, _)) = listener.accept() else {
                    return;
                };
                let mut request = [0u8; 4096];
                let _ = stream.read(&mut request);
                let _ = stream.write_all(&response);
            }
        });
        base
    }
}

#[cfg(test)]
mod tests {
    use super::fake::serve;
    use super::*;

    fn ok(body: &[u8]) -> Vec<u8> {
        let mut response = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        )
        .into_bytes();
        response.extend_from_slice(body);
        response
    }

    #[test]
    fn it_fetches_text_and_files_and_reports_progress() {
        let base = serve(vec![ok(b"[]"), ok(&vec![7u8; 200_000])]);
        let github = Github::for_tests();
        assert_eq!(
            github.get_text(&format!("{base}/list"), 1000).unwrap(),
            "[]"
        );

        let mut file = Vec::new();
        let mut last = (0, None);
        github
            .download(
                &format!("{base}/file"),
                1_000_000,
                &mut file,
                &mut |done, total| last = (done, total),
            )
            .unwrap();
        assert_eq!(file.len(), 200_000);
        assert_eq!(last, (200_000, Some(200_000)), "progress ends at the whole");
    }

    #[test]
    fn a_file_that_is_too_large_is_refused_by_its_header_or_cut_off_when_it_lies() {
        let base = serve(vec![
            ok(&vec![1u8; 5000]),
            b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
                .iter()
                .copied()
                .chain(vec![2u8; 5000])
                .collect(),
        ]);
        let github = Github::for_tests();
        let mut sink = Vec::new();
        let error = github
            .download(&format!("{base}/a"), 1000, &mut sink, &mut |_, _| {})
            .unwrap_err();
        assert_eq!(error, NetError::TooLarge);
        assert!(sink.is_empty(), "nothing was written before it was refused");

        // No length given: it is counted as it comes.
        let mut sink = Vec::new();
        let error = github
            .download(&format!("{base}/b"), 1000, &mut sink, &mut |_, _| {})
            .unwrap_err();
        assert_eq!(error, NetError::TooLarge);
        assert!(
            sink.len() <= 1000,
            "never more than the limit was written: {}",
            sink.len()
        );
    }

    #[test]
    fn a_connection_that_closes_early_is_not_a_finished_download() {
        let mut short =
            b"HTTP/1.1 200 OK\r\nContent-Length: 5000\r\nConnection: close\r\n\r\n".to_vec();
        short.extend(vec![3u8; 100]);
        let base = serve(vec![short]);
        let mut sink = Vec::new();
        let error = Github::for_tests()
            .download(&format!("{base}/a"), 10_000, &mut sink, &mut |_, _| {})
            .unwrap_err();
        assert!(matches!(error, NetError::Interrupted(_)), "{error:?}");
    }

    #[test]
    fn statuses_become_words_a_person_can_act_on() {
        let status = |line: &str| {
            format!("HTTP/1.1 {line}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .into_bytes()
        };
        let base = serve(vec![
            status("404 Not Found"),
            status("403 Forbidden"),
            status("429 Too Many Requests"),
            status("500 Oops"),
        ]);
        let github = Github::for_tests();
        let get = |path: &str| github.get_text(&format!("{base}/{path}"), 100).unwrap_err();
        assert_eq!(get("a"), NetError::Status(404));
        assert_eq!(get("b"), NetError::RateLimited);
        assert_eq!(get("c"), NetError::RateLimited);
        assert_eq!(get("d"), NetError::Status(500));
    }

    #[test]
    fn nothing_is_listening_means_unreachable() {
        let port = {
            std::net::TcpListener::bind("127.0.0.1:0")
                .unwrap()
                .local_addr()
                .unwrap()
                .port()
        };
        let error = Github::for_tests()
            .get_text(&format!("http://127.0.0.1:{port}/x"), 100)
            .unwrap_err();
        assert_eq!(error, NetError::Unreachable);
    }

    #[test]
    fn the_real_client_goes_nowhere_but_this_projects_releases() {
        let github = Github::new("Telepad/test");
        for url in [
            "https://evil.example/telepad",
            "http://api.github.com/repos/omsingh02/telepad/releases",
            "https://api.github.com/repos/someone-else/telepad/releases",
            "https://github.com/omsingh02/telepad/archive/main.zip",
            "https://api.github.com.evil.example/repos/omsingh02/telepad/",
            "file:///etc/passwd",
        ] {
            assert!(
                matches!(github.get_text(url, 10), Err(NetError::Refused(_))),
                "{url}"
            );
        }
    }
}
