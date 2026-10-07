package com.omsingh.telepad.ui.navigation

import kotlinx.serialization.Serializable

/** Where the app can go. These are the type-safe routes the navigation graph uses. */
@Serializable data object Onboarding

@Serializable data object Devices

/** The camera, scanning the QR code on a PC's screen. Not a tab: it covers the whole screen. */
@Serializable data object Scan

@Serializable data object Remote

@Serializable data object Settings

/** The list of open source libraries and their licenses, opened from the About page. */
@Serializable data object Licenses

/** One page of the settings, by the name of its [com.omsingh.telepad.ui.screens.settings.SettingsPage]. */
@Serializable data class SettingsDetail(val page: String)
