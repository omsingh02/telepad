package com.omsingh.telepad.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.settings.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    val preferences: StateFlow<UserPreferences> =
        repository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    /** False until the saved settings have been read, so the app does not flash the wrong theme or screen. */
    val loaded: StateFlow<Boolean> =
        repository.preferences.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
