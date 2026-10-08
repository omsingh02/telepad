package com.omsingh.telepad.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * App-wide Preferences DataStore handle. A damaged settings file is replaced by the defaults
 * rather than crashing the app every time it opens.
 */
val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(
        name = "telepad_settings",
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

/**
 * Reads and writes the [UserPreferences] backed by Preferences DataStore.
 *
 * Preferences DataStore rather than Proto: for a couple of dozen scalar settings the
 * schema file and generated code would cost more than they give. Every read and
 * write goes through this file, with named [Keys], which is what keeps a typo in a
 * key from becoming a silently ignored setting.
 *
 * Every `update` is one atomic transaction, so a half-written set of settings is
 * never observable.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        // Touchpad
        val TAP_TO_CLICK = booleanPreferencesKey("tap_to_click")
        val NATURAL_SCROLL = booleanPreferencesKey("natural_scrolling")
        val DOUBLE_TAP_DRAG = booleanPreferencesKey("double_tap_drag")
        val TWO_FINGER_RC = booleanPreferencesKey("two_finger_rc")
        val THREE_FINGER_MC = booleanPreferencesKey("three_finger_mc")
        val LONG_PRESS_RC = booleanPreferencesKey("long_press_rc")
        val MOMENTUM = booleanPreferencesKey("momentum_scrolling")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
        val SCROLL_SPEED = floatPreferencesKey("scroll_speed")
        val ACCEL_CURVE = stringPreferencesKey("accel_curve")
        val SHOW_TOUCHPAD_BUTTONS = booleanPreferencesKey("show_touchpad_buttons")
        val SHOW_SCROLL_STRIP = booleanPreferencesKey("show_scroll_strip")
        // Keyboard & clipboard
        val CLIPBOARD_SYNC = booleanPreferencesKey("clipboard_sync")
        val ASSUMED_HOST_OS = stringPreferencesKey("assumed_host_os")
        // Connection
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        // Appearance
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        // Feedback
        val HAPTIC = booleanPreferencesKey("haptic")
        // Updates
        val CHECK_FOR_UPDATES = booleanPreferencesKey("check_for_updates")
        // Onboarding & guides
        val ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        val TOUCHPAD_INTRO_SHOWN = booleanPreferencesKey("touchpad_intro_shown")
    }

    /** Reactive view. Emits on any write. UI binds to this via `collectAsState`. */
    val preferences: Flow<UserPreferences> = context.settingsDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::decode)

    /**
     * Apply [transform] to the current preferences and persist the result.
     * Suspending; safe to call from a ViewModel scope.
     */
    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.settingsDataStore.edit { p -> encode(p, transform(decode(p))) }
    }

    /** Reset every setting to its [UserPreferences] default value. */
    suspend fun resetToDefaults() {
        // Keep what is about the person rather than the preferences: they have seen the guides.
        update { current ->
            UserPreferences(
                onboardingShown = current.onboardingShown,
                touchpadIntroShown = current.touchpadIntroShown,
            )
        }
    }

    // ── Decode / encode ──────────────────────────────────────────────

    private fun decode(p: Preferences): UserPreferences {
        val d = UserPreferences()
        return UserPreferences(
            tapToClick = p[Keys.TAP_TO_CLICK] ?: d.tapToClick,
            naturalScrolling = p[Keys.NATURAL_SCROLL] ?: d.naturalScrolling,
            doubleTapDrag = p[Keys.DOUBLE_TAP_DRAG] ?: d.doubleTapDrag,
            twoFingerRightClick = p[Keys.TWO_FINGER_RC] ?: d.twoFingerRightClick,
            threeFingerMiddleClick = p[Keys.THREE_FINGER_MC] ?: d.threeFingerMiddleClick,
            longPressRightClick = p[Keys.LONG_PRESS_RC] ?: d.longPressRightClick,
            momentumScrolling = p[Keys.MOMENTUM] ?: d.momentumScrolling,
            sensitivity = p[Keys.SENSITIVITY] ?: d.sensitivity,
            scrollSpeed = p[Keys.SCROLL_SPEED] ?: d.scrollSpeed,
            accelerationCurve = enumOf(p[Keys.ACCEL_CURVE], d.accelerationCurve),
            showTouchpadButtons = p[Keys.SHOW_TOUCHPAD_BUTTONS] ?: d.showTouchpadButtons,
            showScrollStrip = p[Keys.SHOW_SCROLL_STRIP] ?: d.showScrollStrip,
            clipboardSync = p[Keys.CLIPBOARD_SYNC] ?: d.clipboardSync,
            assumedHostOs = enumOf(p[Keys.ASSUMED_HOST_OS], d.assumedHostOs),
            autoConnect = p[Keys.AUTO_CONNECT] ?: d.autoConnect,
            keepConnectionAlive = p[Keys.KEEP_ALIVE] ?: d.keepConnectionAlive,
            themeMode = enumOf(p[Keys.THEME_MODE], d.themeMode),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
            accentColor = enumOf(p[Keys.ACCENT_COLOR], d.accentColor),
            hapticFeedback = p[Keys.HAPTIC] ?: d.hapticFeedback,
            checkForUpdates = p[Keys.CHECK_FOR_UPDATES] ?: d.checkForUpdates,
            onboardingShown = p[Keys.ONBOARDING_SHOWN] ?: d.onboardingShown,
            touchpadIntroShown = p[Keys.TOUCHPAD_INTRO_SHOWN] ?: d.touchpadIntroShown,
        )
    }

    private fun encode(p: MutablePreferences, u: UserPreferences) {
        p[Keys.TAP_TO_CLICK] = u.tapToClick
        p[Keys.NATURAL_SCROLL] = u.naturalScrolling
        p[Keys.DOUBLE_TAP_DRAG] = u.doubleTapDrag
        p[Keys.TWO_FINGER_RC] = u.twoFingerRightClick
        p[Keys.THREE_FINGER_MC] = u.threeFingerMiddleClick
        p[Keys.LONG_PRESS_RC] = u.longPressRightClick
        p[Keys.MOMENTUM] = u.momentumScrolling
        p[Keys.SENSITIVITY] = u.sensitivity
        p[Keys.SCROLL_SPEED] = u.scrollSpeed
        p[Keys.ACCEL_CURVE] = u.accelerationCurve.name
        p[Keys.SHOW_TOUCHPAD_BUTTONS] = u.showTouchpadButtons
        p[Keys.SHOW_SCROLL_STRIP] = u.showScrollStrip
        p[Keys.CLIPBOARD_SYNC] = u.clipboardSync
        p[Keys.ASSUMED_HOST_OS] = u.assumedHostOs.name
        p[Keys.AUTO_CONNECT] = u.autoConnect
        p[Keys.KEEP_ALIVE] = u.keepConnectionAlive
        p[Keys.THEME_MODE] = u.themeMode.name
        p[Keys.DYNAMIC_COLOR] = u.dynamicColor
        p[Keys.ACCENT_COLOR] = u.accentColor.name
        p[Keys.HAPTIC] = u.hapticFeedback
        p[Keys.CHECK_FOR_UPDATES] = u.checkForUpdates
        p[Keys.ONBOARDING_SHOWN] = u.onboardingShown
        p[Keys.TOUCHPAD_INTRO_SHOWN] = u.touchpadIntroShown
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        if (value == null) fallback
        else runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
