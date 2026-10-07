package com.omsingh.telepad.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mikepenz.aboutlibraries.Libs
import com.omsingh.telepad.BuildConfig
import com.omsingh.telepad.R
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.ui.screens.settings.AboutScreen
import com.omsingh.telepad.ui.screens.settings.LicensesScreen
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

/** The About page: the version, where the project lives, how it treats privacy, and the licenses of what it is built with. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = noAnimations()

    @Test
    fun `it says which version this is`() {
        compose.show { AboutScreen(onBack = {}, onLicenses = {}) }
        compose.onNodeWithText(string(R.string.about_version, BuildConfig.VERSION_NAME)).assertIsDisplayed()
    }

    @Test
    fun `the links go to the source, the issues and the privacy statement`() {
        val platform = RecordingPlatform()
        compose.show(platform) { AboutScreen(onBack = {}, onLicenses = {}) }

        compose.onNodeWithText(string(R.string.about_github)).performClick()
        compose.onNodeWithText(string(R.string.about_issues)).performClick()
        compose.onNodeWithText(string(R.string.about_privacy)).performClick()

        assertEquals(listOf("open:${Links.REPOSITORY}", "open:${Links.ISSUES}", "open:${Links.PRIVACY}"), platform.calls)
    }

    @Test
    fun `the licenses button opens the list of licenses`() {
        var opened = 0
        compose.show { AboutScreen(onBack = {}, onLicenses = { opened++ }) }
        compose.onNodeWithText(string(R.string.about_licenses)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the licenses page has a title and a way back`() {
        var back = 0
        compose.show { LicensesScreen(onBack = { back++ }) }
        compose.onNodeWithText(string(R.string.about_licenses)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertEquals(1, back)
    }

    @Test
    fun `every library the app is built with is in the list, each with a license`() {
        // What the page shows is read from the app's resources, which the build makes from the app's real
        // dependencies (so the list cannot fall behind). The page's own drawing is the library's.
        val json = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val libraries = Libs.Builder().withJson(json).build().libraries
        val names = libraries.map { it.name }

        for (expected in listOf("Kotlin Stdlib", "ZXing Core", "rweather/noise-java", "Jetpack Compose")) {
            assertTrue("$expected is listed (of ${names.size}: ${names.take(8)}...)", names.any { it.contains(expected, ignoreCase = true) })
        }
        val unlicensed = libraries.filter { it.licenses.isEmpty() }.map { it.name }
        assertTrue("every library says which license it is under: $unlicensed", unlicensed.isEmpty())
    }
}
