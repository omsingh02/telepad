use base64::Engine;
use sha2::{Digest, Sha256};
use snow::Builder;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
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

/// Encapsulates established transport session encryption & decryption.
pub struct NoiseTransport {
    session: snow::TransportState,
}

impl NoiseTransport {
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let mut buf = vec![0u8; plaintext.len() + 16];
        let len = self.session.write_message(plaintext, &mut buf)?;
        buf.truncate(len);
        Ok(buf)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let mut buf = vec![0u8; ciphertext.len()];
        let len = self.session.read_message(ciphertext, &mut buf)?;
        buf.truncate(len);
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

        let transport = hs.into_transport_mode()?;
        Ok((out_msg2, client_pubkey, NoiseTransport { session: transport }))
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
        let transport = hs.into_transport_mode()?;
        Ok(NoiseTransport { session: transport })
    }
}

/// Persistent store for trusted client public keys.
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
        fs::write(&self.path, data)?;
        Ok(())
    }
}

/// Cross-platform server static identity key persistence.
pub struct IdentityStore;

impl IdentityStore {
    pub fn load_or_generate(key_path: &Path) -> Result<([u8; 32], [u8; 32]), CryptoError> {
        if key_path.exists() {
            if let Ok(priv_key) = Self::read_key(key_path) {
                if let Ok(pub_key) = Self::derive_public_key(&priv_key) {
                    return Ok((priv_key, pub_key));
                }
            }
        }

        // Generate new keypair
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
        let builder = Builder::new(NOISE_PATTERN.parse()?);
        let keypair = builder.local_private_key(priv_key).generate_keypair()?;
        let mut pub_key = [0u8; 32];
        pub_key.copy_from_slice(&keypair.public);
        Ok(pub_key)
    }

    #[cfg(windows)]
    fn save_key(path: &Path, key: &[u8; 32]) -> Result<(), CryptoError> {
        use std::ptr::null_mut;
        use windows_sys::Win32::Foundation::LocalFree;
        use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};

        unsafe {
            let mut in_blob = CRYPT_INTEGER_BLOB {
                cbData: key.len() as u32,
                pbData: key.as_ptr() as *mut u8,
            };
            let mut out_blob = CRYPT_INTEGER_BLOB {
                cbData: 0,
                pbData: null_mut(),
            };

            let ok = CryptProtectData(&mut in_blob, null_mut(), null_mut(), null_mut(), null_mut(), 0, &mut out_blob);
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
            let mut in_blob = CRYPT_INTEGER_BLOB {
                cbData: data.len() as u32,
                pbData: data.as_ptr() as *mut u8,
            };
            let mut out_blob = CRYPT_INTEGER_BLOB {
                cbData: 0,
                pbData: null_mut(),
            };

            let ok = CryptUnprotectData(&mut in_blob, null_mut(), null_mut(), null_mut(), null_mut(), 0, &mut out_blob);
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
        fs::write(path, key)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
        }
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

        // 4. Test client -> server encryption
        let client_payload = b"Hello from client";
        let encrypted_for_server = client_transport.encrypt(client_payload).unwrap();
        let decrypted_by_server = server_transport.decrypt(&encrypted_for_server).unwrap();
        assert_eq!(client_payload.as_slice(), decrypted_by_server.as_slice());

        // 5. Test server -> client encryption
        let server_payload = b"Welcome from server";
        let encrypted_for_client = server_transport.encrypt(server_payload).unwrap();
        let decrypted_by_client = client_transport.decrypt(&encrypted_for_client).unwrap();
        assert_eq!(server_payload.as_slice(), decrypted_by_client.as_slice());
    }

    #[test]
    fn test_fingerprint_format() {
        let dummy_pubkey = [0x42u8; 32];
        let fp = compute_fingerprint(&dummy_pubkey);
        // Format should be XXYY · XXYY · XXYY (18 chars, 20 UTF-8 bytes)
        assert_eq!(fp.chars().count(), 18);
        assert_eq!(fp.len(), 20);
        assert!(fp.contains('\u{00B7}'));
    }
}
