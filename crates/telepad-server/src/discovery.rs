//! LAN discovery: answering probes, and announcing the server so phones find it
//! without typing an address.

use socket2::{Domain, Protocol, Socket, Type};
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

fn multicast_group() -> Ipv4Addr {
    TELEPAD_MULTICAST_GROUP
        .parse()
        .expect("valid multicast constant")
}

/// Where an announcement from `network` goes: that network's own directed
/// broadcast (which reaches a phone on a hotspot, or behind a router that drops
/// 255.255.255.255) and the discovery multicast group.
///
/// A network without a broadcast domain (a VPN such as Tailscale, a
/// point-to-point link) gets nothing. No phone can hear a broadcast there, and
/// a datagram sent from its address would leave through some *other* network
/// (see [`announcer`]), telling phones the PC lives at an address they cannot
/// reach, so the PC would show up a second time.
pub fn announcement_targets(network: &LocalNetwork, port: u16) -> Vec<SocketAddr> {
    let Some(broadcast) = network.broadcast else {
        return Vec::new();
    };
    vec![
        SocketAddr::V4(SocketAddrV4::new(broadcast, port)),
        SocketAddr::V4(SocketAddrV4::new(multicast_group(), port)),
    ]
}

/// What to use when no network could be enumerated at all: the global
/// broadcast and the multicast group, through whichever adapter the OS picks.
pub fn fallback_targets(port: u16) -> Vec<SocketAddr> {
    vec![
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::BROADCAST, port)),
        SocketAddr::V4(SocketAddrV4::new(multicast_group(), port)),
    ]
}

/// The announcements to send: a source address and its targets, for each
/// subnet that has a broadcast domain. A machine with two addresses on one
/// subnet announces from the first only; two would show up as two PCs.
pub fn announcement_plan(networks: &[LocalNetwork], port: u16) -> Vec<(Ipv4Addr, Vec<SocketAddr>)> {
    let mut subnets = HashSet::new();
    networks
        .iter()
        .filter_map(|network| {
            let broadcast = network.broadcast?;
            subnets
                .insert(broadcast)
                .then(|| (network.ip, announcement_targets(network, port)))
        })
        .collect()
}

/// A socket that announces from one address, and what it sends to.
struct Announcer {
    socket: std::net::UdpSocket,
    targets: Vec<SocketAddr>,
}

/// Builds the socket for one source address.
///
/// Binding a socket to an interface's address does not make it send through
/// that interface: on Linux (and elsewhere) multicast and the global broadcast
/// go out through the default route whichever address the socket is bound to,
/// with that address as the sender. A PC with Tailscale or Docker would then
/// announce itself from addresses that phones on the Wi-Fi cannot reach. So
/// multicast is pinned to this address's interface, and everything else goes
/// to the directed broadcast of this address's own subnet, which routes
/// correctly by itself.
fn announcer(source: Ipv4Addr, mut targets: Vec<SocketAddr>) -> Option<Announcer> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)).ok()?;
    socket.set_broadcast(true).ok()?;
    if socket.set_multicast_if_v4(&source).is_err() {
        // The group could then leave through another network: send the broadcast only.
        targets.retain(|target| !target.ip().is_multicast());
    }
    socket.bind(&SocketAddr::from((source, 0)).into()).ok()?;
    socket.set_nonblocking(true).ok()?;
    Some(Announcer {
        socket: socket.into(),
        targets,
    })
}

fn build_announcers(networks: &[LocalNetwork], port: u16) -> Vec<Announcer> {
    if networks.is_empty() {
        return announcer(Ipv4Addr::UNSPECIFIED, fallback_targets(port))
            .into_iter()
            .collect();
    }
    announcement_plan(networks, port)
        .into_iter()
        .filter_map(|(source, targets)| announcer(source, targets))
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
    let mut announcers: Vec<Announcer> = Vec::new();

    loop {
        ticker.tick().await;

        let networks = netif::local_ipv4_networks();
        let ips: Vec<Ipv4Addr> = networks.iter().map(|n| n.ip).collect();
        if ips != known_ips || announcers.is_empty() {
            if networks.is_empty() {
                warn!("no usable network interface found; discovery will rely on probes only");
            } else {
                let announced: Vec<Ipv4Addr> = announcement_plan(&networks, port)
                    .into_iter()
                    .map(|(source, _)| source)
                    .collect();
                info!(
                    "Announcing on {}",
                    networks
                        .iter()
                        .filter(|n| announced.contains(&n.ip))
                        .map(|n| format!("{}/{}", n.ip, n.prefix_len))
                        .collect::<Vec<_>>()
                        .join(", ")
                );
                for network in networks.iter().filter(|n| !announced.contains(&n.ip)) {
                    debug!(
                        "not announcing from {}/{}: no separate broadcast domain",
                        network.ip, network.prefix_len
                    );
                }
            }
            join_multicast(&socket, &networks, &mut joined);
            announcers = build_announcers(&networks, port);
            known_ips = ips;
        }

        for announcer in &announcers {
            for target in &announcer.targets {
                // Best effort: an interface may be going away, or have no route.
                let _ = announcer.socket.send_to(&tagged, target);
                let _ = announcer.socket.send_to(&raw, target);
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

    /// This machine as it is: Wi-Fi, a Tailscale address, a Docker bridge.
    fn busy_machine() -> Vec<LocalNetwork> {
        vec![
            network([192, 168, 0, 108], 24, Some([192, 168, 0, 255])),
            network([100, 95, 242, 28], 32, None),
            network([172, 19, 0, 1], 16, Some([172, 19, 255, 255])),
        ]
    }

    #[test]
    fn a_network_announces_to_its_own_subnet_and_the_group_only() {
        let lan = network([192, 168, 1, 20], 24, Some([192, 168, 1, 255]));
        let expected: Vec<SocketAddr> = vec![
            "192.168.1.255:5000".parse().unwrap(),
            "239.255.42.67:5000".parse().unwrap(),
        ];
        assert_eq!(announcement_targets(&lan, 5000), expected);
    }

    #[test]
    fn a_network_without_a_broadcast_domain_announces_nothing() {
        // A VPN such as Tailscale: a datagram "from" its address would leave through the
        // Wi-Fi and tell phones the PC lives at an address they cannot reach.
        let vpn = network([100, 95, 242, 28], 32, None);
        assert!(announcement_targets(&vpn, 5000).is_empty());
    }

    #[test]
    fn the_plan_skips_vpns_and_announces_once_per_subnet() {
        let mut networks = busy_machine();
        networks.push(network([192, 168, 0, 109], 24, Some([192, 168, 0, 255]))); // same Wi-Fi
        let sources: Vec<Ipv4Addr> = announcement_plan(&networks, 5000)
            .into_iter()
            .map(|(source, _)| source)
            .collect();
        assert_eq!(
            sources,
            vec![
                Ipv4Addr::new(192, 168, 0, 108),
                Ipv4Addr::new(172, 19, 0, 1)
            ]
        );
    }

    #[test]
    fn every_source_sends_only_to_its_own_subnet() {
        // The global broadcast would leave through the default route with whatever
        // address the socket is bound to; only a subnet's own broadcast is safe.
        for (source, targets) in announcement_plan(&busy_machine(), 5000) {
            let network = busy_machine().into_iter().find(|n| n.ip == source).unwrap();
            for target in targets {
                assert_ne!(target.ip(), Ipv4Addr::BROADCAST, "{source}");
                if !target.ip().is_multicast() {
                    assert_eq!(
                        Some(target.ip()),
                        network.broadcast.map(Into::into),
                        "{source}"
                    );
                }
            }
        }
    }

    #[test]
    fn no_networks_means_the_wildcard_fallback() {
        assert!(announcement_plan(&[], 5000).is_empty());
        let targets = fallback_targets(5000);
        assert_eq!(targets.len(), 2);
        assert!(targets.contains(&"255.255.255.255:5000".parse().unwrap()));
        assert!(targets.contains(&"239.255.42.67:5000".parse().unwrap()));
    }

    #[test]
    fn announcement_port_follows_configuration() {
        for target in fallback_targets(4242) {
            assert_eq!(target.port(), 4242);
        }
        let lan = network([192, 168, 1, 20], 24, Some([192, 168, 1, 255]));
        for target in announcement_targets(&lan, 4242) {
            assert_eq!(target.port(), 4242);
        }
    }

    #[test]
    fn an_announcer_sends_from_its_own_address() {
        let listener = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        listener
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let target = listener.local_addr().unwrap();

        let announcer = announcer(Ipv4Addr::LOCALHOST, vec![target]).expect("an announcer");
        announcer.socket.send_to(b"hello", target).unwrap();

        let mut buf = [0u8; 16];
        let (len, from) = listener.recv_from(&mut buf).unwrap();
        assert_eq!(&buf[..len], b"hello");
        assert_eq!(from.ip(), Ipv4Addr::LOCALHOST);
    }
}
