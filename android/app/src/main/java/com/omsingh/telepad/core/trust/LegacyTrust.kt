package com.omsingh.telepad.core.trust

/**
 * Older versions kept trust as `address -> public key` and, separately, a list of
 * "favourite" servers. This folds both into [PairedDevice]s so that nobody who
 * paired with an earlier version has to pair again.
 */
object LegacyTrust {

    /** A server from the old favourites list. */
    data class Favorite(val name: String, val host: String, val port: Int, val lastConnectedMs: Long)

    fun migrate(
        /** Old trust table: address (lower-cased) to base64 public key. */
        trustedByHost: Map<String, String>,
        favorites: List<Favorite>,
        defaultPort: Int = 5000,
        nowMs: Long = 0L,
    ): List<PairedDevice> {
        val byHost = favorites.associateBy { it.host.lowercase() }
        return trustedByHost.entries
            .filter { (_, key) -> key.isNotBlank() }
            // The same key trusted under two addresses is one PC.
            .distinctBy { (_, key) -> key }
            .map { (host, key) ->
                val favorite = byHost[host.lowercase()]
                PairedDevice(
                    publicKey = key,
                    name = favorite?.name?.takeIf { it.isNotBlank() } ?: host,
                    host = host,
                    port = favorite?.port ?: defaultPort,
                    pairedAtMs = favorite?.lastConnectedMs ?: nowMs,
                    lastConnectedMs = favorite?.lastConnectedMs ?: 0L,
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
