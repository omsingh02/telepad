package com.omsingh.telepad.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The saved preferences as the UI sees them, and whether they have been read yet.
 *
 * Both come from one collector, and the preferences are set before [loaded] flips. Two
 * separate collectors of the same flow can finish in either order, and then something that
 * waits for [loaded] still sees the defaults: the app once opened on its first-run
 * walkthrough every time, whatever had been saved.
 */
class PreferencesState(scope: CoroutineScope, source: Flow<UserPreferences>) {

    private val _preferences = MutableStateFlow(UserPreferences())
    private val _loaded = MutableStateFlow(false)

    /** The saved preferences, or the defaults until [loaded]. */
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    /** True once the saved preferences have been read. When it is true, [preferences] holds them. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            source.collect { saved ->
                _preferences.value = saved
                _loaded.value = true
            }
        }
    }
}
