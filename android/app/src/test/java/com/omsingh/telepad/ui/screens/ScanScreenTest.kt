package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import com.omsingh.telepad.R
import com.omsingh.telepad.ui.screens.scan.CameraAccess
import com.omsingh.telepad.ui.screens.scan.ScanProblem
import com.omsingh.telepad.ui.screens.scan.ScanScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scanner's screen in each state. The camera itself is a slot, filled with a plain box here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class ScanScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var allowed = 0
    private var settings = 0
    private var closed = 0

    @Before
    fun setUp() = noAnimations()

    private fun show(access: CameraAccess, problem: ScanProblem? = null, noCamera: Boolean = false) = compose.show {
        ScanScreen(
            access = access,
            problem = problem,
            noCamera = noCamera,
            onAllow = { allowed++ },
            onOpenSettings = { settings++ },
            onClose = { closed++ },
            camera = { modifier -> Box(modifier.background(Color.DarkGray).testTag("camera")) },
        )
    }

    @Test
    fun `with the camera allowed it shows the camera and what to do`() {
        show(CameraAccess.GRANTED)
        compose.onNodeWithTag("camera").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.scan_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.scan_hint)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.scan_where)).assertIsDisplayed()
    }

    @Test
    fun `before the camera is allowed it says why it is wanted, and asks`() {
        show(CameraAccess.NEEDED)
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.scan_camera_body)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.scan_camera_allow)).performClick()
        assertEquals(1, allowed)
    }

    @Test
    fun `when the camera is refused it says how to turn it on`() {
        show(CameraAccess.DENIED)
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.scan_camera_denied_title)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.scan_open_settings)).performClick()
        assertEquals(1, settings)
    }

    @Test
    fun `a phone with no camera is told to add the PC by address`() {
        show(CameraAccess.GRANTED, noCamera = true)
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.scan_no_camera)).assertIsDisplayed()
    }

    @Test
    fun `something that is not a code to pair with is explained`() {
        show(CameraAccess.GRANTED, problem = ScanProblem.NOT_TELEPAD)
        compose.onNodeWithText(string(R.string.scan_problem_not_telepad)).assertIsDisplayed()
    }

    @Test
    fun `each problem has its own words`() {
        val messages = ScanProblem.values().map { string(it.message) }
        assertEquals(messages.size, messages.toSet().size)
        assertEquals(3, messages.size)
    }

    private fun closeWorksIn(access: CameraAccess) {
        show(access)
        compose.onNodeWithContentDescription(string(R.string.scan_close)).performClick()
        assertEquals(access.name, 1, closed)
    }

    @Test
    fun `the way out is there while scanning`() = closeWorksIn(CameraAccess.GRANTED)

    @Test
    fun `the way out is there while the camera is asked for`() = closeWorksIn(CameraAccess.NEEDED)

    @Test
    fun `the way out is there when the camera is refused`() = closeWorksIn(CameraAccess.DENIED)
}
