use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};

#[derive(Debug, thiserror::Error)]
pub enum PlatformError {
    /// The backend cannot be used at all on this machine (missing permission,
    /// missing kernel module, ...). The message tells the user how to fix it.
    #[error("{0}")]
    Unavailable(String),
    #[error("input injection failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Os(String),
}

pub type Result<T> = std::result::Result<T, PlatformError>;

/// Injects input into the local desktop on behalf of a remote client.
///
/// Keyboard codes are USB HID usages (page 0x07), exactly as they arrive on the
/// wire, so each backend only has to translate them into its native key space.
/// `mods` is the HID modifier bitmap (see `telepad_protocol::modifiers`); it is
/// applied *around* the key event: modifiers go down before the key on press
/// and come back up after it on release.
///
/// An implementation is driven from a single input thread, hence `&mut self`
/// and `Send` rather than `Sync`.
pub trait InputBackend: Send {
    /// Short name for the startup banner, e.g. `"Linux uinput"`.
    fn name(&self) -> &'static str;

    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()>;
    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()>;
    /// `delta` is in wheel notches; positive scrolls up (content moves down).
    fn scroll(&mut self, delta: i16) -> Result<()>;
    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()>;
    fn text(&mut self, text: &str) -> Result<()>;
    fn media(&mut self, action: MediaAction) -> Result<()>;
    fn volume(&mut self, direction: VolumeDirection) -> Result<()>;
    fn launch(&mut self, action: SystemAction) -> Result<()>;
    fn lock_screen(&mut self) -> Result<()>;
}

/// How soon [`DeferredInput`] tries again after the system said no.
const RETRY_AFTER: Duration = Duration::from_secs(1);

/// An input backend for a computer that has not allowed input yet: every call tries to make the real one, at
/// most once in a while, and reports the system's own words until it can. Once the person has allowed it,
/// input starts working without Telepad being started again.
pub struct DeferredInput {
    make: Box<dyn FnMut() -> Result<Box<dyn InputBackend>> + Send>,
    ready: Option<Box<dyn InputBackend>>,
    last_try: Option<Instant>,
    last_error: String,
    retry_after: Duration,
}

impl DeferredInput {
    /// `make` has just failed with `error`; try it again when input is wanted.
    pub fn waiting(
        make: impl FnMut() -> Result<Box<dyn InputBackend>> + Send + 'static,
        error: &PlatformError,
    ) -> Self {
        Self {
            make: Box::new(make),
            ready: None,
            last_try: Some(Instant::now()),
            last_error: error.to_string(),
            retry_after: RETRY_AFTER,
        }
    }

    fn backend(&mut self) -> Result<&mut dyn InputBackend> {
        if self.ready.is_none() {
            if self
                .last_try
                .is_some_and(|at| at.elapsed() < self.retry_after)
            {
                return Err(PlatformError::Unavailable(self.last_error.clone()));
            }
            self.last_try = Some(Instant::now());
            match (self.make)() {
                Ok(backend) => {
                    tracing::info!("input is allowed now ({})", backend.name());
                    self.ready = Some(backend);
                }
                Err(error) => {
                    self.last_error = error.to_string();
                    return Err(error);
                }
            }
        }
        Ok(self.ready.as_deref_mut().expect("made just above"))
    }
}

impl InputBackend for DeferredInput {
    fn name(&self) -> &'static str {
        self.ready
            .as_ref()
            .map_or("none yet (waiting to be allowed)", |backend| backend.name())
    }
    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()> {
        self.backend()?.mouse_move(dx, dy)
    }
    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()> {
        self.backend()?.mouse_button(button, pressed)
    }
    fn scroll(&mut self, delta: i16) -> Result<()> {
        self.backend()?.scroll(delta)
    }
    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()> {
        self.backend()?.key(usage, mods, pressed)
    }
    fn text(&mut self, text: &str) -> Result<()> {
        self.backend()?.text(text)
    }
    fn media(&mut self, action: MediaAction) -> Result<()> {
        self.backend()?.media(action)
    }
    fn volume(&mut self, direction: VolumeDirection) -> Result<()> {
        self.backend()?.volume(direction)
    }
    fn launch(&mut self, action: SystemAction) -> Result<()> {
        self.backend()?.launch(action)
    }
    fn lock_screen(&mut self) -> Result<()> {
        self.backend()?.lock_screen()
    }
}

/// One call made on an [`InputBackend`], as recorded by [`RecordingBackend`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputCall {
    MouseMove(i16, i16),
    MouseButton(MouseButtonKind, bool),
    Scroll(i16),
    Key { usage: u16, mods: u8, pressed: bool },
    Text(String),
    Media(MediaAction),
    Volume(VolumeDirection),
    Launch(SystemAction),
    LockScreen,
}

/// An [`InputBackend`] that injects nothing and remembers what it was asked to
/// do. Used by tests, and by `telepad-server --no-input` for protocol-only runs.
#[derive(Clone, Default)]
pub struct RecordingBackend {
    calls: Arc<Mutex<Vec<InputCall>>>,
}

impl RecordingBackend {
    pub fn new() -> Self {
        Self::default()
    }

    /// Shared handle to the call log, still valid after the backend has been
    /// moved onto the input thread.
    pub fn calls(&self) -> Arc<Mutex<Vec<InputCall>>> {
        Arc::clone(&self.calls)
    }

    fn record(&self, call: InputCall) -> Result<()> {
        self.calls.lock().unwrap().push(call);
        Ok(())
    }
}

impl InputBackend for RecordingBackend {
    fn name(&self) -> &'static str {
        "none (events are discarded)"
    }
    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()> {
        self.record(InputCall::MouseMove(dx, dy))
    }
    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()> {
        self.record(InputCall::MouseButton(button, pressed))
    }
    fn scroll(&mut self, delta: i16) -> Result<()> {
        self.record(InputCall::Scroll(delta))
    }
    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()> {
        self.record(InputCall::Key {
            usage,
            mods,
            pressed,
        })
    }
    fn text(&mut self, text: &str) -> Result<()> {
        self.record(InputCall::Text(text.to_owned()))
    }
    fn media(&mut self, action: MediaAction) -> Result<()> {
        self.record(InputCall::Media(action))
    }
    fn volume(&mut self, direction: VolumeDirection) -> Result<()> {
        self.record(InputCall::Volume(direction))
    }
    fn launch(&mut self, action: SystemAction) -> Result<()> {
        self.record(InputCall::Launch(action))
    }
    fn lock_screen(&mut self) -> Result<()> {
        self.record(InputCall::LockScreen)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    fn not_allowed() -> PlatformError {
        PlatformError::Unavailable("not allowed yet".into())
    }

    /// A deferred backend whose maker fails until `allowed` is set, counting its tries.
    fn deferred(
        allowed: &Arc<AtomicBool>,
        tries: &Arc<AtomicUsize>,
        retry_after: Duration,
    ) -> (DeferredInput, RecordingBackend) {
        let recording = RecordingBackend::new();
        let (allowed, tries, made) = (allowed.clone(), tries.clone(), recording.clone());
        let mut input = DeferredInput::waiting(
            move || {
                tries.fetch_add(1, Ordering::SeqCst);
                if allowed.load(Ordering::SeqCst) {
                    Ok(Box::new(made.clone()) as Box<dyn InputBackend>)
                } else {
                    Err(not_allowed())
                }
            },
            &not_allowed(),
        );
        input.retry_after = retry_after;
        (input, recording)
    }

    #[test]
    fn until_it_is_allowed_every_call_fails_with_the_systems_words() {
        let (allowed, tries) = (
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicUsize::new(0)),
        );
        let (mut input, recording) = deferred(&allowed, &tries, Duration::ZERO);
        for _ in 0..3 {
            let error = input.mouse_move(1, 1).unwrap_err();
            assert!(
                matches!(error, PlatformError::Unavailable(ref words) if words == "not allowed yet")
            );
        }
        assert!(recording.calls().lock().unwrap().is_empty());
        assert_eq!(input.name(), "none yet (waiting to be allowed)");
    }

    #[test]
    fn once_it_is_allowed_the_next_call_goes_through_with_no_restart() {
        let (allowed, tries) = (
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicUsize::new(0)),
        );
        let (mut input, recording) = deferred(&allowed, &tries, Duration::ZERO);
        assert!(input.key(4, 0, true).is_err());

        allowed.store(true, Ordering::SeqCst);
        input.key(4, 0, true).unwrap();
        input.text("hi").unwrap();
        assert_eq!(
            *recording.calls().lock().unwrap(),
            [
                InputCall::Key {
                    usage: 4,
                    mods: 0,
                    pressed: true
                },
                InputCall::Text("hi".into())
            ]
        );
        assert_eq!(
            tries.load(Ordering::SeqCst),
            2,
            "made once it was allowed, and not again"
        );
    }

    #[test]
    fn the_system_is_not_asked_again_for_every_pointer_movement() {
        let (allowed, tries) = (
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicUsize::new(0)),
        );
        let (mut input, _) = deferred(&allowed, &tries, Duration::from_secs(3600));
        for _ in 0..500 {
            let _ = input.mouse_move(1, 0);
        }
        assert_eq!(tries.load(Ordering::SeqCst), 0, "it had just been told no");
    }
}
