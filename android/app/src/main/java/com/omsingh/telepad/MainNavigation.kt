package com.omsingh.telepad

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omsingh.telepad.core.wifi.ServerInfo
import com.omsingh.telepad.ui.screens.AddDeviceScreen
import com.omsingh.telepad.ui.screens.ControlsScreen
import com.omsingh.telepad.ui.screens.HomeScreen
import com.omsingh.telepad.ui.screens.KeyboardScreen
import com.omsingh.telepad.ui.screens.OnboardingScreen
import com.omsingh.telepad.ui.screens.PairingScreen
import com.omsingh.telepad.ui.screens.TouchpadCalibrationScreen
import com.omsingh.telepad.ui.screens.TouchpadScreen
import com.omsingh.telepad.ui.screens.settings.AboutScreen
import com.omsingh.telepad.ui.screens.settings.AppearanceSettingsScreen
import com.omsingh.telepad.ui.screens.settings.ConnectionSettingsScreen
import com.omsingh.telepad.ui.screens.settings.KeyboardSettingsScreen
import com.omsingh.telepad.ui.screens.settings.PrivacySettingsScreen
import com.omsingh.telepad.ui.screens.settings.SettingsDestination
import com.omsingh.telepad.ui.screens.settings.SettingsScreen
import com.omsingh.telepad.ui.screens.settings.TouchpadSettingsScreen
import com.omsingh.telepad.viewmodel.MainViewModel
import com.omsingh.telepad.viewmodel.PairingViewModel
import com.omsingh.telepad.viewmodel.SettingsViewModel

/** Top-level routes. */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD_DEVICE = "add_device"
    const val PAIRING = "pairing"
    const val TOUCHPAD = "touchpad"
    const val KEYBOARD = "keyboard"
    const val CONTROLS = "controls"
    const val SETTINGS = "settings"
    const val SETTINGS_TOUCHPAD = "settings/touchpad"
    const val SETTINGS_TOUCHPAD_CAL = "settings/touchpad/calibrate"
    const val SETTINGS_KEYBOARD = "settings/keyboard"
    const val SETTINGS_CONNECTION = "settings/connection"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_ABOUT = "settings/about"
}

private val tabs = listOf(
    Tab(Routes.HOME,     "Home",     Icons.Filled.Link),
    Tab(Routes.TOUCHPAD, "Touchpad", Icons.Filled.TouchApp),
    Tab(Routes.KEYBOARD, "Keys",     Icons.Filled.Keyboard),
    Tab(Routes.CONTROLS, "Remote",   Icons.Filled.SettingsRemote),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun TelepadNavHost(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    pairingViewModel: PairingViewModel,
    onboardingShown: Boolean,
    onOnboardingFinished: () -> Unit,
    introShown: Boolean,
    onIntroDismissed: () -> Unit,
    bluetoothGranted: Boolean,
    onRequestBluetooth: () -> Unit,
) {
    val nav = rememberNavController()
    var pendingPairing by remember { mutableStateOf<ServerInfo?>(null) }

    // If a pairing target arrives from anywhere, push the pairing screen.
    if (pendingPairing != null) {
        nav.navigate(Routes.PAIRING) {
            launchSingleTop = true
        }
        pendingPairing = null
    }

    Scaffold(
        bottomBar = {
            BottomTabs(nav, hideOn = setOf(Routes.ONBOARDING, Routes.PAIRING, Routes.ADD_DEVICE))
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = if (onboardingShown) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                })
            }

            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = mainViewModel,
                    onNavigateToTouchpad = { nav.navigate(Routes.TOUCHPAD) },
                    onNavigateToAddDevice = { nav.navigate(Routes.ADD_DEVICE) },
                    onPairingNeeded = { server -> pendingPairing = server }
                )
            }

            composable(Routes.ADD_DEVICE) {
                AddDeviceScreen(
                    viewModel = mainViewModel,
                    bluetoothGranted = bluetoothGranted,
                    onRequestBluetooth = onRequestBluetooth,
                    onBack = { nav.popBackStack() },
                    onPairingNeeded = { server -> pendingPairing = server }
                )
            }

            composable(Routes.PAIRING) {
                val target = mainViewModel.pendingPairingFor.collectAsState().value
                if (target != null) {
                    PairingScreen(
                        server = target,
                        viewModel = pairingViewModel,
                        onTrust = { srv, pub ->
                            mainViewModel.completePairingAndConnect(srv, pub)
                            pairingViewModel.reset()
                            nav.popBackStack(Routes.HOME, false)
                            nav.navigate(Routes.TOUCHPAD)
                        },
                        onCancel = {
                            mainViewModel.cancelPairing()
                            nav.popBackStack()
                        }
                    )
                } else {
                    // Should not happen — guarded above.
                    nav.popBackStack()
                }
            }

            composable(Routes.TOUCHPAD) {
                val prefs = settingsViewModel.preferences.collectAsState().value
                TouchpadScreen(
                    viewModel = mainViewModel,
                    preferences = prefs,
                    introShown = introShown,
                    onIntroDismissed = onIntroDismissed,
                )
            }

            composable(Routes.KEYBOARD) { KeyboardScreen(viewModel = mainViewModel) }
            composable(Routes.CONTROLS) { ControlsScreen(viewModel = mainViewModel) }

            composable(Routes.SETTINGS) {
                SettingsScreen(onNavigate = { dest ->
                    nav.navigate(when (dest) {
                        SettingsDestination.Touchpad   -> Routes.SETTINGS_TOUCHPAD
                        SettingsDestination.Keyboard   -> Routes.SETTINGS_KEYBOARD
                        SettingsDestination.Connection -> Routes.SETTINGS_CONNECTION
                        SettingsDestination.Appearance -> Routes.SETTINGS_APPEARANCE
                        SettingsDestination.Privacy    -> Routes.SETTINGS_PRIVACY
                        SettingsDestination.About      -> Routes.SETTINGS_ABOUT
                    })
                })
            }
            composable(Routes.SETTINGS_TOUCHPAD) {
                TouchpadSettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { nav.popBackStack() },
                    onOpenCalibration = { nav.navigate(Routes.SETTINGS_TOUCHPAD_CAL) }
                )
            }
            composable(Routes.SETTINGS_TOUCHPAD_CAL) {
                TouchpadCalibrationScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_KEYBOARD) {
                KeyboardSettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_CONNECTION) {
                ConnectionSettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_APPEARANCE) {
                AppearanceSettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_PRIVACY) {
                PrivacySettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_ABOUT) {
                AboutScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomTabs(nav: NavHostController, hideOn: Set<String>) {
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: return
    if (route in hideOn) return

    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    if (route != tab.route) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                alwaysShowLabel = false,
            )
        }
    }
}

