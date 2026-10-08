//! Starting the server: the steps the console program and the tray app share, so that both
//! begin the same way and fail with the same words.

use crate::pairing::PairingMode;
use crate::{Server, ServerConfig};
use std::path::PathBuf;
use telepad_platform::clipboard::{Clipboard, SystemClipboard};
use telepad_platform::{
    create_input_backend, create_input_backend_when_allowed, paths, BackendOptions, InputBackend,
    RecordingBackend,
};
use telepad_protocol::TELEPAD_PORT;

/// Where input goes.
#[derive(Debug, Clone, Copy)]
pub enum Input {
    /// Into this computer, as a keyboard and mouse would.
    System(BackendOptions),
    /// As `System`, but a computer that has not allowed it yet does not stop the server: input starts working
    /// once it is allowed. For a program that can show the person what to do about it.
    SystemWhenAllowed(BackendOptions),
    /// Nowhere: the protocol runs and input is dropped. For finding out what is wrong.
    Discard,
}

/// What to start the server with.
#[derive(Debug, Clone)]
pub struct Options {
    pub port: u16,
    /// Where the identity key and the paired list live; the system's usual place when `None`.
    pub config_dir: Option<PathBuf>,
    pub pairing: PairingMode,
    pub input: Input,
}

impl Options {
    /// The usual: the standard port and folder, pairing open only on the very first run, real input.
    pub fn new() -> Self {
        Self {
            port: TELEPAD_PORT,
            config_dir: None,
            pairing: PairingMode::WhenUnpaired(crate::pairing::DEFAULT_PAIRING_WINDOW),
            input: Input::System(BackendOptions::default()),
        }
    }
}

impl Default for Options {
    fn default() -> Self {
        Self::new()
    }
}

/// Binds the server: its folder, its input, its name and its socket. Nothing is served until [`Server::run`].
///
/// The error is a message for a person: what went wrong and, where it is known, how to put it right.
pub async fn start(options: Options) -> Result<Server, String> {
    let config_dir = options
        .config_dir
        .clone()
        .unwrap_or_else(paths::default_config_dir);
    paths::ensure_private_dir(&config_dir).map_err(|e| {
        format!(
            "cannot create the config directory {}: {e}",
            config_dir.display()
        )
    })?;

    let input: Box<dyn InputBackend> = match options.input {
        Input::Discard => Box::new(RecordingBackend::new()),
        Input::System(backend) => create_input_backend(&backend).map_err(|e| {
            format!("{e}\n\n(Use --no-input to run without injecting input, for diagnostics.)")
        })?,
        Input::SystemWhenAllowed(backend) => {
            create_input_backend_when_allowed(&backend).map_err(|e| e.to_string())?
        }
    };

    let mut config = ServerConfig::new(config_dir);
    config.bind = std::net::SocketAddr::from(([0, 0, 0, 0], options.port));
    config.hostname = hostname::get()
        .map(|h| h.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "Desktop-PC".to_owned());
    config.pairing = options.pairing;

    let clipboard: crate::clipboard::ClipboardFactory =
        Box::new(|| SystemClipboard::new().map(|c| Box::new(c) as Box<dyn Clipboard>));
    Server::bind(config, input, clipboard)
        .await
        .map_err(|e| e.to_string())
}
