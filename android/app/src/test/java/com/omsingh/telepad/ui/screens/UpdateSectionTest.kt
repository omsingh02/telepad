package com.omsingh.telepad.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omsingh.telepad.R
import com.omsingh.telepad.ui.screens.settings.UpdateSection
import com.omsingh.telepad.update.InstallSource
import com.omsingh.telepad.update.Problem
import com.omsingh.telepad.update.Releases
import com.omsingh.telepad.update.UpdateActions
import com.omsingh.telepad.update.UpdateState
import com.omsingh.telepad.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Updates, on the About page: what is shown in each state, and that each button does the one thing it says. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class UpdateSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = noAnimations()

    private val release = Releases.forTag("v2.0.0-alpha.4")!!
    private val apk = release.asset("telepad-android-v2.0.0-alpha.4.apk")!!
    private val sums = release.sums

    private class Recorder : UpdateActions {
        val calls = mutableListOf<String>()
        override fun check() { calls += "check" }
        override fun install() { calls += "install" }
        override fun setCheckAutomatically(on: Boolean) { calls += "auto:$on" }
    }

    private fun ui(
        state: UpdateState,
        canInstall: Boolean = true,
        source: InstallSource = InstallSource.Direct,
        auto: Boolean = true,
    ) = UpdateUi(state, canInstall, source, auto)

    private fun show(ui: UpdateUi, actions: UpdateActions = UpdateActions.None, platform: RecordingPlatform = RecordingPlatform()) {
        compose.show(platform) { UpdateSection(ui, actions) }
    }

    @Test
    fun `when nothing is known it offers to look, and looking is one tap`() {
        val actions = Recorder()
        show(ui(UpdateState.Unknown), actions)
        compose.onNodeWithText(string(R.string.updates_check)).performClick()
        assertEquals(listOf("check"), actions.calls)
    }

    @Test
    fun `up to date says so and offers to look again`() {
        show(ui(UpdateState.UpToDate))
        compose.onNodeWithText(string(R.string.updates_up_to_date)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_check)).assertIsDisplayed()
    }

    @Test
    fun `a newer version is named, can be read about and can be installed`() {
        val actions = Recorder()
        val platform = RecordingPlatform()
        show(ui(UpdateState.Available(release, apk, sums)), actions, platform)

        compose.onNodeWithText(string(R.string.updates_available, "2.0.0-alpha.4")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_whats_new)).performClick()
        compose.onNodeWithText(string(R.string.updates_install)).performClick()

        assertEquals(listOf("open:https://github.com/omsingh02/telepad/releases/tag/v2.0.0-alpha.4"), platform.calls)
        assertEquals(listOf("install"), actions.calls)
    }

    @Test
    fun `a copy that a store looks after is told which store and is not offered the install`() {
        show(ui(UpdateState.Available(release, apk, sums), canInstall = false, source = InstallSource.Store("F-Droid")))
        compose.onNodeWithText(string(R.string.updates_available, "2.0.0-alpha.4")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_from_store, "F-Droid")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount(string(R.string.updates_install)) == 0)
    }

    @Test
    fun `a release without a file for Android is not offered an install`() {
        show(ui(UpdateState.Available(release, null, sums)))
        compose.onNodeWithText(string(R.string.updates_available, "2.0.0-alpha.4")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount(string(R.string.updates_install)) == 0)
    }

    @Test
    fun `a download shows how far it is`() {
        show(ui(UpdateState.Downloading(release, done = 250, total = 1000)))
        compose.onNodeWithText(string(R.string.updates_downloading_percent, "2.0.0-alpha.4", 25)).assertIsDisplayed()
    }

    @Test
    fun `a download of unknown size just says it is downloading`() {
        show(ui(UpdateState.Downloading(release, done = 5, total = null)))
        compose.onNodeWithText(string(R.string.updates_downloading, "2.0.0-alpha.4")).assertIsDisplayed()
    }

    @Test
    fun `when Android has to allow installs first the person is taken there and can go on after`() {
        val actions = Recorder()
        val platform = RecordingPlatform()
        show(ui(UpdateState.NeedsPermission(release, apk, sums)), actions, platform)
        compose.onNodeWithText(string(R.string.updates_needs_permission)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_allow)).performClick()
        compose.onNodeWithText(string(R.string.updates_install)).performClick()
        assertEquals(listOf("install-permission-settings"), platform.calls)
        assertEquals(listOf("install"), actions.calls)
    }

    @Test
    fun `each problem is put in words and a failed install can be tried again`() {
        val problems = mapOf(
            Problem.Offline to string(R.string.updates_problem_offline),
            Problem.RateLimited to string(R.string.updates_problem_rate_limited),
            Problem.NoFile to string(R.string.updates_problem_no_file),
            Problem.ChecksumMismatch to string(R.string.updates_problem_checksum),
            Problem.Other("no space left") to string(R.string.updates_problem_other, "no space left"),
        )
        val actions = Recorder()
        var shown by mutableStateOf(ui(UpdateState.InstallFailed(release, apk, sums, Problem.Offline)))
        compose.show { UpdateSection(shown, actions) }
        for ((problem, words) in problems) {
            shown = ui(UpdateState.InstallFailed(release, apk, sums, problem))
            compose.settle()
            compose.onNodeWithText(words).assertIsDisplayed()
            compose.onNodeWithText(string(R.string.updates_retry)).performClick()
        }
        assertEquals(List(problems.size) { "install" }, actions.calls)
    }

    @Test
    fun `a failed look says why and can be repeated`() {
        val actions = Recorder()
        show(ui(UpdateState.CheckFailed(Problem.Offline)), actions)
        compose.onNodeWithText(string(R.string.updates_problem_offline)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_check)).performClick()
        assertEquals(listOf("check"), actions.calls)
    }

    @Test
    fun `the choice to look by itself is shown for what it does and flips on a tap`() {
        val actions = Recorder()
        show(ui(UpdateState.Unknown, auto = true), actions)
        compose.onNodeWithText(string(R.string.updates_check_automatically_subtitle)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.updates_check_automatically)).performClick()
        assertEquals(listOf("auto:false"), actions.calls)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
