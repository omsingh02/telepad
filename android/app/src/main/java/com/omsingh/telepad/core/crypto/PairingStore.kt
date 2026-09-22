package com.omsingh.telepad.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Persistent trust store for paired hosts (TOFU pairing model).
 *
 * Stores two kinds of data, both inside an [EncryptedSharedPreferences] file
 * wrapped by an Android Keystore-backed AES-256-GCM master key:
 *
 *  1. **`trusted_hosts`** — JSON-shaped `host_or_address → base64(serverPubkey)`.
 *     On every connect we look up the pubkey by host. If we have one, we run
 *     Noise IK silently. If we don't, the UI shows the pairing screen with the
 *     fingerprint for the user to verify, then calls [trust] to persist.
 *
 *  2. **`local_static_priv`** — our own client static X25519 private key (32 B,
 *     base64). Generated once on first use, reused forever. This is what makes
 *     mutual auth work — the server can recognise the same phone reconnecting.
 *
 * **Why EncryptedSharedPreferences and not a SQLCipher database or KeyStore-only
 * keys?** Two reasons:
 *  - The data set is tiny (one tuple per paired PC, plus one 32-byte secret).
 *    A full DB is overkill.
 *  - We need the *raw bytes* of the private key to feed to noise-java, so a pure
 *    KeyStore key (where `.privateKey` returns an opaque handle and not bytes)
 *    won't work for Noise. EncryptedSharedPreferences stores raw bytes encrypted
 *    at rest with a KeyStore-wrapped AES-256-GCM master key — the right level
 *    of protection for this threat model.
 *
 * **Threat model recap.** We're defending against:
 *  - **Offline device compromise** (someone has your unlocked phone): can't
 *    get keys without root and disk decryption. KeyStore-wrapped storage helps.
 *  - **App-level extraction by malware** (some other app reads our SharedPrefs):
 *    EncryptedSharedPreferences prevents this.
 *  - **App backup leak** (cloud backups carry secrets): explicitly excluded in
 *    `data_extraction_rules.xml` and `backup_rules.xml`.
 *
 * We are *not* defending against:
 *  - Rooted phone with active malware (game over).
 *  - User social-engineered into trusting a malicious fingerprint (UX problem,
 *    not crypto problem).
 *
 * **Thread safety:** Backed by [SharedPreferences] which is thread-safe for
 * reads. Writes use `apply()` (background-committed) so the API is non-blocking
 * for the caller.
 */
class PairingStore private constructor(
    private val prefs: SharedPreferences
) {

    /**
     * Look up the persisted server pubkey for [host]. Returns null if the host
     * has never been trusted — caller should show the pairing flow.
     */
    fun getTrustedPubkey(host: String): String? =
        prefs.getString(trustedKey(host), null)

    /**
     * Look up whether [pubkeyBase64] is trusted under any host address.
     * Allows seamless reconnect when a PC gets a new DHCP lease.
     */
    fun getTrustedPubkeyByFingerprint(pubkeyBase64: String): String? {
        return prefs.all
            .filterKeys { it.startsWith(TRUSTED_PREFIX) }
            .entries
            .firstOrNull { (_, v) -> v == pubkeyBase64 }
            ?.value as? String
    }

    /**
     * Persist [pubkeyBase64] as the trusted pubkey for [host]. Idempotent.
     */
    fun trust(host: String, pubkeyBase64: String) {
        prefs.edit()
            .putString(trustedKey(host), pubkeyBase64)
            .putString(PUBKEY_PREFIX + pubkeyBase64, host)
            .apply()
        Log.i(TAG, "Trusted host: $host")
    }

    /**
     * Forget a previously trusted host. Used by Settings → Privacy → Forget device.
     */
    fun forget(host: String) {
        val pubkey = getTrustedPubkey(host)
        prefs.edit().apply {
            remove(trustedKey(host))
            if (pubkey != null) {
                remove(PUBKEY_PREFIX + pubkey)
            }
        }.apply()
        Log.i(TAG, "Forgot host: $host")
    }

    /** Forget every trusted host. Local static key is preserved. */
    fun forgetAll() {
        val all = prefs.all.keys.filter { it.startsWith(TRUSTED_PREFIX) || it.startsWith(PUBKEY_PREFIX) }
        prefs.edit().apply { all.forEach { remove(it) } }.apply()
        Log.i(TAG, "Forgot all hosts (${all.size})")
    }

    /**
     * Return all currently trusted host → fingerprint pairs, for the
     * "Manage paired devices" screen. The fingerprint is derived on demand
     * so the storage stays minimal.
     */
    fun listTrusted(): List<TrustedHost> {
        return prefs.all
            .filterKeys { it.startsWith(TRUSTED_PREFIX) }
            .mapNotNull { (k, v) ->
                val pubkeyBase64 = v as? String ?: return@mapNotNull null
                val host = k.removePrefix(TRUSTED_PREFIX)
                val raw = runCatching { Fingerprint.ofBase64(pubkeyBase64) }
                    .getOrNull() ?: return@mapNotNull null
                TrustedHost(
                    host = host,
                    pubkeyBase64 = pubkeyBase64,
                    fingerprint = Fingerprint.format(raw)
                )
            }
            .sortedBy { it.host }
    }

    /**
     * Get our local long-term client static key.
     * On first call, generates a fresh 32-byte X25519 private key and stores it.
     * On subsequent calls, returns the persisted bytes.
     *
     * **Never log or expose this**. It's a long-term secret.
     */
    fun localStaticPrivateKey(): ByteArray {
        prefs.getString(KEY_LOCAL_STATIC, null)?.let { existing ->
            return Base64.getDecoder().decode(existing)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Clamp per RFC 7748 §5: turn random 32 bytes into a valid X25519 scalar.
        // (Strictly, noise-java does this internally too, but doing it here means
        //  the bytes-on-disk are also the bytes-fed-to-noise, no transformation.)
        key[0]  = (key[0].toInt() and 0xF8).toByte()
        key[31] = (key[31].toInt() and 0x7F).toByte()
        key[31] = (key[31].toInt() or  0x40).toByte()

        prefs.edit()
            .putString(KEY_LOCAL_STATIC, Base64.getEncoder().encodeToString(key))
            .apply()
        Log.i(TAG, "Generated new local static key")
        return key
    }

    /** For "factory reset" in privacy settings. Forces a new identity on next use. */
    fun resetLocalIdentity() {
        prefs.edit().remove(KEY_LOCAL_STATIC).apply()
        Log.w(TAG, "Local identity reset — next connect will pair as a new client")
    }

    private fun trustedKey(host: String) = TRUSTED_PREFIX + host.lowercase()

    /** UI-facing snapshot of a single trusted host. */
    data class TrustedHost(
        val host: String,
        val pubkeyBase64: String,
        /** Pre-formatted `"7F2A · B9C1 · 4E08"`. */
        val fingerprint: String
    )

    companion object {
        private const val TAG = "PairingStore"
        private const val PREFS_NAME = "telepad_pairing"
        private const val TRUSTED_PREFIX = "host:"
        private const val PUBKEY_PREFIX = "pubkey:"
        private const val KEY_LOCAL_STATIC = "local_static_priv"

        @Volatile private var INSTANCE: PairingStore? = null

        fun get(context: Context): PairingStore {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                return PairingStore(prefs).also { INSTANCE = it }
            }
        }
    }
}
