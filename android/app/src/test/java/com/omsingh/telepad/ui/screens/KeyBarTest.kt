package com.omsingh.telepad.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.ui.screens.remote.KeyBar
import com.omsingh.telepad.ui.theme.rememberHaptics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bar above the phone's keyboard: the keys a phone keyboard lacks, and the modifiers that must look on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class KeyBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<InputEvent>()
    private val keyboard = KeyboardSession { sent += it }
    private val host = HostProfile(HostOs.LINUX)
    private var beforeKeyCalls = 0

    private val none = InputEvent.Modifiers.EMPTY

    @Before
    fun setUp() = noAnimations()

    @Composable
    private fun Bar() {
        KeyBar(host, keyboard, rememberHaptics(enabled = false), beforeKey = { beforeKeyCalls++ })
    }

    private fun stateOf(name: String): String? =
        compose.onNodeWithContentDescription(name).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun tap(usage: Int, mods: InputEvent.Modifiers = none) =
        listOf(InputEvent.KeyPress(usage, mods), InputEvent.KeyRelease(usage, mods))

    @Test
    fun `Esc, Tab, the arrows and the editing keys are keys`() {
        compose.show { Bar() }
        val expected = mapOf(
            "Esc" to HidKeyCodes.ESC,
            "Tab" to HidKeyCodes.TAB,
            string(R.string.key_left) to HidKeyCodes.LEFT,
            string(R.string.key_right) to HidKeyCodes.RIGHT,
            string(R.string.key_up) to HidKeyCodes.UP,
            string(R.string.key_down) to HidKeyCodes.DOWN,
            "Home" to HidKeyCodes.HOME,
            "End" to HidKeyCodes.END,
            "Del" to HidKeyCodes.DELETE,
        )
        for ((name, usage) in expected) {
            sent.clear()
            compose.onNodeWithContentDescription(name).performClick()
            assertEquals(name, tap(usage), sent)
        }
    }

    @Test
    fun `a tapped modifier stays lit until the next key uses it`() {
        compose.show { Bar() }
        val superKey = host.meta.spokenName
        assertEquals(string(R.string.keys_modifier_off, superKey), stateOf(superKey))

        compose.onNodeWithContentDescription(superKey).performClick()
        compose.settle()
        assertEquals("it stays lit after the finger lifts", string(R.string.keys_modifier_armed, superKey), stateOf(superKey))

        compose.onNodeWithContentDescription("Tab").performClick()
        compose.settle()
        assertEquals(string(R.string.keys_modifier_off, superKey), stateOf(superKey))
        assertEquals(tap(HidKeyCodes.TAB, InputEvent.Modifiers(leftMeta = true)), sent)
    }

    @Test
    fun `two quick taps lock a modifier through several keys`() {
        compose.show { Bar() }
        val ctrl = host.ctrl.spokenName
        compose.onNodeWithContentDescription(ctrl).performClick()
        compose.onNodeWithContentDescription(ctrl).performClick()
        compose.settle()
        assertEquals(string(R.string.keys_modifier_locked, ctrl), stateOf(ctrl))

        compose.onNodeWithContentDescription("Tab").performClick()
        compose.onNodeWithContentDescription("Esc").performClick()
        compose.settle()
        assertEquals("a lock outlives the keys pressed under it", string(R.string.keys_modifier_locked, ctrl), stateOf(ctrl))
    }

    @Test
    fun `the function keys appear on Fn and are keys`() {
        compose.show { Bar() }
        compose.onNodeWithContentDescription("F5").assertDoesNotExist()

        compose.onNodeWithContentDescription(string(R.string.keys_function_keys)).performClick()
        compose.settle()
        assertEquals(
            string(R.string.keys_function_keys_shown),
            stateOf(string(R.string.keys_function_keys)),
        )

        compose.onNodeWithContentDescription("F5").performClick()
        // Page Down is far along the row that scrolls, off the screen: use its own click action.
        compose.activate(compose.onNodeWithContentDescription(string(R.string.key_page_down)))
        assertEquals(tap(HidKeyCodes.F5) + tap(HidKeyCodes.PAGE_DOWN), sent)
    }

    @Test
    fun `text typed so far is sent before a key from the bar, and before a modifier is latched`() {
        compose.show { Bar() }

        compose.onNodeWithContentDescription("Tab").performClick()
        assertEquals(1, beforeKeyCalls)

        // A modifier must not catch up the text typed before it.
        compose.onNodeWithContentDescription(host.ctrl.spokenName).performClick()
        assertEquals(2, beforeKeyCalls)
    }

    @Test
    fun `a touch at the very edge of a key still presses it`() {
        compose.show { Bar() }
        // The key's touch area is its whole cell, gap included: no dead space between two keys.
        compose.onNodeWithContentDescription("Esc").performTouchInput {
            down(Offset(1f, height / 2f))
            up()
        }
        assertEquals(tap(HidKeyCodes.ESC), sent)
    }

    @Test
    fun `on a mac the modifiers carry the symbols of a mac keyboard`() {
        val mac = HostProfile(HostOs.MACOS)
        compose.show { KeyBar(mac, keyboard, rememberHaptics(enabled = false), beforeKey = {}) }
        // Spoken as words, written as symbols.
        compose.onNodeWithContentDescription("Command").performClick()
        compose.onNodeWithContentDescription("Tab").performClick()
        assertEquals(tap(HidKeyCodes.TAB, InputEvent.Modifiers(leftMeta = true)), sent)
    }
}
