use std::sync::{Arc, Mutex};
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
