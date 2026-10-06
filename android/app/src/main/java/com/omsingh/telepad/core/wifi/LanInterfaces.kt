package com.omsingh.telepad.core.wifi

/**
 * Which of the phone's network interfaces are the local network.
 *
 * A phone has several at once: Wi-Fi, a hotspot, USB tethering, mobile data, a VPN
 * tunnel. Discovery has to listen and probe on the ones a PC can be reached through,
 * and stay off mobile data. Interface names are not standardised, so this works from
 * the usual Android and Linux naming and falls back to "anything that is not clearly
 * mobile or a tunnel" when a manufacturer picked unfamiliar names.
 */
object LanInterfaces {

    private val LAN_PREFIXES = listOf("wlan", "wifi", "wl", "ap", "swlan", "softap", "eth", "en", "rndis", "usb", "bt-pan", "p2p")
    private val NOT_LAN_PREFIXES = listOf("rmnet", "ccmni", "v4-", "clat", "tun", "tap", "ppp", "dummy", "ipsec", "lo", "wg", "tailscale")

    fun isLan(name: String): Boolean {
        val n = name.lowercase()
        return LAN_PREFIXES.any { n.startsWith(it) } && !isNotLan(n)
    }

    fun isNotLan(name: String): Boolean {
        val n = name.lowercase()
        return NOT_LAN_PREFIXES.any { n.startsWith(it) }
    }

    /** The subset of [names] to use: known LAN interfaces, or else everything not clearly something else. */
    fun pick(names: List<String>): List<String> {
        val lan = names.filter(::isLan)
        return lan.ifEmpty { names.filterNot(::isNotLan) }
    }
}
