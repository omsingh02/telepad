//! LAN discovery: answering probes, and announcing the server so phones find it
//! without typing an address.

use std::collections::HashSet;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::time::Duration;
use telepad_platform::netif::{self, LocalNetwork};
use telepad_protocol::*;
use tokio::net::UdpSocket;
use tracing::{debug, info, warn};

const PONG_PREFIX: &str = "TELEPAD_PONG:";

/// The reply to a discovery probe, in both forms clients understand: tagged
/// with `WIRE_DISCOVERY_REPLY`, and the bare text older clients look for.
pub fn pong_packets(hostname: &str) -> (Vec<u8>, Vec<u8>) {
    let text = format!("{PONG_PREFIX}{hostname}");
    let mut tagged = Vec::with_capacity(1 + text.len());
    tagged.push(WIRE_DISCOVERY_REPLY);
    tagged.extend_from_slice(text.as_bytes());
    (tagged, text.into_bytes())
}

/// Whether `packet` is a discovery probe (tag plus either accepted 8-byte magic).
pub fn is_discovery_probe(packet: &[u8]) -> bool {
    match (packet.first(), packet.get(1..9)) {
        (Some(&WIRE_DISCOVERY_PROBE), Some(magic)) => {
            magic == TELEPAD_DISCOVERY_MAGIC || magic == TELEPAD_DISCOVERY_MAGIC_ALT
        }
        _ => false,
    }
}

/// Whether `packet` is our own announcement (or another server's) coming back
/// to us as broadcast/multicast loopback, which must be ignored.
pub fn is_pong_echo(packet: &[u8]) -> bool {
    packet.first() == Some(&WIRE_DISCOVERY_REPLY) || packet.starts_with(PONG_PREFIX.as_bytes())
}

/// Every address an announcement is sent to: the global broadcast, the
/// multicast group, and each local subnet's directed broadcast (which is what
/// actually reaches a phone on a hotspot or a router that drops 255.255.255.255).
pub fn announcement_targets(networks: &[LocalNetwork], port: u16) -> Vec<SocketAddr> {
    let multicast: Ipv4Addr = TELEPAD_MULTICAST_GROUP
        .parse()
        .expect("valid multicast constant");
    let mut targets = vec![
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::BROADCAST, port)),
        SocketAddr::V4(SocketAddrV4::new(multicast, port)),
    ];
    for broadcast in networks.iter().filter_map(|n| n.broadcast) {
        let target = SocketAddr::V4(SocketAddrV4::new(broadcast, port));
        if !targets.contains(&target) {
            targets.push(target);
        }
    }
    targets
}

/// One socket per local address, plus a wildcard fallback. Binding to each
/// interface's own address forces broadcast frames out of that NIC; a single
/// wildcard socket would send them only through whichever adapter the OS picks
/// (often a virtual one).
fn broadcast_sockets(networks: &[LocalNetwork]) -> Vec<std::net::UdpSocket> {
    let mut addresses: Vec<Ipv4Addr> = networks.iter().map(|n| n.ip).collect();
    addresses.push(Ipv4Addr::UNSPECIFIED);

    addresses
        .into_iter()
        .filter_map(|ip| {
            let socket = std::net::UdpSocket::bind(SocketAddr::from((ip, 0))).ok()?;
            socket.set_broadcast(true).ok()?;
            socket.set_nonblocking(true).ok()?;
            Some(socket)
        })
        .collect()
}

/// Joins the discovery multicast group on every interface we have not joined
/// yet, so probes sent to the group reach us whichever network the phone is on.
///
/// The wildcard interface (`0.0.0.0`, "let the OS choose") is used only when no
/// interface could be enumerated: otherwise it would resolve to one we join
/// explicitly anyway, and the duplicate join is refused with "address in use".
/// Virtual interfaces (VPNs, tunnels) often refuse multicast; that is normal
/// and only worth a warning when nothing at all could be joined.
fn join_multicast(socket: &UdpSocket, networks: &[LocalNetwork], joined: &mut HashSet<Ipv4Addr>) {
    let group: Ipv4Addr = TELEPAD_MULTICAST_GROUP
        .parse()
        .expect("valid multicast constant");
    let interfaces: Vec<Ipv4Addr> = if networks.is_empty() {
        vec![Ipv4Addr::UNSPECIFIED]
    } else {
        networks.iter().map(|n| n.ip).collect()
    };

    let mut attempted = 0;
    let mut succeeded = 0;
    for interface in interfaces {
        if joined.contains(&interface) {
            continue;
        }
        attempted += 1;
        match socket.join_multicast_v4(group, interface) {
            Ok(()) => {
                joined.insert(interface);
                succeeded += 1;
                debug!("joined multicast group {group} on {interface}");
            }
            // Already a member through another route: exactly what we wanted.
            Err(err) if err.kind() == std::io::ErrorKind::AddrInUse => {
                joined.insert(interface);
                succeeded += 1;
            }
            Err(err) => debug!("could not join multicast group {group} on {interface}: {err}"),
        }
    }
    if attempted > 0 && succeeded == 0 {
        warn!(
            "could not join the discovery multicast group on any network; phones will find \
             this PC only through broadcast announcements or by entering its address"
        );
    }
}

/// Keeps discovery working for the life of the server: joins the multicast
/// group, and every `interval` announces the server on all local networks.
///
/// Interfaces are re-enumerated each round, so moving between Wi-Fi networks,
/// plugging in Ethernet or enabling a hotspot is picked up without a restart.
pub async fn run(socket: Arc<UdpSocket>, hostname: String, port: u16, interval: Duration) {
    let (tagged, raw) = pong_packets(&hostname);
    let mut ticker = tokio::time::interval(interval);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    let mut known_ips: Vec<Ipv4Addr> = Vec::new();
    let mut joined: HashSet<Ipv4Addr> = HashSet::new();
    let mut sockets: Vec<std::net::UdpSocket> = Vec::new();
    let mut targets: Vec<SocketAddr> = announcement_targets(&[], port);

    loop {
        ticker.tick().await;

        let networks = netif::local_ipv4_networks();
        let ips: Vec<Ipv4Addr> = networks.iter().map(|n| n.ip).collect();
        if ips != known_ips || sockets.is_empty() {
            if !networks.is_empty() {
                info!(
                    "Announcing on {}",
                    networks
                        .iter()
                        .map(|n| format!("{}/{}", n.ip, n.prefix_len))
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            } else {
                warn!("no usable network interface found; discovery will rely on probes only");
            }
            join_multicast(&socket, &networks, &mut joined);
            sockets = broadcast_sockets(&networks);
            targets = announcement_targets(&networks, port);
            known_ips = ips;
        }

        for socket in &sockets {
            for target in &targets {
                // Best effort: an interface may be going away, or have no route.
                let _ = socket.send_to(&tagged, target);
                let _ = socket.send_to(&raw, target);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn network(ip: [u8; 4], prefix: u8, broadcast: Option<[u8; 4]>) -> LocalNetwork {
        LocalNetwork {
            ip: ip.into(),
            prefix_len: prefix,
            broadcast: broadcast.map(Into::into),
        }
    }

    #[test]
    fn pong_has_a_tagged_and_a_bare_form() {
        let (tagged, raw) = pong_packets("DESKTOP-PC");
        assert_eq!(tagged[0], WIRE_DISCOVERY_REPLY);
        assert_eq!(&tagged[1..], b"TELEPAD_PONG:DESKTOP-PC");
        assert_eq!(raw, b"TELEPAD_PONG:DESKTOP-PC");
    }

    #[test]
    fn hostnames_with_unicode_survive() {
        let (tagged, _) = pong_packets("Büro-Mac 🖥");
        assert_eq!(
            std::str::from_utf8(&tagged[1..]).unwrap(),
            "TELEPAD_PONG:Büro-Mac 🖥"
        );
    }

    #[test]
    fn probes_are_recognised_with_either_magic() {
        let mut probe = vec![WIRE_DISCOVERY_PROBE];
        probe.extend_from_slice(TELEPAD_DISCOVERY_MAGIC);
        assert!(is_discovery_probe(&probe));

        let mut alt = vec![WIRE_DISCOVERY_PROBE];
        alt.extend_from_slice(b"TELEPAD!");
        assert!(is_discovery_probe(&alt));

        // Extra trailing bytes are tolerated.
        alt.extend_from_slice(b"padding");
        assert!(is_discovery_probe(&alt));
    }

    #[test]
    fn malformed_probes_are_rejected() {
        assert!(!is_discovery_probe(&[]));
        assert!(!is_discovery_probe(&[WIRE_DISCOVERY_PROBE]));
        let mut short = vec![WIRE_DISCOVERY_PROBE];
        short.extend_from_slice(&TELEPAD_DISCOVERY_MAGIC[..7]);
        assert!(!is_discovery_probe(&short));
        let mut wrong_magic = vec![WIRE_DISCOVERY_PROBE];
        wrong_magic.extend_from_slice(b"NOTTELEP");
        assert!(!is_discovery_probe(&wrong_magic));
        let mut wrong_tag = vec![WIRE_TRANSPORT];
        wrong_tag.extend_from_slice(TELEPAD_DISCOVERY_MAGIC);
        assert!(!is_discovery_probe(&wrong_tag));
    }

    #[test]
    fn our_own_announcements_are_recognised_as_echoes() {
        let (tagged, raw) = pong_packets("x");
        assert!(is_pong_echo(&tagged));
        assert!(is_pong_echo(&raw));
        assert!(!is_pong_echo(&[WIRE_TRANSPORT, 1, 2, 3]));
        assert!(!is_pong_echo(&[]));
    }

    #[test]
    fn announcements_go_to_broadcast_multicast_and_every_subnet() {
        let nets = [
            network([192, 168, 1, 20], 24, Some([192, 168, 1, 255])),
            network([10, 95, 31, 7], 24, Some([10, 95, 31, 255])),
        ];
        let targets = announcement_targets(&nets, 5000);
        let expected: Vec<SocketAddr> = vec![
            "255.255.255.255:5000".parse().unwrap(),
            "239.255.42.67:5000".parse().unwrap(),
            "192.168.1.255:5000".parse().unwrap(),
            "10.95.31.255:5000".parse().unwrap(),
        ];
        assert_eq!(targets, expected);
    }

    #[test]
    fn point_to_point_links_and_duplicates_add_no_extra_targets() {
        let nets = [
            network([10, 8, 0, 2], 32, None), // VPN: no broadcast domain
            network([192, 168, 1, 20], 24, Some([192, 168, 1, 255])),
            network([192, 168, 1, 21], 24, Some([192, 168, 1, 255])), // second address, same subnet
        ];
        let targets = announcement_targets(&nets, 6000);
        assert_eq!(targets.len(), 3);
        assert!(targets.contains(&"192.168.1.255:6000".parse().unwrap()));
    }

    #[test]
    fn no_networks_still_yields_the_wildcard_targets() {
        assert_eq!(announcement_targets(&[], 5000).len(), 2);
    }

    #[test]
    fn announcement_port_follows_configuration() {
        for target in announcement_targets(&[], 4242) {
            assert_eq!(target.port(), 4242);
        }
    }
}
