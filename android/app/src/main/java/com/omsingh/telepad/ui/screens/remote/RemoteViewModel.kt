package com.omsingh.telepad.ui.screens.remote

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.telepad.connection.ConnectionManager
import com.omsingh.telepad.connection.Notice
import com.omsingh.telepad.core.host.HostInfo
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.host.ShortcutId
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.GestureConfig
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.core.input.SensitivityCurve
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.settings.SettingsRepository
import com.omsingh.telepad.settings.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the remote control screens need to know. */
@Immutable
data class RemoteUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val host: HostProfile = HostProfile(HostOs.UNKNOWN),
    val hostInfo: HostInfo = HostInfo.UNKNOWN,
    val preferences: UserPreferences = UserPreferences(),
    val nowPlaying: NowPlayingState = NowPlayingState.EMPTY,
    /** Text fetched from the PC's clipboard, waiting to be copied to the phone. */
    val pcClipboard: String? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val transport: ConnectionState.Transport?
        get() = when (connection) {
            is ConnectionState.Connected -> connection.transport
            is ConnectionState.Reconnecting -> connection.transport
            is ConnectionState.Connecting -> connection.transport
            is ConnectionState.Failed -> connection.transport
            ConnectionState.Disconnected -> null
        }
    val overWifi: Boolean get() = transport == ConnectionState.Transport.WIFI

    /** Whether the phone and PC can swap clipboard text: needs Wi-Fi, the setting, and a server that supports it. */
    val clipboardAvailable: Boolean
        get() = overWifi && preferences.clipboardSync && hostInfo.capabilities.clipboard
}

/** What the remote screens can ask for. */
interface RemoteActions {
    fun send(event: InputEvent)
    fun disconnect()
    fun pasteFromPhone()
    fun copyFromPc()
    fun copyPcClipboardToPhone()
    fun dismissPcClipboard()
    fun mediaVisible(visible: Boolean)

    companion object {
        /** Does nothing; for previews and tests. */
        val None = object : RemoteActions {
            override fun send(event: InputEvent) = Unit
            override fun disconnect() = Unit
            override fun pasteFromPhone() = Unit
            override fun copyFromPc() = Unit
            override fun copyPcClipboardToPhone() = Unit
            override fun dismissPcClipboard() = Unit
            override fun mediaVisible(visible: Boolean) = Unit
        }
    }
}

/** The gesture engine's settings for these preferences. */
fun UserPreferences.toGestureConfig(): GestureConfig = GestureConfig(
    tapToClick = tapToClick,
    naturalScrolling = naturalScrolling,
    scrollSpeed = scrollSpeed,
    doubleTapDrag = doubleTapDrag,
    twoFingerRightClick = twoFingerRightClick,
    threeFingerMiddleClick = threeFingerMiddleClick,
    longPressRightClick = longPressRightClick,
    momentumScrolling = momentumScrolling,
    sensitivity = sensitivity,
    accelerationCurve = when (accelerationCurve) {
        AccelerationCurve.LINEAR -> SensitivityCurve.AccelCurve.LINEAR
        AccelerationCurve.MACOS -> SensitivityCurve.AccelCurve.MACOS
        AccelerationCurve.WINDOWS -> SensitivityCurve.AccelCurve.WINDOWS
        AccelerationCurve.FLAT -> SensitivityCurve.AccelCurve.FLAT
    },
)

class RemoteViewModel(application: Application) : AndroidViewModel(application), RemoteActions {

    private val manager = ConnectionManager.getInstance(application)
    private val settings = SettingsRepository(application)

    private val preferences: StateFlow<UserPreferences> =
        settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    private val connectionPart = combine(
        manager.connectionState, manager.hostProfile, manager.hostInfo, preferences,
    ) { connection, host, hostInfo, prefs ->
        RemoteUiState(connection = connection, host = host, hostInfo = hostInfo, preferences = prefs)
    }

    val state: StateFlow<RemoteUiState> = combine(
        connectionPart, manager.nowPlayingState, manager.pcClipboard,
    ) { base, nowPlaying, clipboard ->
        base.copy(nowPlaying = nowPlaying, pcClipboard = clipboard)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    /** Brief messages to show once, such as "copied". */
    val notices: SharedFlow<Notice> = manager.notices

    /** Keeps the modifier keys the person latched, across tabs and rotation. */
    val keyboard = KeyboardSession { manager.dispatch(it) }

    override fun send(event: InputEvent) = manager.dispatch(event)
    override fun disconnect() = manager.disconnect()

    /** Puts the phone's clipboard on the PC and pastes it there. */
    override fun pasteFromPhone() {
        manager.pushClipboardToPc()
        viewModelScope.launch {
            // Give the PC a moment to take the clipboard before the paste shortcut arrives.
            delay(CLIPBOARD_SETTLE_MS)
            keyboard.shortcut(state.value.host.chord(ShortcutId.PASTE))
        }
    }

    /** Copies what is selected on the PC and brings it to the phone. */
    override fun copyFromPc() {
        keyboard.shortcut(state.value.host.chord(ShortcutId.COPY))
        viewModelScope.launch {
            delay(CLIPBOARD_SETTLE_MS)
            manager.pullClipboardFromPc()
        }
    }

    override fun copyPcClipboardToPhone() = manager.copyPcClipboardToPhone()
    override fun dismissPcClipboard() = manager.clipboard.clearLatest()
    override fun mediaVisible(visible: Boolean) = manager.setNowPlayingActive(visible)

    override fun onCleared() {
        manager.setNowPlayingActive(false)
    }

    private companion object {
        const val CLIPBOARD_SETTLE_MS = 180L
    }
}
