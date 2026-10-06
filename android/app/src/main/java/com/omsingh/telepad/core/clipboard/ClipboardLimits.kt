package com.omsingh.telepad.core.clipboard

/**
 * Largest clipboard text exchanged with the PC, in UTF-8 bytes.
 *
 * A clipboard message travels in a single UDP datagram. The dispatcher's 2 KiB
 * buffers cannot hold more than ~2 KB, and beyond a typical path MTU (~1.4 KB)
 * datagrams are fragmented and routinely dropped on Wi-Fi, so anything larger
 * would vanish. The desktop server clips what it sends back to the same size.
 */
const val MAX_CLIPBOARD_UTF8_BYTES = 1200

/**
 * The longest prefix of this string whose UTF-8 encoding is at most [maxBytes]
 * bytes. Never splits a character: a code point that would cross the limit is
 * left out whole, including surrogate pairs (emoji) that Kotlin stores as two
 * `Char`s.
 */
fun String.clipToUtf8Bytes(maxBytes: Int): String {
    var bytes = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) return substring(0, index)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return this
}
