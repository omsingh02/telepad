//! The pairing invitation: what the QR code on the PC's screen says.
//!
//! It is a link, `telepad://pair?...`, so that one format serves the app's scanner and anything
//! else that can open links:
//!
//! | field | meaning |
//! | :--- | :--- |
//! | `v` | format version, `1` |
//! | `k` | the PC's public key (32 bytes, base64url without padding). The phone checks the PC it reaches against this key, so there is no code to compare by eye: the QR code, seen on the PC's own screen, is the proof. |
//! | `t` | the one-time pairing token (16 bytes, base64url without padding), see [`crate::pairing`] |
//! | `p` | the UDP port |
//! | `h` | the PC's IPv4 addresses, comma separated, best first |
//! | `n` | the PC's name, percent-encoded |
//!
//! Readers ignore fields they do not know, so fields can be added without breaking an older app.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use std::net::Ipv4Addr;
use std::time::Duration;
use telepad_crypto::PAIRING_TOKEN_LEN;

/// The most addresses put in the code. The code gets denser with every character, and a phone
/// has no use for the addresses of a PC's virtual networks.
const MAX_ADDRESSES: usize = 3;

/// The longest name put in the code, in characters.
const MAX_NAME_CHARS: usize = 40;

/// A pairing invitation, ready to show.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invite {
    /// The link the QR code holds.
    pub url: String,
    /// The PC's addresses that are in the link, best first: what to type into the app when the code cannot be
    /// scanned.
    pub addresses: Vec<Ipv4Addr>,
    /// How long the invitation is good for.
    pub expires_in: Duration,
}

/// Builds the invitation link.
pub fn build_url(
    public_key: &[u8; 32],
    token: &[u8; PAIRING_TOKEN_LEN],
    port: u16,
    name: &str,
    addresses: &[Ipv4Addr],
) -> String {
    let mut url = String::from("telepad://pair?v=1");
    url.push_str("&k=");
    url.push_str(&URL_SAFE_NO_PAD.encode(public_key));
    url.push_str("&t=");
    url.push_str(&URL_SAFE_NO_PAD.encode(token));
    url.push_str("&p=");
    url.push_str(&port.to_string());

    let best = best_addresses(addresses);
    if !best.is_empty() {
        url.push_str("&h=");
        let list: Vec<String> = best.iter().map(Ipv4Addr::to_string).collect();
        url.push_str(&list.join(","));
    }

    // Last, because it is the part that varies and the part that matters least.
    let name: String = name.chars().take(MAX_NAME_CHARS).collect();
    url.push_str("&n=");
    url.push_str(&percent_encode(&name));
    url
}

/// The addresses a phone on the same network is most likely to reach, best first.
///
/// A home network is almost always 192.168.x.x or 10.x.x.x. The other private range (172.16 to
/// 172.31) is where Docker and other virtual networks live, so it comes after them, and anything
/// else (a VPN, for instance) last.
pub fn best_addresses(addresses: &[Ipv4Addr]) -> Vec<Ipv4Addr> {
    fn rank(ip: &Ipv4Addr) -> u8 {
        let [a, b, ..] = ip.octets();
        match (a, b) {
            (192, 168) => 0,
            (10, _) => 1,
            (172, 16..=31) => 2,
            _ => 3,
        }
    }
    let mut sorted: Vec<Ipv4Addr> = addresses.to_vec();
    sorted.sort_by_key(rank);
    sorted.dedup();
    sorted.truncate(MAX_ADDRESSES);
    sorted
}

/// Percent-encodes `text` for a link's query: letters, digits and `-._~` stay as they are.
fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [7; 32];
    const TOKEN: [u8; PAIRING_TOKEN_LEN] = [9; PAIRING_TOKEN_LEN];

    fn field<'a>(url: &'a str, name: &str) -> Option<&'a str> {
        let query = url.strip_prefix("telepad://pair?")?;
        query
            .split('&')
            .find_map(|pair| pair.strip_prefix(name)?.strip_prefix('='))
    }

    fn decode_percent(text: &str) -> String {
        let bytes = text.as_bytes();
        let mut out = Vec::new();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' {
                out.push(u8::from_str_radix(&text[i + 1..i + 3], 16).unwrap());
                i += 3;
            } else {
                out.push(bytes[i]);
                i += 1;
            }
        }
        String::from_utf8(out).unwrap()
    }

    #[test]
    fn the_link_says_what_the_phone_needs() {
        let url = build_url(
            &KEY,
            &TOKEN,
            5000,
            "DESKTOP-PC",
            &["192.168.1.20".parse().unwrap()],
        );

        assert!(url.starts_with("telepad://pair?v=1&"));
        assert_eq!(field(&url, "v"), Some("1"));
        assert_eq!(
            URL_SAFE_NO_PAD.decode(field(&url, "k").unwrap()).unwrap(),
            KEY
        );
        assert_eq!(
            URL_SAFE_NO_PAD.decode(field(&url, "t").unwrap()).unwrap(),
            TOKEN
        );
        assert_eq!(field(&url, "p"), Some("5000"));
        assert_eq!(field(&url, "h"), Some("192.168.1.20"));
        assert_eq!(field(&url, "n"), Some("DESKTOP-PC"));
    }

    #[test]
    fn the_key_and_token_have_no_padding_and_no_characters_that_need_escaping() {
        let url = build_url(&[0xFB; 32], &[0xFF; PAIRING_TOKEN_LEN], 5000, "pc", &[]);
        for name in ["k", "t"] {
            let value = field(&url, name).unwrap();
            assert!(!value.contains('='), "{name} is padded");
            assert!(
                value
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'),
                "{name}: {value}"
            );
        }
        assert_eq!(field(&url, "k").unwrap().len(), 43, "32 bytes");
        assert_eq!(field(&url, "t").unwrap().len(), 22, "16 bytes");
    }

    #[test]
    fn a_name_with_spaces_and_accents_survives() {
        let url = build_url(&KEY, &TOKEN, 5000, "Om's PC & café", &[]);
        let name = field(&url, "n").unwrap();
        assert!(!name.contains(' ') && !name.contains('&') && !name.contains('\''));
        assert_eq!(decode_percent(name), "Om's PC & café");
        // Nothing in it can end the field early or start another.
        assert_eq!(
            url.matches('&').count(),
            4,
            "v, k, t, p and n are the only fields"
        );
    }

    #[test]
    fn a_long_name_is_cut_to_keep_the_code_small() {
        let url = build_url(&KEY, &TOKEN, 5000, &"x".repeat(200), &[]);
        assert_eq!(field(&url, "n").unwrap().len(), MAX_NAME_CHARS);
    }

    #[test]
    fn a_home_network_address_comes_before_a_docker_or_vpn_one() {
        let addresses: Vec<Ipv4Addr> = ["100.64.0.5", "172.17.0.1", "192.168.1.20", "10.0.0.7"]
            .iter()
            .map(|a| a.parse().unwrap())
            .collect();
        let best: Vec<String> = best_addresses(&addresses)
            .iter()
            .map(|a| a.to_string())
            .collect();
        assert_eq!(
            best,
            ["192.168.1.20", "10.0.0.7", "172.17.0.1"],
            "three at most, and the VPN left out"
        );
    }

    #[test]
    fn without_addresses_there_is_no_address_field() {
        let url = build_url(&KEY, &TOKEN, 5000, "pc", &[]);
        assert_eq!(field(&url, "h"), None);
    }

    #[test]
    fn the_link_is_short_enough_for_a_small_code() {
        let url = build_url(
            &KEY,
            &TOKEN,
            5000,
            "DESKTOP-PC",
            &["192.168.1.20".parse().unwrap(), "10.0.0.7".parse().unwrap()],
        );
        assert!(url.len() < 170, "{} characters", url.len());
    }
}
