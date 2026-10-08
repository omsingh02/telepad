package com.omsingh.telepad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(text: String) = Version.parse(text)!!

    @Test fun `tags and plain numbers parse and print back the same`() {
        listOf(
            "2.0.0" to "2.0.0",
            "v2.0.0" to "2.0.0",
            "v2.0.0-alpha.4" to "2.0.0-alpha.4",
            "10.20.30-rc.1" to "10.20.30-rc.1",
            "1.0.0+build.5" to "1.0.0",
            "1.0.0-beta+exp.sha.5114f85" to "1.0.0-beta",
        ).forEach { (text, printed) -> assertEquals(text, printed, v(text).toString()) }
    }

    @Test fun `what is not a version is refused`() {
        listOf(
            "", "v", "2", "2.0", "2.0.x", "2.0.0.1", "-1.0.0", "2.0.0-", "2.0.0-alpha..1", "2.0.0-al pha",
            "latest", "2.0.0-alpha.", " 2.0.0", "99999999999999999999.0.0",
        ).forEach { assertNull("\"$it\" should not parse", Version.parse(it)) }
    }

    @Test fun `the order is the one in the semantic versioning rules`() {
        // The example chain from semver.org, section 11, then ours.
        val chain = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0-alpha.3", "2.0.0-alpha.4", "2.0.0", "2.0.1", "10.0.0",
        )
        chain.zipWithNext().forEach { (a, b) ->
            assertTrue("$a < $b", v(a) < v(b))
            assertTrue("$b > $a", v(b) > v(a))
        }
        assertEquals("build metadata does not count", v("1.0.0+a"), v("1.0.0+b"))
        assertTrue("numbers compare as numbers, not as text", v("2.0.0-alpha.10") > v("2.0.0-alpha.9"))
    }

    @Test fun `a person on an alpha hears about later alphas and the release`() {
        val on = v("2.0.0-alpha.3")
        assertTrue(on.isOffered(v("2.0.0-alpha.4")))
        assertTrue(on.isOffered(v("2.0.0-beta.1")))
        assertTrue(on.isOffered(v("2.0.0")))
        assertFalse("not itself", on.isOffered(v("2.0.0-alpha.3")))
        assertFalse("never backwards", on.isOffered(v("2.0.0-alpha.2")))
    }

    @Test fun `a person on a release hears only about releases`() {
        val on = v("2.0.0")
        assertTrue(on.isOffered(v("2.0.1")))
        assertTrue(on.isOffered(v("3.0.0")))
        assertFalse("not moved onto a pre-release", on.isOffered(v("2.1.0-rc.1")))
        assertFalse(on.isOffered(v("2.0.0-alpha.9")))
        assertFalse(on.isOffered(v("2.0.0")))
        assertFalse(on.isOffered(v("1.9.9")))
    }
}
