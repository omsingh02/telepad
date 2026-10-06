package com.omsingh.telepad.core.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * The short code a person compares between the PC's screen and the phone, to be sure
 * they are talking to each other and not to something in between.
 *
 * The fingerprint is the first 10 bytes (80 bits) of `SHA-256(serverStaticPubkey)`, as 20
 * uppercase hex characters in groups of four: `7F2A · B9C1 · 4E08 · 91D3 · 0AC7`.
 *
 * **Why 80 bits.** The PC's public key is not secret: it is handed to anyone who asks. An
 * impostor can therefore set about finding a key of their own whose fingerprint matches the
 * PC's, in advance and at leisure. At 48 bits (the length this used to be) that takes about
 * 2^47 attempts, which a determined attacker with a few GPUs can afford. At 80 bits it cannot
 * be done. The first three groups are what the shorter code showed, so an older version beside
 * a newer one still agrees on them.
 *
 * **Why SHA-256.** The platform provides it, so no extra dependency is needed, and for a
 * one-off hash of 32 bytes any speed difference is microseconds.
 */
object Fingerprint {

    /** Length in bytes of the fingerprint prefix (80 bits). */
    private const val FINGERPRINT_BYTES = 10

    /** Hex characters per displayed group. */
    private const val GROUP = 4

    /**
     * Compute the fingerprint of a 32-byte X25519 public key.
     *
     * @param publicKey Exactly 32 bytes. Throws if shorter.
     * @return 20-character uppercase hex string like `"7F2AB9C14E0891D30AC7"` (no spaces).
     *         Use [format] to add visual separators.
     */
    fun of(publicKey: ByteArray): String {
        require(publicKey.size == 32) {
            "Expected 32-byte X25519 public key, got ${publicKey.size}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val hex = StringBuilder(FINGERPRINT_BYTES * 2)
        for (i in 0 until FINGERPRINT_BYTES) {
            val b = digest[i].toInt() and 0xFF
            hex.append(HEX_CHARS[b ushr 4])
            hex.append(HEX_CHARS[b and 0x0F])
        }
        return hex.toString()
    }

    /**
     * Decode a base64-encoded public key and compute its fingerprint.
     * Convenience for the common case where the server has handed us its
     * pubkey as a string for display/transport.
     */
    fun ofBase64(publicKeyBase64: String): String {
        val key = Base64.getDecoder().decode(publicKeyBase64)
        return of(key)
    }

    /**
     * Format a raw hex fingerprint into the human-friendly grouped form.
     * Input: `"7F2AB9C14E0891D30AC7"`. Output: `"7F2A · B9C1 · 4E08 · 91D3 · 0AC7"`.
     */
    fun format(rawHex: String): String {
        require(rawHex.length == FINGERPRINT_BYTES * 2) {
            "Expected ${FINGERPRINT_BYTES * 2} hex characters, got ${rawHex.length}"
        }
        return rawHex.chunked(GROUP).joinToString(" · ")
    }

    /**
     * Constant-time comparison of two fingerprints. Use this rather than
     * `==` to avoid timing oracles on the (small) chance an attacker can
     * observe comparison latency. For strings this short it is mostly
     * principle — but it's the right principle.
     */
    fun matches(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
}
