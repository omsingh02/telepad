package com.omsingh.telepad.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.ui.Fixtures
import com.omsingh.telepad.ui.screens.remote.RemoteActions
import com.omsingh.telepad.ui.screens.remote.RemoteScreen
import com.omsingh.telepad.ui.screens.remote.RemoteUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class RemoteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<InputEvent>()
    private var disconnected = 0
    private var wentToDevices = 0
    private var pasted = 0

    private val actions = object : RemoteActions {
        override fun send(event: InputEvent) { sent += event }
        override fun disconnect() { disconnected++ }
        override fun pasteFromPhone() { pasted++ }
        override fun copyFromPc() {}
        override fun copyPcClipboardToPhone() {}
        override fun dismissPcClipboard() {}
        override fun mediaVisible(visible: Boolean) {}
    }

    /** The keyboard shares the same record of what was sent as everything else. */
    private val keyboard = KeyboardSession { sent += it }

    @Before
    fun setUp() = noAnimations()

    private fun show(state: RemoteUiState) =
        compose.show { RemoteScreen(state, actions, keyboard, onGoToDevices = { wentToDevices++ }) }

    private fun openTab(label: Int) {
        compose.onNodeWithText(string(label)).performClick()
        compose.settle()
    }

    /**
     * Clicks by the control's own click action, so that a control scrolled out of view (it is
     * not on screen, but it is there) can be used. Scrolling in a test with a frozen clock would
     * never finish.
     */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.activate() {
        val click = fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action
        compose.runOnUiThread { click?.invoke() }
        compose.settle()
    }

    private fun tap(usage: Int, mods: InputEvent.Modifiers = InputEvent.Modifiers.EMPTY) =
        listOf(InputEvent.KeyPress(usage, mods), InputEvent.KeyRelease(usage, mods))

    // ── Shell ────────────────────────────────────────────────────────

    @Test
    fun `the connection chip names the PC and its latency`() {
        show(Fixtures.remote())
        compose.onNodeWithText("Desk PC").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.devices_latency, 4)).assertIsDisplayed()
    }

    @Test
    fun `without a connection the remote points back to the devices`() {
        show(Fixtures.remote(connection = ConnectionState.Disconnected))
        compose.onNodeWithText(string(R.string.remote_not_connected_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.remote_go_to_devices)).performClick()
        assertEquals(1, wentToDevices)
    }

    @Test
    fun `a failed connection explains why on the remote too`() {
        val failed = ConnectionState.Failed("Desk PC", ConnectionState.Transport.WIFI, com.omsingh.telepad.core.input.FailureReason.CONNECTION_LOST)
        show(Fixtures.remote(connection = failed))
        compose.onNodeWithText(string(R.string.failure_lost_title, "Desk PC")).assertIsDisplayed()
    }

    @Test
    fun `while reconnecting the controls are covered and say why`() {
        show(Fixtures.remote(connection = ConnectionState.Reconnecting("Desk PC", ConnectionState.Transport.WIFI, 2)))
        compose.onNodeWithText(string(R.string.remote_reconnecting)).assertIsDisplayed()
    }

    @Test
    fun `the three modes can be switched between`() {
        show(Fixtures.remote())
        compose.onNodeWithContentDescription(string(R.string.pad_description)).assertIsDisplayed()

        openTab(R.string.remote_mode_keys)
        compose.onNodeWithText(string(R.string.keys_section_shortcuts)).assertIsDisplayed()

        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.media_volume)).assertIsDisplayed()

        openTab(R.string.remote_mode_pad)
        compose.onNodeWithContentDescription(string(R.string.pad_description)).assertIsDisplayed()
    }

    @Test
    fun `the connection details can end the connection`() {
        show(Fixtures.remote())
        compose.onNodeWithText("Desk PC").performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.devices_disconnect)).performClick()
        assertEquals(1, disconnected)
    }

    // ── Mouse buttons ────────────────────────────────────────────────

    @Test
    fun `the left button goes down and up like a real one`() {
        show(Fixtures.remote())
        compose.onNodeWithContentDescription(string(R.string.pad_action_left_click)).performClick()
        assertEquals(
            listOf<InputEvent>(
                InputEvent.MouseButton(InputEvent.Button.LEFT, true),
                InputEvent.MouseButton(InputEvent.Button.LEFT, false),
            ),
            sent.filterIsInstance<InputEvent.MouseButton>(),
        )
    }

    @Test
    fun `the mouse buttons can be hidden`() {
        show(Fixtures.remote(preferences = com.omsingh.telepad.settings.UserPreferences(showTouchpadButtons = false)))
        compose.onNodeWithContentDescription(string(R.string.pad_action_left_click)).assertDoesNotExist()
    }

    // ── Keyboard ─────────────────────────────────────────────────────

    private val typingField get() = compose.onNodeWithContentDescription(string(R.string.keys_field_description))

    @Test
    fun `typed text is sent as it is typed`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        typingField.performTextInput("hello")
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("hello")), sent)
    }

    @Test
    fun `autocorrect becomes backspaces and the new ending`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        typingField.performTextInput("hello")
        sent.clear()

        typingField.performTextReplacement("help")
        assertEquals(tap(HidKeyCodes.BACKSPACE) + tap(HidKeyCodes.BACKSPACE) + InputEvent.TextInput("p"), sent)
    }

    @Test
    fun `a latched control key turns the next letter into a shortcut`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithContentDescription("Control").performClick()
        compose.settle()
        typingField.performTextInput("c")
        assertEquals(tap(HidKeyCodes.C, InputEvent.Modifiers(leftCtrl = true)), sent)
    }

    @Test
    fun `Enter sends the line and the field starts afresh`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        typingField.performTextInput("ls\n")
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("ls\n")), sent)
        compose.settle() // the field empties itself

        // Nothing is left in the field to be compared with the next line.
        sent.clear()
        typingField.performTextInput("a")
        assertEquals(listOf<InputEvent>(InputEvent.TextInput("a")), sent)
    }

    @Test
    fun `the bar above the phone's keyboard sends keys and takes part in chords`() {
        show(Fixtures.remote(os = HostOs.LINUX))
        openTab(R.string.remote_mode_keys)

        compose.onNodeWithContentDescription("Tab").performClick()
        assertEquals(tap(HidKeyCodes.TAB), sent)

        // Super, then a letter typed on the phone's own keyboard: Super+Q.
        sent.clear()
        compose.onNodeWithContentDescription("Super").performClick()
        compose.settle()
        typingField.performTextInput("q")
        assertEquals(tap(HidKeyCodes.Q, InputEvent.Modifiers(leftMeta = true)), sent)
    }

    @Test
    fun `the PC keyboard replaces the phone's keyboard and can be left again`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithContentDescription("q").assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.keys_more)).performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.keys_menu_pc_layout)).performClick()
        compose.settle()
        compose.onNodeWithContentDescription("q").assertExists()
        typingField.assertDoesNotExist()

        compose.onNodeWithText(string(R.string.keys_menu_phone_layout)).performClick()
        compose.settle()
        typingField.assertExists()
        compose.onNodeWithContentDescription("q").assertDoesNotExist()
    }

    @Test
    fun `the keyboard chosen stays chosen when the tabs are switched`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithContentDescription(string(R.string.keys_more)).performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.keys_menu_pc_layout)).performClick()
        compose.settle()

        openTab(R.string.remote_mode_pad)
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithContentDescription("q").assertExists()
    }

    @Test
    fun `a modifier that is still on is shown on the other tabs, and one tap lets go`() {
        show(Fixtures.remote(os = HostOs.LINUX))
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithContentDescription("Control").performClick()
        compose.settle()

        openTab(R.string.remote_mode_pad)
        compose.onNodeWithText(string(R.string.keys_chord_active, "Ctrl")).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.keys_release_modifiers)).performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.keys_chord_active, "Ctrl")).assertDoesNotExist()
        assertTrue(!keyboard.hasModifiers)
    }

    @Test
    fun `the shortcut chips send the keys of the PC's own operating system`() {
        show(Fixtures.remote(os = HostOs.WINDOWS))
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithText(string(R.string.shortcut_copy)).activate()
        assertEquals(tap(HidKeyCodes.C, InputEvent.Modifiers(leftCtrl = true)), sent)
    }

    @Test
    fun `on a mac the same shortcut uses Command and says so`() {
        show(Fixtures.remote(os = HostOs.MACOS))
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithText("⌘C").assertExists()
        compose.onNodeWithText(string(R.string.shortcut_copy)).activate()
        assertEquals(tap(HidKeyCodes.C, InputEvent.Modifiers(leftMeta = true)), sent)
    }

    @Test
    fun `clipboard buttons only appear where the clipboard can be shared`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithText(string(R.string.keys_paste_phone)).assertExists()
        // Below the keyboard, off the first screenful: use the node's own click action.
        compose.activate(compose.onNodeWithText(string(R.string.keys_paste_phone)))
        assertEquals(1, pasted)
    }

    @Test
    fun `over bluetooth there is no clipboard and the limits are stated`() {
        show(Fixtures.remote(connection = ConnectionState.Connected("PC", ConnectionState.Transport.BLUETOOTH)))
        openTab(R.string.remote_mode_keys)
        compose.onNodeWithText(string(R.string.keys_bt_limits)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.keys_paste_phone)).assertDoesNotExist()
    }

    // ── Media ────────────────────────────────────────────────────────

    @Test
    fun `media buttons send media commands`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_media)
        compose.onNodeWithContentDescription(string(R.string.media_play_pause)).performClick()
        compose.onNodeWithContentDescription(string(R.string.media_next)).performClick()
        compose.onNodeWithContentDescription(string(R.string.media_previous)).performClick()
        assertEquals(
            listOf<InputEvent>(
                InputEvent.MediaCommand(InputEvent.MediaAction.PLAY_PAUSE),
                InputEvent.MediaCommand(InputEvent.MediaAction.NEXT),
                InputEvent.MediaCommand(InputEvent.MediaAction.PREV),
            ),
            sent,
        )
    }

    @Test
    fun `volume buttons send volume commands`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_media)
        compose.onNodeWithContentDescription(string(R.string.media_volume_up)).performClick()
        compose.onNodeWithContentDescription(string(R.string.media_mute)).performClick()
        assertTrue(sent.containsAll(listOf(InputEvent.VolumeCommand(InputEvent.VolumeDirection.UP), InputEvent.VolumeCommand(InputEvent.VolumeDirection.MUTE))))
    }

    @Test
    fun `the slide buttons press the arrow keys`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.media_slide_next)).activate()
        assertEquals(tap(HidKeyCodes.RIGHT), sent)
    }

    @Test
    fun `what is playing is shown when the PC can say`() {
        show(Fixtures.remote(nowPlaying = true))
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText("Midnight City").assertIsDisplayed()
    }

    @Test
    fun `no empty now playing card when the PC cannot say`() {
        show(Fixtures.remote(nowPlaying = false))
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.media_nothing_playing)).assertDoesNotExist()
    }

    @Test
    fun `system actions follow the PC's operating system`() {
        show(Fixtures.remote(os = HostOs.MACOS))
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.action_task_view_macos)).assertExists()
        compose.onNodeWithText(string(R.string.action_task_manager_macos)).assertExists()
        compose.onNodeWithText(string(R.string.action_task_view_windows)).assertDoesNotExist()
    }

    @Test
    fun `over bluetooth only actions with a shortcut are offered`() {
        show(
            Fixtures.remote(
                os = HostOs.MACOS,
                connection = ConnectionState.Connected("MacBook", ConnectionState.Transport.BLUETOOTH),
                nowPlaying = false,
            ),
        )
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.action_lock)).assertExists()
        compose.onNodeWithText(string(R.string.action_browser)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.action_file_manager_macos)).assertDoesNotExist()
    }

    @Test
    fun `locking the screen is always possible`() {
        show(Fixtures.remote())
        openTab(R.string.remote_mode_media)
        compose.onNodeWithText(string(R.string.action_lock)).activate()
        assertTrue(sent.contains(InputEvent.LockScreen))
    }
}
