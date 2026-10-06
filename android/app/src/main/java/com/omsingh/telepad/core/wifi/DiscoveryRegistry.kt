package com.omsingh.telepad.core.wifi

/** A PC that announced itself on the network. */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    /** The PC's public key once it has been asked for it, base64; null until then. */
    val publicKey: String?,
    val lastSeenMs: Long,
)

/**
 * The list of PCs currently visible on the network.
 *
 * A server announces itself every few seconds and answers every probe, so being
 * heard recently is what "online" means. Entries not heard from within [ttlMs]
 * are dropped, so a PC that was switched off disappears instead of lingering
 * (the previous list only ever grew until the app restarted).
 *
 * A PC with several network interfaces announces from each, which would show it
 * twice. Once its public key is known, announcements with the same key are one
 * entry, represented by the address heard most recently.
 *
 * Time is passed in. Not thread-safe: confine to one thread or guard externally.
 */
class DiscoveryRegistry(private val ttlMs: Long = 15_000L) {

    private val byAddress = LinkedHashMap<String, DiscoveredServer>()

    /** Records an announcement. Returns true if the visible list may have changed. */
    fun onAnnouncement(host: String, port: Int, name: String, nowMs: Long): Boolean {
        val id = idOf(host, port)
        val existing = byAddress[id]
        val cleanName = name.trim().take(MAX_NAME_LENGTH).ifBlank { host }
        val updated = DiscoveredServer(
            name = cleanName,
            host = host,
            port = port,
            publicKey = existing?.publicKey,
            lastSeenMs = nowMs,
        )
        byAddress[id] = updated
        return existing == null || existing.name != cleanName
    }

    /** Whether the key of this address has not been asked for yet. */
    fun needsKey(host: String, port: Int): Boolean = byAddress[idOf(host, port)]?.publicKey == null

    fun onPublicKey(host: String, port: Int, publicKey: String) {
        val id = idOf(host, port)
        val existing = byAddress[id] ?: return
        byAddress[id] = existing.copy(publicKey = publicKey)
    }

    /** The visible servers, sorted by name then address. */
    fun snapshot(nowMs: Long): List<DiscoveredServer> {
        byAddress.values.removeAll { nowMs - it.lastSeenMs > ttlMs }

        val newestPerKey = LinkedHashMap<String, DiscoveredServer>()
        val unkeyed = ArrayList<DiscoveredServer>()
        for (server in byAddress.values) {
            val key = server.publicKey
            if (key == null) {
                unkeyed += server
            } else {
                val current = newestPerKey[key]
                if (current == null || server.lastSeenMs > current.lastSeenMs) newestPerKey[key] = server
            }
        }
        return (newestPerKey.values + unkeyed)
            .sortedWith(compareBy({ it.name.lowercase() }, { it.host }))
    }

    fun clear() = byAddress.clear()

    private fun idOf(host: String, port: Int) = "${host.lowercase()}:$port"

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
