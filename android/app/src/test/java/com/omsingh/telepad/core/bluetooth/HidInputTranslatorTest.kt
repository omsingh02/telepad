package com.omsingh.telepad.core.bluetooth

import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HidInputTranslatorTest {

    private class Report(val id: Int, val data: ByteArray) {
        val buttons get() = data[0].toInt() and 0xFF
        val dx get() = (data[1].toInt() and 0xFF) or (data[2].toInt() shl 8)
        val dy get() = (data[3].toInt() and 0xFF) or (data[4].toInt() shl 8)
        val wheel get() = data[5].toInt()
        val modifiers get() = data[0].toInt() and 0xFF
        val key get() = data[2].toInt() and 0xFF
        val usage get() = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    }

    private class RecordingSink : HidReportSink {
        @Volatile override var isConnected = true
        val reports = CopyOnWriteArrayList<Report>()
        override fun send(reportId: Int, data: ByteArray) {
            reports += Report(reportId, data.copyOf())
        }
        fun of(id: Int) = reports.filter { it.id == id }
        val mouse get() = of(HidReportDescriptor.REPORT_ID_MOUSE)
        val keyboard get() = of(HidReportDescriptor.REPORT_ID_KEYBOARD)
        val consumer get() = of(HidReportDescriptor.REPORT_ID_CONSUMER)
    }

    private val executor = ScheduledThreadPoolExecutor(1)
    private val sink = RecordingSink()
    private var os = HostOs.WINDOWS
    private val untypeable = CopyOnWriteArrayList<Int>()
    private val translator = HidInputTranslator(
        sink = sink,
        executor = executor,
        host = { HostProfile(os) },
        timing = HidInputTranslator.Timing(keyDownMs = 1, betweenKeysMs = 1, tapMs = 2),
        onUntypeable = { untypeable += it },
    )

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Waits until everything queued so far, and anything it scheduled soon after, has run. */
    private fun settle() {
        repeat(3) {
            val done = CountDownLatch(1)
            executor.execute { done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            Thread.sleep(30)
        }
    }

    private fun send(vararg events: InputEvent) = events.forEach(translator::handle)

    // ── Pointer ──────────────────────────────────────────────────────

    @Test
    fun `a click is the left button going down and up`() {
        send(InputEvent.Click)
        settle()
        assertEquals(listOf(0x01, 0x00), sink.mouse.map { it.buttons })
        assertTrue(sink.mouse.all { it.dx == 0 && it.dy == 0 && it.wheel == 0 })
    }

    @Test
    fun `a right click uses the right button`() {
        send(InputEvent.RightClick)
        settle()
        assertEquals(listOf(0x02, 0x00), sink.mouse.map { it.buttons })
    }

    @Test
    fun `pointer movement is sent as signed 16 bit deltas`() {
        send(InputEvent.MouseMove(300f, -2f))
        settle()
        val r = sink.mouse.single()
        assertEquals(300, r.dx)
        assertEquals(-2, r.dy.toShort().toInt())
    }

    @Test
    fun `a stream of moves queued behind a busy link becomes one report with the total`() {
        val release = CountDownLatch(1)
        executor.execute { release.await(2, TimeUnit.SECONDS) } // the Bluetooth stack is busy
        repeat(100) { send(InputEvent.MouseMove(1f, 2f)) }
        release.countDown()
        settle()
        val moves = sink.mouse
        assertEquals("the backlog collapses instead of replaying", 1, moves.size)
        assertEquals(100, moves.single().dx)
        assertEquals(200, moves.single().dy)
    }

    @Test
    fun `a drag holds the button while the pointer moves, then lets go`() {
        send(InputEvent.DragStart(), InputEvent.MouseMove(5f, 0f), InputEvent.DragEnd())
        settle()
        assertEquals(listOf(0x01, 0x01, 0x00), sink.mouse.map { it.buttons })
        assertEquals(listOf(0, 5, 0), sink.mouse.map { it.dx })
    }

    @Test
    fun `movement that came before a click lands before it`() {
        send(InputEvent.MouseMove(7f, 0f), InputEvent.Click)
        settle()
        assertEquals(listOf(7, 0, 0), sink.mouse.map { it.dx })
        assertEquals(listOf(0x00, 0x01, 0x00), sink.mouse.map { it.buttons })
    }

    @Test
    fun `a click during a drag leaves the dragged button held`() {
        send(InputEvent.MouseButton(InputEvent.Button.LEFT, true), InputEvent.RightClick, InputEvent.MouseButton(InputEvent.Button.LEFT, false))
        settle()
        assertEquals(listOf(0x01, 0x03, 0x01, 0x00), sink.mouse.map { it.buttons })
    }

    @Test
    fun `the middle button has its own bit`() {
        send(InputEvent.MouseButton(InputEvent.Button.MIDDLE, true), InputEvent.MouseButton(InputEvent.Button.MIDDLE, false))
        settle()
        assertEquals(listOf(0x04, 0x00), sink.mouse.map { it.buttons })
    }

    @Test
    fun `scrolling sends wheel notches`() {
        send(InputEvent.Scroll(1f), InputEvent.Scroll(-1f))
        settle()
        assertEquals(listOf(1, -1), sink.mouse.map { it.wheel })
    }

    // ── Keyboard ─────────────────────────────────────────────────────

    @Test
    fun `a chord reports its modifiers only while the key is down`() {
        val ctrl = InputEvent.Modifiers(leftCtrl = true)
        send(InputEvent.KeyPress(HidKeyCodes.C, ctrl), InputEvent.KeyRelease(HidKeyCodes.C, ctrl))
        settle()
        val reports = sink.keyboard
        assertEquals(2, reports.size)
        assertEquals(HidModifierMask.LEFT_CTRL, reports[0].modifiers)
        assertEquals(HidKeyCodes.C, reports[0].key)
        assertEquals("nothing may stay held", 0, reports[1].modifiers)
        assertEquals(0, reports[1].key)
    }

    @Test
    fun `typing a capital letter leaves Shift released`() {
        send(InputEvent.TextInput("Ab"))
        settle()
        val seen = sink.keyboard.map { it.modifiers to it.key }
        assertEquals(
            listOf(
                HidModifierMask.LEFT_SHIFT to HidKeyCodes.A,
                0 to 0,
                0 to HidKeyCodes.B,
                0 to 0,
            ),
            seen,
        )
    }

    @Test
    fun `text arriving in pieces is typed strictly in order, key by key`() {
        send(InputEvent.TextInput("a"), InputEvent.TextInput("a"), InputEvent.TextInput("b"))
        settle()
        // Two separate presses of 'a' must stay two presses, not merge into one.
        assertEquals(listOf(HidKeyCodes.A, 0, HidKeyCodes.A, 0, HidKeyCodes.B, 0), sink.keyboard.map { it.key })
    }

    @Test
    fun `characters a keyboard cannot type are reported and skipped`() {
        send(InputEvent.TextInput("é😀"))
        settle()
        assertTrue(sink.keyboard.isEmpty())
        assertEquals(listOf(2), untypeable)
    }

    @Test
    fun `typing with a held modifier wraps every key in it`() {
        send(InputEvent.TextInput("c", InputEvent.Modifiers(leftCtrl = true)))
        settle()
        assertEquals(HidModifierMask.LEFT_CTRL, sink.keyboard.first().modifiers)
        assertEquals(0, sink.keyboard.last().modifiers)
    }

    // ── Media, lock, system actions ──────────────────────────────────

    @Test
    fun `a media key is pressed and then released`() {
        send(InputEvent.MediaCommand(InputEvent.MediaAction.PLAY_PAUSE))
        settle()
        assertEquals(listOf(HidReportDescriptor.CONSUMER_PLAY_PAUSE, 0), sink.consumer.map { it.usage })
    }

    @Test
    fun `volume keys map to their usages`() {
        send(
            InputEvent.VolumeCommand(InputEvent.VolumeDirection.UP),
            InputEvent.VolumeCommand(InputEvent.VolumeDirection.DOWN),
            InputEvent.VolumeCommand(InputEvent.VolumeDirection.MUTE),
        )
        settle()
        val pressed = sink.consumer.map { it.usage }.filter { it != 0 }
        assertEquals(
            listOf(HidReportDescriptor.CONSUMER_VOLUME_UP, HidReportDescriptor.CONSUMER_VOLUME_DOWN, HidReportDescriptor.CONSUMER_MUTE),
            pressed,
        )
    }

    @Test
    fun `locking the screen sends the shortcut of the host OS`() {
        os = HostOs.WINDOWS
        send(InputEvent.LockScreen)
        settle()
        assertEquals(HidModifierMask.LEFT_META to HidKeyCodes.L, sink.keyboard.first().let { it.modifiers to it.key })
        assertEquals(0, sink.keyboard.last().modifiers)

        sink.reports.clear()
        os = HostOs.MACOS
        send(InputEvent.LockScreen)
        settle()
        assertEquals(
            (HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_META) to HidKeyCodes.Q,
            sink.keyboard.first().let { it.modifiers to it.key },
        )
    }

    @Test
    fun `a system action is sent as a shortcut where the OS has one`() {
        send(InputEvent.LaunchAction(InputEvent.SystemAction.SHOW_DESKTOP))
        settle()
        assertEquals(HidModifierMask.LEFT_META to HidKeyCodes.D, sink.keyboard.first().let { it.modifiers to it.key })
        assertEquals(0, sink.keyboard.last().modifiers)
    }

    @Test
    fun `a system action with no shortcut is ignored`() {
        send(InputEvent.LaunchAction(InputEvent.SystemAction.BROWSER))
        settle()
        assertTrue(sink.keyboard.isEmpty())
    }

    @Test
    fun `tapping Super alone works as a key`() {
        os = HostOs.LINUX
        send(InputEvent.LaunchAction(InputEvent.SystemAction.TASK_VIEW))
        settle()
        assertEquals(HidModifierMask.LEFT_META, sink.keyboard.first().modifiers)
        assertEquals(0, sink.keyboard.last().modifiers)
    }

    @Test
    fun `clipboard events cannot cross a keyboard and are ignored`() {
        send(InputEvent.ClipboardGet, InputEvent.ClipboardSet("x"))
        settle()
        assertTrue(sink.reports.isEmpty())
    }

    // ── Connection edges ─────────────────────────────────────────────

    @Test
    fun `nothing is sent while no host is connected`() {
        sink.isConnected = false
        send(InputEvent.Click, InputEvent.MouseMove(3f, 3f), InputEvent.KeyPress(HidKeyCodes.A), InputEvent.TextInput("x"))
        settle()
        assertTrue(sink.reports.isEmpty())
    }

    @Test
    fun `release all lets go of keys, buttons and media`() {
        send(
            InputEvent.KeyPress(HidKeyCodes.LEFT_SHIFT),
            InputEvent.KeyPress(HidKeyCodes.A),
            InputEvent.MouseButton(InputEvent.Button.LEFT, true),
        )
        settle()
        sink.reports.clear()
        translator.releaseAll()
        settle()
        assertEquals(0, sink.keyboard.last().modifiers)
        assertEquals(0, sink.keyboard.last().key)
        assertEquals(0, sink.mouse.last().buttons)
        assertEquals(0, sink.consumer.last().usage)
    }

    @Test
    fun `release all also abandons text still being typed`() {
        send(InputEvent.TextInput("x".repeat(500)))
        translator.releaseAll()
        settle()
        val pressesAfterwards = sink.keyboard.count { it.key != 0 }
        Thread.sleep(100)
        assertEquals("no further keys after release", pressesAfterwards, sink.keyboard.count { it.key != 0 })
        assertTrue(pressesAfterwards < 500)
    }

    @Test
    fun `a thrown error in the sink does not kill the executor`() {
        var failures = 0
        val flaky = object : HidReportSink {
            override val isConnected = true
            override fun send(reportId: Int, data: ByteArray) {
                failures++
                if (failures == 1) error("binder died")
            }
        }
        val t = HidInputTranslator(flaky, executor, { HostProfile(HostOs.WINDOWS) }, HidInputTranslator.Timing(1, 1, 1))
        t.handle(InputEvent.Click)
        t.handle(InputEvent.Click)
        settle()
        assertTrue("later events still go out: $failures", failures >= 3)
    }
}
