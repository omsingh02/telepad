package com.omsingh.telepad.core.host

import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {

    private val windows = HostProfile(HostOs.WINDOWS)
    private val mac = HostProfile(HostOs.MACOS)
    private val linux = HostProfile(HostOs.LINUX)

    @Test
    fun `unknown hosts behave exactly like Windows`() {
        val unknown = HostProfile(HostOs.UNKNOWN)
        assertEquals(HostOs.WINDOWS, unknown.os)
        for (id in ShortcutId.values()) {
            assertEquals(windows.chord(id), unknown.chord(id))
        }
        assertEquals(windows.meta, unknown.meta)
    }

    @Test
    fun `modifier keys are named the way the host names them`() {
        assertEquals("Win", windows.meta.symbol)
        assertEquals("Windows", windows.meta.spokenName)
        assertEquals("⌘", mac.meta.symbol)
        assertEquals("Command", mac.meta.spokenName)
        assertEquals("Super", linux.meta.symbol)
        assertEquals("⌥", mac.alt.symbol)
        assertEquals("Option", mac.alt.spokenName)
        assertEquals("Alt", linux.alt.symbol)
        assertEquals("⌃", mac.ctrl.symbol)
        assertEquals("Ctrl", windows.ctrl.symbol)
        assertEquals("Control", linux.ctrl.spokenName)
    }

    @Test
    fun `copy and paste use the primary modifier of each OS`() {
        assertEquals(HidModifierMask.LEFT_CTRL, windows.chord(ShortcutId.COPY).modifiers)
        assertEquals(HidModifierMask.LEFT_CTRL, linux.chord(ShortcutId.PASTE).modifiers)
        // On a Mac the phone sends Command (the server turns HID left-GUI into Command).
        assertEquals(HidModifierMask.LEFT_META, mac.chord(ShortcutId.COPY).modifiers)
        assertEquals(HidKeyCodes.C, mac.chord(ShortcutId.COPY).keyCode)
        assertEquals(HidKeyCodes.V, mac.chord(ShortcutId.PASTE).keyCode)
    }

    @Test
    fun `redo differs between Windows and the others`() {
        assertEquals(Chord(HidKeyCodes.Y, HidModifierMask.LEFT_CTRL), windows.chord(ShortcutId.REDO))
        assertEquals(
            Chord(HidKeyCodes.Z, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT),
            linux.chord(ShortcutId.REDO)
        )
        assertEquals(
            Chord(HidKeyCodes.Z, HidModifierMask.LEFT_META or HidModifierMask.LEFT_SHIFT),
            mac.chord(ShortcutId.REDO)
        )
    }

    @Test
    fun `app switching is Command-Tab on a Mac and Alt-Tab elsewhere`() {
        assertEquals(Chord(HidKeyCodes.TAB, HidModifierMask.LEFT_META), mac.chord(ShortcutId.SWITCH_APP))
        assertEquals(Chord(HidKeyCodes.TAB, HidModifierMask.LEFT_ALT), windows.chord(ShortcutId.SWITCH_APP))
        assertEquals(Chord(HidKeyCodes.TAB, HidModifierMask.LEFT_ALT), linux.chord(ShortcutId.SWITCH_APP))
    }

    @Test
    fun `refresh is Command-R on a Mac and F5 elsewhere`() {
        assertEquals(Chord(HidKeyCodes.R, HidModifierMask.LEFT_META), mac.chord(ShortcutId.REFRESH))
        assertEquals(Chord(HidKeyCodes.F5, 0), windows.chord(ShortcutId.REFRESH))
    }

    @Test
    fun `every shortcut maps to a real key and macOS never uses Control for them`() {
        for (id in ShortcutId.values()) {
            for (profile in listOf(windows, mac, linux)) {
                val chord = profile.chord(id)
                assertTrue("$id has no key", chord.keyCode != 0)
            }
            val macMods = mac.chord(id).modifiers
            assertEquals("$id must not use Control on a Mac", 0, macMods and HidModifierMask.LEFT_CTRL)
        }
    }

    @Test
    fun `chords render the way users read them`() {
        assertEquals("Ctrl+C", windows.describe(windows.chord(ShortcutId.COPY)))
        assertEquals("⌘C", mac.describe(mac.chord(ShortcutId.COPY)))
        assertEquals("Ctrl+Shift+Z", linux.describe(linux.chord(ShortcutId.REDO)))
        assertEquals("⇧⌘Z".length, mac.describe(mac.chord(ShortcutId.REDO)).length)
        assertEquals("Alt+Tab", windows.describe(windows.chord(ShortcutId.SWITCH_APP)))
        assertEquals("⌘Tab", mac.describe(mac.chord(ShortcutId.SWITCH_APP)))
        assertEquals("F5", windows.describe(windows.chord(ShortcutId.REFRESH)))
    }

    @Test
    fun `key names cover letters digits function keys and common keys`() {
        assertEquals("A", HostProfile.keyName(HidKeyCodes.A))
        assertEquals("Z", HostProfile.keyName(HidKeyCodes.Z))
        assertEquals("1", HostProfile.keyName(HidKeyCodes.NUM_1))
        assertEquals("0", HostProfile.keyName(HidKeyCodes.NUM_0))
        assertEquals("F1", HostProfile.keyName(HidKeyCodes.F1))
        assertEquals("F12", HostProfile.keyName(HidKeyCodes.F12))
        assertEquals("Tab", HostProfile.keyName(HidKeyCodes.TAB))
        assertEquals("Esc", HostProfile.keyName(HidKeyCodes.ESC))
    }

    // ── host info wire codec ────────────────────────────────────────────

    private fun reply(os: Int, caps: Int, v: Triple<Int, Int, Int> = Triple(2, 1, 7)) = byteArrayOf(
        HostInfoCodec.MSG_HOST_INFO, os.toByte(), caps.toByte(),
        v.first.toByte(), v.second.toByte(), v.third.toByte()
    )

    @Test
    fun `host info replies decode`() {
        val info = HostInfoCodec.parse(reply(os = 3, caps = 0x02), 6)!!
        assertEquals(HostOs.LINUX, info.os)
        assertTrue(info.capabilities.clipboard)
        assertTrue(!info.capabilities.nowPlaying)
        assertEquals("2.1.7", info.version)
    }

    @Test
    fun `every os byte decodes and unknown ones degrade gracefully`() {
        assertEquals(HostOs.WINDOWS, HostInfoCodec.parse(reply(1, 0), 6)!!.os)
        assertEquals(HostOs.MACOS, HostInfoCodec.parse(reply(2, 0), 6)!!.os)
        assertEquals(HostOs.LINUX, HostInfoCodec.parse(reply(3, 0), 6)!!.os)
        assertEquals(HostOs.UNKNOWN, HostInfoCodec.parse(reply(0, 0), 6)!!.os)
        assertEquals(HostOs.UNKNOWN, HostInfoCodec.parse(reply(250, 0), 6)!!.os)
    }

    @Test
    fun `capability bits are independent`() {
        assertTrue(HostInfoCodec.parse(reply(1, 0x01), 6)!!.capabilities.nowPlaying)
        assertTrue(!HostInfoCodec.parse(reply(1, 0x01), 6)!!.capabilities.clipboard)
        val both = HostInfoCodec.parse(reply(1, 0x03), 6)!!.capabilities
        assertTrue(both.nowPlaying && both.clipboard)
        val none = HostInfoCodec.parse(reply(1, 0x00), 6)!!.capabilities
        assertTrue(!none.nowPlaying && !none.clipboard)
    }

    @Test
    fun `trailing fields from a newer server are ignored`() {
        val longer = reply(2, 0x03) + byteArrayOf(9, 9, 9)
        val info = HostInfoCodec.parse(longer, longer.size)!!
        assertEquals(HostOs.MACOS, info.os)
        assertEquals("2.1.7", info.version)
    }

    @Test
    fun `malformed or unrelated messages are rejected`() {
        assertNull(HostInfoCodec.parse(reply(1, 0), 5))                      // truncated
        assertNull(HostInfoCodec.parse(ByteArray(0), 0))
        assertNull(HostInfoCodec.parse(byteArrayOf(0x80.toByte(), 1, 2, 3, 4, 5), 6)) // clipboard data
        assertNull(HostInfoCodec.parse(reply(1, 0), 99))                     // length lies about the buffer
        assertNotEquals(HostInfoCodec.MSG_HOST_INFO, HostInfoCodec.MSG_HOST_INFO_QUERY)
    }

    @Test
    fun `version bytes above 127 are not read as negative numbers`() {
        val info = HostInfoCodec.parse(reply(1, 0, Triple(200, 255, 128)), 6)!!
        assertEquals("200.255.128", info.version)
    }

    // ── System chords (needed when only key presses can be sent) ─────────

    @Test
    fun `lock screen is the standard shortcut of each OS`() {
        assertEquals(Chord(HidKeyCodes.L, HidModifierMask.LEFT_META), windows.lockChord)
        assertEquals(Chord(HidKeyCodes.L, HidModifierMask.LEFT_META), linux.lockChord)
        assertEquals(
            Chord(HidKeyCodes.Q, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_META),
            mac.lockChord,
        )
    }

    @Test
    fun `windows has a shortcut for every action except opening a browser`() {
        for (action in InputEvent.SystemAction.values()) {
            val chord = windows.systemChord(action)
            if (action == InputEvent.SystemAction.BROWSER) assertNull(chord) else assertTrue("$action", chord != null)
        }
        assertEquals(Chord(HidKeyCodes.D, HidModifierMask.LEFT_META), windows.systemChord(InputEvent.SystemAction.SHOW_DESKTOP))
        assertEquals(
            Chord(HidKeyCodes.ESC, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT),
            windows.systemChord(InputEvent.SystemAction.TASK_MANAGER),
        )
    }

    @Test
    fun `mac shortcuts use Command and Control the way macOS does`() {
        assertEquals(Chord(HidKeyCodes.UP, HidModifierMask.LEFT_CTRL), mac.systemChord(InputEvent.SystemAction.TASK_VIEW))
        assertEquals(Chord(HidKeyCodes.F11), mac.systemChord(InputEvent.SystemAction.SHOW_DESKTOP))
        assertNull(mac.systemChord(InputEvent.SystemAction.FILE_MANAGER))
        assertNull(mac.systemChord(InputEvent.SystemAction.BROWSER))
    }

    @Test
    fun `linux offers only what the common desktops share`() {
        assertEquals(Chord(HidKeyCodes.PRINT_SCREEN), linux.systemChord(InputEvent.SystemAction.SCREENSHOT))
        assertEquals(Chord(HidKeyCodes.LEFT_META), linux.systemChord(InputEvent.SystemAction.TASK_VIEW))
        assertNull(linux.systemChord(InputEvent.SystemAction.TASK_MANAGER))
    }

    @Test
    fun `unknown hosts get the windows shortcuts`() {
        val unknown = HostProfile(HostOs.UNKNOWN)
        for (action in InputEvent.SystemAction.values()) {
            assertEquals(windows.systemChord(action), unknown.systemChord(action))
        }
        assertEquals(windows.lockChord, unknown.lockChord)
    }

    @Test
    fun `new chords have readable names`() {
        assertEquals("Win+Shift+S", windows.describe(windows.systemChord(InputEvent.SystemAction.SCREENSHOT)!!))
        assertEquals("⌃↑", mac.describe(mac.systemChord(InputEvent.SystemAction.TASK_VIEW)!!))
        assertEquals("PrtSc", linux.describe(linux.systemChord(InputEvent.SystemAction.SCREENSHOT)!!))
    }
}
