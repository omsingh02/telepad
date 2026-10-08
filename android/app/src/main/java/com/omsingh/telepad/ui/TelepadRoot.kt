package com.omsingh.telepad.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.ui.navigation.Devices
import com.omsingh.telepad.ui.navigation.Onboarding
import com.omsingh.telepad.ui.navigation.Remote
import com.omsingh.telepad.ui.navigation.Scan
import com.omsingh.telepad.ui.navigation.Settings
import com.omsingh.telepad.ui.navigation.SettingsDetail
import com.omsingh.telepad.ui.screens.devices.DevicesRoute
import com.omsingh.telepad.ui.screens.devices.DevicesViewModel
import com.omsingh.telepad.ui.screens.onboarding.OnboardingScreen
import com.omsingh.telepad.ui.screens.remote.RemoteRoute
import com.omsingh.telepad.ui.screens.remote.RemoteViewModel
import com.omsingh.telepad.ui.screens.scan.ScanRoute
import com.omsingh.telepad.ui.screens.settings.AboutScreen
import com.omsingh.telepad.ui.screens.settings.LicensesScreen
import com.omsingh.telepad.ui.navigation.Licenses
import com.omsingh.telepad.ui.screens.settings.AppearanceSettingsScreen
import com.omsingh.telepad.ui.screens.settings.ConnectionSettingsScreen
import com.omsingh.telepad.ui.screens.settings.KeyboardSettingsScreen
import com.omsingh.telepad.ui.screens.settings.PrivacySettingsScreen
import com.omsingh.telepad.ui.screens.settings.SettingsHomeScreen
import com.omsingh.telepad.ui.screens.settings.SettingsPage
import com.omsingh.telepad.ui.screens.settings.SettingsViewModel
import com.omsingh.telepad.ui.screens.settings.TouchpadSettingsScreen
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.update.UpdateState
import com.omsingh.telepad.update.UpdateViewModel

private enum class Tab(val icon: ImageVector, val label: Int) {
    DEVICES(Icons.Rounded.Computer, R.string.nav_devices),
    REMOTE(Icons.Rounded.SettingsRemote, R.string.nav_remote),
    SETTINGS(Icons.Rounded.Settings, R.string.nav_settings),
}

/**
 * The app: three places (Devices, Remote, Settings) and the pages under Settings.
 *
 * Connecting from the device list takes you straight to the Remote, since that is what
 * you connected for. The navigation is a bar under the content on a phone held upright and a
 * rail beside it when there is more width than height, and it steps out of the way of
 * the on-screen keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelepadRoot(
    settingsViewModel: SettingsViewModel,
    devicesViewModel: DevicesViewModel,
    remoteViewModel: RemoteViewModel,
    updateViewModel: UpdateViewModel,
    startAtOnboarding: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    // Only the first value counts: changing the start destination later would rebuild the graph.
    val start = remember { if (startAtOnboarding) Onboarding else Devices }
    val preferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val paired by settingsViewModel.pairedDevices.collectAsStateWithLifecycle()
    val remote by remoteViewModel.state.collectAsStateWithLifecycle()
    val updates by updateViewModel.ui.collectAsStateWithLifecycle()
    // A newer version is waiting: Settings wears a dot, and About says so.
    val updateWaiting = updates.release != null && updates.state !is UpdateState.Installing

    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val tab = destination.tab()
    val imeVisible = WindowInsets.isImeVisible
    val showNavigation = tab != null && !imeVisible
    val connected = remote.connection is ConnectionState.Connected

    // A pairing that starts away from the device list (a link opened from outside the app) is shown
    // there, where the sheet that says how it goes lives.
    val devices by devicesViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(devices.pairing != null) {
        if (devices.pairing != null && navController.currentDestination.tab() != Tab.DEVICES) {
            navController.navigateToTab(Tab.DEVICES)
        }
    }

    // Connecting from the device list leads to the Remote, but only the first time a
    // connection comes up: a reconnect must not pull someone away from what they are doing.
    var previous by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    LaunchedEffect(remote.connection) {
        val now = remote.connection
        if (now is ConnectionState.Connected && previous is ConnectionState.Connecting && navController.currentDestination.tab() == Tab.DEVICES) {
            navController.navigateToTab(Tab.REMOTE)
        }
        previous = now
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val rail = maxWidth > maxHeight || maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail && showNavigation) {
                NavigationRail {
                    for (item in Tab.values()) {
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { navController.navigateToTab(item) },
                            icon = { TabIcon(item, connected, updateWaiting) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    AnimatedVisibility(
                        visible = !rail && showNavigation,
                        enter = fadeIn() , exit = fadeOut(),
                    ) {
                        NavigationBar {
                            for (item in Tab.values()) {
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { navController.navigateToTab(item) },
                                    icon = { TabIcon(item, connected, updateWaiting) },
                                    label = { Text(stringResource(item.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = start,
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    enterTransition = { fadeIn(tween(210, delayMillis = 90)) },
                    exitTransition = { fadeOut(tween(90)) },
                    popEnterTransition = { fadeIn(tween(210, delayMillis = 90)) },
                    popExitTransition = { fadeOut(tween(90)) },
                ) {
                    composable<Onboarding> {
                        OnboardingScreen(
                            onFinish = {
                                settingsViewModel.update { it.copy(onboardingShown = true) }
                                navController.navigate(Devices) { popUpTo<Onboarding> { inclusive = true } }
                            },
                        )
                    }
                    composable<Devices> {
                        DevicesRoute(
                            devicesViewModel,
                            onOpenRemote = { navController.navigateToTab(Tab.REMOTE) },
                            onScan = { navController.navigate(Scan) },
                        )
                    }
                    composable<Scan> {
                        ScanRoute(
                            onInvite = { invite ->
                                // Back to the device list, where the pairing sheet shows how it goes.
                                devicesViewModel.pairWithInvite(invite)
                                navController.popBackStack()
                            },
                            onClose = { navController.popBackStack() },
                        )
                    }
                    composable<Remote> {
                        RemoteRoute(remoteViewModel, onGoToDevices = { navController.navigateToTab(Tab.DEVICES) })
                    }
                    composable<Settings> {
                        SettingsHomeScreen(
                            actions = settingsViewModel,
                            updateAvailable = updateWaiting,
                            onOpen = { page -> navController.navigate(SettingsDetail(page.name)) },
                        )
                    }
                    composable<Licenses> {
                        LicensesScreen(onBack = { navController.popBackStack() })
                    }
                    composable<SettingsDetail>(
                        enterTransition = { slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260)) },
                        exitTransition = { slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(200)) },
                        popEnterTransition = { slideInHorizontally(tween(260)) { -it / 8 } + fadeIn(tween(260)) },
                        popExitTransition = { slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(200)) },
                    ) { entry ->
                        val page = runCatching { SettingsPage.valueOf(entry.toRoute<SettingsDetail>().page) }.getOrDefault(SettingsPage.TOUCHPAD)
                        val back = { navController.popBackStack(); Unit }
                        when (page) {
                            SettingsPage.TOUCHPAD -> TouchpadSettingsScreen(preferences, settingsViewModel, back)
                            SettingsPage.KEYBOARD -> KeyboardSettingsScreen(preferences, settingsViewModel, back)
                            SettingsPage.CONNECTION -> ConnectionSettingsScreen(preferences, settingsViewModel, back)
                            SettingsPage.APPEARANCE -> AppearanceSettingsScreen(preferences, settingsViewModel, back)
                            SettingsPage.PRIVACY -> PrivacySettingsScreen(paired, settingsViewModel, back)
                            SettingsPage.ABOUT -> AboutScreen(
                                back,
                                onLicenses = { navController.navigate(Licenses) },
                                updates = updates,
                                updateActions = updateViewModel,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Remote's icon wears a dot while connected, so the status is visible from anywhere; Settings wears one
 * while a newer version is waiting.
 */
@Composable
private fun TabIcon(tab: Tab, connected: Boolean, updateWaiting: Boolean) {
    if (tab == Tab.REMOTE && connected) {
        BadgedBox(badge = { Badge(containerColor = TelepadTheme.extended.success) }) {
            Icon(tab.icon, contentDescription = null)
        }
    } else if (tab == Tab.SETTINGS && updateWaiting) {
        BadgedBox(badge = { Badge() }) {
            Icon(tab.icon, contentDescription = null)
        }
    } else {
        Icon(tab.icon, contentDescription = null)
    }
}

private fun NavDestination?.tab(): Tab? = when {
    this == null -> null
    hasRoute<Devices>() -> Tab.DEVICES
    hasRoute<Remote>() -> Tab.REMOTE
    hasRoute<Settings>() -> Tab.SETTINGS
    else -> null
}

private fun NavHostController.navigateToTab(tab: Tab) {
    val route: Any = when (tab) {
        Tab.DEVICES -> Devices
        Tab.REMOTE -> Remote
        Tab.SETTINGS -> Settings
    }
    navigate(route) {
        popUpTo<Devices> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
