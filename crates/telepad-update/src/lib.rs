//! Finding out whether a newer Telepad has been released, and installing it.
//!
//! What this program does on the internet is this and nothing else: it fetches the project's feed of releases
//! (and, for a newer one, that release's list of checksums) and, when the person says to, one release's file; all
//! of it from `github.com`. It sends no identifier of the person or the computer; like any request it shows the
//! address it comes from and the program's name and version. Nothing it downloads is used before its SHA-256
//! matches the `SHA256SUMS` published with the release.

pub mod apply;
pub mod fetch;
pub mod kind;
pub mod net;
pub mod release;
pub mod settings;
pub mod sums;
pub mod system;
pub mod updater;
pub mod version;
