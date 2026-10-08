package com.omsingh.telepad.core.wifi

import java.net.Inet4Address
import java.net.NetworkInterface

/** One of the phone's own IPv4 networks: its address on it and how many leading bits are the network. */
data class LocalNetwork(val address: String, val prefixLength: Int)

/** What the phone's own networks are. A seam, so that tests can say. */
fun interface LocalNetworks {
    fun ipv4(): List<LocalNetwork>
}

/** The phone's real networks, read from the system: Wi-Fi, Ethernet and the like, not mobile data or tunnels. */
object SystemLocalNetworks : LocalNetworks {
    override fun ipv4(): List<LocalNetwork> = try {
        val up = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.interfaceAddresses.any { a -> a.address is Inet4Address } }
        val wanted = LanInterfaces.pick(up.map { it.name }).toSet()
        up.filter { it.name in wanted }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { ia ->
                val address = ia.address as? Inet4Address ?: return@mapNotNull null
                LocalNetwork(address.hostAddress ?: return@mapNotNull null, ia.networkPrefixLength.toInt())
            }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * What the phone's network says about a PC it could not reach, to say more than "check the network".
 *
 * If the PC's address is on one of the phone's own networks, the network is not the problem: the PC is probably
 * blocking Telepad (a firewall). If it is not, the two are on different networks, and that is the thing to fix.
 */
sealed interface NetworkHint {
    /** Nothing can be said: the PC has no address of the kind that can be compared. */
    data object Unknown : NetworkHint

    /** The phone is on no local network (Wi-Fi is off, or only mobile data is on). */
    data object NoNetwork : NetworkHint

    /** The PC is on the same network as the phone. */
    data class SameNetwork(val pcAddress: String) : NetworkHint

    /** The PC was last at [pcAddress], which is not on any of the phone's networks ([phoneAddress] is one of them). */
    data class DifferentNetwork(val phoneAddress: String, val pcAddress: String) : NetworkHint

    companion object {
        fun between(locals: List<LocalNetwork>, pcHost: String): NetworkHint {
            val pc = ipv4OrNull(pcHost) ?: return Unknown
            if (locals.isEmpty()) return NoNetwork
            for (local in locals) {
                val own = ipv4OrNull(local.address) ?: continue
                if (sameNetwork(own, pc, local.prefixLength)) return SameNetwork(pcHost)
            }
            val example = locals.first().address
            return DifferentNetwork(example, pcHost)
        }

        /** The same, for a PC with several addresses: the same network if any one of them is. */
        fun betweenAny(locals: List<LocalNetwork>, pcHosts: List<String>): NetworkHint {
            val hints = pcHosts.map { between(locals, it) }
            return hints.firstOrNull { it is SameNetwork } ?: hints.firstOrNull { it !is Unknown } ?: Unknown
        }

        private fun sameNetwork(a: Int, b: Int, prefix: Int): Boolean {
            val bits = prefix.coerceIn(0, 32)
            if (bits == 0) return true
            val mask = if (bits >= 32) -1 else (-1 shl (32 - bits))
            return (a and mask) == (b and mask)
        }

        /** "192.168.0.109" as a number, or null for a name, an IPv6 address or anything else. */
        private fun ipv4OrNull(text: String): Int? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            var result = 0
            for (part in parts) {
                val n = part.toIntOrNull()?.takeIf { it in 0..255 && part.length <= 3 } ?: return null
                result = (result shl 8) or n
            }
            return result
        }
    }
}
