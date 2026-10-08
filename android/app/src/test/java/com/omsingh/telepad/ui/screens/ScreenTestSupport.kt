package com.omsingh.telepad.ui.screens

import android.app.Application
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.platform.PlatformActions
import com.omsingh.telepad.ui.theme.TelepadTheme
import org.robolectric.RuntimeEnvironment

/** What the platform was asked to do (open a link, request a permission), for tests to check. */
class RecordingPlatform : PlatformActions {
    val calls = mutableListOf<String>()
    override fun requestBluetoothPermission() { calls += "bluetooth-permission" }
    override fun enableBluetooth() { calls += "enable-bluetooth" }
    override fun requestNotificationPermission() { calls += "notification-permission" }
    override fun openUrl(url: String) { calls += "open:$url" }
    override fun openBluetoothSettings() { calls += "bluetooth-settings" }
    override fun openAppSettings() { calls += "app-settings" }
    override fun openInstallPermissionSettings() { calls += "install-permission-settings" }
}

/** Switches system animations off, as Developer options does, so that nothing loops. */
fun noAnimations() {
    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
}

/** Shows [content] in the app theme, with the given platform, and lets it settle. */
fun ComposeContentTestRule.show(platform: PlatformActions = PlatformActions.None, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        TelepadTheme {
            CompositionLocalProvider(LocalPlatformActions provides platform) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }
    settle()
}

/** Lets pending recomposition, animations and effects run. */
fun ComposeContentTestRule.settle(ms: Long = 400) {
    mainClock.advanceTimeBy(ms)
}

fun string(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

/** The application class the tests run with: the real one starts services that tests do not want. */
typealias PlainApplication = Application

/**
 * Clicks [node] by its own click action. Unlike a touch, this works on a control that is scrolled
 * out of view (it is not on screen, but it is there), and scrolling in a test with a frozen clock
 * would never finish.
 */
fun ComposeContentTestRule.activate(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
    val click = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action
    runOnUiThread { click?.invoke() }
    settle()
}
