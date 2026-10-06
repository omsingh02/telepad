package com.omsingh.telepad.core.wifi

import com.omsingh.telepad.core.wifi.AddressInput.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressInputTest {

    private fun parse(host: String, port: String = "") = AddressInput.parse(host, port)

    @Test
    fun `an ip address with no port uses the default`() {
        assertEquals(Result.Valid("192.168.1.20", 5000), parse("192.168.1.20"))
    }

    @Test
    fun `a port can be given`() {
        assertEquals(Result.Valid("10.0.0.2", 6001), parse("10.0.0.2", "6001"))
    }

    @Test
    fun `surrounding spaces are ignored`() {
        assertEquals(Result.Valid("192.168.1.20", 5000), parse("  192.168.1.20 ", " 5000 "))
    }

    @Test
    fun `host names are accepted`() {
        assertEquals(Result.Valid("desk.local", 5000), parse("desk.local"))
        assertEquals(Result.Valid("my-pc", 5000), parse("my-pc"))
        assertEquals(Result.Valid("a.b-c.example.com", 5000), parse("a.b-c.example.com"))
    }

    @Test
    fun `malformed addresses are refused`() {
        for (bad in listOf("", "  ", "192.168.1", "192.168.1.256", "1.2.3.4.5", "192.168.1.", ".1.2.3", "-pc", "pc-", "my pc", "pc/path", "http://pc", "pc:5000", "pc_name", "ünïcode")) {
            assertEquals("'$bad'", Result.InvalidHost, parse(bad))
        }
    }

    @Test
    fun `ports must be numbers in range`() {
        for (bad in listOf("0", "65536", "99999", "-1", "5000a", "50 00", "123456", "1.5")) {
            assertEquals("'$bad'", Result.InvalidPort, parse("10.0.0.2", bad))
        }
        assertEquals(Result.Valid("10.0.0.2", 1), parse("10.0.0.2", "1"))
        assertEquals(Result.Valid("10.0.0.2", 65535), parse("10.0.0.2", "65535"))
    }

    @Test
    fun `a bad host is reported before a bad port`() {
        assertEquals(Result.InvalidHost, parse("???", "0"))
    }

    @Test
    fun `host validity can be asked on its own`() {
        assertTrue(AddressInput.isValidHost("127.0.0.1"))
        assertFalse(AddressInput.isValidHost("999.0.0.1"))
        assertFalse(AddressInput.isValidHost("x".repeat(300)))
    }
}
