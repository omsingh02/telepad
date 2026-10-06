package com.omsingh.telepad.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.omsingh.telepad.connection.BluetoothAvailability
import com.omsingh.telepad.connection.PairingUiState
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.ThemeMode
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.screens.devices.AddDeviceContent
import com.omsingh.telepad.ui.screens.devices.AddTab
import com.omsingh.telepad.ui.screens.devices.DevicesActions
import com.omsingh.telepad.ui.screens.devices.DevicesScreen
import com.omsingh.telepad.ui.screens.devices.DevicesUiState
import com.omsingh.telepad.ui.screens.devices.PairingSheetContent
import com.omsingh.telepad.ui.screens.onboarding.OnboardingScreen
import com.omsingh.telepad.ui.screens.remote.RemoteActions
import com.omsingh.telepad.ui.screens.remote.RemoteScreen
import com.omsingh.telepad.ui.screens.settings.AboutScreen
import com.omsingh.telepad.ui.screens.settings.AppearanceSettingsScreen
import com.omsingh.telepad.ui.screens.settings.ConnectionSettingsScreen
import com.omsingh.telepad.ui.screens.settings.KeyboardSettingsScreen
import com.omsingh.telepad.ui.screens.settings.PrivacySettingsScreen
import com.omsingh.telepad.ui.screens.settings.SettingsActions
import com.omsingh.telepad.ui.screens.settings.SettingsHomeScreen
import com.omsingh.telepad.ui.screens.settings.TouchpadSettingsScreen
import com.omsingh.telepad.ui.theme.TelepadTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w411dp-h891dp-normal-long-notround-any-320dpi-keyshidden-nonav"
private const val PHONE_LANDSCAPE = "w891dp-h411dp-normal-long-notround-land-any-320dpi-keyshidden-nonav"
private const val TABLET = "w800dp-h1280dp-large-notlong-notround-any-240dpi-keyshidden-nonav"

/**
 * Pictures of every screen, in light and dark and in the awkward shapes (landscape, tablet),
 * produced with real rendering and no device. They are kept in `src/test/screenshots` so the
 * README can show them and a design change shows up in review as a changed picture.
 *
 * `./gradlew recordRoborazziDebug` redraws them; `verifyRoborazziDebug` fails if one changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PHONE, application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun noAnimations() {
        // The same switch a person turns off in Developer options: nothing animates, nothing loops.
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
    }

    private fun shot(
        name: String,
        dark: Boolean = false,
        accent: AccentColor = AccentColor.CYAN,
        interact: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            TelepadTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, accent = accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        interact()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun openTab(label: String) = { compose.onNodeWithText(label).performClick(); Unit }

    // ── Devices ──────────────────────────────────────────────────────

    @Test fun devices_list() = shot("devices-list") {
        DevicesScreen(Fixtures.devicesList, DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun devices_list_dark() = shot("devices-list-dark", dark = true) {
        DevicesScreen(Fixtures.devicesList, DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun devices_connected() = shot("devices-connected") {
        DevicesScreen(Fixtures.devicesConnected, DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun devices_empty() = shot("devices-empty") {
        DevicesScreen(DevicesUiState(searching = true), DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun devices_unreachable() = shot("devices-unreachable") {
        val failed = ConnectionState.Failed("Desk PC", ConnectionState.Transport.WIFI, FailureReason.UNREACHABLE)
        DevicesScreen(Fixtures.devicesList.copy(connection = failed), DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun devices_reconnecting() = shot("devices-reconnecting") {
        val state = ConnectionState.Reconnecting("Desk PC", ConnectionState.Transport.WIFI, 2)
        DevicesScreen(Fixtures.devicesList.copy(connection = state, activeId = "KEY-DESK"), DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun pairing_verify() = shot("pairing-verify") { SheetOver { PairingSheetContent(Fixtures.verify, {}, {}, {}) } }

    @Test fun pairing_verify_dark() = shot("pairing-verify-dark", dark = true) { SheetOver { PairingSheetContent(Fixtures.verify, {}, {}, {}) } }

    @Test fun pairing_key_changed() = shot("pairing-key-changed") { SheetOver { PairingSheetContent(Fixtures.keyChanged, {}, {}, {}) } }

    @Test fun pairing_not_paired() = shot("pairing-not-paired") {
        SheetOver { PairingSheetContent(PairingUiState.Failed("Desk PC", FailureReason.NOT_PAIRED), {}, {}, {}) }
    }

    @Test fun add_address() = shot("add-address") {
        SheetOver { AddDeviceContent(AddTab.ADDRESS, {}, emptyList(), BluetoothAvailability.READY, { _, _ -> }, {}) }
    }

    @Test fun add_bluetooth() = shot("add-bluetooth") {
        SheetOver { AddDeviceContent(AddTab.BLUETOOTH, {}, Fixtures.bluetoothDevices, BluetoothAvailability.READY, { _, _ -> }, {}) }
    }

    /** Draws [content] the way a bottom sheet does: over a dimmed screen, with the handle on top. */
    @Composable
    private fun SheetOver(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    content()
                }
            }
        }
    }

    // ── Remote ───────────────────────────────────────────────────────

    private fun remote(state: com.omsingh.telepad.ui.screens.remote.RemoteUiState) = @Composable {
        RemoteScreen(state, RemoteActions.None, KeyboardSession { }, onGoToDevices = {})
    }

    @Test fun remote_pad() = shot("remote-pad", content = remote(Fixtures.remote()))

    @Test fun remote_pad_dark() = shot("remote-pad-dark", dark = true, content = remote(Fixtures.remote()))

    @Test fun remote_not_connected() = shot("remote-not-connected", content = remote(Fixtures.remote(connection = ConnectionState.Disconnected)))

    @Test fun remote_reconnecting() = shot(
        "remote-reconnecting",
        content = remote(Fixtures.remote(connection = ConnectionState.Reconnecting("Desk PC", ConnectionState.Transport.WIFI, 1))),
    )

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun remote_pad_landscape() = shot("remote-pad-landscape", content = remote(Fixtures.remote()))

    @Test @Config(qualifiers = TABLET) fun remote_pad_tablet() = shot("remote-pad-tablet", content = remote(Fixtures.remote()))

    // ── Onboarding and settings ──────────────────────────────────────

    @Test fun onboarding() = shot("onboarding") { OnboardingScreen(onFinish = {}) }

    @Test fun settings_home() = shot("settings-home") { SettingsHomeScreen(SettingsActions.None, onOpen = {}) }

    @Test fun settings_touchpad() = shot("settings-touchpad") {
        TouchpadSettingsScreen(UserPreferences(), SettingsActions.None, onBack = {})
    }

    @Test fun settings_keyboard() = shot("settings-keyboard") { KeyboardSettingsScreen(UserPreferences(), SettingsActions.None, onBack = {}) }

    @Test fun settings_connection() = shot("settings-connection") { ConnectionSettingsScreen(UserPreferences(), SettingsActions.None, onBack = {}) }

    @Test fun settings_appearance() = shot("settings-appearance") { AppearanceSettingsScreen(UserPreferences(), SettingsActions.None, onBack = {}) }

    @Test fun settings_privacy() = shot("settings-privacy") { PrivacySettingsScreen(Fixtures.pairedDevices, SettingsActions.None, onBack = {}) }

    @Test fun settings_about() = shot("settings-about") { AboutScreen(onBack = {}) }

    // ── Themes ───────────────────────────────────────────────────────

    @Test fun accent_purple() = shot("devices-purple", accent = AccentColor.PURPLE) {
        DevicesScreen(Fixtures.devicesConnected, DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun accent_orange_dark() = shot("devices-orange-dark", dark = true, accent = AccentColor.ORANGE) {
        DevicesScreen(Fixtures.devicesConnected, DevicesActions.None, onOpenRemote = {}, nowMs = Fixtures.NOW)
    }

    @Test fun remote_keys() = shot("remote-keys", interact = openTab("Keys"), content = remote(Fixtures.remote()))

    @Test fun remote_keys_dark() = shot("remote-keys-dark", dark = true, interact = openTab("Keys"), content = remote(Fixtures.remote()))

    @Test fun remote_keys_mac() = shot("remote-keys-mac", interact = openTab("Keys"), content = remote(Fixtures.remote(os = HostOs.MACOS)))

    @Test fun remote_media() = shot("remote-media", interact = openTab("Media"), content = remote(Fixtures.remote()))

    @Test fun remote_media_dark() = shot("remote-media-dark", dark = true, interact = openTab("Media"), content = remote(Fixtures.remote()))

    @Test fun remote_media_bluetooth_mac() = shot(
        "remote-media-bluetooth-mac",
        interact = openTab("Media"),
        content = remote(
            Fixtures.remote(
                os = HostOs.MACOS,
                connection = ConnectionState.Connected("MacBook", ConnectionState.Transport.BLUETOOTH),
                nowPlaying = false,
            ),
        ),
    )
}
