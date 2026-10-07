//! The Telepad desktop server as a library, so the whole network behaviour can
//! be tested end to end over loopback. The `telepad-server` binary is a thin
//! wrapper that adds the command line, logging and console.

pub mod clipboard;
pub mod console;
pub mod discovery;
pub mod input;
pub mod invite;
pub mod launch;
pub mod pairing;
pub mod qr;
pub mod server;
pub mod sessions;

pub use invite::Invite;
pub use pairing::PairingMode;
pub use server::{Server, ServerConfig, ServerError, ServerHandle, Status};
