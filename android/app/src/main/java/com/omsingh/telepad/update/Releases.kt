package com.omsingh.telepad.update

/**
 * What GitHub says about the releases, reduced to what an update needs, and trusting as little of it as can be.
 *
 * The list is the project's releases feed (`releases.atom`), an ordinary page of github.com, so it is not held to
 * the small hourly allowance that GitHub's programming interface gives an address: 60 requests, which a mobile
 * network shares between thousands of phones. Only one thing is read from it: which tags exist. The page of a
 * release and the address of each of its files are built here from the tag and the file's name, never taken from
 * the feed. (The desktop app does the same, with the same tests.)
 */
object Releases {
    const val REPOSITORY = "omsingh02/telepad"

    /** The feed of releases, pre-releases included. */
    const val LIST_URL = "https://github.com/$REPOSITORY/releases.atom"

    /** The start of a release's file address; the tag and the file's name follow. */
    const val DOWNLOAD_BASE = "https://github.com/$REPOSITORY/releases/download"

    private const val PAGE_BASE = "https://github.com/$REPOSITORY/releases/tag"

    /** A published release. */
    data class Release(
        val version: Version,
        /** The git tag, such as `v2.0.0-alpha.4`. */
        val tag: String,
        /** Its page, for what changed and for downloading by hand. */
        val page: String,
    ) {
        /** The file of this release called [name], at its address; `null` unless [name] is only a file name. */
        fun asset(name: String): Asset? =
            if (plainName(name)) Asset(name, size = 0, url = "$DOWNLOAD_BASE/$tag/$name") else null

        /** The list of checksums every release carries. */
        val sums: Asset get() = asset("SHA256SUMS")!!
    }

    /** A file attached to a release. [size] is 0 when it is not known (the download says). */
    data class Asset(val name: String, val size: Long, val url: String)

    /** The release with this tag, or `null` if the tag is not one the project writes (`v` and a version number). */
    fun forTag(tag: String): Release? {
        val version = Version.parse(tag) ?: return null
        if (tag != "v$version") return null
        return Release(version, tag, "$PAGE_BASE/$tag")
    }

    /** The file of release [version] that updates this app. */
    fun apkName(version: Version): String = "telepad-android-v$version.apk"

    /** A file name that is only a name: nothing that could put a download anywhere but where it is told to go. */
    private fun plainName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 && !name.startsWith('.') &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }

    /**
     * Reads the feed. The tag of each release is taken from the link to its page, and only a tag that is a version
     * number as the project writes them counts; anything else in the feed is left out, not an error. Returns
     * `null` when [feed] is not a feed at all.
     */
    fun parse(feed: String): List<Release>? {
        if ("<feed" !in feed) return null
        // What a release says about itself is written into the feed as escaped text, so it cannot add an entry or
        // a link; its notes are of no interest here, and are cut out before looking.
        val link = "href=\"$PAGE_BASE/"
        return withoutContent(feed).split("<entry>").drop(1).mapNotNull { part ->
            val entry = part.substringBefore("</entry>")
            val at = entry.indexOf(link)
            if (at < 0) return@mapNotNull null
            forTag(entry.substring(at + link.length).substringBefore('"'))
        }
    }

    /** [text] without anything between `<content` and `</content>`. */
    private fun withoutContent(text: String): String {
        val out = StringBuilder()
        var rest = text
        while (true) {
            val start = rest.indexOf("<content")
            if (start < 0) break
            out.append(rest, 0, start)
            val end = rest.indexOf("</content>", start)
            if (end < 0) {
                rest = ""
                break
            }
            rest = rest.substring(end + "</content>".length)
        }
        return out.append(rest).toString()
    }

    /** The latest release that [current] should hear about, if there is one. */
    fun newestFor(releases: List<Release>, current: Version): Release? =
        releases.filter { current.isOffered(it.version) }.maxByOrNull { it.version }

    /** Whether [url] is somewhere this project's feed and files are fetched from. */
    fun owns(url: String): Boolean = url == LIST_URL || url.startsWith("$DOWNLOAD_BASE/")
}
