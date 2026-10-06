package com.omsingh.telepad.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Things only the Activity can do: ask for a permission, open another app. The UI
 * asks for them through this interface rather than holding on to the Activity, which
 * keeps screens previewable and testable (a test supplies [None]).
 */
interface PlatformActions {
    /** Ask for the permission needed to see paired Bluetooth devices. */
    fun requestBluetoothPermission()

    /** Ask the system to switch Bluetooth on. */
    fun enableBluetooth()

    /** Ask for permission to show the notification that keeps the connection alive. */
    fun requestNotificationPermission()

    /** Open a web page. */
    fun openUrl(url: String)

    /**
     * Show the system's Bluetooth settings, where a PC is paired with this phone. The phone
     * is visible to other devices for as long as that screen is open.
     */
    fun openBluetoothSettings()

    /** Show the system's settings for this app (for a permission that was refused for good). */
    fun openAppSettings()

    companion object {
        /** Does nothing; for previews and tests. */
        val None = object : PlatformActions {
            override fun requestBluetoothPermission() = Unit
            override fun enableBluetooth() = Unit
            override fun requestNotificationPermission() = Unit
            override fun openUrl(url: String) = Unit
            override fun openBluetoothSettings() = Unit
            override fun openAppSettings() = Unit
        }
    }
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { PlatformActions.None }

/** Where people get Telepad for their PC, and where the project lives. */
object Links {
    const val RELEASES = "https://github.com/omsingh02/telepad/releases/latest"
    const val REPOSITORY = "https://github.com/omsingh02/telepad"
    const val ISSUES = "https://github.com/omsingh02/telepad/issues"
}
