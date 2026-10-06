package com.omsingh.telepad.ui.components

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.GestureConfig
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.ui.theme.rememberHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The glue between a finger on the screen and the gesture engine, run through Compose's own
 * touch injection. The engine's rules have their own tests; this checks that what Compose delivers
 * (pointers, positions, timestamps) is turned into the right mouse actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = Application::class)
class TouchSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<InputEvent>()

    private val padDescription: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.pad_description)

    @Before
    fun setUp() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TelepadTheme {
                TouchSurface(
                    onEvent = { events += it },
                    config = GestureConfig(sensitivity = 1f),
                    haptics = rememberHaptics(false),
                    modifier = Modifier.size(300.dp),
                    scrollStrip = true,
                )
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun pad() = compose.onNodeWithContentDescription(padDescription)

    private fun moves() = events.filterIsInstance<InputEvent.MouseMove>()

    @Test
    fun `a tap clicks`() {
        pad().performTouchInput {
            down(center)
            advanceEventTime(60)
            up()
        }
        assertEquals(listOf<InputEvent>(InputEvent.Click), events)
    }

    @Test
    fun `two taps in a row are two clicks`() {
        repeat(2) {
            pad().performTouchInput {
                down(center)
                advanceEventTime(50)
                up()
                advanceEventTime(90)
            }
        }
        assertEquals(listOf<InputEvent>(InputEvent.Click, InputEvent.Click), events)
    }

    @Test
    fun `dragging a finger moves the pointer the same way`() {
        pad().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f), delayMillis = 16)
            moveBy(Offset(60f, 0f), delayMillis = 16)
            up()
        }
        assertTrue("pointer moved", moves().isNotEmpty())
        assertTrue("to the right", moves().all { it.dx >= 0f })
        assertTrue("only sideways", moves().all { it.dy == 0f })
        assertTrue("and no click", events.none { it == InputEvent.Click })
    }

    @Test
    fun `dragging down and to the left moves down and to the left`() {
        pad().performTouchInput {
            down(center)
            moveBy(Offset(-80f, 80f), delayMillis = 16)
            moveBy(Offset(-80f, 80f), delayMillis = 16)
            up()
        }
        val total = moves().fold(0f to 0f) { (x, y), m -> (x + m.dx) to (y + m.dy) }
        assertTrue("left: ${total.first}", total.first < 0f)
        assertTrue("down: ${total.second}", total.second > 0f)
    }

    @Test
    fun `a tap with two fingers is a right click`() {
        pad().performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            advanceEventTime(60)
            up(0)
            up(1)
        }
        assertEquals(listOf<InputEvent>(InputEvent.RightClick), events)
    }

    @Test
    fun `a tap with three fingers is a middle click`() {
        pad().performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center)
            down(2, center + Offset(60f, 0f))
            advanceEventTime(60)
            up(0)
            up(1)
            up(2)
        }
        assertEquals(
            listOf<InputEvent>(
                InputEvent.MouseButton(InputEvent.Button.MIDDLE, true),
                InputEvent.MouseButton(InputEvent.Button.MIDDLE, false),
            ),
            events,
        )
    }

    @Test
    fun `two fingers sliding down scroll, and the pointer stays put`() {
        pad().performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            repeat(8) {
                moveBy(0, Offset(0f, 40f), delayMillis = 16)
                moveBy(1, Offset(0f, 40f), delayMillis = 16)
            }
            up(0)
            up(1)
        }
        val scrolls = events.filterIsInstance<InputEvent.Scroll>()
        assertTrue("scrolled", scrolls.isNotEmpty())
        assertTrue("one direction", scrolls.all { it.delta > 0 })
        assertTrue("no pointer movement", moves().isEmpty())
    }

    @Test
    fun `a finger that stays still and lifts quickly does not move the pointer`() {
        pad().performTouchInput {
            down(center)
            advanceEventTime(40)
            up()
        }
        assertTrue(moves().isEmpty())
    }

    @Test
    fun `touches that start on the scroll strip scroll without moving the pointer`() {
        val strip = RuntimeEnvironment.getApplication().getString(R.string.pad_scroll_strip)
        compose.onNodeWithContentDescription(strip).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, 30f), delayMillis = 16) }
            up()
        }
        assertTrue("scrolled", events.any { it is InputEvent.Scroll })
        assertTrue("pointer untouched", moves().isEmpty())
    }
}
