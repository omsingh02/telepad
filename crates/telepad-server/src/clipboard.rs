//! Clipboard access on a dedicated thread.
//!
//! Clipboard APIs are blocking, can stall (X11 selection transfers wait on the
//! owning application), and on some platforms their handles cannot cross
//! threads. They also have to stay alive: on X11 and Wayland the program that
//! set the clipboard serves its contents, so a handle dropped right after
//! `set_text` makes the text vanish. One long-lived actor thread solves all of
//! that and keeps the network task from ever blocking on it.

use std::sync::mpsc;
use std::time::{Duration, Instant};
use telepad_platform::clipboard::Clipboard;
use telepad_platform::PlatformError;
use tokio::sync::oneshot;
use tracing::{debug, warn};

/// How long a clipboard read may take before the phone is left without an answer.
const GET_TIMEOUT: Duration = Duration::from_secs(2);
/// Minimum delay between attempts to open a clipboard that was unavailable.
const RETRY_AFTER: Duration = Duration::from_secs(5);

/// Opens the system clipboard. Runs on the actor thread, and again later if the
/// first attempt fails (for instance before a desktop session exists).
pub type ClipboardFactory = Box<dyn Fn() -> Result<Box<dyn Clipboard>, PlatformError> + Send>;

enum Request {
    Set(String),
    Get(oneshot::Sender<Option<String>>),
}

#[derive(Clone)]
pub struct ClipboardHandle {
    tx: mpsc::Sender<Request>,
}

impl ClipboardHandle {
    pub fn spawn(factory: ClipboardFactory) -> std::io::Result<Self> {
        let (tx, rx) = mpsc::channel::<Request>();
        std::thread::Builder::new().name("telepad-clipboard".into()).spawn(move || {
            let mut clipboard: Option<Box<dyn Clipboard>> = None;
            let mut last_attempt: Option<Instant> = None;
            let mut warned = false;

            for request in rx {
                if clipboard.is_none()
                    && last_attempt.is_none_or(|at| at.elapsed() >= RETRY_AFTER)
                {
                    last_attempt = Some(Instant::now());
                    match factory() {
                        Ok(opened) => {
                            clipboard = Some(opened);
                            warned = false;
                        }
                        Err(err) if !warned => {
                            warn!("clipboard unavailable, clipboard sync is disabled for now: {err}");
                            warned = true;
                        }
                        Err(_) => {}
                    }
                }

                match request {
                    Request::Set(text) => match clipboard.as_mut() {
                        Some(c) => {
                            if let Err(err) = c.set_text(&text) {
                                warn!("could not set the clipboard: {err}");
                            }
                        }
                        None => debug!("dropping clipboard text: no clipboard"),
                    },
                    Request::Get(reply) => {
                        let text = match clipboard.as_mut() {
                            Some(c) => c.get_text().unwrap_or_else(|err| {
                                warn!("could not read the clipboard: {err}");
                                None
                            }),
                            None => None,
                        };
                        // The requester may have timed out and gone away.
                        let _ = reply.send(text);
                    }
                }
            }
        })?;
        Ok(Self { tx })
    }

    pub fn set(&self, text: String) {
        let _ = self.tx.send(Request::Set(text));
    }

    /// The clipboard's text, or `None` when it is empty, not text, unavailable
    /// or slow to answer.
    pub async fn get(&self) -> Option<String> {
        let (reply, answer) = oneshot::channel();
        self.tx.send(Request::Get(reply)).ok()?;
        tokio::time::timeout(GET_TIMEOUT, answer).await.ok()?.ok()?
    }
}

/// Cuts `text` to at most `max_bytes` without splitting a UTF-8 character.
pub fn truncate_to_char_boundary(text: &str, max_bytes: usize) -> &str {
    if text.len() <= max_bytes {
        return text;
    }
    let mut end = max_bytes;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    struct Memory(Arc<Mutex<Option<String>>>);

    impl Clipboard for Memory {
        fn get_text(&mut self) -> Result<Option<String>, PlatformError> {
            Ok(self.0.lock().unwrap().clone())
        }
        fn set_text(&mut self, text: &str) -> Result<(), PlatformError> {
            *self.0.lock().unwrap() = Some(text.to_owned());
            Ok(())
        }
    }

    fn memory_factory(cell: Arc<Mutex<Option<String>>>) -> ClipboardFactory {
        Box::new(move || Ok(Box::new(Memory(cell.clone())) as Box<dyn Clipboard>))
    }

    #[tokio::test]
    async fn set_then_get_round_trips() {
        let cell = Arc::new(Mutex::new(None));
        let handle = ClipboardHandle::spawn(memory_factory(cell.clone())).unwrap();
        assert_eq!(handle.get().await, None, "empty clipboard has no text");
        handle.set("héllo 🚀".into());
        assert_eq!(handle.get().await.as_deref(), Some("héllo 🚀"));
        assert_eq!(cell.lock().unwrap().as_deref(), Some("héllo 🚀"));
    }

    #[tokio::test]
    async fn requests_are_handled_in_order() {
        let handle = ClipboardHandle::spawn(memory_factory(Arc::new(Mutex::new(None)))).unwrap();
        handle.set("one".into());
        handle.set("two".into());
        assert_eq!(handle.get().await.as_deref(), Some("two"));
    }

    #[tokio::test]
    async fn unavailable_clipboard_answers_none_without_hammering_the_factory() {
        let attempts = Arc::new(Mutex::new(0u32));
        let factory: ClipboardFactory = {
            let attempts = attempts.clone();
            Box::new(move || {
                *attempts.lock().unwrap() += 1;
                Err(PlatformError::Os("no display".into()))
            })
        };
        let handle = ClipboardHandle::spawn(factory).unwrap();
        assert_eq!(handle.get().await, None);
        handle.set("dropped".into());
        // More requests inside the retry window must not reopen the clipboard each time.
        assert_eq!(handle.get().await, None);
        assert_eq!(*attempts.lock().unwrap(), 1);
    }

    #[test]
    fn truncation_respects_utf8_boundaries() {
        assert_eq!(truncate_to_char_boundary("hello", 10), "hello");
        assert_eq!(truncate_to_char_boundary("hello", 5), "hello");
        assert_eq!(truncate_to_char_boundary("hello", 3), "hel");
        assert_eq!(truncate_to_char_boundary("hello", 0), "");
        // 'é' is two bytes: cutting inside it backs off to the previous boundary.
        assert_eq!(truncate_to_char_boundary("aé", 2), "a");
        assert_eq!(truncate_to_char_boundary("aé", 3), "aé");
        // '🚀' is four bytes.
        assert_eq!(truncate_to_char_boundary("ab🚀", 4), "ab");
        assert_eq!(truncate_to_char_boundary("ab🚀", 6), "ab🚀");
        // Every cut of a multi-byte string is valid UTF-8 and never longer than asked.
        let text = "a한🚀é".repeat(20);
        for max in 0..text.len() + 3 {
            let cut = truncate_to_char_boundary(&text, max);
            assert!(cut.len() <= max && text.starts_with(cut));
        }
    }
}
