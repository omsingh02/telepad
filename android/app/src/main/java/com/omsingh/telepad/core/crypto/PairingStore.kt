package com.omsingh.telepad.core.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.omsingh.telepad.core.trust.LegacyTrust
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.core.trust.TrustStore
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/** The phone's own long-term identity, which the Noise handshake presents to the PC. */
interface ClientIdentity {
    /**
     * The 32-byte X25519 private key. Created on first use, then kept for good, which is
     * what lets a PC recognise this phone when it comes back. Never log or expose it.
     */
    fun localStaticPrivateKey(): ByteArray
}

/**
 * Everything security-relevant the phone remembers: its own identity key and the PCs
 * it has paired with.
 *
 * Both live in an [EncryptedSharedPreferences] file wrapped by an Android Keystore
 * AES-256-GCM master key.
 *
 *  - **Paired devices** are stored by the PC's public key, which is its identity (see
 *    [PairedDevice]). The trust decision about a PC at some address is made by
 *    [com.omsingh.telepad.core.trust.TrustResolver].
 *  - **The local static key** is generated once. We need its *raw bytes* to hand to the
 *    Noise library, so a Keystore-only key (which only ever gives an opaque handle)
 *    would not do; encrypted preferences protect the bytes at rest instead.
 *
 * The threat model: an app that can read this app's files, or a cloud backup, learns
 * nothing (backups of these files are excluded in the manifest rules). Not covered: a
 * rooted phone running malware, and a user talked into trusting the wrong fingerprint.
 */
class PairingStore private constructor(
    private val prefs: SharedPreferences,
) : TrustStore {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Paired devices ───────────────────────────────────────────────

    @Synchronized
    override fun all(): List<PairedDevice> =
        prefs.all.entries
            .filter { it.key.startsWith(DEVICE_PREFIX) }
            .mapNotNull { (_, value) ->
                (value as? String)?.let { runCatching { json.decodeFromString(PairedDevice.serializer(), it) }.getOrNull() }
            }
            .sortedWith(compareByDescending<PairedDevice> { it.lastConnectedMs }.thenBy { it.name.lowercase() })

    @Synchronized
    override fun put(device: PairedDevice) {
        prefs.edit()
            .putString(DEVICE_PREFIX + device.publicKey, json.encodeToString(PairedDevice.serializer(), device))
            .apply()
    }

    @Synchronized
    override fun remove(publicKey: String) {
        prefs.edit().remove(DEVICE_PREFIX + publicKey).apply()
        Log.i(TAG, "Forgot a paired device")
    }

    @Synchronized
    override fun clear() {
        val keys = prefs.all.keys.filter { it.startsWith(DEVICE_PREFIX) }
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        Log.i(TAG, "Forgot all paired devices (${keys.size})")
    }

    // ── Our own identity ─────────────────────────────────────────────

    // `commit` rather than `apply`: the key must be on disk before it is used to pair, or a crash
    // in between would leave a PC trusting a key the phone no longer has. Callers are off the main thread.
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun localStaticPrivateKey(): ByteArray {
        prefs.getString(KEY_LOCAL_STATIC, null)?.let { existing ->
            return Base64.getDecoder().decode(existing)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Clamp per RFC 7748 section 5, so the bytes on disk are exactly the bytes
        // fed to Noise, with no transformation in between.
        key[0] = (key[0].toInt() and 0xF8).toByte()
        key[31] = (key[31].toInt() and 0x7F).toByte()
        key[31] = (key[31].toInt() or 0x40).toByte()

        prefs.edit()
            .putString(KEY_LOCAL_STATIC, Base64.getEncoder().encodeToString(key))
            .commit()
        Log.i(TAG, "Generated a new local identity")
        return key
    }

    /**
     * Throws away this phone's identity. The next connection pairs as a brand-new
     * phone, so every PC has to be told to trust it again.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun resetLocalIdentity() {
        prefs.edit().remove(KEY_LOCAL_STATIC).commit()
        Log.w(TAG, "Local identity reset")
    }

    // ── Upgrading from the address-keyed format ──────────────────────

    /** Whether trust is still stored the old way, by address, and [migrateLegacy] has work to do. */
    override val needsMigration: Boolean get() = prefs.getInt(KEY_SCHEMA, 1) < SCHEMA_VERSION

    /**
     * Earlier versions stored trust as `address -> public key`. Converts those entries
     * (named from [favorites], the old list of recent servers) into [PairedDevice]s, once.
     * Returns how many devices were carried over.
     */
    @SuppressLint("ApplySharedPref") // a one-time migration, which must finish before the old data is gone
    @Synchronized
    override fun migrateLegacy(favorites: List<LegacyTrust.Favorite>, nowMs: Long): Int {
        if (prefs.getInt(KEY_SCHEMA, 1) >= SCHEMA_VERSION) return 0

        val legacy = prefs.all.entries
            .filter { it.key.startsWith(LEGACY_TRUSTED_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(LEGACY_TRUSTED_PREFIX) to it.value as String }
        val migrated = LegacyTrust.migrate(legacy, favorites, nowMs = nowMs)

        val editor = prefs.edit()
        for (device in migrated) {
            // Never overwrite a device that has already been paired the new way.
            if (prefs.getString(DEVICE_PREFIX + device.publicKey, null) == null) {
                editor.putString(DEVICE_PREFIX + device.publicKey, json.encodeToString(PairedDevice.serializer(), device))
            }
        }
        prefs.all.keys
            .filter { it.startsWith(LEGACY_TRUSTED_PREFIX) || it.startsWith(LEGACY_PUBKEY_PREFIX) }
            .forEach { editor.remove(it) }
        editor.putInt(KEY_SCHEMA, SCHEMA_VERSION).commit()
        Log.i(TAG, "Migrated ${migrated.size} paired device(s) to the new format")
        return migrated.size
    }

    companion object {
        private const val TAG = "PairingStore"
        private const val PREFS_NAME = "telepad_pairing"
        private const val DEVICE_PREFIX = "device:"
        private const val LEGACY_TRUSTED_PREFIX = "host:"
        private const val LEGACY_PUBKEY_PREFIX = "pubkey:"
        private const val KEY_LOCAL_STATIC = "local_static_priv"
        private const val KEY_SCHEMA = "schema"
        private const val SCHEMA_VERSION = 2

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
