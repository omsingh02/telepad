package com.omsingh.telepad.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omsingh.telepad.R
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.HostOsChoice
import com.omsingh.telepad.settings.ThemeMode
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.Fixtures
import com.omsingh.telepad.ui.screens.onboarding.OnboardingScreen
import com.omsingh.telepad.ui.screens.settings.AppearanceSettingsScreen
import com.omsingh.telepad.ui.screens.settings.ConnectionSettingsScreen
import com.omsingh.telepad.ui.screens.settings.KeyboardSettingsScreen
import com.omsingh.telepad.ui.screens.settings.PrivacySettingsScreen
import com.omsingh.telepad.ui.screens.settings.SettingsActions
import com.omsingh.telepad.ui.screens.settings.SettingsHomeScreen
import com.omsingh.telepad.ui.screens.settings.SettingsPage
import com.omsingh.telepad.ui.screens.settings.TouchpadSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Applies each change to a copy of the preferences, as the real repository does. */
    private class Recorder(var preferences: UserPreferences = UserPreferences()) : SettingsActions {
        val forgotten = mutableListOf<PairedDevice>()
        var forgotAll = 0
        var identityResets = 0
        var defaultsRestored = 0
        override fun update(transform: (UserPreferences) -> UserPreferences) { preferences = transform(preferences) }
        override fun resetToDefaults() { defaultsRestored++ }
        override fun forget(device: PairedDevice) { forgotten += device }
        override fun forgetAll() { forgotAll++ }
        override fun resetIdentity() { identityResets++ }
    }

    private val actions = Recorder()

    @Before
    fun setUp() = noAnimations()

    // ── Home ─────────────────────────────────────────────────────────

    @Test
    fun `every settings page is reachable from the home screen`() {
        val opened = mutableListOf<SettingsPage>()
        compose.show { SettingsHomeScreen(actions, onOpen = { opened += it }) }
        compose.onNodeWithText(string(R.string.settings_touchpad)).performClick()
        compose.onNodeWithText(string(R.string.settings_keyboard)).performClick()
        compose.onNodeWithText(string(R.string.settings_connection)).performClick()
        compose.onNodeWithText(string(R.string.settings_appearance)).performClick()
        compose.onNodeWithText(string(R.string.settings_privacy)).performClick()
        compose.onNodeWithText(string(R.string.settings_about)).performClick()
        assertEquals(SettingsPage.values().toList(), opened)
    }

    @Test
    fun `resetting to defaults asks first`() {
        compose.show { SettingsHomeScreen(actions, onOpen = {}) }
        compose.onNodeWithText(string(R.string.settings_reset)).performClick()
        compose.settle()
        assertEquals(0, actions.defaultsRestored)
        compose.onNode(hasText(string(R.string.settings_reset)) and hasAnyAncestor(isDialog())).performClick()
        compose.settle()
        assertEquals(1, actions.defaultsRestored)
    }

    // ── Touchpad ─────────────────────────────────────────────────────

    @Test
    fun `the touchpad switches change the preference they name`() {
        compose.show { TouchpadSettingsScreen(actions.preferences, actions, onBack = {}) }
        // Most of the page is below the fold of a phone screen, so switch by the row's own action.
        compose.activate(compose.onNodeWithText(string(R.string.touchpad_natural)))
        assertFalse(actions.preferences.naturalScrolling)
        compose.activate(compose.onNodeWithText(string(R.string.touchpad_tap)))
        assertFalse(actions.preferences.tapToClick)
        compose.activate(compose.onNodeWithText(string(R.string.touchpad_haptics)))
        assertFalse(actions.preferences.hapticFeedback)
    }

    @Test
    fun `the touchpad page has a pad to try the settings on`() {
        compose.show { TouchpadSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.touchpad_try_body)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.pad_description)).assertIsDisplayed()
    }

    @Test
    fun `the back arrow leaves the page`() {
        var left = 0
        compose.show { TouchpadSettingsScreen(actions.preferences, actions, onBack = { left++ }) }
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertEquals(1, left)
    }

    // ── Keyboard, connection, appearance ─────────────────────────────

    @Test
    fun `clipboard sharing can be switched off`() {
        compose.show { KeyboardSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.keyboard_clipboard)).performClick()
        assertFalse(actions.preferences.clipboardSync)
    }

    @Test
    fun `the PC type can be chosen for bluetooth`() {
        compose.show { KeyboardSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.remote_os_macos)).performClick()
        assertEquals(HostOsChoice.MACOS, actions.preferences.assumedHostOs)
    }

    @Test
    fun `staying connected in the background asks for notification permission`() {
        val platform = RecordingPlatform()
        compose.show(platform) { ConnectionSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.connection_keep_alive)).performClick()
        assertTrue(actions.preferences.keepConnectionAlive)
        assertEquals(listOf("notification-permission"), platform.calls)
    }

    @Test
    fun `automatic connection is on by default and can be turned off`() {
        assertTrue(actions.preferences.autoConnect)
        compose.show { ConnectionSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.connection_auto)).performClick()
        assertFalse(actions.preferences.autoConnect)
    }

    @Test
    fun `theme and accent can be chosen`() {
        compose.show { AppearanceSettingsScreen(actions.preferences, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.appearance_theme_dark)).performClick()
        assertEquals(ThemeMode.DARK, actions.preferences.themeMode)
        compose.onNodeWithContentDescription(string(R.string.accent_purple)).performClick()
        assertEquals(AccentColor.PURPLE, actions.preferences.accentColor)
    }

    // ── Privacy ──────────────────────────────────────────────────────

    @Test
    fun `a paired PC is forgotten only after confirming`() {
        compose.show { PrivacySettingsScreen(Fixtures.pairedDevices, actions, onBack = {}) }
        compose.onNodeWithText("Desk PC").assertIsDisplayed()
        compose.onAllForgetButtons()[0].performClick()
        compose.settle()
        assertTrue(actions.forgotten.isEmpty())
        compose.onNode(hasText(string(R.string.action_forget)) and hasAnyAncestor(isDialog())).performClick()
        compose.settle()
        assertEquals(listOf(Fixtures.pairedDevices[0]), actions.forgotten)
    }

    @Test
    fun `forgetting everything asks first`() {
        compose.show { PrivacySettingsScreen(Fixtures.pairedDevices, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.privacy_forget_all)).performClick()
        compose.settle()
        assertEquals(0, actions.forgotAll)
        compose.onNode(hasText(string(R.string.privacy_forget_all)) and hasAnyAncestor(isDialog())).performClick()
        compose.settle()
        assertEquals(1, actions.forgotAll)
    }

    @Test
    fun `replacing the phone's key asks first`() {
        compose.show { PrivacySettingsScreen(Fixtures.pairedDevices, actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.privacy_identity_reset)).performClick()
        compose.settle()
        assertEquals(0, actions.identityResets)
        compose.onNode(hasText(string(R.string.privacy_identity_reset)) and hasAnyAncestor(isDialog())).performClick()
        compose.settle()
        assertEquals(1, actions.identityResets)
    }

    @Test
    fun `with no paired PCs the page says so`() {
        compose.show { PrivacySettingsScreen(emptyList(), actions, onBack = {}) }
        compose.onNodeWithText(string(R.string.privacy_paired_none)).assertIsDisplayed()
    }

    @Test
    fun `paired PCs show a fingerprint to compare`() {
        compose.show { PrivacySettingsScreen(Fixtures.pairedDevices, actions, onBack = {}) }
        // Any fingerprint line; the exact value depends on the key.
        compose.onAllNodes(hasText("Fingerprint ", substring = true)).onFirst().assertIsDisplayed()
    }

    // ── Onboarding ───────────────────────────────────────────────────

    @Test
    fun `onboarding pages advance and the last one finishes`() {
        var finished = 0
        compose.show { OnboardingScreen(onFinish = { finished++ }) }
        compose.onNodeWithText(string(R.string.onboarding_welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_next)).performClick()
        compose.settle(800)
        compose.onNodeWithText(string(R.string.onboarding_install_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_next)).performClick()
        compose.settle(800)
        compose.onNodeWithText(string(R.string.onboarding_network_title)).assertIsDisplayed()
        assertEquals(0, finished)
        compose.onNodeWithText(string(R.string.onboarding_get_started)).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `onboarding can be skipped`() {
        var finished = 0
        compose.show { OnboardingScreen(onFinish = { finished++ }) }
        compose.onNodeWithText(string(R.string.action_skip)).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `onboarding links to the download`() {
        val platform = RecordingPlatform()
        compose.show(platform) { OnboardingScreen(onFinish = {}) }
        compose.onNodeWithText(string(R.string.action_next)).performClick()
        compose.settle(800)
        compose.onNodeWithText(string(R.string.onboarding_download)).performClick()
        assertEquals(listOf("open:${Links.DOWNLOAD}"), platform.calls)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllForgetButtons() =
        onAllNodes(hasText(string(R.string.action_forget)))
}
