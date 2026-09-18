package com.omsingh.telepad.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.core.crypto.PairingStore
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.settings.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts the persistent [UserPreferences] and the list of trusted-paired PCs.
 *
 * Why one ViewModel for "settings + paired devices" rather than two? They're
 * the same surface in the UI (Settings → Privacy → Forget devices), and
 * splitting causes the "forget device" action to need cross-VM plumbing.
 * Keep them together until the UI fragments.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)
    private val pairingStore = PairingStore.get(application)

    val preferences: StateFlow<UserPreferences> = repo.preferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserPreferences()
    )

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.resetToDefaults() }
    }

    // ── Paired (trusted) device management ───────────────────────────

    fun listTrustedHosts(): List<PairingStore.TrustedHost> = pairingStore.listTrusted()

    fun forgetTrustedHost(host: String) {
        pairingStore.forget(host)
    }

    fun forgetAllTrustedHosts() {
        pairingStore.forgetAll()
    }

    /**
     * Local identity reset: throws away our own client static key. The next
     * connection will pair as a brand-new client (server will treat us as
     * "a new phone"). Useful for "factory reset" in privacy settings.
     */
    fun resetLocalIdentity() {
        pairingStore.resetLocalIdentity()
    }
}
