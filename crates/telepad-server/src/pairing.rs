//! Who is allowed to connect.
//!
//! The Noise handshake proves a client holds *some* key, and tells the server
//! which one. It says nothing about whether the PC's owner wants that device to
//! type on their keyboard. Without a gate here, anyone on the same network can
//! fetch the server's public key (the pairing intro is unauthenticated by
//! design), complete a handshake, and inject keystrokes.
//!
//! The rule is therefore: a device that is not already paired is only accepted
//! while the owner has explicitly opened a *pairing window*, and is remembered
//! once accepted. The window closes as soon as one device has paired, so it is
//! open for the phone the owner is holding and for no one else.

use std::time::Duration;
use telepad_crypto::{CryptoError, PairingStore};
use tokio::time::Instant;

/// How long the window stays open when the owner opens it without saying.
pub const DEFAULT_PAIRING_WINDOW: Duration = Duration::from_secs(300);

/// How the server decides about devices it has never seen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingMode {
    /// Accept and remember every device, forever. This restores the behaviour
    /// of earlier versions and lets anyone on the network control the PC.
    AcceptAny,
    /// Accept a new device for this long after startup, or until one has paired.
    Window(Duration),
    /// Like [`Window`](Self::Window), but only when no device is paired yet, so a
    /// first run works out of the box and later runs are locked down.
    WhenUnpaired(Duration),
    /// Never accept a new device (until the owner opens a window).
    Closed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Admission {
    /// A device that was paired earlier.
    Known,
    /// A device accepted just now because a pairing window was open; it has been remembered
    /// and the window is now closed.
    NewlyPaired,
    /// An unknown device while pairing is closed.
    Rejected,
}

impl Admission {
    pub fn is_allowed(self) -> bool {
        !matches!(self, Admission::Rejected)
    }
}

pub struct Pairing {
    store: PairingStore,
    accept_any: bool,
    window_until: Option<Instant>,
}

impl Pairing {
    pub fn new(store: PairingStore, mode: PairingMode, now: Instant) -> Self {
        let mut pairing = Self {
            store,
            accept_any: false,
            window_until: None,
        };
        match mode {
            PairingMode::AcceptAny => pairing.accept_any = true,
            PairingMode::Window(duration) => pairing.open_window(now, duration),
            PairingMode::WhenUnpaired(duration) => {
                if pairing.store.is_empty() {
                    pairing.open_window(now, duration);
                }
            }
            PairingMode::Closed => {}
        }
        pairing
    }

    pub fn open_window(&mut self, now: Instant, duration: Duration) {
        self.window_until = Some(now + duration);
    }

    pub fn close_window(&mut self) {
        self.window_until = None;
    }

    /// Time left in the pairing window, or `None` if it is closed.
    pub fn window_remaining(&self, now: Instant) -> Option<Duration> {
        self.window_until
            .map(|until| until.saturating_duration_since(now))
            .filter(|remaining| !remaining.is_zero())
    }

    pub fn accepts_any_device(&self) -> bool {
        self.accept_any
    }

    pub fn is_open(&self, now: Instant) -> bool {
        self.accept_any || self.window_remaining(now).is_some()
    }

    /// Decides whether the device with this public key may connect, pairing it
    /// if a window is open. A failure to save the pairing is reported but does
    /// not refuse the device: it can still use this session.
    pub fn admit(&mut self, pubkey: &[u8; 32], now: Instant) -> (Admission, Option<CryptoError>) {
        if self.store.is_trusted(pubkey) {
            return (Admission::Known, None);
        }
        if !self.is_open(now) {
            return (Admission::Rejected, None);
        }
        let save_error = self.store.trust(pubkey).err();
        // The owner opened the window for the phone in their hand, so shut it behind that
        // phone rather than leaving it open for anyone else on the network. (Under
        // `AcceptAny` there is no window to close.)
        self.window_until = None;
        (Admission::NewlyPaired, save_error)
    }

    pub fn paired_devices(&self) -> Vec<[u8; 32]> {
        self.store.trusted_keys()
    }

    pub fn paired_count(&self) -> usize {
        self.store.len()
    }

    pub fn forget_all(&mut self) -> Result<(), CryptoError> {
        self.store.forget_all()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    const SEC: Duration = Duration::from_secs(1);

    /// The folder a test keeps its paired list in. It is removed when the test ends, pass or
    /// fail, so that running the tests does not fill the temp directory.
    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            let dir = std::env::temp_dir().join(format!("telepad_pairing_{:016x}", rand_u64()));
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn file(&self) -> PathBuf {
            self.0.join("trusted.json")
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn store() -> (PairingStore, Scratch) {
        let scratch = Scratch::new();
        (PairingStore::load_or_default(scratch.file()), scratch)
    }

    fn rand_u64() -> u64 {
        use std::hash::{BuildHasher, Hasher};
        std::collections::hash_map::RandomState::new()
            .build_hasher()
            .finish()
    }

    fn key(n: u8) -> [u8; 32] {
        [n; 32]
    }

    fn pairing(mode: PairingMode) -> (Pairing, Instant, Scratch) {
        let (store, scratch) = store();
        let now = Instant::now();
        (Pairing::new(store, mode, now), now, scratch)
    }

    #[test]
    fn closed_mode_rejects_strangers() {
        let (mut p, now, _scratch) = pairing(PairingMode::Closed);
        assert!(!p.is_open(now));
        assert_eq!(p.admit(&key(1), now).0, Admission::Rejected);
        assert_eq!(
            p.paired_count(),
            0,
            "a rejected device must not be remembered"
        );
    }

    #[test]
    fn known_devices_always_get_in_even_when_closed() {
        let (store, scratch) = store();
        let mut seeded = store;
        seeded.trust(&key(1)).unwrap();
        let now = Instant::now();
        let mut p = Pairing::new(
            PairingStore::load_or_default(scratch.file()),
            PairingMode::Closed,
            now,
        );
        assert_eq!(p.admit(&key(1), now).0, Admission::Known);
        assert_eq!(p.admit(&key(2), now).0, Admission::Rejected);
    }

    #[test]
    fn an_open_window_pairs_and_remembers_new_devices() {
        let (mut p, now, scratch) = pairing(PairingMode::Window(60 * SEC));
        assert!(p.is_open(now));
        let (admission, err) = p.admit(&key(7), now);
        assert_eq!(admission, Admission::NewlyPaired);
        assert!(err.is_none());
        // Once paired it is simply known, and survives a restart even with the window shut.
        assert_eq!(p.admit(&key(7), now).0, Admission::Known);
        let mut restarted = Pairing::new(
            PairingStore::load_or_default(scratch.file()),
            PairingMode::Closed,
            now,
        );
        assert_eq!(restarted.admit(&key(7), now).0, Admission::Known);
    }

    #[test]
    fn the_window_closes_behind_the_first_phone_that_pairs() {
        // The owner opened it for the phone in their hand, not for whoever else is on the
        // network, so a second stranger arriving in the same minute is turned away.
        let (mut p, now, _scratch) = pairing(PairingMode::Window(60 * SEC));
        assert_eq!(p.admit(&key(1), now).0, Admission::NewlyPaired);
        assert!(!p.is_open(now));
        assert_eq!(p.window_remaining(now), None);
        assert_eq!(p.admit(&key(2), now).0, Admission::Rejected);
        assert_eq!(p.paired_count(), 1, "the stranger must not be remembered");
        assert_eq!(
            p.admit(&key(1), now).0,
            Admission::Known,
            "the phone that paired keeps working"
        );
    }

    #[test]
    fn a_first_run_window_also_closes_after_one_phone() {
        let (mut p, now, _scratch) = pairing(PairingMode::WhenUnpaired(60 * SEC));
        assert_eq!(p.admit(&key(1), now).0, Admission::NewlyPaired);
        assert_eq!(p.admit(&key(2), now).0, Admission::Rejected);
    }

    #[test]
    fn the_owner_can_reopen_it_for_the_next_phone() {
        let (mut p, now, _scratch) = pairing(PairingMode::Window(60 * SEC));
        p.admit(&key(1), now);
        p.open_window(now, 60 * SEC);
        assert_eq!(p.admit(&key(2), now).0, Admission::NewlyPaired);
        assert_eq!(p.paired_devices(), vec![key(1), key(2)]);
    }

    #[test]
    fn a_paired_phone_reconnecting_does_not_use_up_the_window() {
        // Only pairing a new phone closes it: the owner's own phone reconnecting while they
        // wait to add another must not.
        let (store, scratch) = store();
        let mut seeded = store;
        seeded.trust(&key(9)).unwrap();
        let now = Instant::now();
        let mut p = Pairing::new(
            PairingStore::load_or_default(scratch.file()),
            PairingMode::Window(60 * SEC),
            now,
        );
        assert_eq!(p.admit(&key(9), now).0, Admission::Known);
        assert!(p.is_open(now));
        assert_eq!(p.admit(&key(3), now).0, Admission::NewlyPaired);
    }

    #[test]
    fn the_window_closes_when_its_time_is_up() {
        let (mut p, now, _scratch) = pairing(PairingMode::Window(10 * SEC));
        assert_eq!(p.window_remaining(now), Some(10 * SEC));
        assert!(p.is_open(now + 9 * SEC));
        assert_eq!(p.admit(&key(1), now + 10 * SEC).0, Admission::Rejected);
        assert_eq!(p.window_remaining(now + 11 * SEC), None);
        assert!(!p.is_open(now + 11 * SEC));
    }

    #[test]
    fn when_unpaired_opens_only_on_a_first_run() {
        let (mut first_run, now, scratch) = pairing(PairingMode::WhenUnpaired(60 * SEC));
        assert!(
            first_run.is_open(now),
            "nothing paired yet, so onboarding is open"
        );
        first_run.admit(&key(1), now);

        let second_run = Pairing::new(
            PairingStore::load_or_default(scratch.file()),
            PairingMode::WhenUnpaired(60 * SEC),
            now,
        );
        assert!(
            !second_run.is_open(now),
            "a paired install stays locked down"
        );
    }

    #[test]
    fn the_owner_can_open_and_close_the_window_at_any_time() {
        let (mut p, now, _scratch) = pairing(PairingMode::Closed);
        p.open_window(now, 30 * SEC);
        assert_eq!(p.admit(&key(1), now).0, Admission::NewlyPaired);
        p.close_window();
        assert_eq!(p.admit(&key(2), now).0, Admission::Rejected);
        assert_eq!(
            p.admit(&key(1), now).0,
            Admission::Known,
            "closing does not unpair"
        );
    }

    #[test]
    fn accept_any_never_rejects() {
        let (mut p, now, _scratch) = pairing(PairingMode::AcceptAny);
        assert!(p.accepts_any_device() && p.is_open(now));
        for n in 0..5 {
            assert!(p
                .admit(&key(n), now + Duration::from_secs(86_400))
                .0
                .is_allowed());
        }
        assert_eq!(p.paired_count(), 5);
    }

    #[test]
    fn forgetting_all_devices_locks_them_out_again() {
        let (mut p, now, _scratch) = pairing(PairingMode::Window(60 * SEC));
        p.admit(&key(1), now);
        p.close_window();
        p.forget_all().unwrap();
        assert_eq!(p.paired_count(), 0);
        assert_eq!(p.admit(&key(1), now).0, Admission::Rejected);
    }

    #[test]
    fn listing_devices() {
        let (mut p, now, _scratch) = pairing(PairingMode::AcceptAny);
        p.admit(&key(2), now);
        p.admit(&key(1), now);
        assert_eq!(p.paired_devices(), vec![key(1), key(2)]);
    }

    #[test]
    fn a_save_failure_is_reported_but_the_device_is_still_admitted() {
        // Point the store at a path whose parent is a file, so saving must fail.
        let scratch = Scratch::new();
        let blocker = scratch.0.join("blocker");
        std::fs::write(&blocker, b"x").unwrap();
        let store = PairingStore::load_or_default(blocker.join("trusted.json"));
        let now = Instant::now();
        let mut p = Pairing::new(store, PairingMode::Window(60 * SEC), now);
        let (admission, err) = p.admit(&key(1), now);
        assert_eq!(admission, Admission::NewlyPaired);
        assert!(
            err.is_some(),
            "the caller needs to know the pairing was not persisted"
        );
    }
}
