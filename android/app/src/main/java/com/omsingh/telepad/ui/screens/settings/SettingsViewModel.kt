package com.omsingh.telepad.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.settings.PreferencesState
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.settings.UserPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the settings screens can change. */
interface SettingsActions {
    fun update(transform: (UserPreferences) -> UserPreferences)
    fun resetToDefaults()
    fun forget(device: PairedDevice)
    fun forgetAll()
    fun resetIdentity()

    companion object {
        /** Does nothing; for previews and tests. */
        val None = object : SettingsActions {
            override fun update(transform: (UserPreferences) -> UserPreferences) = Unit
            override fun resetToDefaults() = Unit
            override fun forget(device: PairedDevice) = Unit
            override fun forgetAll() = Unit
            override fun resetIdentity() = Unit
        }
    }
}

/** Holds the preferences for the whole app (the activity reads them for the theme) and the paired PCs. */
class SettingsViewModel(application: Application) : AndroidViewModel(application), SettingsActions {

    private val repository = SettingsRepository(application)
    private val manager = ConnectionManager.getInstance(application)

    private val state = PreferencesState(viewModelScope, repository.preferences)

    val preferences: StateFlow<UserPreferences> = state.preferences

    /** False until the saved settings have been read, so the app does not flash the wrong theme or screen. */
    val loaded: StateFlow<Boolean> = state.loaded

    val pairedDevices: StateFlow<List<PairedDevice>> = manager.pairedDevices

    override fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { repository.update(transform) }
    }

    override fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    override fun forget(device: PairedDevice) = manager.forget(device)
    override fun forgetAll() = manager.forgetAll()
    override fun resetIdentity() = manager.resetIdentity()
}
