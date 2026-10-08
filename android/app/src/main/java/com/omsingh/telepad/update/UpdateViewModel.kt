package com.omsingh.telepad.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the About page shows about updates. */
data class UpdateUi(
    val state: UpdateState,
    /** Whether this copy can install an update itself (a store's copy cannot, and says which store). */
    val canInstall: Boolean,
    val source: InstallSource,
    /** Whether the app looks for updates by itself, when it opens. */
    val checkAutomatically: Boolean,
) {
    /** The state with an update waiting, if there is one. */
    val release get() = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.InstallFailed -> state.release
        else -> null
    }

    companion object {
        val None = UpdateUi(UpdateState.Unknown, canInstall = true, source = InstallSource.Direct, checkAutomatically = true)
    }
}

/** What the About page can ask of the updater. */
interface UpdateActions {
    fun check()
    fun install()
    fun setCheckAutomatically(on: Boolean)

    companion object {
        /** Does nothing; for previews and tests. */
        val None = object : UpdateActions {
            override fun check() = Unit
            override fun install() = Unit
            override fun setCheckAutomatically(on: Boolean) = Unit
        }
    }
}

/** Connects the About page to the [UpdateManager], and looks for an update when the app opens (once a day, if allowed). */
class UpdateViewModel(application: Application) : AndroidViewModel(application), UpdateActions {

    private val manager = UpdateManager.getInstance(application)
    private val repository = SettingsRepository(application)

    val ui: StateFlow<UpdateUi> = combine(manager.state, repository.preferences) { state, preferences ->
        UpdateUi(state, manager.canInstall, manager.source, preferences.checkForUpdates)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UpdateUi(manager.state.value, manager.canInstall, manager.source, checkAutomatically = true),
    )

    init {
        viewModelScope.launch {
            if (repository.preferences.first().checkForUpdates) manager.checkIfDue()
        }
    }

    override fun check() = manager.check()
    override fun install() = manager.install()

    override fun setCheckAutomatically(on: Boolean) {
        viewModelScope.launch { repository.update { it.copy(checkForUpdates = on) } }
    }
}
