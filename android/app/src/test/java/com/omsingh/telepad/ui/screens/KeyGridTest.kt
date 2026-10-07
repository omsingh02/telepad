package com.omsingh.telepad.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.ui.screens.remote.KeyGrid
import com.omsingh.telepad.ui.theme.rememberHaptics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The on-screen keyboard as it is used: a modifier that is on has to look on, and stay looking on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class KeyGridTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<InputEvent>()
    private val keyboard = KeyboardSession { sent += it }
    private val host = HostProfile(HostOs.LINUX)
    private val superKey = host.meta.spokenName

    @Before
    fun setUp() = noAnimations()

    @Composable
    private fun Grid() {
        KeyGrid(host, keyboard, rememberHaptics(enabled = false))
    }

    private fun stateOf(name: String): String? =
        compose.onNodeWithContentDescription(name).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun off(name: String) = string(R.string.keys_modifier_off, name)
    private fun armed(name: String) = string(R.string.keys_modifier_armed, name)
    private fun locked(name: String) = string(R.string.keys_modifier_locked, name)

    @Test
    fun `a tapped modifier stays on until the next key uses it`() {
        compose.show { Grid() }
        assertEquals(off(superKey), stateOf(superKey))

        compose.onNodeWithContentDescription(superKey).performClick()
        compose.settle()
        assertEquals("it stays lit after the finger lifts", armed(superKey), stateOf(superKey))

        compose.onNodeWithContentDescription("q").performClick()
        compose.settle()
        assertEquals(off(superKey), stateOf(superKey))
        assertEquals(
            listOf(
                InputEvent.KeyPress(HidKeyCodes.Q, InputEvent.Modifiers(leftMeta = true)),
                InputEvent.KeyRelease(HidKeyCodes.Q, InputEvent.Modifiers(leftMeta = true)),
            ),
            sent,
        )
    }

    @Test
    fun `two quick taps lock it, and it stays locked through other keys until tapped again`() {
        compose.show { Grid() }
        compose.onNodeWithContentDescription(superKey).performClick()
        compose.onNodeWithContentDescription(superKey).performClick()
        compose.settle()
        assertEquals(locked(superKey), stateOf(superKey))

        compose.onNodeWithContentDescription("1").performClick()
        compose.settle()
        assertEquals("a lock outlives the keys pressed under it", locked(superKey), stateOf(superKey))
    }

    @Test
    fun `the chord line names what is on`() {
        compose.show { Grid() }
        compose.onNodeWithText(string(R.string.keys_chord_hint)).assertExists()

        compose.onNodeWithContentDescription(superKey).performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.keys_chord_active, host.meta.symbol)).assertExists()
    }

    @Test
    fun `shift changes the letters the keys show`() {
        compose.show { Grid() }
        compose.onNodeWithText("q").assertExists()

        compose.onNodeWithContentDescription(host.shift.spokenName).performClick()
        compose.settle()
        compose.onNodeWithText("Q").assertExists()
    }
}
