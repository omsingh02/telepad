//! The one thread that talks to the operating system's input APIs.
//!
//! Network handling never calls the platform backend directly. Doing so would
//! let a slow call (launching a program, typing a long string with delays)
//! stall packet reception, and would let events from different packets race.
//! Instead every input message is queued here and applied strictly in order.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::mpsc;
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use telepad_platform::{InputBackend, PlatformError};
use telepad_protocol::ClientMessage;
use tracing::{error, warn};

enum Command {
    Apply(ClientMessage),
    Stop,
}

/// What kind of message this is, such as `TextInput`, and nothing of what it holds: what a person typed must never
/// end up in a log.
fn kind(message: &ClientMessage) -> String {
    format!("{message:?}")
        .split(|c: char| !c.is_alphanumeric())
        .next()
        .unwrap_or("input")
        .to_owned()
}

/// Handle to the input thread. Cheap to use from any thread or task.
pub struct InputWorker {
    tx: mpsc::Sender<Command>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl InputWorker {
    pub fn spawn(mut backend: Box<dyn InputBackend>) -> std::io::Result<Self> {
        let (tx, rx) = mpsc::channel::<Command>();
        let thread = std::thread::Builder::new()
            .name("telepad-input".into())
            .spawn(move || {
                let mut throttle = ErrorThrottle::new(Duration::from_secs(10));
                for command in rx {
                    let message = match command {
                        Command::Apply(message) => message,
                        Command::Stop => break,
                    };
                    // A bug in a platform backend must not take input down for good.
                    let outcome =
                        catch_unwind(AssertUnwindSafe(|| apply(backend.as_mut(), &message)));
                    let failure = match outcome {
                        Ok(Some(Err(err))) => err.to_string(),
                        Ok(_) => continue,
                        Err(_) => format!(
                            "the input backend panicked while handling a {} message",
                            kind(&message)
                        ),
                    };
                    if let Some(suppressed) = throttle.should_log(&failure, Instant::now()) {
                        if suppressed > 0 {
                            warn!("input: {failure} ({suppressed} similar errors suppressed)");
                        } else {
                            warn!("input: {failure}");
                        }
                    }
                }
            })?;
        Ok(Self {
            tx,
            thread: Mutex::new(Some(thread)),
        })
    }

    pub fn send(&self, message: ClientMessage) {
        if self.tx.send(Command::Apply(message)).is_err() {
            error!("the input thread has stopped; dropping input");
        }
    }

    pub fn send_all(&self, messages: impl IntoIterator<Item = ClientMessage>) {
        for message in messages {
            self.send(message);
        }
    }

    /// Applies everything already queued, then stops the thread.
    pub fn shutdown(&self) {
        let _ = self.tx.send(Command::Stop);
        if let Some(thread) = self.thread.lock().unwrap().take() {
            let _ = thread.join();
        }
    }
}

impl Drop for InputWorker {
    fn drop(&mut self) {
        let _ = self.tx.send(Command::Stop);
    }
}

/// Performs a message on a backend. Returns `None` for messages that are not
/// input (clipboard and media queries are handled elsewhere).
pub fn apply(
    backend: &mut dyn InputBackend,
    message: &ClientMessage,
) -> Option<Result<(), PlatformError>> {
    Some(match message {
        ClientMessage::MouseMove { dx, dy } => backend.mouse_move(*dx, *dy),
        ClientMessage::MouseButton { button, pressed } => backend.mouse_button(*button, *pressed),
        ClientMessage::Scroll { delta } => backend.scroll(*delta),
        ClientMessage::KeyPress { keycode, mods } => backend.key(*keycode, *mods, true),
        ClientMessage::KeyRelease { keycode, mods } => backend.key(*keycode, *mods, false),
        ClientMessage::TextInput(text) => backend.text(text),
        ClientMessage::MediaCmd(action) => backend.media(*action),
        ClientMessage::VolumeCmd(direction) => backend.volume(*direction),
        ClientMessage::LockScreen => backend.lock_screen(),
        ClientMessage::LaunchAction(action) => backend.launch(*action),
        ClientMessage::ClipboardGet
        | ClientMessage::ClipboardSet(_)
        | ClientMessage::NowPlayingQuery
        | ClientMessage::HostInfoQuery => return None,
    })
}

/// Rate-limits repeated identical log lines: the first occurrence is reported
/// immediately, later ones at most once per interval, with a count of how many
/// were swallowed in between.
pub struct ErrorThrottle {
    interval: Duration,
    seen: HashMap<String, (Instant, u64)>,
}

impl ErrorThrottle {
    pub fn new(interval: Duration) -> Self {
        Self {
            interval,
            seen: HashMap::new(),
        }
    }

    /// `Some(suppressed)` when the message should be logged now (with the
    /// number of identical messages dropped since the last report), `None` when
    /// it should be swallowed.
    pub fn should_log(&mut self, key: &str, now: Instant) -> Option<u64> {
        // Bound memory if an error message embeds changing data.
        if self.seen.len() > 64 {
            self.seen
                .retain(|_, (at, _)| now.saturating_duration_since(*at) < self.interval);
        }
        match self.seen.get_mut(key) {
            Some((last, suppressed)) => {
                if now.saturating_duration_since(*last) >= self.interval {
                    let dropped = std::mem::take(suppressed);
                    *last = now;
                    Some(dropped)
                } else {
                    *suppressed += 1;
                    None
                }
            }
            None => {
                self.seen.insert(key.to_owned(), (now, 0));
                Some(0)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use telepad_platform::{InputCall, RecordingBackend};
    use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};

    fn wait_for(calls: &Arc<Mutex<Vec<InputCall>>>, count: usize) -> Vec<InputCall> {
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            let snapshot = calls.lock().unwrap().clone();
            if snapshot.len() >= count || Instant::now() > deadline {
                return snapshot;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn a_log_line_names_the_kind_of_message_and_never_what_was_typed() {
        let typed = ClientMessage::TextInput("my password is hunter2".into());
        assert_eq!(kind(&typed), "TextInput");
        assert!(!kind(&typed).contains("hunter2"));

        let clipboard = ClientMessage::ClipboardSet("a secret".into());
        assert_eq!(kind(&clipboard), "ClipboardSet");
        assert_eq!(
            kind(&ClientMessage::MouseMove { dx: 3, dy: -4 }),
            "MouseMove"
        );
        assert_eq!(kind(&ClientMessage::LockScreen), "LockScreen");
    }

    #[test]
    fn every_input_message_reaches_the_matching_backend_call() {
        let mut backend = RecordingBackend::new();
        let calls = backend.calls();
        let messages = [
            ClientMessage::MouseMove { dx: -3, dy: 4 },
            ClientMessage::MouseButton {
                button: MouseButtonKind::Right,
                pressed: true,
            },
            ClientMessage::Scroll { delta: -1 },
            ClientMessage::KeyPress {
                keycode: 0x06,
                mods: 0x01,
            },
            ClientMessage::KeyRelease {
                keycode: 0x06,
                mods: 0x01,
            },
            ClientMessage::TextInput("héllo".into()),
            ClientMessage::MediaCmd(MediaAction::Next),
            ClientMessage::VolumeCmd(VolumeDirection::Mute),
            ClientMessage::LockScreen,
            ClientMessage::LaunchAction(SystemAction::Screenshot),
        ];
        for message in &messages {
            assert!(apply(&mut backend, message).expect("input message").is_ok());
        }
        assert_eq!(
            *calls.lock().unwrap(),
            vec![
                InputCall::MouseMove(-3, 4),
                InputCall::MouseButton(MouseButtonKind::Right, true),
                InputCall::Scroll(-1),
                InputCall::Key {
                    usage: 0x06,
                    mods: 0x01,
                    pressed: true
                },
                InputCall::Key {
                    usage: 0x06,
                    mods: 0x01,
                    pressed: false
                },
                InputCall::Text("héllo".into()),
                InputCall::Media(MediaAction::Next),
                InputCall::Volume(VolumeDirection::Mute),
                InputCall::LockScreen,
                InputCall::Launch(SystemAction::Screenshot),
            ]
        );
    }

    #[test]
    fn non_input_messages_are_not_applied() {
        let mut backend = RecordingBackend::new();
        for message in [
            ClientMessage::ClipboardGet,
            ClientMessage::ClipboardSet("x".into()),
            ClientMessage::NowPlayingQuery,
            ClientMessage::HostInfoQuery,
        ] {
            assert!(apply(&mut backend, &message).is_none());
        }
        assert!(backend.calls().lock().unwrap().is_empty());
    }

    #[test]
    fn the_worker_applies_messages_in_order_and_flushes_on_shutdown() {
        let backend = RecordingBackend::new();
        let calls = backend.calls();
        let worker = InputWorker::spawn(Box::new(backend)).unwrap();
        for i in 0..200i16 {
            worker.send(ClientMessage::MouseMove { dx: i, dy: -i });
        }
        worker.shutdown();
        let recorded = calls.lock().unwrap().clone();
        assert_eq!(recorded.len(), 200, "shutdown must drain the queue");
        for (i, call) in recorded.iter().enumerate() {
            assert_eq!(*call, InputCall::MouseMove(i as i16, -(i as i16)));
        }
    }

    #[test]
    fn sending_after_shutdown_does_not_panic() {
        let worker = InputWorker::spawn(Box::new(RecordingBackend::new())).unwrap();
        worker.shutdown();
        worker.send(ClientMessage::LockScreen);
        worker.shutdown();
    }

    struct Flaky {
        fail_next: bool,
        calls: Arc<Mutex<Vec<InputCall>>>,
    }

    impl InputBackend for Flaky {
        fn name(&self) -> &'static str {
            "flaky"
        }
        fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<(), PlatformError> {
            if std::mem::take(&mut self.fail_next) {
                panic!("simulated backend bug");
            }
            self.calls
                .lock()
                .unwrap()
                .push(InputCall::MouseMove(dx, dy));
            Ok(())
        }
        fn mouse_button(&mut self, _: MouseButtonKind, _: bool) -> Result<(), PlatformError> {
            Err(PlatformError::Os("simulated failure".into()))
        }
        fn scroll(&mut self, _: i16) -> Result<(), PlatformError> {
            Ok(())
        }
        fn key(&mut self, _: u16, _: u8, _: bool) -> Result<(), PlatformError> {
            Ok(())
        }
        fn text(&mut self, _: &str) -> Result<(), PlatformError> {
            Ok(())
        }
        fn media(&mut self, _: MediaAction) -> Result<(), PlatformError> {
            Ok(())
        }
        fn volume(&mut self, _: VolumeDirection) -> Result<(), PlatformError> {
            Ok(())
        }
        fn launch(&mut self, _: SystemAction) -> Result<(), PlatformError> {
            Ok(())
        }
        fn lock_screen(&mut self) -> Result<(), PlatformError> {
            Ok(())
        }
    }

    #[test]
    fn a_panic_or_error_in_the_backend_does_not_stop_later_input() {
        let calls = Arc::new(Mutex::new(Vec::new()));
        let worker = InputWorker::spawn(Box::new(Flaky {
            fail_next: true,
            calls: calls.clone(),
        }))
        .unwrap();
        worker.send(ClientMessage::MouseMove { dx: 1, dy: 1 }); // panics
        worker.send(ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: true,
        }); // errors
        worker.send(ClientMessage::MouseMove { dx: 2, dy: 2 }); // must still be applied
        let recorded = wait_for(&calls, 1);
        worker.shutdown();
        assert_eq!(recorded, vec![InputCall::MouseMove(2, 2)]);
    }

    // ── ErrorThrottle ───────────────────────────────────────────────────

    #[test]
    fn throttle_reports_first_occurrence_then_suppresses() {
        let mut t = ErrorThrottle::new(Duration::from_secs(10));
        let t0 = Instant::now();
        assert_eq!(t.should_log("boom", t0), Some(0));
        assert_eq!(t.should_log("boom", t0 + Duration::from_secs(1)), None);
        assert_eq!(t.should_log("boom", t0 + Duration::from_secs(9)), None);
        // After the interval it reports again, saying how many it swallowed.
        assert_eq!(t.should_log("boom", t0 + Duration::from_secs(10)), Some(2));
        assert_eq!(t.should_log("boom", t0 + Duration::from_secs(11)), None);
        assert_eq!(t.should_log("boom", t0 + Duration::from_secs(21)), Some(1));
    }

    #[test]
    fn throttle_tracks_distinct_messages_independently() {
        let mut t = ErrorThrottle::new(Duration::from_secs(10));
        let t0 = Instant::now();
        assert_eq!(t.should_log("a", t0), Some(0));
        assert_eq!(t.should_log("b", t0), Some(0));
        assert_eq!(t.should_log("a", t0), None);
        assert_eq!(t.should_log("b", t0), None);
    }

    #[test]
    fn throttle_memory_is_bounded() {
        let mut t = ErrorThrottle::new(Duration::from_secs(1));
        let t0 = Instant::now();
        for i in 0..1000 {
            t.should_log(&format!("error {i}"), t0 + Duration::from_secs(i / 10));
        }
        assert!(t.seen.len() <= 200, "{} entries retained", t.seen.len());
    }
}
