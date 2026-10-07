package com.omsingh.telepad.core.wifi

import java.net.URLDecoder
import java.util.Base64

/**
 * What the QR code on the PC's screen says: which PC it is, how to reach it, and the one-time token that
 * lets this phone pair although the PC is not otherwise open to new phones.
 *
 * It is a link, `telepad://pair?v=1&k=...&t=...&p=5000&h=192.168.1.20&n=DESKTOP-PC`:
 *
 *  - `v` the format version (this reads 1; anything newer needs a newer app)
 *  - `k` the PC's public key, 32 bytes, base64url without padding. It comes from the PC's own screen, so there is
 *    no fingerprint to compare by eye: seeing the code is the proof.
 *  - `t` the one-time pairing token, 16 bytes, base64url without padding
 *  - `p` the UDP port
 *  - `h` the PC's IPv4 addresses, comma separated, best first
 *  - `n` the PC's name, percent-encoded
 *
 * Fields that are not known are ignored, so a newer PC can add some without breaking this app.
 * The same format is written by the server (`invite.rs`).
 */
class PairingInvite(
    val publicKey: ByteArray,
    val token: ByteArray,
    val port: Int,
    val hosts: List<String>,
    val name: String?,
) {
    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(publicKey)

    /** Arrays compare by contents, so that two reads of one code are equal. */
    override fun equals(other: Any?): Boolean =
        other is PairingInvite &&
            publicKey.contentEquals(other.publicKey) && token.contentEquals(other.token) &&
            port == other.port && hosts == other.hosts && name == other.name

    override fun hashCode(): Int =
        ((publicKey.contentHashCode() * 31 + token.contentHashCode()) * 31 + port) * 31 + hosts.hashCode() + (name?.hashCode() ?: 0)

    /** The token is a secret that the code's owner holds for a few minutes: it is never printed. */
    override fun toString(): String = "PairingInvite(name=$name, hosts=$hosts, port=$port)"

    /** How reading a piece of text went. */
    sealed interface Read {
        /** A Telepad code that can be used. */
        data class Valid(val invite: PairingInvite) : Read

        /** Some other text or link: a code for something else. */
        data object NotTelepad : Read

        /** A Telepad code in a newer format than this app understands. */
        data object NeedsNewerApp : Read

        /** Looks like a Telepad code but is damaged or incomplete. */
        data object Damaged : Read
    }

    companion object {
        const val PREFIX = "telepad://pair?"
        private const val FORMAT_VERSION = 1
        private const val KEY_BYTES = 32
        private const val TOKEN_BYTES = 16
        private const val MAX_HOSTS = 8
        private const val MAX_NAME_CHARS = 64

        fun parse(text: String): Read {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return Read.NotTelepad

            val fields = HashMap<String, String>()
            for (pair in trimmed.substring(PREFIX.length).split('&')) {
                val at = pair.indexOf('=')
                if (at <= 0) continue
                fields.putIfAbsent(pair.substring(0, at), pair.substring(at + 1))
            }

            // A missing version is read as the first: the field only matters once there is a second.
            val version = fields["v"]?.toIntOrNull() ?: if (fields.containsKey("v")) return Read.Damaged else FORMAT_VERSION
            if (version > FORMAT_VERSION) return Read.NeedsNewerApp
            if (version < FORMAT_VERSION) return Read.Damaged

            val key = decodeBase64(fields["k"], KEY_BYTES) ?: return Read.Damaged
            val token = decodeBase64(fields["t"], TOKEN_BYTES) ?: return Read.Damaged
            val port = fields["p"]?.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toIntOrNull()
                ?.takeIf { it in 1..65535 } ?: return Read.Damaged

            // Only addresses that really are addresses: the rest are dropped, not trusted.
            val hosts = fields["h"].orEmpty().split(',')
                .map(String::trim)
                .filter { it.isNotEmpty() && AddressInput.isValidHost(it) }
                .distinct()
                .take(MAX_HOSTS)

            val name = fields["n"]
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.filter { !it.isISOControl() }
                ?.trim()
                ?.take(MAX_NAME_CHARS)
                ?.takeIf { it.isNotEmpty() }

            return Read.Valid(PairingInvite(key, token, port, hosts, name))
        }

        private fun decodeBase64(text: String?, expectedBytes: Int): ByteArray? {
            if (text.isNullOrEmpty()) return null
            val bytes = try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return bytes.takeIf { it.size == expectedBytes }
        }
    }
}
