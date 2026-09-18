package com.omsingh.telepad.core.crypto

import java.security.MessageDigest
import java.util.Base64
import kotlin.experimental.and

/**
 * Cryptographic fingerprint utilities for human-readable server verification.
 *
 * The fingerprint is the first 6 bytes (48 bits) of `SHA-256(serverStaticPubkey)`
 * rendered as 12 uppercase hex characters in 3 segments of 4: `7F2A · B9C1 · 4E08`.
 *
 * **Why 48 bits?** Two reasons:
 *  - It's short enough to read off a PC screen and verify on a phone in ~3 seconds.
 *  - It's long enough that an attacker who wants to perform a real-time MITM with
 *    a colliding fingerprint would need to grind ~2^47 X25519 keypairs and pick
 *    one that happens to match — computationally infeasible during a pairing window.
 *
 * **Why SHA-256 and not BLAKE2s?** The Android platform ships SHA-256 in the
 * standard JCA provider — no extra dependency. BLAKE2s would have been ~10×
 * faster but for a 32-byte one-shot input the difference is microseconds, and
 * portability across phones running ancient providers matters more.
 */
object Fingerprint {

    /** Length in bytes of the fingerprint prefix (48 bits). */
    private const val FINGERPRINT_BYTES = 6

    /**
     * Compute the fingerprint of a 32-byte X25519 public key.
     *
     * @param publicKey Exactly 32 bytes. Throws if shorter.
     * @return 12-character uppercase hex string like `"7F2AB9C14E08"` (no spaces).
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
     * Input: `"7F2AB9C14E08"`. Output: `"7F2A · B9C1 · 4E08"`.
     */
    fun format(rawHex: String): String {
        require(rawHex.length == FINGERPRINT_BYTES * 2) {
            "Expected ${FINGERPRINT_BYTES * 2} hex characters, got ${rawHex.length}"
        }
        return buildString(rawHex.length + 6) {
            append(rawHex, 0, 4)
            append(" · ")
            append(rawHex, 4, 8)
            append(" · ")
            append(rawHex, 8, 12)
        }
    }

    /**
     * Constant-time comparison of two fingerprints. Use this rather than
     * `==` to avoid timing oracles on the (small) chance an attacker can
     * observe comparison latency. For 12-character strings this is mostly
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
