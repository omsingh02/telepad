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

/// Computes the 12-character display fingerprint: `"7F2A · B9C1 · 4E08"`
/// matching the Android and C server implementations.
pub fn compute_fingerprint(pubkey: &[u8; 32]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(pubkey);
    let hash = hasher.finalize();

    format!(
        "{:02X}{:02X} \u{00B7} {:02X}{:02X} \u{00B7} {:02X}{:02X}",
        hash[0], hash[1], hash[2], hash[3], hash[4], hash[5]
    )
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
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let mut hs = builder.local_private_key(&self.static_key).build_responder()?;

        let mut read_buf = vec![0u8; msg1.len()];
        hs.read_message(msg1, &mut read_buf)?;

        let remote_key_slice = hs.get_remote_static().ok_or(CryptoError::MissingRemoteKey)?;
        let mut client_pubkey = [0u8; 32];
        client_pubkey.copy_from_slice(remote_key_slice);

        let mut out_msg2 = vec![0u8; NOISE_IK_MSG2_LEN + 16];
        let len = hs.write_message(&[], &mut out_msg2)?;
        out_msg2.truncate(len);

        let transport = hs.into_stateless_transport_mode()?;
        Ok((out_msg2, client_pubkey, NoiseTransport::new(transport)))
    }
}

/// Drives the client initiator side (used for tests and client bindings).
pub struct NoiseInitiator {
    local_priv: [u8; 32],
    remote_pub: [u8; 32],
}

impl NoiseInitiator {
    pub fn new(local_priv: [u8; 32], remote_pub: [u8; 32]) -> Self {
        Self { local_priv, remote_pub }
    }

    pub fn start_handshake(&self) -> Result<(Vec<u8>, snow::HandshakeState), CryptoError> {
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let mut hs = builder
            .local_private_key(&self.local_priv)
            .remote_public_key(&self.remote_pub)
            .build_initiator()?;

        let mut out_msg1 = vec![0u8; NOISE_IK_MSG1_LEN + 16];
        let len = hs.write_message(&[], &mut out_msg1)?;
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

        Self { path, trusted_clients }
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

    fn save(&self) -> Result<(), CryptoError> {
        if let Some(parent) = self.path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let data = serde_json::to_string_pretty(&self.trusted_clients)?;
        let tmp_path = self.path.with_extension("tmp");
        fs::write(&tmp_path, data)?;
        fs::rename(&tmp_path, &self.path)?;
        Ok(())
    }
}

/// Cross-platform server static identity key persistence.
pub struct IdentityStore;

impl IdentityStore {
    pub fn load_or_generate(key_path: &Path) -> Result<([u8; 32], [u8; 32]), CryptoError> {
        if key_path.exists() {
            let priv_key = Self::read_key(key_path)?;
            let pub_key = Self::derive_public_key(&priv_key)?;
            return Ok((priv_key, pub_key));
        }

        // Generate new keypair only when the key file does NOT exist.
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let keypair = builder.generate_keypair()?;
        let mut priv_key = [0u8; 32];
        let mut pub_key = [0u8; 32];
        priv_key.copy_from_slice(&keypair.private);
        pub_key.copy_from_slice(&keypair.public);

        if let Some(parent) = key_path.parent() {
            let _ = fs::create_dir_all(parent);
        }

        Self::save_key(key_path, &priv_key)?;
        Ok((priv_key, pub_key))
    }

    pub fn derive_public_key(priv_key: &[u8; 32]) -> Result<[u8; 32], CryptoError> {
        let point = curve25519_dalek::montgomery::MontgomeryPoint::mul_base_clamped(*priv_key);
        Ok(point.0)
    }

    #[cfg(windows)]
    fn save_key(path: &Path, key: &[u8; 32]) -> Result<(), CryptoError> {
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

            let ok = CryptProtectData(&in_blob, null_mut(), null_mut(), null_mut(), null_mut(), 0, &mut out_blob);
            if ok == 0 {
                return Err(CryptoError::DpapiError);
            }

            let protected_bytes = std::slice::from_raw_parts(out_blob.pbData, out_blob.cbData as usize);
            let res = fs::write(path, protected_bytes);
            LocalFree(out_blob.pbData as _);
            res.map_err(CryptoError::Io)
        }
    }

    #[cfg(windows)]
    fn read_key(path: &Path) -> Result<[u8; 32], CryptoError> {
        use std::ptr::null_mut;
        use windows_sys::Win32::Foundation::LocalFree;
        use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};

        let data = fs::read(path)?;
        // Handle migration from raw 32-byte file
        if data.len() == 32 {
            let mut key = [0u8; 32];
            key.copy_from_slice(&data);
            let _ = Self::save_key(path, &key);
            return Ok(key);
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

            let ok = CryptUnprotectData(&in_blob, null_mut(), null_mut(), null_mut(), null_mut(), 0, &mut out_blob);
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
            Ok(key)
        }
    }

    #[cfg(not(windows))]
    fn save_key(path: &Path, key: &[u8; 32]) -> Result<(), CryptoError> {
        use std::fs::OpenOptions;
        use std::io::Write;
        #[cfg(unix)]
        use std::os::unix::fs::OpenOptionsExt;

        let mut opts = OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        opts.mode(0o600);
        let mut file = opts.open(path)?;
        file.write_all(key)?;
        Ok(())
    }

    #[cfg(not(windows))]
    fn read_key(path: &Path) -> Result<[u8; 32], CryptoError> {
        let data = fs::read(path)?;
        if data.len() != 32 {
            return Err(CryptoError::InvalidKeyLength { expected: 32, actual: data.len() });
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&data);
        Ok(key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let decrypted_by_server = server_transport.decrypt(nonce_c, &encrypted_for_server).unwrap();
        assert_eq!(client_payload.as_slice(), decrypted_by_server.as_slice());

        // 5. Test server -> client encryption
        let server_payload = b"Welcome from server";
        let (nonce_s, encrypted_for_client) = server_transport.encrypt(server_payload).unwrap();
        let decrypted_by_client = client_transport.decrypt(nonce_s, &encrypted_for_client).unwrap();
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
        assert_eq!(fp1, "425E \u{00B7} D4E4 \u{00B7} A36B");

        // Vector 2: 32 bytes from 0x00 to 0x1F
        let mut seq_pubkey = [0u8; 32];
        for i in 0..32 {
            seq_pubkey[i] = i as u8;
        }
        let fp2 = compute_fingerprint(&seq_pubkey);
        assert_eq!(fp2, "630D \u{00B7} CD29 \u{00B7} 66C4");
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
        let dir = std::env::temp_dir().join(format!("telepad_test_pairing_{}", rand::random::<u64>()));
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
}
