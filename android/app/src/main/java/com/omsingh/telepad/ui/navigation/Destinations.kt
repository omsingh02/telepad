package com.omsingh.telepad.ui.navigation

import kotlinx.serialization.Serializable

/** Where the app can go. These are the type-safe routes the navigation graph uses. */
@Serializable data object Onboarding

@Serializable data object Devices

@Serializable data object Remote

@Serializable data object Settings

/** One page of the settings, by the name of its [com.omsingh.telepad.ui.screens.settings.SettingsPage]. */
@Serializable data class SettingsDetail(val page: String)
