package com.omsingh.telepad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun v(text: String) = Version.parse(text)!!

    /** A feed with these tags, in the shape GitHub writes it. */
    private fun feedOf(vararg tags: String): String {
        val entries = tags.joinToString("") { tag ->
            """
            <entry>
              <id>tag:github.com,2008:Repository/1/$tag</id>
              <updated>2026-10-07T22:39:05Z</updated>
              <link rel="alternate" type="text/html" href="https://github.com/omsingh02/telepad/releases/tag/$tag"/>
              <title>Telepad $tag</title>
              <content type="html">&lt;p&gt;notes&lt;/p&gt;</content>
            </entry>
            """.trimIndent() + "\n"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>tag:github.com,2008:https://github.com/omsingh02/telepad/releases</id>
              <link type="text/html" rel="alternate" href="https://github.com/omsingh02/telepad/releases"/>
              <title>Release notes from telepad</title>
            $entries</feed>
        """.trimIndent()
    }

    @Test fun `it reads the tags of the releases in the feed`() {
        val tags = Releases.parse(feedOf("v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"))!!.map { it.tag }
        assertEquals(listOf("v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"), tags)
    }

    @Test fun `what the notes say cannot add a release`() {
        val evil = """<entry><link rel="alternate" href="https://github.com/omsingh02/telepad/releases/tag/v9.9.9"/></entry>"""
        val escaped = feedOf("v2.0.0-alpha.3").replace("notes", evil.replace("<", "&lt;").replace(">", "&gt;"))
        assertEquals(1, Releases.parse(escaped)!!.size)
        // Unescaped markup inside <content> is cut out before looking.
        val raw = feedOf("v2.0.0-alpha.3").replace("&lt;p&gt;notes&lt;/p&gt;", evil)
        assertEquals(listOf("v2.0.0-alpha.3"), Releases.parse(raw)!!.map { it.tag })
    }

    @Test fun `tags that are not versions are left out and so are links to elsewhere`() {
        val feed = feedOf("nightly", "2.0.0-alpha.2", "v2.0.0-alpha.1", "v1.0", "v2.0.0-alpha.3")
        assertEquals(listOf("v2.0.0-alpha.1", "v2.0.0-alpha.3"), Releases.parse(feed)!!.map { it.tag })
        val elsewhere = feedOf("v2.0.0").replace("github.com/omsingh02/telepad", "evil.example/x")
        assertTrue(Releases.parse(elsewhere)!!.isEmpty())
    }

    @Test fun `addresses are made here from the tag and the name and never copied`() {
        val release = Releases.forTag("v2.0.0-alpha.4")!!
        assertEquals("https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.4", release.page)
        val apk = release.asset("telepad-android-v2.0.0-alpha.4.apk")!!
        assertEquals(
            "https://github.com/omsingh02/telepad/releases/download/v2.0.0-alpha.4/telepad-android-v2.0.0-alpha.4.apk",
            apk.url,
        )
        assertEquals("SHA256SUMS", release.sums.name)
    }

    @Test fun `a file whose name could lead out of the download folder is refused`() {
        val release = Releases.forTag("v2.0.0")!!
        listOf("../../etc/passwd", ".hidden", "has space.apk", "a/b", "", "a\\b").forEach {
            assertNull(it, release.asset(it))
        }
    }

    @Test fun `it picks the newest that the person should hear about`() {
        val releases = Releases.parse(feedOf("v2.0.0-alpha.4", "v2.0.0-alpha.3", "v1.0.1"))!!
        fun tag(current: String) = Releases.newestFor(releases, v(current))?.tag
        assertEquals("v2.0.0-alpha.4", tag("2.0.0-alpha.3"))
        assertEquals("v2.0.0-alpha.4", tag("2.0.0-alpha.1"))
        assertNull("already on it", tag("2.0.0-alpha.4"))
        assertNull("a release is not moved back onto alphas", tag("2.0.0"))
        assertEquals("an old release hears about releases only", "v1.0.1", tag("1.0.0"))
    }

    @Test fun `what is not a feed is null and an empty feed is not`() {
        assertNull(Releases.parse("<html>rate limited</html>"))
        assertNull(Releases.parse("""{"message":"Not Found"}"""))
        assertEquals(emptyList<Releases.Release>(), Releases.parse(feedOf()))
    }

    @Test fun `the apk is named the way the release names it`() {
        assertEquals("telepad-android-v2.0.0-alpha.3.apk", Releases.apkName(v("2.0.0-alpha.3")))
        assertEquals("telepad-android-v2.1.0.apk", Releases.apkName(v("2.1.0")))
    }

    @Test fun `this projects feed and downloads are owned and nothing near them`() {
        assertTrue(Releases.owns(Releases.LIST_URL))
        assertTrue(Releases.owns("https://github.com/omsingh02/telepad/releases/download/v2.0.0/x.apk"))
        listOf(
            "https://evil.example/telepad",
            "http://github.com/omsingh02/telepad/releases.atom",
            "https://github.com/someone-else/telepad/releases.atom",
            "https://github.com/omsingh02/telepad/archive/main.zip",
            "https://github.com.evil.example/omsingh02/telepad/releases/download/v1/x",
            "https://api.github.com/repos/omsingh02/telepad/releases",
            "file:///etc/passwd",
        ).forEach { assertFalse(it, Releases.owns(it)) }
        assertNotNull(Releases.parse(feedOf()))
    }
}
