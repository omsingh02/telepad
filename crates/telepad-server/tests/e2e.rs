//! End-to-end tests: the real server on loopback UDP sockets, driven by a real
//! Noise client, with a recording backend standing in for the operating system.
//!
//! They run unchanged on Windows, Linux and macOS, so the protocol and security
//! behaviour is verified on every platform CI covers.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use telepad_crypto::{generate_keypair, NoiseInitiator, NoiseTransport};
use telepad_platform::clipboard::Clipboard;
use telepad_platform::{InputCall, PlatformError, RecordingBackend};
use telepad_protocol::*;
use telepad_server::clipboard::ClipboardFactory;
use telepad_server::server::{frame_transport, MAX_CLIPBOARD_REPLY_BYTES};
use telepad_server::{PairingMode, Server, ServerConfig, ServerHandle};
use tokio::net::UdpSocket;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

const WAIT: Duration = Duration::from_secs(3);
const QUIET: Duration = Duration::from_millis(250);

// ── Harness ──────────────────────────────────────────────────────────────

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

struct TestServer {
    addr: SocketAddr,
    public_key: [u8; 32],
    handle: ServerHandle,
    calls: Arc<Mutex<Vec<InputCall>>>,
    clipboard: Arc<Mutex<Option<String>>>,
    stop: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<()>>,
    dir: PathBuf,
    remove_dir_on_drop: bool,
}

fn temp_dir() -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telepad_e2e_{:016x}", rand::random::<u64>()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

impl TestServer {
    async fn start(configure: impl FnOnce(&mut ServerConfig)) -> Self {
        Self::start_in(temp_dir(), true, configure).await
    }

    async fn start_in(
        dir: PathBuf,
        remove_dir_on_drop: bool,
        configure: impl FnOnce(&mut ServerConfig),
    ) -> Self {
        let mut config = ServerConfig::new(dir.clone());
        config.bind = "127.0.0.1:0".parse().unwrap();
        config.hostname = "TEST-PC".into();
        config.pairing = PairingMode::Window(Duration::from_secs(60));
        config.discovery = false;
        config.maintenance_interval = Duration::from_millis(20);
        config.session_idle_timeout = Duration::from_secs(30);
        config.held_input_timeout = Duration::from_secs(30);
        configure(&mut config);

        let backend = RecordingBackend::new();
        let calls = backend.calls();
        let clipboard = Arc::new(Mutex::new(None));
        let factory: ClipboardFactory = {
            let cell = clipboard.clone();
            Box::new(move || Ok(Box::new(Memory(cell.clone())) as Box<dyn Clipboard>))
        };

        let server = Server::bind(config, Box::new(backend), factory)
            .await
            .expect("bind");
        let addr = server.local_addr().unwrap();
        let (stop, stopped) = oneshot::channel::<()>();
        let handle = server.handle();
        let public_key = server.public_key();
        let task = tokio::spawn(server.run(async {
            let _ = stopped.await;
        }));
        Self {
            addr,
            public_key,
            handle,
            calls,
            clipboard,
            stop: Some(stop),
            task: Some(task),
            dir,
            remove_dir_on_drop,
        }
    }

    /// Stops the server and waits for it to finish, as a shutdown would.
    async fn stop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(task) = self.task.take() {
            tokio::time::timeout(WAIT, task)
                .await
                .expect("server stops")
                .unwrap();
        }
    }

    fn calls(&self) -> Vec<InputCall> {
        self.calls.lock().unwrap().clone()
    }

    async fn wait_for_calls(&self, count: usize) -> Vec<InputCall> {
        let deadline = tokio::time::Instant::now() + WAIT;
        loop {
            let calls = self.calls();
            if calls.len() >= count || tokio::time::Instant::now() > deadline {
                return calls;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        if self.remove_dir_on_drop {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
enum Handshake {
    Established,
    Rejected,
    NoReply,
}

struct Client {
    socket: UdpSocket,
    private: [u8; 32],
    transport: Option<NoiseTransport>,
}

impl Client {
    async fn connect(server: &TestServer) -> Self {
        let (private, _public) = generate_keypair().unwrap();
        Self::with_keys(server, private).await
    }

    async fn with_keys(server: &TestServer, private: [u8; 32]) -> Self {
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(server.addr).await.unwrap();
        Self {
            socket,
            private,
            transport: None,
        }
    }

    async fn recv(&self) -> Option<Vec<u8>> {
        let mut buf = vec![0u8; 4096];
        let len = tokio::time::timeout(WAIT, self.socket.recv(&mut buf))
            .await
            .ok()?
            .ok()?;
        buf.truncate(len);
        Some(buf)
    }

    async fn recv_quiet(&self) -> Option<Vec<u8>> {
        let mut buf = vec![0u8; 4096];
        let len = tokio::time::timeout(QUIET, self.socket.recv(&mut buf))
            .await
            .ok()?
            .ok()?;
        buf.truncate(len);
        Some(buf)
    }

    async fn handshake(&mut self, server_public: [u8; 32]) -> Handshake {
        let (msg1, state) = NoiseInitiator::new(self.private, server_public)
            .start_handshake()
            .unwrap();
        let mut packet = vec![WIRE_HANDSHAKE_INIT];
        packet.extend_from_slice(&msg1);
        self.socket.send(&packet).await.unwrap();

        match self.recv_quiet_or_wait().await {
            Some(reply) if reply[0] == WIRE_HANDSHAKE_RESP => {
                self.transport =
                    Some(NoiseInitiator::finish_handshake(state, &reply[1..]).unwrap());
                Handshake::Established
            }
            Some(reply) if reply == [WIRE_PAIRING_REJECTED] => Handshake::Rejected,
            Some(other) => panic!("unexpected handshake reply {other:?}"),
            None => Handshake::NoReply,
        }
    }

    async fn recv_quiet_or_wait(&self) -> Option<Vec<u8>> {
        // Long enough for a slow CI machine, short enough to keep refusals quick.
        let mut buf = vec![0u8; 4096];
        let len = tokio::time::timeout(Duration::from_millis(800), self.socket.recv(&mut buf))
            .await
            .ok()?
            .ok()?;
        buf.truncate(len);
        Some(buf)
    }

    fn encrypt(&self, message: &ClientMessage) -> Vec<u8> {
        let mut plain = bytes::BytesMut::new();
        message.encode(&mut plain).unwrap();
        let (nonce, ciphertext) = self
            .transport
            .as_ref()
            .expect("handshake first")
            .encrypt(&plain)
            .unwrap();
        frame_transport(nonce, &ciphertext)
    }

    async fn send(&self, message: ClientMessage) {
        self.socket.send(&self.encrypt(&message)).await.unwrap();
    }

    async fn recv_message(&mut self) -> Option<ServerMessage> {
        let packet = self.recv().await?;
        assert_eq!(packet[0], WIRE_TRANSPORT);
        let nonce = u64::from_le_bytes(packet[1..9].try_into().unwrap());
        let plain = self.transport.as_mut()?.decrypt(nonce, &packet[9..]).ok()?;
        Some(ServerMessage::decode(&plain).unwrap())
    }
}

async fn connected(server: &TestServer) -> Client {
    let mut client = Client::connect(server).await;
    assert_eq!(
        client.handshake(server.public_key).await,
        Handshake::Established
    );
    client
}

// ── Discovery and pairing intro ──────────────────────────────────────────

#[tokio::test]
async fn a_discovery_probe_is_answered_with_the_hostname_in_both_forms() {
    let server = TestServer::start(|_| {}).await;
    let client = Client::connect(&server).await;
    let mut probe = vec![WIRE_DISCOVERY_PROBE];
    probe.extend_from_slice(TELEPAD_DISCOVERY_MAGIC);
    client.socket.send(&probe).await.unwrap();

    let tagged = client.recv().await.expect("tagged reply");
    assert_eq!(tagged[0], WIRE_DISCOVERY_REPLY);
    assert_eq!(&tagged[1..], b"TELEPAD_PONG:TEST-PC");
    let raw = client.recv().await.expect("bare reply");
    assert_eq!(raw, b"TELEPAD_PONG:TEST-PC");
}

#[tokio::test]
async fn a_probe_with_the_wrong_magic_gets_no_answer() {
    let server = TestServer::start(|_| {}).await;
    let client = Client::connect(&server).await;
    let mut probe = vec![WIRE_DISCOVERY_PROBE];
    probe.extend_from_slice(b"NOTTELEP");
    client.socket.send(&probe).await.unwrap();
    assert!(client.recv_quiet().await.is_none());
}

#[tokio::test]
async fn the_pairing_intro_returns_the_servers_public_key() {
    let server = TestServer::start(|_| {}).await;
    let client = Client::connect(&server).await;
    client.socket.send(&[WIRE_PAIRING_INTRO_REQ]).await.unwrap();
    let reply = client.recv().await.expect("intro reply");
    assert_eq!(reply[0], WIRE_PAIRING_INTRO_RESP);
    assert_eq!(&reply[1..], &server.public_key);
}

// ── Control ──────────────────────────────────────────────────────────────

#[tokio::test]
async fn a_paired_phone_controls_the_pc() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;

    let messages = [
        ClientMessage::MouseMove { dx: 12, dy: -7 },
        ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: true,
        },
        ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: false,
        },
        ClientMessage::Scroll { delta: -2 },
        ClientMessage::KeyPress {
            keycode: 0x06,
            mods: modifiers::LCTRL,
        },
        ClientMessage::KeyRelease {
            keycode: 0x06,
            mods: modifiers::LCTRL,
        },
        ClientMessage::TextInput("héllo 🚀".into()),
        ClientMessage::MediaCmd(MediaAction::PlayPause),
        ClientMessage::VolumeCmd(VolumeDirection::Down),
        ClientMessage::LaunchAction(SystemAction::TaskView),
        ClientMessage::LockScreen,
    ];
    for message in &messages {
        client.send(message.clone()).await;
    }

    let expected = vec![
        InputCall::MouseMove(12, -7),
        InputCall::MouseButton(MouseButtonKind::Left, true),
        InputCall::MouseButton(MouseButtonKind::Left, false),
        InputCall::Scroll(-2),
        InputCall::Key {
            usage: 0x06,
            mods: modifiers::LCTRL,
            pressed: true,
        },
        InputCall::Key {
            usage: 0x06,
            mods: modifiers::LCTRL,
            pressed: false,
        },
        InputCall::Text("héllo 🚀".into()),
        InputCall::Media(MediaAction::PlayPause),
        InputCall::Volume(VolumeDirection::Down),
        InputCall::Launch(SystemAction::TaskView),
        InputCall::LockScreen,
    ];
    assert_eq!(
        server.wait_for_calls(expected.len()).await,
        expected,
        "applied in order"
    );
}

#[tokio::test]
async fn many_rapid_events_arrive_complete_and_in_order() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    for i in 0..300i16 {
        client.send(ClientMessage::MouseMove { dx: i, dy: 1 }).await;
        // A phone's radio paces its packets; give the server task a turn every
        // so often, as it would get between real packets. (Without this, all 300
        // datagrams land in the socket buffer before the server runs at all.)
        if i % 20 == 19 {
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    }
    let calls = server.wait_for_calls(300).await;
    assert_eq!(calls.len(), 300);
    for (i, call) in calls.iter().enumerate() {
        assert_eq!(*call, InputCall::MouseMove(i as i16, 1));
    }
}

// ── Pairing policy ───────────────────────────────────────────────────────

#[tokio::test]
async fn an_unpaired_phone_is_refused_when_pairing_is_closed() {
    let server = TestServer::start(|c| c.pairing = PairingMode::Closed).await;
    let mut client = Client::connect(&server).await;
    assert_eq!(
        client.handshake(server.public_key).await,
        Handshake::Rejected
    );

    assert!(server.calls().is_empty());
    assert_eq!(server.handle.status().await.sessions, 0);
    assert_eq!(server.handle.status().await.paired_devices, 0);
}

#[tokio::test]
async fn packets_from_a_refused_phone_do_nothing() {
    let server = TestServer::start(|c| c.pairing = PairingMode::Closed).await;
    // Build a transport the phone *could* have had if the server had agreed.
    let (private, _public) = generate_keypair().unwrap();
    let accepted_elsewhere = {
        let other = TestServer::start(|_| {}).await;
        let mut c = Client::with_keys(&other, private).await;
        assert_eq!(c.handshake(other.public_key).await, Handshake::Established);
        c
    };
    // Aim its packets at the locked-down server instead.
    accepted_elsewhere
        .socket
        .connect(server.addr)
        .await
        .unwrap();
    accepted_elsewhere.send(ClientMessage::LockScreen).await;
    accepted_elsewhere
        .send(ClientMessage::MouseMove { dx: 1, dy: 1 })
        .await;
    tokio::time::sleep(QUIET).await;
    assert!(server.calls().is_empty());
}

#[tokio::test]
async fn a_new_phone_pairs_inside_the_window_which_closes_behind_it() {
    let server = TestServer::start(|_| {}).await;
    assert!(server.handle.status().await.pairing_window.is_some());
    let (private, _public) = generate_keypair().unwrap();
    let mut first = Client::with_keys(&server, private).await;
    assert_eq!(
        first.handshake(server.public_key).await,
        Handshake::Established
    );
    assert_eq!(server.handle.status().await.paired_devices, 1);
    // Nobody closed it: pairing one phone did.
    assert_eq!(server.handle.status().await.pairing_window, None);

    // The same phone reconnects (from a new socket, as the app does) ...
    let mut again = Client::with_keys(&server, private).await;
    assert_eq!(
        again.handshake(server.public_key).await,
        Handshake::Established
    );
    // ... but a different, unknown phone is turned away.
    let mut stranger = Client::connect(&server).await;
    assert_eq!(
        stranger.handshake(server.public_key).await,
        Handshake::Rejected
    );
}

#[tokio::test]
async fn the_pairing_window_expires_on_its_own() {
    let server =
        TestServer::start(|c| c.pairing = PairingMode::Window(Duration::from_millis(300))).await;
    let mut early = Client::connect(&server).await;
    assert_eq!(
        early.handshake(server.public_key).await,
        Handshake::Established
    );
    tokio::time::sleep(Duration::from_millis(450)).await;
    let mut late = Client::connect(&server).await;
    assert_eq!(late.handshake(server.public_key).await, Handshake::Rejected);
    assert_eq!(server.handle.status().await.pairing_window, None);
}

#[tokio::test]
async fn the_owner_can_reopen_pairing_while_the_server_runs() {
    let server = TestServer::start(|c| c.pairing = PairingMode::Closed).await;
    let mut before = Client::connect(&server).await;
    assert_eq!(
        before.handshake(server.public_key).await,
        Handshake::Rejected
    );

    server.handle.open_pairing(Duration::from_secs(30)).await;
    let mut during = Client::connect(&server).await;
    assert_eq!(
        during.handshake(server.public_key).await,
        Handshake::Established
    );
}

#[tokio::test]
async fn first_run_is_open_then_a_restart_locks_new_phones_out() {
    let dir = temp_dir();
    let (private, _public) = generate_keypair().unwrap();

    let mut first_run = TestServer::start_in(dir.clone(), false, |c| {
        c.pairing = PairingMode::WhenUnpaired(Duration::from_secs(60));
    })
    .await;
    let server_key = first_run.public_key;
    let mut owner = Client::with_keys(&first_run, private).await;
    assert_eq!(owner.handshake(server_key).await, Handshake::Established);
    first_run.stop().await;

    // Same directory: same identity, same paired list, but no longer open.
    let mut second_run = TestServer::start_in(dir.clone(), true, |c| {
        c.pairing = PairingMode::WhenUnpaired(Duration::from_secs(60));
    })
    .await;
    assert_eq!(
        second_run.public_key, server_key,
        "the identity persists across restarts"
    );
    let mut owner_again = Client::with_keys(&second_run, private).await;
    assert_eq!(
        owner_again.handshake(server_key).await,
        Handshake::Established
    );
    let mut stranger = Client::connect(&second_run).await;
    assert_eq!(stranger.handshake(server_key).await, Handshake::Rejected);
    second_run.stop().await;
}

#[tokio::test]
async fn accept_any_mode_admits_everyone_like_earlier_versions() {
    let server = TestServer::start(|c| c.pairing = PairingMode::AcceptAny).await;
    for _ in 0..3 {
        let mut client = Client::connect(&server).await;
        assert_eq!(
            client.handshake(server.public_key).await,
            Handshake::Established
        );
    }
}

#[tokio::test]
async fn forgetting_all_phones_disconnects_them_and_locks_them_out() {
    let server = TestServer::start(|_| {}).await;
    let (private, _public) = generate_keypair().unwrap();
    let mut phone = Client::with_keys(&server, private).await;
    assert_eq!(
        phone.handshake(server.public_key).await,
        Handshake::Established
    );
    phone
        .send(ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: true,
        })
        .await;
    server.wait_for_calls(1).await;

    assert_eq!(server.handle.forget_all().await.unwrap(), 1);
    assert_eq!(server.handle.status().await.sessions, 0);

    // The held button is released on the way out, and the old session is dead.
    let calls = server.wait_for_calls(2).await;
    assert_eq!(
        calls[1],
        InputCall::MouseButton(MouseButtonKind::Left, false)
    );
    phone.send(ClientMessage::MouseMove { dx: 1, dy: 1 }).await;
    tokio::time::sleep(QUIET).await;
    assert_eq!(server.calls().len(), 2);

    let mut again = Client::with_keys(&server, private).await;
    assert_eq!(
        again.handshake(server.public_key).await,
        Handshake::Rejected
    );
}

// ── Packet integrity ─────────────────────────────────────────────────────

#[tokio::test]
async fn a_replayed_packet_is_applied_only_once() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    let packet = client.encrypt(&ClientMessage::Scroll { delta: 1 });
    client.socket.send(&packet).await.unwrap();
    client.socket.send(&packet).await.unwrap();
    client.socket.send(&packet).await.unwrap();
    server.wait_for_calls(1).await;
    tokio::time::sleep(QUIET).await;
    assert_eq!(server.calls(), vec![InputCall::Scroll(1)]);
}

#[tokio::test]
async fn out_of_order_delivery_is_tolerated() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    let packets: Vec<Vec<u8>> = (1..=4)
        .map(|d| client.encrypt(&ClientMessage::Scroll { delta: d }))
        .collect();
    for index in [0usize, 2, 1, 3] {
        client.socket.send(&packets[index]).await.unwrap();
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let calls = server.wait_for_calls(4).await;
    assert_eq!(
        calls,
        vec![
            InputCall::Scroll(1),
            InputCall::Scroll(3),
            InputCall::Scroll(2),
            InputCall::Scroll(4)
        ]
    );
}

#[tokio::test]
async fn a_tampered_packet_is_dropped_and_does_not_poison_the_session() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    let mut tampered = client.encrypt(&ClientMessage::LockScreen);
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;
    client.socket.send(&tampered).await.unwrap();
    tokio::time::sleep(QUIET).await;
    assert!(
        server.calls().is_empty(),
        "a forged lock-screen must not run"
    );

    // The session still works afterwards.
    client.send(ClientMessage::MouseMove { dx: 1, dy: 2 }).await;
    assert_eq!(
        server.wait_for_calls(1).await,
        vec![InputCall::MouseMove(1, 2)]
    );
}

#[tokio::test]
async fn a_captured_packet_sent_from_another_address_is_ignored() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    let captured = client.encrypt(&ClientMessage::LockScreen);

    let attacker = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    attacker.send_to(&captured, server.addr).await.unwrap();
    tokio::time::sleep(QUIET).await;
    assert!(server.calls().is_empty());
}

#[tokio::test]
async fn transport_data_without_a_handshake_is_ignored() {
    let server = TestServer::start(|_| {}).await;
    let client = Client::connect(&server).await;
    let mut junk = vec![WIRE_TRANSPORT];
    junk.extend_from_slice(&1u64.to_le_bytes());
    junk.extend_from_slice(&[0x42; 40]);
    client.socket.send(&junk).await.unwrap();
    tokio::time::sleep(QUIET).await;
    assert!(server.calls().is_empty());
}

#[tokio::test]
async fn garbage_and_truncated_datagrams_do_not_disturb_the_server() {
    let server = TestServer::start(|_| {}).await;
    let noise = Client::connect(&server).await;
    for datagram in [
        vec![],
        vec![0x00],
        vec![WIRE_HANDSHAKE_INIT],
        vec![WIRE_HANDSHAKE_INIT; 40],
        vec![WIRE_TRANSPORT],
        vec![WIRE_TRANSPORT; 20],
        vec![WIRE_DISCOVERY_PROBE, 1, 2, 3],
        vec![0xFF; 1500],
        vec![0xC0; 97],
    ] {
        let _ = noise.socket.send(&datagram).await;
    }
    tokio::time::sleep(QUIET).await;

    // A genuine phone still connects and works.
    let phone = connected(&server).await;
    phone.send(ClientMessage::Scroll { delta: 5 }).await;
    assert_eq!(server.wait_for_calls(1).await, vec![InputCall::Scroll(5)]);
}

// ── Sessions ─────────────────────────────────────────────────────────────

#[tokio::test]
async fn keys_and_buttons_held_by_a_phone_that_goes_quiet_are_released() {
    let server = TestServer::start(|c| c.held_input_timeout = Duration::from_millis(200)).await;
    let client = connected(&server).await;
    client
        .send(ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: true,
        })
        .await;
    client
        .send(ClientMessage::KeyPress {
            keycode: 0x04,
            mods: modifiers::LSHIFT,
        })
        .await;
    assert_eq!(server.wait_for_calls(2).await.len(), 2);

    // The phone vanishes without releasing anything.
    let calls = server.wait_for_calls(4).await;
    assert_eq!(calls.len(), 4, "{calls:?}");
    assert!(calls.contains(&InputCall::MouseButton(MouseButtonKind::Left, false)));
    assert!(calls.contains(&InputCall::Key {
        usage: 0x04,
        mods: modifiers::LSHIFT,
        pressed: false
    }));

    // Released exactly once, and the session is still usable.
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(server.calls().len(), 4);
    client.send(ClientMessage::Scroll { delta: 1 }).await;
    assert_eq!(
        server.wait_for_calls(5).await.last(),
        Some(&InputCall::Scroll(1))
    );
}

#[tokio::test]
async fn an_active_phone_keeps_what_it_is_holding() {
    let server = TestServer::start(|c| c.held_input_timeout = Duration::from_millis(250)).await;
    let client = connected(&server).await;
    client
        .send(ClientMessage::MouseButton {
            button: MouseButtonKind::Left,
            pressed: true,
        })
        .await;
    // Heartbeat-style traffic (the app polls now-playing every 2 s) keeps the hold alive.
    for _ in 0..8 {
        tokio::time::sleep(Duration::from_millis(80)).await;
        client.send(ClientMessage::NowPlayingQuery).await;
    }
    assert_eq!(
        server.calls(),
        vec![InputCall::MouseButton(MouseButtonKind::Left, true)],
        "a drag in progress must not be cut short"
    );
}

#[tokio::test]
async fn idle_sessions_expire_and_their_packets_stop_working() {
    let server = TestServer::start(|c| c.session_idle_timeout = Duration::from_millis(200)).await;
    let client = connected(&server).await;
    assert_eq!(server.handle.status().await.sessions, 1);
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(server.handle.status().await.sessions, 0);
    client.send(ClientMessage::MouseMove { dx: 1, dy: 1 }).await;
    tokio::time::sleep(QUIET).await;
    assert!(server.calls().is_empty());
}

#[tokio::test]
async fn reconnecting_from_the_same_address_replaces_the_session() {
    let server = TestServer::start(|_| {}).await;
    let (private, _public) = generate_keypair().unwrap();
    let mut client = Client::with_keys(&server, private).await;
    assert_eq!(
        client.handshake(server.public_key).await,
        Handshake::Established
    );
    client
        .send(ClientMessage::MouseButton {
            button: MouseButtonKind::Right,
            pressed: true,
        })
        .await;
    server.wait_for_calls(1).await;

    // Handshake again on the same socket (same address): the old session's held
    // button is released and the new session works.
    assert_eq!(
        client.handshake(server.public_key).await,
        Handshake::Established
    );
    let calls = server.wait_for_calls(2).await;
    assert_eq!(
        calls[1],
        InputCall::MouseButton(MouseButtonKind::Right, false)
    );
    assert_eq!(server.handle.status().await.sessions, 1);
    client.send(ClientMessage::Scroll { delta: 3 }).await;
    assert_eq!(
        server.wait_for_calls(3).await.last(),
        Some(&InputCall::Scroll(3))
    );
}

#[tokio::test]
async fn the_number_of_sessions_is_capped() {
    // Six different phones, which a pairing window (it closes after one) would not let in.
    let server = TestServer::start(|c| {
        c.max_sessions = 3;
        c.pairing = PairingMode::AcceptAny;
    })
    .await;
    let mut clients = Vec::new();
    for _ in 0..6 {
        clients.push(connected(&server).await);
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(server.handle.status().await.sessions, 3);
    // The most recent phone is among the survivors.
    let newest = clients.last().unwrap();
    newest.send(ClientMessage::Scroll { delta: 1 }).await;
    assert_eq!(server.wait_for_calls(1).await, vec![InputCall::Scroll(1)]);
}

#[tokio::test]
async fn shutdown_releases_everything_still_held() {
    let mut server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    client
        .send(ClientMessage::KeyPress {
            keycode: 0xE1,
            mods: 0,
        })
        .await;
    client
        .send(ClientMessage::MouseButton {
            button: MouseButtonKind::Middle,
            pressed: true,
        })
        .await;
    server.wait_for_calls(2).await;

    server.stop().await;
    let calls = server.calls();
    assert!(
        calls.contains(&InputCall::Key {
            usage: 0xE1,
            mods: 0,
            pressed: false
        }),
        "{calls:?}"
    );
    assert!(
        calls.contains(&InputCall::MouseButton(MouseButtonKind::Middle, false)),
        "{calls:?}"
    );
}

// ── Clipboard and now-playing ────────────────────────────────────────────

#[tokio::test]
async fn the_clipboard_can_be_set_and_read_from_the_phone() {
    let server = TestServer::start(|_| {}).await;
    let mut client = connected(&server).await;

    client
        .send(ClientMessage::ClipboardSet("héllo 📋".into()))
        .await;
    let deadline = tokio::time::Instant::now() + WAIT;
    while server.clipboard.lock().unwrap().is_none() && tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(
        server.clipboard.lock().unwrap().as_deref(),
        Some("héllo 📋")
    );

    client.send(ClientMessage::ClipboardGet).await;
    assert_eq!(
        client.recv_message().await,
        Some(ServerMessage::ClipboardData("héllo 📋".into()))
    );
}

#[tokio::test]
async fn asking_for_an_empty_clipboard_gets_no_reply() {
    let server = TestServer::start(|_| {}).await;
    let client = connected(&server).await;
    client.send(ClientMessage::ClipboardGet).await;
    assert!(client.recv_quiet().await.is_none());
}

#[tokio::test]
async fn a_long_clipboard_is_clipped_to_fit_in_one_datagram() {
    let server = TestServer::start(|_| {}).await;
    let mut client = connected(&server).await;
    // 3-byte characters, so a naive byte cut would split one.
    let long = "한".repeat(2000);
    *server.clipboard.lock().unwrap() = Some(long.clone());

    client.send(ClientMessage::ClipboardGet).await;
    let Some(ServerMessage::ClipboardData(text)) = client.recv_message().await else {
        panic!("expected clipboard data");
    };
    assert!(!text.is_empty() && text.len() <= MAX_CLIPBOARD_REPLY_BYTES);
    assert!(
        long.starts_with(&text),
        "the clipped text is a clean prefix"
    );
}

#[tokio::test]
async fn now_playing_queries_are_answered() {
    let server = TestServer::start(|_| {}).await;
    let mut client = connected(&server).await;
    client.send(ClientMessage::NowPlayingQuery).await;
    assert_eq!(
        client.recv_message().await,
        Some(ServerMessage::NowPlaying(NowPlayingState::default()))
    );
}

#[tokio::test]
async fn the_server_tells_the_phone_which_os_it_runs_on() {
    let server = TestServer::start(|_| {}).await;
    let mut client = connected(&server).await;
    client.send(ClientMessage::HostInfoQuery).await;
    let Some(ServerMessage::HostInfo(info)) = client.recv_message().await else {
        panic!("expected host info");
    };
    assert_eq!(info.os, HostOs::current());
    assert_ne!(
        info.os,
        HostOs::Unknown,
        "tests only run on the three supported systems"
    );
    assert!(info.supports(capabilities::CLIPBOARD));
    assert!(!info.supports(capabilities::NOW_PLAYING));
    let (major, ..) = info.version;
    assert_eq!(major, 2);
}

#[tokio::test]
async fn host_info_needs_a_paired_session_like_everything_else() {
    let server = TestServer::start(|c| c.pairing = PairingMode::Closed).await;
    let stranger = Client::connect(&server).await;
    // A raw, unauthenticated datagram that merely looks like a query gets nothing.
    stranger
        .socket
        .send(&[WIRE_TRANSPORT, 0, 0, 0, 0, 0, 0, 0, 0, MSG_TYPE_HOST_INFO_Q])
        .await
        .unwrap();
    assert!(stranger.recv_quiet().await.is_none());
}

#[tokio::test]
async fn replies_use_fresh_nonces_so_the_phone_can_decrypt_every_one() {
    let server = TestServer::start(|_| {}).await;
    let mut client = connected(&server).await;
    for _ in 0..20 {
        client.send(ClientMessage::NowPlayingQuery).await;
    }
    for _ in 0..20 {
        assert!(client.recv_message().await.is_some());
    }
}
