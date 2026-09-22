package com.omsingh.telepad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.viewmodel.MainViewModel
import com.omsingh.telepad.viewmodel.PairingViewModel
import com.omsingh.telepad.viewmodel.SettingsViewModel

/**
 * Single activity, hosts the whole NavGraph.
 *
 * Permission strategy is just-in-time: we don't ask for Bluetooth at startup.
 * The AddDevice screen's Bluetooth tab triggers the request only when the user
 * tries to use it. Same pattern for POST_NOTIFICATIONS — only requested when
 * the keep-alive setting is toggled on for the first time.
 *
 * Onboarding-shown flag is persisted in a single SharedPreferences entry,
 * keeping the splash-to-first-screen path cheap.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val pairingViewModel: PairingViewModel by viewModels()

    private val bluetoothPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }.toTypedArray()

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        bluetoothGranted.value = granted
        mainViewModel.onBluetoothPermissionChanged(granted)
    }

    private val bluetoothGranted by lazy { mutableStateOf(hasBluetoothPermission()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val prefs by settingsViewModel.preferences.collectAsState()

            TelepadTheme(
                themeMode = prefs.themeMode,
                accentColor = prefs.accentColor,
                dynamicColor = prefs.dynamicColor,
            ) {
                TelepadNavHost(
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel,
                    pairingViewModel = pairingViewModel,
                    onboardingShown = prefs.onboardingShown,
                    onOnboardingFinished = {
                        settingsViewModel.updatePreferences { it.copy(onboardingShown = true) }
                    },
                    introShown = prefs.touchpadIntroShown,
                    onIntroDismissed = {
                        settingsViewModel.updatePreferences { it.copy(touchpadIntroShown = true) }
                    },
                    bluetoothGranted = bluetoothGranted.value,
                    onRequestBluetooth = {
                        bluetoothPermissionLauncher.launch(bluetoothPermissions)
                    },
                )
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean = bluetoothPermissions.all { p ->
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }
}
