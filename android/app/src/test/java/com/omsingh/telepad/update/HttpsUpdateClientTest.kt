package com.omsingh.telepad.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class HttpsUpdateClientTest {
    private val client = HttpsUpdateClient("Telepad-Android/test")

    @Test fun `it goes nowhere but this projects releases`() {
        listOf(
            "https://evil.example/telepad",
            "http://api.github.com/repos/omsingh02/telepad/releases",
            "https://api.github.com/repos/someone-else/telepad/releases",
            "https://github.com/omsingh02/telepad/archive/main.zip",
            "https://api.github.com.evil.example/repos/omsingh02/telepad/",
            "file:///etc/passwd",
        ).forEach { url ->
            assertThrows(url, FetchFailure.Interrupted::class.java) { client.getText(url, 10) }
        }
    }

    @Test fun `a file is copied whole and progress ends at the whole`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        var last = 0L to (null as Long?)
        HttpsUpdateClient.copy(data.inputStream(), out, limit = 1_000_000, total = data.size.toLong()) { done, total -> last = done to total }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong() to data.size.toLong(), last)
    }

    @Test fun `a file that is larger than the limit is cut off, whatever it says its size is`() {
        val out = ByteArrayOutputStream()
        assertThrows(FetchFailure.TooLarge::class.java) {
            HttpsUpdateClient.copy(ByteArray(5000).inputStream(), out, limit = 1000, total = null) { _, _ -> }
        }
        assertEquals("never more than the limit was written", true, out.size() <= 1000)
    }

    @Test fun `a connection that closes early is not a finished download`() {
        val out = ByteArrayOutputStream()
        assertThrows(FetchFailure.Interrupted::class.java) {
            HttpsUpdateClient.copy(ByteArray(100).inputStream(), out, limit = 10_000, total = 5000) { _, _ -> }
        }
    }
}
