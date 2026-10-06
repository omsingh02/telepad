package com.omsingh.telepad.core.wifi

/**
 * Reads the address and port a person typed in to reach a PC.
 *
 * Forgiving about what is harmless (spaces, an empty port meaning the default) and
 * strict about what would only fail later and confusingly (a stray character, a port
 * out of range).
 */
object AddressInput {

    sealed interface Result {
        data class Valid(val host: String, val port: Int) : Result
        data object InvalidHost : Result
        data object InvalidPort : Result
    }

    fun parse(hostText: String, portText: String, defaultPort: Int = DiscoveryPackets.PORT): Result {
        val host = hostText.trim()
        if (!isValidHost(host)) return Result.InvalidHost

        val portDigits = portText.trim()
        val port = if (portDigits.isEmpty()) {
            defaultPort
        } else {
            if (portDigits.length > 5 || !portDigits.all { it in '0'..'9' }) return Result.InvalidPort
            portDigits.toInt()
        }
        if (port !in 1..65535) return Result.InvalidPort
        return Result.Valid(host, port)
    }

    /** A dotted IPv4 address, or a hostname such as `desk.local` or `my-pc`. */
    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.all { it.isDigit() || it == '.' }) return isValidIpv4(host)
        return host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
    }

    private fun isValidIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.toInt() in 0..255 }
    }
}
