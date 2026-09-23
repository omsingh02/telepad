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

    // Hostname never changes after init — keep it outside the mutex so
    // discovery probes don't need to lock.
    let hostname = Arc::new(hostname);

    let state = Arc::new(Mutex::new(ServerState {
        static_priv,
        static_pub,
        pairing_store,
        clients: HashMap::new(),
    }));

    let bind_addr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, args.port);
    let socket = Arc::new(UdpSocket::bind(bind_addr).await?);
    let _ = socket.set_broadcast(true);
    info!("UDP socket listening on {}", bind_addr);

    let local_nets = get_local_ipv4_and_broadcasts();
    info!("Detected local IPv4 networks: {:?}", local_nets);

    // Spawn multicast listener task
    let mcast_socket = Arc::clone(&socket);
    let mcast_nets = local_nets.clone();
    tokio::spawn(async move {
        let mcast_ip: Ipv4Addr = TELEPAD_MULTICAST_GROUP.parse().unwrap();
        if let Err(e) = mcast_socket.join_multicast_v4(mcast_ip, Ipv4Addr::UNSPECIFIED) {
            warn!("Could not join multicast group {} on 0.0.0.0: {}", TELEPAD_MULTICAST_GROUP, e);
        } else {
            info!("Joined multicast group {} on 0.0.0.0", TELEPAD_MULTICAST_GROUP);
        }
        for (ip, _) in mcast_nets {
            if let Err(e) = mcast_socket.join_multicast_v4(mcast_ip, ip) {
                warn!("Could not join multicast group {} on {}: {}", TELEPAD_MULTICAST_GROUP, ip, e);
            } else {
                info!("Joined multicast group {} on {}", TELEPAD_MULTICAST_GROUP, ip);
            }
        }
    });

    // Spawn periodic discovery announcement task (RustDesk-style multi-socket broadcast)
    let announce_hostname = hostname.clone();
    let announce_port = args.port;
    let bcast_nets = local_nets.clone();
    tokio::spawn(async move {
        let mcast_addr: SocketAddr = format!("{}:{}", TELEPAD_MULTICAST_GROUP, announce_port)
            .parse()
            .unwrap();
        let bcast_addr: SocketAddr = format!("255.255.255.255:{}", announce_port)
            .parse()
            .unwrap();
        let reply_str = format!("TELEPAD_PONG:{}", announce_hostname);
        let mut tagged_pong = vec![WIRE_DISCOVERY_REPLY];
        tagged_pong.extend_from_slice(reply_str.as_bytes());
        let raw_pong = reply_str.as_bytes().to_vec();

        let local_ips: Vec<Ipv4Addr> = bcast_nets.iter().map(|(ip, _)| *ip).collect();
        let bcast_sockets = create_broadcast_sockets(&local_ips);
        info!("Created {} broadcast sockets for announcement", bcast_sockets.len());

        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3));
        loop {
            interval.tick().await;
            for s in &bcast_sockets {
                // Global broadcast
                let _ = s.send_to(&tagged_pong, bcast_addr);
                let _ = s.send_to(&raw_pong, bcast_addr);

                // Multicast
                let _ = s.send_to(&tagged_pong, mcast_addr);
                let _ = s.send_to(&raw_pong, mcast_addr);

                // Subnet directed broadcasts (e.g. 10.95.31.255 on hotspot)
                for (_, bcast) in &bcast_nets {
                    let addr = SocketAddr::V4(SocketAddrV4::new(*bcast, announce_port));
                    let _ = s.send_to(&tagged_pong, addr);
                    let _ = s.send_to(&raw_pong, addr);
                }
            }
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
                if len >= 1 + 8
                    && (&packet[1..9] == TELEPAD_DISCOVERY_MAGIC
                        || &packet[1..9] == TELEPAD_DISCOVERY_MAGIC_ALT)
                {
                    debug!("Received discovery probe from {}", src);
                    // No mutex needed — hostname is immutable Arc<String>.
                    let reply_str = format!("TELEPAD_PONG:{}", hostname);
                    let mut out = vec![WIRE_DISCOVERY_REPLY];
                    out.extend_from_slice(reply_str.as_bytes());
                    let _ = socket.send_to(&out, src).await;
                    let _ = socket.send_to(reply_str.as_bytes(), src).await;
                }
            }
            WIRE_DISCOVERY_REPLY => {
                // Silently ignore incoming discovery replies / broadcast echoes
            }
            b'T' if packet.starts_with(b"TELEPAD_PONG:") => {
                // Silently ignore raw PONG broadcast echoes
            }
            WIRE_PAIRING_INTRO_REQ => {
                info!("Received pairing intro request from {}", src);
                let state_guard = state.lock().await;
                let mut out = vec![WIRE_PAIRING_INTRO_RESP];
                out.extend_from_slice(&state_guard.static_pub);
                match socket.send_to(&out, src).await {
                    Ok(n) => info!("Sent pairing intro response ({} bytes) to {}", n, src),
                    Err(e) => error!("Failed to send pairing intro response to {}: {}", src, e),
                }
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
                if packet.len() < 25 {
                    debug!("Transport packet from {} too short: {} bytes", src, packet.len());
                    continue;
                }
                let nonce = u64::from_le_bytes(packet[1..9].try_into().unwrap());
                let ciphertext = &packet[9..];
                let decoded = {
                    let mut state_guard = state.lock().await;
                    if let Some(session) = state_guard.clients.get_mut(&src) {
                        session.last_seen = Instant::now();
                        match session.transport.decrypt(nonce, ciphertext) {
                            Ok(plaintext) => ClientMessage::decode(&plaintext).ok(),
                            Err(e) => {
                                debug!("Transport decrypt error from {} (nonce {}): {}", src, nonce, e);
                                None
                            }
                        }
                    } else {
                        debug!("Received transport packet from unauthenticated client {}", src);
                        None
                    }
                }; // lock dropped here

                if let Some(msg) = decoded {
                    // For messages that need to send replies (clipboard, now-playing),
                    // we re-acquire the lock briefly for encryption only.
                    handle_client_message(msg, &socket, src, &state).await;
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
    state: &Arc<Mutex<ServerState>>,
) {
    match msg {
        // ── Input events: no lock needed ──────────────────────────────
        ClientMessage::MouseMove { dx, dy } => {
            input::platform::send_mouse_move(dx, dy);
        }
        ClientMessage::MouseButton { button, pressed } => {
            input::platform::send_mouse_button(button, pressed);
        }
        ClientMessage::Scroll { delta } => {
            input::platform::send_scroll(delta);
        }
        ClientMessage::KeyPress { keycode, mods } => {
            input::platform::send_key(keycode, mods, true);
        }
        ClientMessage::KeyRelease { keycode, mods } => {
            input::platform::send_key(keycode, mods, false);
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

        // ── Reply messages: briefly re-acquire lock for encryption ────
        ClientMessage::ClipboardGet => {
            if let Ok(mut clipboard) = arboard::Clipboard::new() {
                if let Ok(text) = clipboard.get_text() {
                    let s_msg = ServerMessage::ClipboardData(text);
                    let mut p_buf = BytesMut::new();
                    if s_msg.encode(&mut p_buf).is_ok() {
                        let mut state_guard = state.lock().await;
                        if let Some(session) = state_guard.clients.get_mut(&src) {
                            if let Ok((nonce, encrypted)) = session.transport.encrypt(&p_buf) {
                                let mut out = Vec::with_capacity(1 + 8 + encrypted.len());
                                out.push(WIRE_TRANSPORT);
                                out.extend_from_slice(&nonce.to_le_bytes());
                                out.extend_from_slice(&encrypted);
                                drop(state_guard); // release lock before async send
                                let _ = socket.send_to(&out, src).await;
                            }
                        }
                    }
                }
            }
        }
        ClientMessage::NowPlayingQuery => {
            let s_msg = ServerMessage::NowPlaying(NowPlayingState::default());
            let mut p_buf = BytesMut::new();
            if s_msg.encode(&mut p_buf).is_ok() {
                let mut state_guard = state.lock().await;
                if let Some(session) = state_guard.clients.get_mut(&src) {
                    if let Ok((nonce, encrypted)) = session.transport.encrypt(&p_buf) {
                        let mut out = Vec::with_capacity(1 + 8 + encrypted.len());
                        out.push(WIRE_TRANSPORT);
                        out.extend_from_slice(&nonce.to_le_bytes());
                        out.extend_from_slice(&encrypted);
                        drop(state_guard); // release lock before async send
                        let _ = socket.send_to(&out, src).await;
                    }
                }
            }
        }
    }
}

/// Create broadcast sockets bound to each local IPv4 address + 0.0.0.0 (RustDesk approach).
/// Binding directly to each interface's local IP forces broadcast frames through that NIC,
/// preventing Windows from funneling broadcasts solely through virtual or loopback adapters.
fn create_broadcast_sockets(local_ips: &[Ipv4Addr]) -> Vec<std::net::UdpSocket> {
    let mut ips = local_ips.to_vec();
    ips.push(Ipv4Addr::UNSPECIFIED); // 0.0.0.0 for fallback

    let mut sockets = Vec::new();
    for ip in ips {
        if let Ok(s) = std::net::UdpSocket::bind(SocketAddr::from((ip, 0))) {
            if s.set_broadcast(true).is_ok() {
                let _ = s.set_nonblocking(true);
                sockets.push(s);
            }
        }
    }
    sockets
}

#[cfg(windows)]
fn get_local_ipv4_and_broadcasts() -> Vec<(Ipv4Addr, Ipv4Addr)> {
    use std::ptr::null_mut;
    use windows_sys::Win32::Foundation::ERROR_BUFFER_OVERFLOW;
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetAdaptersAddresses, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER,
        GAA_FLAG_SKIP_MULTICAST, IP_ADAPTER_ADDRESSES_LH,
    };
    use windows_sys::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN};

    let mut list = Vec::new();
    let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    let mut buf_len = 15000;
    let mut buf = vec![0u8; buf_len as usize];

    let mut ret = unsafe {
        GetAdaptersAddresses(
            AF_INET as u32,
            flags,
            null_mut(),
            buf.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH,
            &mut buf_len,
        )
    };

    if ret == ERROR_BUFFER_OVERFLOW {
        buf.resize(buf_len as usize, 0);
        ret = unsafe {
            GetAdaptersAddresses(
                AF_INET as u32,
                flags,
                null_mut(),
                buf.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH,
                &mut buf_len,
            )
        };
    }

    if ret == 0 {
        let mut curr = buf.as_ptr() as *const IP_ADAPTER_ADDRESSES_LH;
        while !curr.is_null() {
            unsafe {
                let adapter = &*curr;
                if adapter.OperStatus == 1 {
                    let mut unicast = adapter.FirstUnicastAddress;
                    while !unicast.is_null() {
                        let uni = &*unicast;
                        if !uni.Address.lpSockaddr.is_null() {
                            let sockaddr = &*(uni.Address.lpSockaddr as *const SOCKADDR_IN);
                            if sockaddr.sin_family == AF_INET {
                                let ip_bytes = sockaddr.sin_addr.S_un.S_addr.to_ne_bytes();
                                let ip = Ipv4Addr::from(ip_bytes);
                                if !ip.is_loopback() && !ip.is_link_local() {
                                    let prefix = uni.OnLinkPrefixLength.min(32);
                                    let mask_u32 = if prefix == 0 {
                                        0
                                    } else {
                                        !0u32 << (32 - prefix)
                                    };
                                    let ip_u32 = u32::from_be_bytes(ip_bytes);
                                    let bcast_u32 = ip_u32 | !mask_u32;
                                    let bcast = Ipv4Addr::from(bcast_u32);
                                    if !list.iter().any(|(i, _)| *i == ip) {
                                        list.push((ip, bcast));
                                    }
                                }
                            }
                        }
                        unicast = uni.Next;
                    }
                }
                curr = adapter.Next;
            }
        }
    }
    list
}

#[cfg(not(windows))]
fn get_local_ipv4_and_broadcasts() -> Vec<(Ipv4Addr, Ipv4Addr)> {
    vec![]
}
