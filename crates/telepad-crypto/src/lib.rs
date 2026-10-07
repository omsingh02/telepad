use base64::Engine;
use sha2::{Digest, Sha256};
use snow::Builder;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use thiserror::Error;

pub const NOISE_PATTERN: &str = "Noise_IK_25519_ChaChaPoly_BLAKE2s";
pub const NOISE_IK_MSG1_LEN: usize = 96;
pub const NOISE_IK_MSG2_LEN: usize = 48;

#[derive(Error, Debug)]
pub enum CryptoError {
    #[error("Noise protocol error: {0}")]
    Noise(#[from] snow::Error),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),
    #[error("DPAPI protection error")]
    DpapiError,
    #[error("Handshake invalid or remote public key missing")]
    MissingRemoteKey,
    #[error("Invalid key length: expected {expected}, got {actual}")]
    InvalidKeyLength { expected: usize, actual: usize },
    #[error("Replay packet detected with nonce {0}")]
    ReplayDetected(u64),
}

/// Generates a fresh X25519 static keypair `(private, public)` for the Noise pattern.
pub fn generate_keypair() -> Result<([u8; 32], [u8; 32]), CryptoError> {
    let keypair = Builder::new(NOISE_PATTERN.parse()?).generate_keypair()?;
    let mut private = [0u8; 32];
    let mut public = [0u8; 32];
    private.copy_from_slice(&keypair.private);
    public.copy_from_slice(&keypair.public);
    Ok((private, public))
}

/// How many bytes of the key's hash a fingerprint shows.
const FINGERPRINT_BYTES: usize = 10;

/// Computes the display fingerprint: the first 10 bytes of the SHA-256 of the public key, as
/// five groups of four hex digits, `"7F2A · B9C1 · 4E08 · 91D3 · 0AC7"`. It is what a person
/// compares between the PC's screen and the phone to be sure they are talking to each other.
///
/// It is 80 bits long because the PC's public key is not secret (it is handed to anyone who
/// asks), so an impostor can set about finding a key of their own whose fingerprint matches,
/// in advance and at leisure. At 48 bits that takes about 2^47 attempts, which a determined
/// attacker with a few GPUs can afford; at 80 bits it is out of reach. The first three groups
/// are the same as the shorter fingerprint older versions showed, so versions side by side
/// still agree on them.
pub fn compute_fingerprint(pubkey: &[u8; 32]) -> String {
    let hash = Sha256::digest(pubkey);
    hash[..FINGERPRINT_BYTES]
        .chunks(2)
        .map(|pair| format!("{:02X}{:02X}", pair[0], pair[1]))
        .collect::<Vec<_>>()
        .join(" \u{00B7} ")
}

/// Sliding replay window (128 packets) to defend against UDP replay and reordering.
#[derive(Debug, Clone, Default)]
pub struct ReplayWindow {
    max_nonce: u64,
    bitmap: u128,
    initialized: bool,
}

impl ReplayWindow {
    pub const WINDOW_SIZE: u64 = 128;

    pub fn new() -> Self {
        Self::default()
    }

    /// Checks if a nonce is valid (not replayed and within acceptable window).
    pub fn check(&self, nonce: u64) -> bool {
        if !self.initialized {
            return true;
        }
        if nonce > self.max_nonce {
            return true;
        }
        let diff = self.max_nonce - nonce;
        if diff >= Self::WINDOW_SIZE {
            return false; // Too old
        }
        (self.bitmap & (1u128 << diff)) == 0
    }

    /// Commits a verified nonce to the replay window.
    pub fn update(&mut self, nonce: u64) {
        if !self.initialized {
            self.initialized = true;
            self.max_nonce = nonce;
            self.bitmap = 1;
            return;
        }
        if nonce > self.max_nonce {
            let diff = nonce - self.max_nonce;
            if diff >= Self::WINDOW_SIZE {
                self.bitmap = 1;
            } else {
                self.bitmap = (self.bitmap << diff) | 1;
            }
            self.max_nonce = nonce;
        } else {
            let diff = self.max_nonce - nonce;
            if diff < Self::WINDOW_SIZE {
                self.bitmap |= 1u128 << diff;
            }
        }
    }
}

/// Encapsulates established transport session encryption & decryption with explicit nonce & replay protection.
pub struct NoiseTransport {
    session: snow::StatelessTransportState,
    send_nonce: AtomicU64,
    replay_window: ReplayWindow,
}

impl NoiseTransport {
    pub fn new(session: snow::StatelessTransportState) -> Self {
        Self {
            session,
            send_nonce: AtomicU64::new(0),
            replay_window: ReplayWindow::new(),
        }
    }

    /// Encrypts plaintext using an explicit monotonically increasing nonce.
    /// Returns the (nonce, ciphertext) pair.
    pub fn encrypt(&self, plaintext: &[u8]) -> Result<(u64, Vec<u8>), CryptoError> {
        let nonce = self.send_nonce.fetch_add(1, Ordering::SeqCst);
        let mut buf = vec![0u8; plaintext.len() + 16];
        let len = self.session.write_message(nonce, plaintext, &mut buf)?;
        buf.truncate(len);
        Ok((nonce, buf))
    }

    /// Decrypts ciphertext with an explicit datagram nonce.
    /// Rejects replayed or out-of-window nonces before attempting decryption.
    pub fn decrypt(&mut self, nonce: u64, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if !self.replay_window.check(nonce) {
            return Err(CryptoError::ReplayDetected(nonce));
        }
        let mut buf = vec![0u8; ciphertext.len()];
        let len = self.session.read_message(nonce, ciphertext, &mut buf)?;
        buf.truncate(len);
        self.replay_window.update(nonce);
        Ok(buf)
    }
}

/// Length of the one-time token a pairing invitation (the QR code) carries.
pub const PAIRING_TOKEN_LEN: usize = 16;

/// A fresh random token for one pairing invitation: 128 bits from the operating system's
/// secure random number generator.
pub fn random_token() -> [u8; PAIRING_TOKEN_LEN] {
    use rand::RngCore;
    let mut token = [0u8; PAIRING_TOKEN_LEN];
    rand::rngs::OsRng.fill_bytes(&mut token);
    token
}

/// Compares two secrets without stopping at the first difference, so the time it takes says
/// nothing about how much of a guess was right.
pub fn secrets_equal(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut difference = 0u8;
    for (x, y) in a.iter().zip(b) {
        difference |= x ^ y;
    }
    difference == 0
}

/// Drives the server responder side of Noise IK.
pub struct NoiseResponder {
    static_key: [u8; 32],
}

impl NoiseResponder {
    pub fn new(static_key: [u8; 32]) -> Self {
        Self { static_key }
    }

    pub fn respond_handshake(
        &self,
        msg1: &[u8],
    ) -> Result<(Vec<u8>, [u8; 32], NoiseTransport), CryptoError> {
        let outcome = self.respond_handshake_with_payload(msg1)?;
        Ok((outcome.reply, outcome.client_key, outcome.transport))
    }

    /// Like [`respond_handshake`](Self::respond_handshake), and also returns what the initiator
    /// put inside its first message. That is encrypted to this server's key, so nobody watching
    /// the network can read it; a phone pairing by QR code puts the code's one-time token there.
    pub fn respond_handshake_with_payload(
        &self,
        msg1: &[u8],
    ) -> Result<HandshakeOutcome, CryptoError> {
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let mut hs = builder
            .local_private_key(&self.static_key)
            .build_responder()?;

        let mut payload = vec![0u8; msg1.len()];
        let payload_len = hs.read_message(msg1, &mut payload)?;
        payload.truncate(payload_len);

        let remote_key_slice = hs
            .get_remote_static()
            .ok_or(CryptoError::MissingRemoteKey)?;
        let mut client_pubkey = [0u8; 32];
        client_pubkey.copy_from_slice(remote_key_slice);

        let mut out_msg2 = vec![0u8; NOISE_IK_MSG2_LEN + 16];
        let len = hs.write_message(&[], &mut out_msg2)?;
        out_msg2.truncate(len);

        let transport = hs.into_stateless_transport_mode()?;
        Ok(HandshakeOutcome {
            reply: out_msg2,
            client_key: client_pubkey,
            transport: NoiseTransport::new(transport),
            payload,
        })
    }
}

/// What the responder learns from the first handshake message.
pub struct HandshakeOutcome {
    /// The second handshake message, to send back.
    pub reply: Vec<u8>,
    /// The initiator's static public key.
    pub client_key: [u8; 32],
    /// The session's transport.
    pub transport: NoiseTransport,
    /// What the initiator carried inside the first message: empty, or a pairing token.
    pub payload: Vec<u8>,
}

/// Drives the client initiator side (used for tests and client bindings).
pub struct NoiseInitiator {
    local_priv: [u8; 32],
    remote_pub: [u8; 32],
}

impl NoiseInitiator {
    pub fn new(local_priv: [u8; 32], remote_pub: [u8; 32]) -> Self {
        Self {
            local_priv,
            remote_pub,
        }
    }

    pub fn start_handshake(&self) -> Result<(Vec<u8>, snow::HandshakeState), CryptoError> {
        self.start_handshake_with_payload(&[])
    }

    /// Starts the handshake with `payload` inside the first message, encrypted to the server.
    pub fn start_handshake_with_payload(
        &self,
        payload: &[u8],
    ) -> Result<(Vec<u8>, snow::HandshakeState), CryptoError> {
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let mut hs = builder
            .local_private_key(&self.local_priv)
            .remote_public_key(&self.remote_pub)
            .build_initiator()?;

        let mut out_msg1 = vec![0u8; NOISE_IK_MSG1_LEN + 16 + payload.len()];
        let len = hs.write_message(payload, &mut out_msg1)?;
        out_msg1.truncate(len);

        Ok((out_msg1, hs))
    }

    pub fn finish_handshake(
        mut hs: snow::HandshakeState,
        msg2: &[u8],
    ) -> Result<NoiseTransport, CryptoError> {
        let mut read_buf = vec![0u8; msg2.len()];
        hs.read_message(msg2, &mut read_buf)?;
        let transport = hs.into_stateless_transport_mode()?;
        Ok(NoiseTransport::new(transport))
    }
}

/// Writes `data` to `path` atomically: the bytes go to a private temporary file
/// in the same directory first, then are moved into place, so a crash can never
/// leave a half-written file behind.
///
/// With `replace == false` an existing file is never touched and `Ok(false)` is
/// returned, which makes concurrent first-time creation safe (exactly one
/// writer wins). The file is created readable by its owner only on Unix.
fn atomic_write(path: &Path, data: &[u8], replace: bool) -> std::io::Result<bool> {
    use std::fs::OpenOptions;
    use std::io::{ErrorKind, Write};
    #[cfg(unix)]
    use std::os::unix::fs::OpenOptionsExt;

    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let tmp = path.with_file_name(format!(".{name}.{:016x}.tmp", rand::random::<u64>()));

    let mut opts = OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    opts.mode(0o600);
    {
        let mut file = opts.open(&tmp)?;
        file.write_all(data)?;
        file.sync_all()?;
    }

    let outcome = if replace {
        fs::rename(&tmp, path).map(|()| true)
    } else {
        // A hard link fails if `path` exists, which is exactly the no-clobber
        // guarantee we need, and is atomic.
        match fs::hard_link(&tmp, path) {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == ErrorKind::AlreadyExists => Ok(false),
            // Filesystems without hard links: fall back to check-then-rename.
            Err(_) if !path.exists() => fs::rename(&tmp, path).map(|()| true),
            Err(_) => Ok(false),
        }
    };
    let _ = fs::remove_file(&tmp);
    outcome
}

/// Persistent store for trusted client public keys with atomic write semantics.
pub struct PairingStore {
    path: PathBuf,
    trusted_clients: HashSet<String>,
}

impl PairingStore {
    pub fn load_or_default(path: PathBuf) -> Self {
        let trusted_clients = if path.exists() {
            fs::read_to_string(&path)
                .ok()
                .and_then(|data| serde_json::from_str(&data).ok())
                .unwrap_or_default()
        } else {
            HashSet::new()
        };

        Self {
            path,
            trusted_clients,
        }
    }

    pub fn is_trusted(&self, pubkey: &[u8; 32]) -> bool {
        let b64 = base64::engine::general_purpose::STANDARD.encode(pubkey);
        self.trusted_clients.contains(&b64)
    }

    pub fn trust(&mut self, pubkey: &[u8; 32]) -> Result<(), CryptoError> {
        let b64 = base64::engine::general_purpose::STANDARD.encode(pubkey);
        self.trusted_clients.insert(b64);
        self.save()
    }

    pub fn forget(&mut self, pubkey: &[u8; 32]) -> Result<(), CryptoError> {
        let b64 = base64::engine::general_purpose::STANDARD.encode(pubkey);
        self.trusted_clients.remove(&b64);
        self.save()
    }

    pub fn forget_all(&mut self) -> Result<(), CryptoError> {
        self.trusted_clients.clear();
        self.save()
    }

    /// Number of paired devices.
    pub fn len(&self) -> usize {
        self.trusted_clients.len()
    }

    pub fn is_empty(&self) -> bool {
        self.trusted_clients.is_empty()
    }

    /// Public keys of all paired devices, in a stable (sorted) order. Entries
    /// that are not valid 32-byte keys (a hand-edited file) are skipped.
    pub fn trusted_keys(&self) -> Vec<[u8; 32]> {
        let mut keys: Vec<[u8; 32]> = self
            .trusted_clients
            .iter()
            .filter_map(|b64| {
                let bytes = base64::engine::general_purpose::STANDARD.decode(b64).ok()?;
                <[u8; 32]>::try_from(bytes.as_slice()).ok()
            })
            .collect();
        keys.sort_unstable();
        keys
    }

    fn save(&self) -> Result<(), CryptoError> {
        if let Some(parent) = self.path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let data = serde_json::to_string_pretty(&self.trusted_clients)?;
        atomic_write(&self.path, data.as_bytes(), true)?;
        Ok(())
    }
}

/// Cross-platform server static identity key persistence.
///
/// The on-disk form is platform specific (DPAPI-protected on Windows, raw bytes
/// readable only by the owner elsewhere); everything else is shared.
pub struct IdentityStore;

impl IdentityStore {
    pub fn load_or_generate(key_path: &Path) -> Result<([u8; 32], [u8; 32]), CryptoError> {
        if key_path.exists() {
            return Self::load(key_path);
        }

        // Generate a new keypair only when the key file does NOT exist.
        let (priv_key, pub_key) = generate_keypair()?;

        if let Some(parent) = key_path.parent() {
            let _ = fs::create_dir_all(parent);
        }

        let encoded = encode_key(&priv_key)?;
        if atomic_write(key_path, &encoded, false)? {
            Ok((priv_key, pub_key))
        } else {
            // Another process created the identity between our check and our
            // write. Use theirs so both agree on a single fingerprint.
            Self::load(key_path)
        }
    }

    fn load(key_path: &Path) -> Result<([u8; 32], [u8; 32]), CryptoError> {
        let data = fs::read(key_path)?;
        let (priv_key, needs_migration) = decode_key(&data)?;
        if needs_migration {
            // Best effort: an unmigrated key still works, it is just unprotected.
            if let Ok(encoded) = encode_key(&priv_key) {
                let _ = atomic_write(key_path, &encoded, true);
            }
        }
        let pub_key = Self::derive_public_key(&priv_key)?;
        Ok((priv_key, pub_key))
    }

    pub fn derive_public_key(priv_key: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
        let point = curve25519_dalek::montgomery::MontgomeryPoint::mul_base_clamped(*priv_key);
        Ok(point.0)
    }
}

/// Turns a private key into the bytes stored on disk (DPAPI-encrypted for the
/// current Windows user).
#[cfg(windows)]
fn encode_key(key: &[u8; 32]) -> Result<Vec<u8>, CryptoError> {
    use std::ptr::null_mut;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};

    unsafe {
        let in_blob = CRYPT_INTEGER_BLOB {
            cbData: key.len() as u32,
            pbData: key.as_ptr() as *mut u8,
        };
        let mut out_blob = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: null_mut(),
        };

        let ok = CryptProtectData(
            &in_blob,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
            0,
            &mut out_blob,
        );
        if ok == 0 {
            return Err(CryptoError::DpapiError);
        }

        let protected =
            std::slice::from_raw_parts(out_blob.pbData, out_blob.cbData as usize).to_vec();
        LocalFree(out_blob.pbData as _);
        Ok(protected)
    }
}

/// Inverse of [`encode_key`]. The flag reports that the file held a legacy
/// unprotected 32-byte key and should be rewritten in the protected form.
#[cfg(windows)]
fn decode_key(data: &[u8]) -> Result<([u8; 32], bool), CryptoError> {
    use std::ptr::null_mut;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};

    // Handle migration from a raw 32-byte file (a DPAPI blob is always longer).
    if data.len() == 32 {
        let mut key = [0u8; 32];
        key.copy_from_slice(data);
        return Ok((key, true));
    }

    unsafe {
        let in_blob = CRYPT_INTEGER_BLOB {
            cbData: data.len() as u32,
            pbData: data.as_ptr() as *mut u8,
        };
        let mut out_blob = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: null_mut(),
        };

        let ok = CryptUnprotectData(
            &in_blob,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
            0,
            &mut out_blob,
        );
        if ok == 0 {
            return Err(CryptoError::DpapiError);
        }

        if out_blob.cbData != 32 {
            LocalFree(out_blob.pbData as _);
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: out_blob.cbData as usize,
            });
        }

        let mut key = [0u8; 32];
        key.copy_from_slice(std::slice::from_raw_parts(out_blob.pbData, 32));
        LocalFree(out_blob.pbData as _);
        Ok((key, false))
    }
}

#[cfg(not(windows))]
fn encode_key(key: &[u8; 32]) -> Result<Vec<u8>, CryptoError> {
    Ok(key.to_vec())
}

#[cfg(not(windows))]
fn decode_key(data: &[u8]) -> Result<([u8; 32], bool), CryptoError> {
    let key: [u8; 32] = data.try_into().map_err(|_| CryptoError::InvalidKeyLength {
        expected: 32,
        actual: data.len(),
    })?;
    Ok((key, false))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_payload_in_the_first_message_reaches_the_server_and_no_one_else() {
        let (server_priv, server_pub) = generate_keypair().unwrap();
        let (client_priv, client_pub) = generate_keypair().unwrap();
        let token = random_token();

        let (msg1, hs) = NoiseInitiator::new(client_priv, server_pub)
            .start_handshake_with_payload(&token)
            .unwrap();
        // The token is not in the clear on the network.
        assert!(!msg1.windows(token.len()).any(|window| window == token));

        let outcome = NoiseResponder::new(server_priv)
            .respond_handshake_with_payload(&msg1)
            .unwrap();
        assert_eq!(outcome.payload, token);
        assert_eq!(outcome.client_key, client_pub);

        // The session still works afterwards.
        let client = NoiseInitiator::finish_handshake(hs, &outcome.reply).unwrap();
        let (nonce, packet) = client.encrypt(b"hello").unwrap();
        let mut server = outcome.transport;
        assert_eq!(server.decrypt(nonce, &packet).unwrap(), b"hello");
    }

    #[test]
    fn a_handshake_without_a_payload_has_an_empty_one_and_the_old_length() {
        let (server_priv, server_pub) = generate_keypair().unwrap();
        let (client_priv, _) = generate_keypair().unwrap();

        let (msg1, _hs) = NoiseInitiator::new(client_priv, server_pub)
            .start_handshake()
            .unwrap();
        assert_eq!(
            msg1.len(),
            NOISE_IK_MSG1_LEN,
            "older servers expect exactly this"
        );

        let outcome = NoiseResponder::new(server_priv)
            .respond_handshake_with_payload(&msg1)
            .unwrap();
        assert!(outcome.payload.is_empty());
    }

    #[test]
    fn the_plain_responder_still_accepts_a_handshake_that_carries_a_payload() {
        // An older server ignores the payload: a phone that sends a token still connects to it.
        let (server_priv, server_pub) = generate_keypair().unwrap();
        let (client_priv, client_pub) = generate_keypair().unwrap();
        let (msg1, _hs) = NoiseInitiator::new(client_priv, server_pub)
            .start_handshake_with_payload(&random_token())
            .unwrap();

        let (_reply, key, _transport) = NoiseResponder::new(server_priv)
            .respond_handshake(&msg1)
            .unwrap();
        assert_eq!(key, client_pub);
    }

    #[test]
    fn tokens_are_random_and_compared_whole() {
        let a = random_token();
        let b = random_token();
        assert_ne!(a, b);
        assert!(secrets_equal(&a, &a));
        assert!(!secrets_equal(&a, &b));

        let mut almost = a;
        almost[PAIRING_TOKEN_LEN - 1] ^= 1;
        assert!(
            !secrets_equal(&a, &almost),
            "a difference in the last byte counts"
        );
        assert!(
            !secrets_equal(&a, &a[..PAIRING_TOKEN_LEN - 1]),
            "so does a different length"
        );
        assert!(secrets_equal(b"", b""));
    }

    #[test]
    fn test_noise_ik_handshake_and_transport() {
        let builder = Builder::new(NOISE_PATTERN.parse().unwrap());
        let server_kp = builder.generate_keypair().unwrap();
        let client_kp = builder.generate_keypair().unwrap();

        let mut server_priv = [0u8; 32];
        server_priv.copy_from_slice(&server_kp.private);
        let mut server_pub = [0u8; 32];
        server_pub.copy_from_slice(&server_kp.public);

        let mut client_priv = [0u8; 32];
        client_priv.copy_from_slice(&client_kp.private);
        let mut client_pub = [0u8; 32];
        client_pub.copy_from_slice(&client_kp.public);

        let responder = NoiseResponder::new(server_priv);
        let initiator = NoiseInitiator::new(client_priv, server_pub);

        // 1. Client starts handshake (msg1)
        let (msg1, hs_client) = initiator.start_handshake().unwrap();
        assert_eq!(msg1.len(), NOISE_IK_MSG1_LEN);

        // 2. Server responds (msg2)
        let (msg2, extracted_client_pub, mut server_transport) =
            responder.respond_handshake(&msg1).unwrap();
        assert_eq!(extracted_client_pub, client_pub);
        assert_eq!(msg2.len(), NOISE_IK_MSG2_LEN);

        // 3. Client finishes handshake
        let mut client_transport = NoiseInitiator::finish_handshake(hs_client, &msg2).unwrap();

        // 4. Test client -> server encryption with explicit nonce
        let client_payload = b"Hello from client";
        let (nonce_c, encrypted_for_server) = client_transport.encrypt(client_payload).unwrap();
        let decrypted_by_server = server_transport
            .decrypt(nonce_c, &encrypted_for_server)
            .unwrap();
        assert_eq!(client_payload.as_slice(), decrypted_by_server.as_slice());

        // 5. Test server -> client encryption
        let server_payload = b"Welcome from server";
        let (nonce_s, encrypted_for_client) = server_transport.encrypt(server_payload).unwrap();
        let decrypted_by_client = client_transport
            .decrypt(nonce_s, &encrypted_for_client)
            .unwrap();
        assert_eq!(server_payload.as_slice(), decrypted_by_client.as_slice());
    }

    #[test]
    fn test_noise_transport_out_of_order_and_replay() {
        let builder = Builder::new(NOISE_PATTERN.parse().unwrap());
        let server_kp = builder.generate_keypair().unwrap();
        let client_kp = builder.generate_keypair().unwrap();

        let mut server_priv = [0u8; 32];
        server_priv.copy_from_slice(&server_kp.private);
        let mut server_pub = [0u8; 32];
        server_pub.copy_from_slice(&server_kp.public);

        let mut client_priv = [0u8; 32];
        client_priv.copy_from_slice(&client_kp.private);

        let responder = NoiseResponder::new(server_priv);
        let initiator = NoiseInitiator::new(client_priv, server_pub);

        let (msg1, hs_client) = initiator.start_handshake().unwrap();
        let (msg2, _, mut server_transport) = responder.respond_handshake(&msg1).unwrap();
        let client_transport = NoiseInitiator::finish_handshake(hs_client, &msg2).unwrap();

        // Generate 4 packets from client: 0, 1, 2, 3
        let p0 = client_transport.encrypt(b"packet 0").unwrap();
        let p1 = client_transport.encrypt(b"packet 1").unwrap();
        let p2 = client_transport.encrypt(b"packet 2").unwrap();
        let p3 = client_transport.encrypt(b"packet 3").unwrap();

        // Deliver out of order: packet 0, then packet 2, then packet 1, then packet 3
        let d0 = server_transport.decrypt(p0.0, &p0.1).unwrap();
        assert_eq!(d0.as_slice(), b"packet 0");

        let d2 = server_transport.decrypt(p2.0, &p2.1).unwrap();
        assert_eq!(d2.as_slice(), b"packet 2");

        let d1 = server_transport.decrypt(p1.0, &p1.1).unwrap();
        assert_eq!(d1.as_slice(), b"packet 1");

        let d3 = server_transport.decrypt(p3.0, &p3.1).unwrap();
        assert_eq!(d3.as_slice(), b"packet 3");

        // Attempt replay: re-delivering packet 1 must fail
        let replay_err = server_transport.decrypt(p1.0, &p1.1);
        assert!(matches!(replay_err, Err(CryptoError::ReplayDetected(1))));

        // Attempt replay: re-delivering packet 3 must fail
        let replay_err_3 = server_transport.decrypt(p3.0, &p3.1);
        assert!(matches!(replay_err_3, Err(CryptoError::ReplayDetected(3))));
    }

    #[test]
    fn test_canonical_fingerprint_vectors() {
        // Vector 1: 32 bytes of 0x42
        let dummy_pubkey = [0x42u8; 32];
        let fp1 = compute_fingerprint(&dummy_pubkey);
        assert_eq!(
            fp1,
            "425E \u{00B7} D4E4 \u{00B7} A36B \u{00B7} 30EA \u{00B7} 21B9"
        );

        // Vector 2: 32 bytes from 0x00 to 0x1F
        let mut seq_pubkey = [0u8; 32];
        for (i, byte) in seq_pubkey.iter_mut().enumerate() {
            *byte = i as u8;
        }
        let fp2 = compute_fingerprint(&seq_pubkey);
        assert_eq!(
            fp2,
            "630D \u{00B7} CD29 \u{00B7} 66C4 \u{00B7} 3366 \u{00B7} 9112"
        );
    }

    #[test]
    fn test_derive_public_key_matches_snow() {
        let builder = Builder::new(NOISE_PATTERN.parse().unwrap());
        let kp = builder.generate_keypair().unwrap();
        let mut priv_key = [0u8; 32];
        priv_key.copy_from_slice(&kp.private);
        let derived = IdentityStore::derive_public_key(&priv_key).unwrap();
        assert_eq!(derived.as_slice(), kp.public.as_slice());
    }

    #[test]
    fn test_identity_store_does_not_overwrite_corrupt_file() {
        let dir = std::env::temp_dir().join(format!("telepad_test_{}", rand::random::<u64>()));
        let _ = fs::create_dir_all(&dir);
        let key_file = dir.join("server.key");

        // Write corrupt/invalid key data (not 32 bytes and not DPAPI on Windows)
        fs::write(&key_file, b"corrupted data").unwrap();

        // load_or_generate must return an error and NOT overwrite with a newly generated key
        let res = IdentityStore::load_or_generate(&key_file);
        assert!(res.is_err());
        assert_eq!(fs::read(&key_file).unwrap(), b"corrupted data");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_pairing_store_atomic_save() {
        let dir =
            std::env::temp_dir().join(format!("telepad_test_pairing_{}", rand::random::<u64>()));
        let _ = fs::create_dir_all(&dir);
        let store_file = dir.join("trusted.json");

        let mut store = PairingStore::load_or_default(store_file.clone());
        let dummy_pubkey = [0x55u8; 32];
        assert!(!store.is_trusted(&dummy_pubkey));

        store.trust(&dummy_pubkey).unwrap();
        assert!(store.is_trusted(&dummy_pubkey));

        // Reload from disk
        let store_reloaded = PairingStore::load_or_default(store_file);
        assert!(store_reloaded.is_trusted(&dummy_pubkey));

        let _ = fs::remove_dir_all(&dir);
    }

    fn scratch_dir(tag: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("telepad_{tag}_{:016x}", rand::random::<u64>()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn files_in(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    #[test]
    fn identity_is_created_once_and_reloaded_identically() {
        let dir = scratch_dir("identity");
        let path = dir.join("identity.key");
        let first = IdentityStore::load_or_generate(&path).unwrap();
        let second = IdentityStore::load_or_generate(&path).unwrap();
        assert_eq!(first, second);
        assert_eq!(IdentityStore::derive_public_key(&first.0).unwrap(), first.1);
        // Only the key itself is left; no temporary files.
        assert_eq!(files_in(&dir), vec!["identity.key".to_string()]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn concurrent_first_start_agrees_on_one_identity() {
        let dir = scratch_dir("race");
        let path = dir.join("identity.key");
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let path = path.clone();
                std::thread::spawn(move || IdentityStore::load_or_generate(&path).unwrap())
            })
            .collect();
        let results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        // Every racing server must end up with the identity that is on disk.
        assert!(
            results.iter().all(|r| *r == results[0]),
            "servers disagree on the identity"
        );
        assert_eq!(IdentityStore::load_or_generate(&path).unwrap(), results[0]);
        assert_eq!(files_in(&dir), vec!["identity.key".to_string()]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn identity_file_is_readable_only_by_its_owner() {
        use std::os::unix::fs::PermissionsExt;
        let dir = scratch_dir("perm");
        let path = dir.join("identity.key");
        IdentityStore::load_or_generate(&path).unwrap();
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn atomic_write_without_replace_never_clobbers() {
        let dir = scratch_dir("atomic");
        let path = dir.join("f");
        assert!(atomic_write(&path, b"one", false).unwrap());
        assert!(!atomic_write(&path, b"two", false).unwrap());
        assert_eq!(fs::read(&path).unwrap(), b"one");
        assert!(atomic_write(&path, b"three", true).unwrap());
        assert_eq!(fs::read(&path).unwrap(), b"three");
        assert_eq!(files_in(&dir), vec!["f".to_string()]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn pairing_store_lists_and_forgets_devices() {
        let dir = scratch_dir("pairing_list");
        let path = dir.join("trusted.json");
        let mut store = PairingStore::load_or_default(path.clone());
        assert!(store.is_empty());
        assert_eq!(store.len(), 0);

        let (a, b) = ([0x01u8; 32], [0x02u8; 32]);
        store.trust(&b).unwrap();
        store.trust(&a).unwrap();
        store.trust(&a).unwrap(); // idempotent
        assert_eq!(store.len(), 2);
        assert_eq!(store.trusted_keys(), vec![a, b], "keys come back sorted");

        // Survives a restart.
        let mut reloaded = PairingStore::load_or_default(path.clone());
        assert_eq!(reloaded.trusted_keys(), vec![a, b]);
        reloaded.forget(&a).unwrap();
        assert!(!reloaded.is_trusted(&a));
        assert!(reloaded.is_trusted(&b));
        reloaded.forget_all().unwrap();
        assert!(PairingStore::load_or_default(path).is_empty());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn pairing_store_skips_malformed_entries() {
        let dir = scratch_dir("pairing_bad");
        let path = dir.join("trusted.json");
        let good = base64::engine::general_purpose::STANDARD.encode([0x07u8; 32]);
        let short = base64::engine::general_purpose::STANDARD.encode([0x07u8; 5]);
        fs::write(&path, format!(r#"["{good}", "{short}", "not base64!!"]"#)).unwrap();
        let store = PairingStore::load_or_default(path);
        assert_eq!(store.trusted_keys(), vec![[0x07u8; 32]]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn pairing_store_with_garbage_file_starts_empty_instead_of_failing() {
        let dir = scratch_dir("pairing_garbage");
        let path = dir.join("trusted.json");
        fs::write(&path, b"{ this is not json").unwrap();
        assert!(PairingStore::load_or_default(path).is_empty());
        let _ = fs::remove_dir_all(&dir);
    }
}
