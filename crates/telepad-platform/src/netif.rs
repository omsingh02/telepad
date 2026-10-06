//! Enumeration of the machine's usable IPv4 networks, for discovery.

use std::net::Ipv4Addr;

/// One IPv4 address assigned to a local interface, with its subnet's broadcast
/// address (when it has one).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LocalNetwork {
    pub ip: Ipv4Addr,
    pub prefix_len: u8,
    /// Directed-broadcast address of the subnet. `None` for point-to-point
    /// links and /32 hosts, which have no broadcast domain.
    pub broadcast: Option<Ipv4Addr>,
}

/// Computes the directed-broadcast address of `ip/prefix_len`.
///
/// Returns `None` when the subnet has no broadcast domain (/31 and /32).
pub fn broadcast_address(ip: Ipv4Addr, prefix_len: u8) -> Option<Ipv4Addr> {
    if prefix_len >= 31 {
        return None;
    }
    let mask = u32::MAX
        .checked_shl(32 - u32::from(prefix_len))
        .unwrap_or(0);
    Some(Ipv4Addr::from(u32::from(ip) | !mask))
}

/// Lists every operational, non-loopback, non-link-local IPv4 address on this
/// machine, one entry per distinct address.
///
/// Works the same on Windows, Linux and macOS. Failure to enumerate (rare) is
/// reported as an empty list: discovery then falls back to the wildcard
/// broadcast/multicast paths instead of aborting the server.
pub fn local_ipv4_networks() -> Vec<LocalNetwork> {
    let interfaces = match if_addrs::get_if_addrs() {
        Ok(list) => list,
        Err(err) => {
            tracing::warn!("could not enumerate network interfaces: {err}");
            return Vec::new();
        }
    };

    let mut networks: Vec<LocalNetwork> = Vec::new();
    for interface in interfaces {
        let if_addrs::IfAddr::V4(v4) = &interface.addr else {
            continue;
        };
        if !interface.is_oper_up() || v4.ip.is_loopback() || v4.ip.is_link_local() {
            continue;
        }
        if v4.ip.is_unspecified() || networks.iter().any(|n| n.ip == v4.ip) {
            continue;
        }
        // Prefer the OS-reported broadcast address, but derive it for
        // platforms/drivers that do not report one on a normal subnet.
        let broadcast = if interface.is_p2p {
            None
        } else {
            v4.broadcast
                .or_else(|| broadcast_address(v4.ip, v4.prefixlen))
        };
        networks.push(LocalNetwork {
            ip: v4.ip,
            prefix_len: v4.prefixlen,
            broadcast,
        });
    }
    networks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> Ipv4Addr {
        s.parse().unwrap()
    }

    #[test]
    fn broadcast_for_common_prefixes() {
        assert_eq!(
            broadcast_address(ip("192.168.1.42"), 24),
            Some(ip("192.168.1.255"))
        );
        assert_eq!(
            broadcast_address(ip("10.95.31.7"), 24),
            Some(ip("10.95.31.255"))
        );
        assert_eq!(
            broadcast_address(ip("172.16.5.9"), 12),
            Some(ip("172.31.255.255"))
        );
        assert_eq!(
            broadcast_address(ip("192.168.1.130"), 25),
            Some(ip("192.168.1.255"))
        );
        assert_eq!(
            broadcast_address(ip("192.168.1.10"), 26),
            Some(ip("192.168.1.63"))
        );
        assert_eq!(
            broadcast_address(ip("10.1.2.3"), 8),
            Some(ip("10.255.255.255"))
        );
    }

    #[test]
    fn broadcast_for_extreme_prefixes() {
        // /0 covers the whole address space.
        assert_eq!(
            broadcast_address(ip("10.0.0.1"), 0),
            Some(ip("255.255.255.255"))
        );
        assert_eq!(
            broadcast_address(ip("10.0.0.1"), 1),
            Some(ip("127.255.255.255"))
        );
        // /31 (point-to-point) and /32 (host route) have no broadcast domain.
        assert_eq!(broadcast_address(ip("10.0.0.1"), 31), None);
        assert_eq!(broadcast_address(ip("10.0.0.1"), 32), None);
    }

    #[test]
    fn enumeration_returns_only_usable_unique_addresses() {
        let networks = local_ipv4_networks();
        for (i, n) in networks.iter().enumerate() {
            assert!(!n.ip.is_loopback(), "{n:?}");
            assert!(!n.ip.is_link_local(), "{n:?}");
            assert!(!n.ip.is_unspecified(), "{n:?}");
            assert!(n.prefix_len <= 32, "{n:?}");
            if let Some(b) = n.broadcast {
                // The broadcast address must live in the same subnet as the host.
                let mask = u32::MAX
                    .checked_shl(32 - u32::from(n.prefix_len))
                    .unwrap_or(0);
                assert_eq!(u32::from(b) & mask, u32::from(n.ip) & mask, "{n:?}");
            }
            assert!(
                networks[i + 1..].iter().all(|o| o.ip != n.ip),
                "duplicate {n:?}"
            );
        }
    }
}
