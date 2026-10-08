package com.omsingh.telepad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumsTest {
    private val real = """
        07dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f  telepad-android-v2.0.0-alpha.3.apk
        8602407dc0a8c417c3e16a154fdf511055ef7df1d91ab4647c671327122a7159  telepad-v2.0.0-alpha.3-windows-x86_64-setup.exe
        d45f44c1d353049f894fe726402203a0e99228de9df49761a0e1db78cc54b9b8 *telepad-v2.0.0-alpha.3-windows-x86_64.exe
    """.trimIndent()

    @Test fun `it reads what sha256sum writes`() {
        val sums = Checksums.parse(real)
        assertEquals("07dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f", sums["telepad-android-v2.0.0-alpha.3.apk"])
        assertNotNull("binary mode", sums["telepad-v2.0.0-alpha.3-windows-x86_64.exe"])
        assertNull(sums["telepad-v9.9.9.apk"])
    }

    @Test fun `a line that is not a checksum is ignored not trusted`() {
        val sums = Checksums.parse(
            """
            short  a.bin
            zz7dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f  b.bin
            07dc84f18da40a4b56f1e93256020ae503c8f99b7ab6c00de913dfe5fc62dd2f c.bin

            # a comment
            """.trimIndent(),
        )
        listOf("a.bin", "b.bin", "c.bin").forEach { assertNull(it, sums[it]) }
    }

    @Test fun `the hash of a file is the one everybody knows`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.sha256("abc".byteInputStream()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Checksums.sha256("".byteInputStream()))
    }
}
