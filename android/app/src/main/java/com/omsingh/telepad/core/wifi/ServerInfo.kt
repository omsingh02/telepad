package com.omsingh.telepad.core.wifi

/**
 * UI-level snapshot of a discovered (or saved) Wi-Fi server.
 *
 * [host] is the resolved IPv4 address — *not* a DNS name, because Telepad runs
 * over UDP on the LAN and there's no resolver in the loop after discovery.
 *
 * [pubkeyBase64] is only present once we've completed pairing. Discovery
 * doesn't include it (the server's PONG carries only `hostname` for display);
 * pairing fetches it via a dedicated handshake exchange and stores it in
 * `PairingStore` keyed by [host].
 */
data class ServerInfo(
    val name: String,
    val host: String,
    val port: Int,
    val pubkeyBase64: String? = null,
) {
    val displayLabel: String
        get() = if (name.isBlank()) "$host:$port" else name
}
