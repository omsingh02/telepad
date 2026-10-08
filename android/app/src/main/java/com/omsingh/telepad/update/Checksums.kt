package com.omsingh.telepad.update

import java.io.InputStream
import java.security.MessageDigest

/** `SHA256SUMS`, the list of checksums every release carries (the format `sha256sum` writes and checks). */
class Checksums private constructor(private val byName: Map<String, String>) {

    /** The checksum recorded for the file called [name], as 64 lower-case hex digits. */
    operator fun get(name: String): String? = byName[name]

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /** Reads the file's text. A line that is not `<64 hex digits>  <name>` is ignored. */
        fun parse(text: String): Checksums {
            val found = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val space = line.indexOf(' ')
                if (space < 0) continue
                val hash = line.substring(0, space)
                // Two spaces say "text", a space and a star say "binary": the same bytes either way here.
                val rest = line.substring(space + 1)
                val name = when {
                    rest.startsWith(" ") || rest.startsWith("*") -> rest.substring(1).trimEnd()
                    else -> continue
                }
                if (HEX64.matches(hash) && name.isNotEmpty()) found.putIfAbsent(name, hash.lowercase())
            }
            return Checksums(found)
        }

        /** The SHA-256 of everything [input] holds, as 64 lower-case hex digits. */
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
