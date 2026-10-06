//! Per-client sessions: who is connected, what they are holding down, and when
//! to forget them.
//!
//! This module is pure bookkeeping (no sockets, no clock of its own), so every
//! rule here is covered by plain unit tests.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::Duration;
use telepad_crypto::NoiseTransport;
use telepad_protocol::{ClientMessage, MouseButtonKind};
use tokio::time::Instant;

/// Buttons and keys a client has pressed but not yet released.
///
/// A phone that drops off the network mid-drag, or whose app is killed while a
/// key is down, never sends the release. Remembering what is held lets the
/// server release it on the client's behalf instead of leaving a mouse button
/// or modifier stuck down on the PC.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct HeldInputs {
    buttons: [bool; 3],
    /// `(usage, modifiers)` of each key pressed, in press order.
    keys: Vec<(u16, u8)>,
}

impl HeldInputs {
    fn button_slot(button: MouseButtonKind) -> usize {
        match button {
            MouseButtonKind::Left => 0,
            MouseButtonKind::Right => 1,
            MouseButtonKind::Middle => 2,
        }
    }

    /// Updates the held set from a message the client sent.
    pub fn observe(&mut self, message: &ClientMessage) {
        match *message {
            ClientMessage::MouseButton { button, pressed } => {
                self.buttons[Self::button_slot(button)] = pressed;
            }
            ClientMessage::KeyPress { keycode, mods } => {
                match self.keys.iter_mut().find(|(k, _)| *k == keycode) {
                    Some(held) => held.1 = mods,
                    None => self.keys.push((keycode, mods)),
                }
            }
            ClientMessage::KeyRelease { keycode, .. } => {
                self.keys.retain(|(k, _)| *k != keycode);
            }
            _ => {}
        }
    }

    pub fn is_empty(&self) -> bool {
        !self.buttons.iter().any(|&b| b) && self.keys.is_empty()
    }

    /// The messages that release everything held, newest key first, clearing
    /// the held set.
    pub fn take_release_messages(&mut self) -> Vec<ClientMessage> {
        let mut messages: Vec<ClientMessage> = self
            .keys
            .drain(..)
            .rev()
            .map(|(keycode, mods)| ClientMessage::KeyRelease { keycode, mods })
            .collect();
        for (slot, button) in [
            MouseButtonKind::Left,
            MouseButtonKind::Right,
            MouseButtonKind::Middle,
        ]
        .into_iter()
        .enumerate()
        {
            if std::mem::take(&mut self.buttons[slot]) {
                messages.push(ClientMessage::MouseButton {
                    button,
                    pressed: false,
                });
            }
        }
        messages
    }
}

pub struct Session {
    pub transport: NoiseTransport,
    pub client_pubkey: [u8; 32],
    pub last_seen: Instant,
    pub held: HeldInputs,
}

impl Session {
    pub fn new(transport: NoiseTransport, client_pubkey: [u8; 32], now: Instant) -> Self {
        Self {
            transport,
            client_pubkey,
            last_seen: now,
            held: HeldInputs::default(),
        }
    }
}

/// All live sessions, keyed by the client's UDP address.
pub struct SessionTable {
    sessions: HashMap<SocketAddr, Session>,
    max_sessions: usize,
}

impl SessionTable {
    pub fn new(max_sessions: usize) -> Self {
        Self {
            sessions: HashMap::new(),
            max_sessions: max_sessions.max(1),
        }
    }

    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    pub fn is_empty(&self) -> bool {
        self.sessions.is_empty()
    }

    pub fn contains(&self, addr: &SocketAddr) -> bool {
        self.sessions.contains_key(addr)
    }

    pub fn get(&self, addr: &SocketAddr) -> Option<&Session> {
        self.sessions.get(addr)
    }

    pub fn get_mut(&mut self, addr: &SocketAddr) -> Option<&mut Session> {
        self.sessions.get_mut(addr)
    }

    /// Adds a session. A session already registered for `addr` is replaced, and
    /// when the table is full the session quiet for longest is evicted. Returns
    /// the messages that release anything the dropped session was holding.
    pub fn insert(&mut self, addr: SocketAddr, session: Session) -> Vec<ClientMessage> {
        let mut releases = Vec::new();
        if let Some(mut old) = self.sessions.remove(&addr) {
            releases.extend(old.held.take_release_messages());
        }
        if self.sessions.len() >= self.max_sessions {
            let oldest = self
                .sessions
                .iter()
                .min_by_key(|(_, s)| s.last_seen)
                .map(|(addr, _)| *addr);
            if let Some(mut evicted) = oldest.and_then(|a| self.sessions.remove(&a)) {
                releases.extend(evicted.held.take_release_messages());
            }
        }
        self.sessions.insert(addr, session);
        releases
    }

    /// Drops sessions that have been silent for longer than `idle`. Returns how
    /// many were removed and the releases for what they held.
    pub fn expire_idle(&mut self, now: Instant, idle: Duration) -> (usize, Vec<ClientMessage>) {
        let expired: Vec<SocketAddr> = self
            .sessions
            .iter()
            .filter(|(_, s)| now.saturating_duration_since(s.last_seen) > idle)
            .map(|(addr, _)| *addr)
            .collect();
        let mut releases = Vec::new();
        for addr in &expired {
            if let Some(mut session) = self.sessions.remove(addr) {
                releases.extend(session.held.take_release_messages());
            }
        }
        (expired.len(), releases)
    }

    /// Releases what sessions hold once they have been silent for `quiet`,
    /// without ending the session (the phone may simply be asleep).
    pub fn release_stale_holds(&mut self, now: Instant, quiet: Duration) -> Vec<ClientMessage> {
        let mut releases = Vec::new();
        for session in self.sessions.values_mut() {
            if !session.held.is_empty() && now.saturating_duration_since(session.last_seen) > quiet
            {
                releases.extend(session.held.take_release_messages());
            }
        }
        releases
    }

    /// Drops every session, returning the releases for everything they held.
    pub fn clear(&mut self) -> Vec<ClientMessage> {
        let releases = self.release_all();
        self.sessions.clear();
        releases
    }

    /// Releases everything every session holds (used at shutdown).
    pub fn release_all(&mut self) -> Vec<ClientMessage> {
        self.sessions
            .values_mut()
            .flat_map(|s| s.held.take_release_messages())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use telepad_crypto::{generate_keypair, NoiseInitiator, NoiseResponder};

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from(([192, 168, 1, 10], port))
    }

    /// A real transport, since `NoiseTransport` has no cheaper constructor.
    fn transport() -> NoiseTransport {
        let (server_priv, server_pub) = generate_keypair().unwrap();
        let (client_priv, _) = generate_keypair().unwrap();
        let (msg1, _hs) = NoiseInitiator::new(client_priv, server_pub)
            .start_handshake()
            .unwrap();
        NoiseResponder::new(server_priv)
            .respond_handshake(&msg1)
            .unwrap()
            .2
    }

    fn session(now: Instant) -> Session {
        Session::new(transport(), [9; 32], now)
    }

    fn press(keycode: u16, mods: u8) -> ClientMessage {
        ClientMessage::KeyPress { keycode, mods }
    }
    fn release(keycode: u16) -> ClientMessage {
        ClientMessage::KeyRelease { keycode, mods: 0 }
    }
    fn button(button: MouseButtonKind, pressed: bool) -> ClientMessage {
        ClientMessage::MouseButton { button, pressed }
    }

    // ── HeldInputs ──────────────────────────────────────────────────────

    #[test]
    fn tracks_buttons_and_keys() {
        let mut held = HeldInputs::default();
        assert!(held.is_empty());
        held.observe(&button(MouseButtonKind::Left, true));
        held.observe(&press(0x04, 0));
        assert!(!held.is_empty());
        held.observe(&button(MouseButtonKind::Left, false));
        held.observe(&release(0x04));
        assert!(held.is_empty());
    }

    #[test]
    fn ignores_messages_that_hold_nothing() {
        let mut held = HeldInputs::default();
        held.observe(&ClientMessage::MouseMove { dx: 1, dy: 1 });
        held.observe(&ClientMessage::Scroll { delta: 1 });
        held.observe(&ClientMessage::TextInput("hi".into()));
        held.observe(&ClientMessage::LockScreen);
        assert!(held.is_empty());
    }

    #[test]
    fn repeated_press_of_the_same_key_is_held_once() {
        let mut held = HeldInputs::default();
        held.observe(&press(0x04, 0));
        held.observe(&press(0x04, 0x01));
        assert_eq!(
            held.take_release_messages(),
            vec![ClientMessage::KeyRelease {
                keycode: 0x04,
                mods: 0x01
            }]
        );
    }

    #[test]
    fn release_messages_undo_everything_and_clear_the_set() {
        let mut held = HeldInputs::default();
        held.observe(&press(0xE1, 0));
        held.observe(&press(0x04, 0x01));
        held.observe(&button(MouseButtonKind::Right, true));
        held.observe(&button(MouseButtonKind::Middle, true));
        let releases = held.take_release_messages();
        assert_eq!(
            releases,
            vec![
                ClientMessage::KeyRelease {
                    keycode: 0x04,
                    mods: 0x01
                }, // newest key first
                ClientMessage::KeyRelease {
                    keycode: 0xE1,
                    mods: 0
                },
                button(MouseButtonKind::Right, false),
                button(MouseButtonKind::Middle, false),
            ]
        );
        assert!(held.is_empty());
        assert!(
            held.take_release_messages().is_empty(),
            "nothing left to release"
        );
    }

    #[test]
    fn releasing_a_key_that_was_never_pressed_is_harmless() {
        let mut held = HeldInputs::default();
        held.observe(&release(0x04));
        assert!(held.is_empty());
    }

    // ── SessionTable ────────────────────────────────────────────────────

    #[test]
    fn insert_and_lookup() {
        let now = Instant::now();
        let mut table = SessionTable::new(4);
        assert!(table.is_empty());
        assert!(table.insert(addr(1), session(now)).is_empty());
        assert_eq!(table.len(), 1);
        assert!(table.contains(&addr(1)));
        assert!(table.get_mut(&addr(2)).is_none());
    }

    #[test]
    fn replacing_a_session_releases_what_the_old_one_held() {
        let now = Instant::now();
        let mut table = SessionTable::new(4);
        table.insert(addr(1), session(now));
        table
            .get_mut(&addr(1))
            .unwrap()
            .held
            .observe(&button(MouseButtonKind::Left, true));
        let releases = table.insert(addr(1), session(now));
        assert_eq!(releases, vec![button(MouseButtonKind::Left, false)]);
        assert_eq!(table.len(), 1);
        assert!(table.get(&addr(1)).unwrap().held.is_empty());
    }

    #[test]
    fn full_table_evicts_the_session_quiet_for_longest() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(2);
        table.insert(addr(1), session(t0));
        table.insert(addr(2), session(t0 + Duration::from_secs(5)));
        table
            .get_mut(&addr(1))
            .unwrap()
            .held
            .observe(&press(0x04, 0));
        // addr(1) is the oldest, so it goes, and its held key is released.
        let releases = table.insert(addr(3), session(t0 + Duration::from_secs(9)));
        assert_eq!(
            releases,
            vec![ClientMessage::KeyRelease {
                keycode: 0x04,
                mods: 0
            }]
        );
        assert_eq!(table.len(), 2);
        assert!(!table.contains(&addr(1)));
        assert!(table.contains(&addr(2)) && table.contains(&addr(3)));
    }

    #[test]
    fn table_never_exceeds_its_cap() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(3);
        for i in 0..50u16 {
            table.insert(addr(i), session(t0 + Duration::from_millis(u64::from(i))));
            assert!(table.len() <= 3);
        }
        assert_eq!(table.len(), 3);
    }

    #[test]
    fn idle_sessions_expire_and_release_their_inputs() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(8);
        table.insert(addr(1), session(t0));
        table.insert(addr(2), session(t0 + Duration::from_secs(50)));
        table
            .get_mut(&addr(1))
            .unwrap()
            .held
            .observe(&button(MouseButtonKind::Left, true));

        let (removed, releases) =
            table.expire_idle(t0 + Duration::from_secs(61), Duration::from_secs(60));
        assert_eq!(removed, 1);
        assert_eq!(releases, vec![button(MouseButtonKind::Left, false)]);
        assert!(!table.contains(&addr(1)));
        assert!(table.contains(&addr(2)), "recently active session survives");

        let (removed, releases) =
            table.expire_idle(t0 + Duration::from_secs(61), Duration::from_secs(60));
        assert_eq!((removed, releases.len()), (0, 0));
    }

    #[test]
    fn expiry_is_exclusive_at_the_boundary() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(8);
        table.insert(addr(1), session(t0));
        let (removed, _) = table.expire_idle(t0 + Duration::from_secs(60), Duration::from_secs(60));
        assert_eq!(removed, 0, "exactly the timeout is still alive");
    }

    #[test]
    fn stale_holds_are_released_but_the_session_stays() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(8);
        table.insert(addr(1), session(t0));
        table.insert(addr(2), session(t0));
        table
            .get_mut(&addr(1))
            .unwrap()
            .held
            .observe(&press(0x04, 0));
        table
            .get_mut(&addr(2))
            .unwrap()
            .held
            .observe(&press(0x05, 0));
        // Session 2 was heard from again recently; session 1 has gone quiet.
        table.get_mut(&addr(2)).unwrap().last_seen = t0 + Duration::from_secs(9);

        let releases =
            table.release_stale_holds(t0 + Duration::from_secs(11), Duration::from_secs(10));
        assert_eq!(
            releases,
            vec![ClientMessage::KeyRelease {
                keycode: 0x04,
                mods: 0
            }]
        );
        assert!(table.contains(&addr(1)), "the session itself is kept");
        assert!(table.get(&addr(1)).unwrap().held.is_empty());
        assert!(!table.get(&addr(2)).unwrap().held.is_empty());
    }

    #[test]
    fn clear_drops_all_sessions_and_releases_their_inputs() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(8);
        table.insert(addr(1), session(t0));
        table.insert(addr(2), session(t0));
        table
            .get_mut(&addr(2))
            .unwrap()
            .held
            .observe(&button(MouseButtonKind::Left, true));
        assert_eq!(table.clear(), vec![button(MouseButtonKind::Left, false)]);
        assert!(table.is_empty());
    }

    #[test]
    fn release_all_releases_held_inputs_but_keeps_sessions() {
        let t0 = Instant::now();
        let mut table = SessionTable::new(8);
        table.insert(addr(1), session(t0));
        table.insert(addr(2), session(t0));
        table
            .get_mut(&addr(1))
            .unwrap()
            .held
            .observe(&press(0x04, 0));
        table
            .get_mut(&addr(2))
            .unwrap()
            .held
            .observe(&button(MouseButtonKind::Left, true));
        let mut releases = table.release_all();
        releases.sort_by_key(|m| format!("{m:?}"));
        assert_eq!(releases.len(), 2);
        assert!(table.release_all().is_empty());
        assert_eq!(table.len(), 2, "release_all does not drop sessions");
    }
}
