mod input;

use bytes::BytesMut;
use clap::Parser;
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use telepad_crypto::{
    compute_fingerprint, IdentityStore, NoiseResponder, NoiseTransport, PairingStore,
};
use telepad_protocol::*;
use tokio::net::UdpSocket;
use tokio::sync::Mutex;
use tracing::{debug, error, info, warn};

#[derive(Parser, Debug)]
#[command(name = "telepad-server", version = "2.0.0", about = "Telepad Modern Desktop Server Daemon")]
struct Cli {
    #[arg(short, long, default_value_t = TELEPAD_PORT)]
    port: u16,

    #[arg(short, long)]
    verbose: bool,

    #[arg(long)]
    key_dir: Option<PathBuf>,
}

struct ClientSession {
    transport: NoiseTransport,
    #[allow(dead_code)]
    client_pubkey: [u8; 32],
    last_seen: Instant,
}

struct ServerState {
    static_priv: [u8; 32],
    static_pub: [u8; 32],
    hostname: String,
    pairing_store: PairingStore,
    clients: HashMap<SocketAddr, ClientSession>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Cli::parse();

    let env_filter = if args.verbose {
        "telepad_server=debug,telepad_crypto=debug,telepad_protocol=debug,info"
    } else {
        "telepad_server=info,info"
    };

    tracing_subscriber::fmt()
        .with_env_filter(env_filter)
        .init();

    let config_dir = args.key_dir.unwrap_or_else(|| {
        if let Ok(appdata) = std::env::var("APPDATA") {
            PathBuf::from(appdata).join("Telepad")
        } else if let Ok(home) = std::env::var("HOME") {
            PathBuf::from(home).join(".config").join("telepad")
        } else {
            PathBuf::from("./telepad-data")
        }
    });

    let _ = std::fs::create_dir_all(&config_dir);
    let key_path = config_dir.join("identity.key");
    let pairing_path = config_dir.join("trusted_clients.json");

    let (static_priv, static_pub) = IdentityStore::load_or_generate(&key_path)?;
    let pairing_store = PairingStore::load_or_default(pairing_path);
    let fingerprint = compute_fingerprint(&static_pub);

    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "Desktop-PC".to_string());

    println!("==================================================");
    println!(" Telepad Desktop Server v2.0.0 (Rust)");
    println!(" Port:        {}", args.port);
    println!(" Hostname:    {}", hostname);
    println!(" Fingerprint: {}", fingerprint);
    println!("==================================================");

    let state = Arc::new(Mutex::new(ServerState {
        static_priv,
        static_pub,
        hostname,
        pairing_store,
        clients: HashMap::new(),
    }));

    let bind_addr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, args.port);
    let socket = Arc::new(UdpSocket::bind(bind_addr).await?);
    info!("UDP socket listening on {}", bind_addr);

    // Spawn multicast listener task
    let mcast_socket = Arc::clone(&socket);
    tokio::spawn(async move {
        let mcast_ip: Ipv4Addr = TELEPAD_MULTICAST_GROUP.parse().unwrap();
        if let Err(e) = mcast_socket.join_multicast_v4(mcast_ip, Ipv4Addr::UNSPECIFIED) {
            warn!("Could not join multicast group {}: {}", TELEPAD_MULTICAST_GROUP, e);
        } else {
            info!("Joined multicast group {}", TELEPAD_MULTICAST_GROUP);
        }
    });

    let mut buf = vec![0u8; 4096];
    loop {
        let (len, src) = match socket.recv_from(&mut buf).await {
            Ok(res) => res,
            Err(e) => {
                error!("Socket receive error: {}", e);
                continue;
            }
        };

        if len == 0 {
            continue;
        }

        let packet = &buf[..len];
        let tag = packet[0];

        match tag {
            WIRE_DISCOVERY_PROBE => {
                if len >= 1 + TELEPAD_DISCOVERY_MAGIC.len()
                    && &packet[1..1 + TELEPAD_DISCOVERY_MAGIC.len()] == TELEPAD_DISCOVERY_MAGIC
                {
                    debug!("Received discovery probe from {}", src);
                    let state_guard = state.lock().await;
                    let reply_str = format!("TELEPAD_PONG:{}", state_guard.hostname);
                    let mut out = vec![WIRE_DISCOVERY_REPLY];
                    out.extend_from_slice(reply_str.as_bytes());
                    let _ = socket.send_to(&out, src).await;
                }
            }
            WIRE_PAIRING_INTRO_REQ => {
                debug!("Received pairing intro request from {}", src);
                let state_guard = state.lock().await;
                let mut out = vec![WIRE_PAIRING_INTRO_RESP];
                out.extend_from_slice(&state_guard.static_pub);
                let _ = socket.send_to(&out, src).await;
            }
            WIRE_HANDSHAKE_INIT => {
                debug!("Received handshake init from {}", src);
                let mut state_guard = state.lock().await;
                let responder = NoiseResponder::new(state_guard.static_priv);
                match responder.respond_handshake(&packet[1..]) {
                    Ok((resp_msg2, client_pubkey, transport)) => {
                        if !state_guard.pairing_store.is_trusted(&client_pubkey) {
                            let _ = state_guard.pairing_store.trust(&client_pubkey);
                            info!("Auto-trusted new client from {}", src);
                        }
                        state_guard.clients.insert(
                            src,
                            ClientSession {
                                transport,
                                client_pubkey,
                                last_seen: Instant::now(),
                            },
                        );

                        let mut out = vec![WIRE_HANDSHAKE_RESP];
                        out.extend_from_slice(&resp_msg2);
                        let _ = socket.send_to(&out, src).await;
                        info!("Handshake successful with {}", src);
                    }
                    Err(e) => {
                        warn!("Handshake from {} failed: {}", src, e);
                    }
                }
            }
            WIRE_TRANSPORT => {
                let mut state_guard = state.lock().await;
                if let Some(session) = state_guard.clients.get_mut(&src) {
                    session.last_seen = Instant::now();
                    match session.transport.decrypt(&packet[1..]) {
                        Ok(plaintext) => {
                            match ClientMessage::decode(&plaintext) {
                                Ok(msg) => {
                                    handle_client_message(msg, &socket, src, session).await;
                                }
                                Err(e) => {
                                    warn!("Error decoding client message from {}: {}", src, e);
                                }
                            }
                        }
                        Err(e) => {
                            debug!("Transport decrypt error from {}: {}", src, e);
                        }
                    }
                } else {
                    debug!("Received transport packet from unauthenticated client {}", src);
                }
            }
            _ => {
                debug!("Received unknown packet tag 0x{:02X} from {}", tag, src);
            }
        }
    }
}

async fn handle_client_message(
    msg: ClientMessage,
    socket: &UdpSocket,
    src: SocketAddr,
    session: &mut ClientSession,
) {
    match msg {
        ClientMessage::MouseMove { dx, dy } => {
            input::platform::send_mouse_move(dx, dy);
        }
        ClientMessage::MouseButton { button, pressed } => {
            input::platform::send_mouse_button(button, pressed);
        }
        ClientMessage::Scroll { delta } => {
            input::platform::send_scroll(delta);
        }
        ClientMessage::KeyPress { keycode, .. } => {
            input::platform::send_key(keycode, true);
        }
        ClientMessage::KeyRelease { keycode, .. } => {
            input::platform::send_key(keycode, false);
        }
        ClientMessage::TextInput(text) => {
            input::platform::send_text(&text);
        }
        ClientMessage::MediaCmd(action) => {
            input::platform::send_media_cmd(action);
        }
        ClientMessage::VolumeCmd(direction) => {
            input::platform::send_volume_cmd(direction);
        }
        ClientMessage::LockScreen => {
            input::platform::lock_workstation();
        }
        ClientMessage::LaunchAction(action) => {
            input::platform::send_launch_action(action);
        }
        ClientMessage::ClipboardSet(text) => {
            if let Ok(mut clipboard) = arboard::Clipboard::new() {
                let _ = clipboard.set_text(text);
            }
        }
        ClientMessage::ClipboardGet => {
            if let Ok(mut clipboard) = arboard::Clipboard::new() {
                if let Ok(text) = clipboard.get_text() {
                    let s_msg = ServerMessage::ClipboardData(text);
                    let mut p_buf = BytesMut::new();
                    s_msg.encode(&mut p_buf);
                    if let Ok(encrypted) = session.transport.encrypt(&p_buf) {
                        let mut out = vec![WIRE_TRANSPORT];
                        out.extend_from_slice(&encrypted);
                        let _ = socket.send_to(&out, src).await;
                    }
                }
            }
        }
        ClientMessage::NowPlayingQuery => {
            // Return empty / idle now-playing status
            let s_msg = ServerMessage::NowPlaying(NowPlayingState::default());
            let mut p_buf = BytesMut::new();
            s_msg.encode(&mut p_buf);
            if let Ok(encrypted) = session.transport.encrypt(&p_buf) {
                let mut out = vec![WIRE_TRANSPORT];
                out.extend_from_slice(&encrypted);
                let _ = socket.send_to(&out, src).await;
            }
        }
    }
}
