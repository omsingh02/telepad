//! `SHA256SUMS`, the list of checksums that every release carries (the format `sha256sum` writes and checks).

use sha2::{Digest, Sha256};
use std::io::Read;

/// The checksums of a release's files, by name.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Sums(Vec<(String, [u8; 32])>);

impl Sums {
    /// Reads the file's text. A line that is not `<64 hex digits>  <name>` is ignored.
    pub fn parse(text: &str) -> Self {
        Self(
            text.lines()
                .filter_map(|line| {
                    let (hash, name) = line.split_once(' ')?;
                    // Two spaces say "text", a space and a star say "binary": the same bytes either way here.
                    let name = name.strip_prefix(' ').or_else(|| name.strip_prefix('*'))?;
                    Some((name.trim_end().to_owned(), from_hex(hash)?))
                })
                .collect(),
        )
    }

    /// The checksum recorded for the file called `name`.
    pub fn get(&self, name: &str) -> Option<[u8; 32]> {
        self.0
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, hash)| *hash)
    }
}

fn from_hex(text: &str) -> Option<[u8; 32]> {
    if text.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (byte, pair) in out.iter_mut().zip(text.as_bytes().chunks(2)) {
        let pair = std::str::from_utf8(pair).ok()?;
        *byte = u8::from_str_radix(pair, 16).ok()?;
    }
    Some(out)
}

/// The SHA-256 of everything `reader` holds, as hex.
pub fn hash_reader(mut reader: impl Read) -> std::io::Result<[u8; 32]> {
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hasher.finalize().into())
}

/// A checksum as the 64 hex digits people compare.
pub fn to_hex(hash: &[u8; 32]) -> String {
    hash.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL: &str = "\
07dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f  telepad-android-v2.0.0-alpha.3.apk
8602407dc0a8c417c3e16a154fdf511055ef7df1d91ab4647c671327122a7159  telepad-v2.0.0-alpha.3-windows-x86_64-setup.exe
d45f44c1d353049f894fe726402203a0e99228de9df49761a0e1db78cc54b9b8 *telepad-v2.0.0-alpha.3-windows-x86_64.exe
";

    #[test]
    fn it_reads_what_sha256sum_writes() {
        let sums = Sums::parse(REAL);
        assert_eq!(
            to_hex(
                &sums
                    .get("telepad-v2.0.0-alpha.3-windows-x86_64-setup.exe")
                    .unwrap()
            ),
            "8602407dc0a8c417c3e16a154fdf511055ef7df1d91ab4647c671327122a7159"
        );
        assert!(
            sums.get("telepad-v2.0.0-alpha.3-windows-x86_64.exe")
                .is_some(),
            "binary mode"
        );
        assert_eq!(sums.get("telepad-v9.9.9.apk"), None);
    }

    #[test]
    fn a_line_that_is_not_a_checksum_is_ignored_not_trusted() {
        let sums = Sums::parse(
            "short  a.bin\n\
             zz7dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f  b.bin\n\
             07dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f c.bin\n\
             \n\
             # a comment\n",
        );
        for name in ["a.bin", "b.bin", "c.bin"] {
            assert_eq!(sums.get(name), None, "{name}");
        }
    }

    #[test]
    fn the_hash_of_a_file_is_the_one_everybody_knows() {
        let hash = hash_reader(&b"abc"[..]).unwrap();
        assert_eq!(
            to_hex(&hash),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            to_hex(&hash_reader(&b""[..]).unwrap()),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }
}
