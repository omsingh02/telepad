package com.omsingh.telepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.core.wifi.PairingInvite
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.platform.PlatformActions
import com.omsingh.telepad.ui.TelepadRoot
import com.omsingh.telepad.ui.screens.devices.DevicesViewModel
import com.omsingh.telepad.ui.screens.remote.RemoteViewModel
import com.omsingh.telepad.ui.screens.settings.SettingsViewModel
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.update.UpdateViewModel

/**
 * The only activity. It hosts the Compose UI and does what only an activity can: ask for
 * permissions and open other apps.
 *
 * Permissions are asked for at the moment they are needed, with a reason on screen:
 * Bluetooth only when the person opens the Bluetooth tab, notifications only when they
 * switch on staying connected in the background.
 */
class MainActivity : ComponentActivity() {

    private val settings: SettingsViewModel by viewModels()
    private val devices: DevicesViewModel by viewModels()
    private val remote: RemoteViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()

    private val manager by lazy { ConnectionManager.getInstance(application) }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        manager.refreshBluetooth()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.refreshBluetooth()
    }

    private val platform = object : PlatformActions {
        override fun requestBluetoothPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                manager.refreshBluetooth()
            }
        }

        override fun enableBluetooth() {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        override fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        override fun openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

        override fun openBluetoothSettings() = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

        override fun openAppSettings() =
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

        override fun openInstallPermissionSettings() =
            open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))

        private fun open(intent: Intent) {
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // Nothing on this phone can handle it; there is nothing useful to do.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until the saved settings are read; the app itself is built after that.
        splash.setKeepOnScreenCondition { !settings.loaded.value }
        enableEdgeToEdge()
        // A link that started the app. (Not again when the system rebuilds the activity: the code in
        // it was good for one use.)
        if (savedInstanceState == null) openPairingLink(intent)

        setContent {
            val preferences by settings.preferences.collectAsStateWithLifecycle()
            val loaded by settings.loaded.collectAsStateWithLifecycle()
            TelepadTheme(
                themeMode = preferences.themeMode,
                accent = preferences.accentColor,
                dynamicColor = preferences.dynamicColor,
            ) {
                CompositionLocalProvider(LocalPlatformActions provides platform) {
                    // The first screen depends on the saved settings, and it is chosen once, when
                    // the app is first built. So nothing is built until they are read (the splash
                    // screen covers the wait); building it earlier opened the walkthrough every time.
                    if (loaded) {
                        TelepadRoot(
                            settingsViewModel = settings,
                            devicesViewModel = devices,
                            remoteViewModel = remote,
                            updateViewModel = updates,
                            startAtOnboarding = !preferences.onboardingShown,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        manager.onAppForegrounded()
    }

    /** A link opened while the app is running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPairingLink(intent)
    }

    /**
     * Pairs with the PC in a `telepad://pair` link, which is what its QR code says, when something other
     * than the app's own scanner opens it (a camera app, a message). Anything else is ignored.
     */
    private fun openPairingLink(intent: Intent?) {
        val link = intent?.data?.takeIf { it.scheme.equals("telepad", ignoreCase = true) && it.host.equals("pair", ignoreCase = true) }
            ?: return
        // Handled: it must not be handled again.
        intent.data = null
        when (val read = PairingInvite.parse(link.toString())) {
            is PairingInvite.Read.Valid -> devices.pairWithInvite(read.invite)
            PairingInvite.Read.NeedsNewerApp -> Toast.makeText(this, R.string.scan_problem_newer, Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this, R.string.scan_problem_damaged, Toast.LENGTH_LONG).show()
        }
    }
}
