package com.omsingh.telepad.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingInviteTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val token = ByteArray(16) { (0xA0 + it).toByte() }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun link(
        v: String? = "1",
        k: String? = b64(key),
        t: String? = b64(token),
        p: String? = "5000",
        h: String? = "192.168.1.20",
        n: String? = "DESKTOP-PC",
        extra: String = "",
    ): String {
        val fields = listOfNotNull(
            v?.let { "v=$it" }, k?.let { "k=$it" }, t?.let { "t=$it" },
            p?.let { "p=$it" }, h?.let { "h=$it" }, n?.let { "n=$it" },
        )
        return PairingInvite.PREFIX + (fields.joinToString("&") + extra)
    }

    private fun valid(text: String): PairingInvite =
        (PairingInvite.parse(text) as? PairingInvite.Read.Valid)?.invite ?: error("not valid: ${PairingInvite.parse(text)}")

    // ── A code the PC really printed ────────────────────────────────

    @Test
    fun `a link written by the real server is read`() {
        // Copied from the server's own output (`telepad-server`, run in a terminal), so that the two
        // implementations are known to agree on the format.
        val printed = "telepad://pair?v=1&k=fnAmD9d6yOTnzAOrejyusK8TBS5RLE2adzJ3PjmRSSU" +
            "&t=Qr0sz9kxtCEBwKtBn9ykkw&p=5057&h=10.10.189.161,100.95.242.28&n=deed"

        val invite = valid(printed)

        assertEquals(32, invite.publicKey.size)
        assertEquals(16, invite.token.size)
        assertEquals(5057, invite.port)
        assertEquals(listOf("10.10.189.161", "100.95.242.28"), invite.hosts)
        assertEquals("deed", invite.name)
        assertEquals("fnAmD9d6yOTnzAOrejyusK8TBS5RLE2adzJ3PjmRSSU", b64(invite.publicKey))
    }

    // ── Reading the fields ──────────────────────────────────────────

    @Test
    fun `all the fields are read`() {
        val invite = valid(link())
        assertTrue(key.contentEquals(invite.publicKey))
        assertTrue(token.contentEquals(invite.token))
        assertEquals(5000, invite.port)
        assertEquals(listOf("192.168.1.20"), invite.hosts)
        assertEquals("DESKTOP-PC", invite.name)
        assertEquals(java.util.Base64.getEncoder().encodeToString(key), invite.publicKeyBase64)
    }

    @Test
    fun `a name with spaces and accents is decoded`() {
        assertEquals("Om's PC & café", valid(link(n = "Om%27s%20PC%20%26%20caf%C3%A9")).name)
    }

    @Test
    fun `no addresses or no name is fine`() {
        val invite = valid(link(h = null, n = null))
        assertEquals(emptyList<String>(), invite.hosts)
        assertNull(invite.name)
    }

    @Test
    fun `fields that are not known are ignored`() {
        assertEquals("DESKTOP-PC", valid(link(extra = "&future=1&x=%FF")).name)
    }

    @Test
    fun `something that is not an address is dropped from the list, not trusted`() {
        val invite = valid(link(h = "javascript:alert(1),192.168.1.5,,300.1.1.1,my-pc.local"))
        assertEquals(listOf("192.168.1.5", "my-pc.local"), invite.hosts)
    }

    @Test
    fun `a name cannot carry control characters or run on`() {
        assertEquals("ab", valid(link(n = "a%0D%0Ab")).name)
        assertEquals(64, valid(link(n = "x".repeat(300))).name?.length)
    }

    @Test
    fun `surrounding spaces and a capital scheme are tolerated`() {
        assertEquals(5000, valid("  " + link().replace("telepad://pair", "TELEPAD://PAIR") + "\n").port)
    }

    // ── Refusing what is wrong ──────────────────────────────────────

    @Test
    fun `text that is not a Telepad link is not one`() {
        for (text in listOf("", "hello", "https://example.org/pair?k=1", "telepad://other?k=1", "WIFI:S:home;T:WPA;P:pw;;")) {
            assertEquals(text, PairingInvite.Read.NotTelepad, PairingInvite.parse(text))
        }
    }

    @Test
    fun `a newer format needs a newer app`() {
        assertEquals(PairingInvite.Read.NeedsNewerApp, PairingInvite.parse(link(v = "2")))
        assertEquals(PairingInvite.Read.NeedsNewerApp, PairingInvite.parse(link(v = "9")))
    }

    @Test
    fun `a missing version is read as the first, a garbled one is damaged`() {
        assertEquals(5000, valid(link(v = null)).port)
        assertEquals(PairingInvite.Read.Damaged, PairingInvite.parse(link(v = "one")))
        assertEquals(PairingInvite.Read.Damaged, PairingInvite.parse(link(v = "0")))
    }

    @Test
    fun `a key or token of the wrong length, or not base64, is damaged`() {
        val damaged = PairingInvite.Read.Damaged
        assertEquals(damaged, PairingInvite.parse(link(k = null)))
        assertEquals(damaged, PairingInvite.parse(link(t = null)))
        assertEquals(damaged, PairingInvite.parse(link(k = b64(ByteArray(31)))))
        assertEquals(damaged, PairingInvite.parse(link(k = b64(ByteArray(33)))))
        assertEquals(damaged, PairingInvite.parse(link(t = b64(ByteArray(15)))))
        assertEquals(damaged, PairingInvite.parse(link(t = "!!!not-base64!!!")))
        assertEquals(damaged, PairingInvite.parse(link(k = "")))
    }

    @Test
    fun `a port that is missing or out of range is damaged`() {
        val damaged = PairingInvite.Read.Damaged
        for (port in listOf(null, "0", "65536", "99999", "-5", "50 00", "abc", "123456", "")) {
            assertEquals(port, damaged, PairingInvite.parse(link(p = port)))
        }
        assertEquals(65535, valid(link(p = "65535")).port)
        assertEquals(1, valid(link(p = "1")).port)
    }

    @Test
    fun `the first of a repeated field wins, so one cannot be slipped in behind it`() {
        val other = b64(ByteArray(32) { 9 })
        val invite = valid(link() + "&k=$other")
        assertTrue(key.contentEquals(invite.publicKey))
    }

    // ── Equality and secrecy ────────────────────────────────────────

    @Test
    fun `two reads of one code are equal`() {
        assertEquals(valid(link()), valid(link()))
        assertEquals(valid(link()).hashCode(), valid(link()).hashCode())
        assertNotEquals(valid(link()), valid(link(p = "5001")))
        assertNotEquals(valid(link()), valid(link(t = b64(ByteArray(16) { 1 }))))
    }

    @Test
    fun `the token is never in its printed form`() {
        val printed = valid(link()).toString()
        assertFalse(printed.contains(b64(token)))
        assertFalse(printed.contains(b64(key)))
        assertTrue(printed.contains("DESKTOP-PC"))
    }
}
