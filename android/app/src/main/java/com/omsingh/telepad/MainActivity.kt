package com.omsingh.telepad

import android.Manifest
import android.content.SharedPreferences
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    private lateinit var onboardingPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onboardingPrefs = getSharedPreferences("telepad_onboarding", MODE_PRIVATE)
        val initialOnboardingShown = onboardingPrefs.getBoolean(KEY_ONBOARDING_SHOWN, false)
        val initialIntroShown = onboardingPrefs.getBoolean(KEY_TOUCHPAD_INTRO_SHOWN, false)

        setContent {
            val prefs by settingsViewModel.preferences.collectAsState()
            var onboardingShown by remember { mutableStateOf(initialOnboardingShown) }
            var introShown by remember { mutableStateOf(initialIntroShown) }

            TelepadTheme(
                themeMode = prefs.themeMode,
                accentColor = prefs.accentColor,
                dynamicColor = prefs.dynamicColor,
            ) {
                TelepadNavHost(
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel,
                    pairingViewModel = pairingViewModel,
                    onboardingShown = onboardingShown,
                    onOnboardingFinished = {
                        onboardingPrefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
                        onboardingShown = true
                    },
                    introShown = introShown,
                    onIntroDismissed = {
                        onboardingPrefs.edit().putBoolean(KEY_TOUCHPAD_INTRO_SHOWN, true).apply()
                        introShown = true
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

    private companion object {
        const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
        const val KEY_TOUCHPAD_INTRO_SHOWN = "touchpad_intro_shown"
    }
}
