package com.omsingh.telepad.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-wide Preferences DataStore handle. */
val Context.settingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "telepad_settings")

/**
 * Reads and writes the [UserPreferences] backed by Preferences DataStore.
 *
 * **Why Preferences DataStore and not Proto DataStore?** Trade-off:
 *  - Preferences = simpler, no schema file, no codegen, smaller APK.
 *  - Proto = type-safe, schema evolution, but adds the protobuf runtime.
 *
 * For ~15 scalar settings this trade-off favors Preferences. The risk
 * (forgotten key typos) is mitigated by routing every read through this one
 * file with named [Keys] constants.
 *
 * **Atomicity:** DataStore writes are transactional under the hood — every
 * `edit { }` block applies as a single atomic update. We always rewrite the
 * full set inside `update()` so partial states never leak.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        // Touchpad
        val TAP_TO_CLICK            = booleanPreferencesKey("tap_to_click")
        val NATURAL_SCROLL          = booleanPreferencesKey("natural_scrolling")
        val DOUBLE_TAP_DRAG         = booleanPreferencesKey("double_tap_drag")
        val TWO_FINGER_RC           = booleanPreferencesKey("two_finger_rc")
        val LONG_PRESS_RC           = booleanPreferencesKey("long_press_rc")
        val SENSITIVITY             = floatPreferencesKey("sensitivity")
        val SCROLL_SPEED            = floatPreferencesKey("scroll_speed")
        val ACCEL_CURVE             = stringPreferencesKey("accel_curve")
        val SHOW_TOUCHPAD_BUTTONS   = booleanPreferencesKey("show_touchpad_buttons")
        // Keyboard
        val CLIPBOARD_SYNC          = booleanPreferencesKey("clipboard_sync")
        // Connection
        val AUTO_SELECT_TRANSPORT   = booleanPreferencesKey("auto_select_transport")
        val KEEP_ALIVE              = booleanPreferencesKey("keep_alive")
        // Appearance
        val THEME_MODE              = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR           = booleanPreferencesKey("dynamic_color")
        val ACCENT_COLOR            = stringPreferencesKey("accent_color")
        // Feedback
        val HAPTIC                  = booleanPreferencesKey("haptic")
        // Developer
        val DEBUG_LATENCY           = booleanPreferencesKey("debug_latency")
        // Onboarding & Guides
        val ONBOARDING_SHOWN        = booleanPreferencesKey("onboarding_shown")
        val TOUCHPAD_INTRO_SHOWN    = booleanPreferencesKey("touchpad_intro_shown")
    }

    /** Reactive view. Emits on any write. UI binds to this via `collectAsState`. */
    val preferences: Flow<UserPreferences> = context.settingsDataStore.data.map { p ->
        decode(p)
    }

    /**
     * Apply [transform] to the current preferences and persist the result.
     * Coroutine-suspending; safe to call from a ViewModel scope.
     */
    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.settingsDataStore.edit { p ->
            val current = decode(p)
            val next = transform(current)
            encode(p, next)
        }
    }

    /** Reset every setting to its [UserPreferences] default value. */
    suspend fun resetToDefaults() {
        update { UserPreferences() }
    }

    // ── Decode / encode ──────────────────────────────────────────────

    private fun decode(p: Preferences): UserPreferences = UserPreferences(
        tapToClick          = p[Keys.TAP_TO_CLICK] ?: true,
        naturalScrolling    = p[Keys.NATURAL_SCROLL] ?: true,
        doubleTapDrag       = p[Keys.DOUBLE_TAP_DRAG] ?: true,
        twoFingerRightClick = p[Keys.TWO_FINGER_RC] ?: true,
        longPressRightClick = p[Keys.LONG_PRESS_RC] ?: true,
        sensitivity         = p[Keys.SENSITIVITY] ?: 1.6f,
        scrollSpeed         = p[Keys.SCROLL_SPEED] ?: 2.2f,
        accelerationCurve   = enumOf(p[Keys.ACCEL_CURVE], AccelerationCurve.MACOS),
        showTouchpadButtons = p[Keys.SHOW_TOUCHPAD_BUTTONS] ?: true,
        clipboardSync       = p[Keys.CLIPBOARD_SYNC] ?: true,
        autoSelectTransport = p[Keys.AUTO_SELECT_TRANSPORT] ?: true,
        keepConnectionAlive = p[Keys.KEEP_ALIVE] ?: false,
        themeMode           = enumOf(p[Keys.THEME_MODE], ThemeMode.SYSTEM),
        dynamicColor        = p[Keys.DYNAMIC_COLOR] ?: false,
        accentColor         = enumOf(p[Keys.ACCENT_COLOR], AccentColor.CYAN),
        hapticFeedback      = p[Keys.HAPTIC] ?: true,
        debugLatencyOverlay = p[Keys.DEBUG_LATENCY] ?: false,
        onboardingShown     = p[Keys.ONBOARDING_SHOWN] ?: false,
        touchpadIntroShown  = p[Keys.TOUCHPAD_INTRO_SHOWN] ?: false,
    )

    private fun encode(p: androidx.datastore.preferences.core.MutablePreferences, u: UserPreferences) {
        p[Keys.TAP_TO_CLICK]            = u.tapToClick
        p[Keys.NATURAL_SCROLL]          = u.naturalScrolling
        p[Keys.DOUBLE_TAP_DRAG]         = u.doubleTapDrag
        p[Keys.TWO_FINGER_RC]           = u.twoFingerRightClick
        p[Keys.LONG_PRESS_RC]           = u.longPressRightClick
        p[Keys.SENSITIVITY]             = u.sensitivity
        p[Keys.SCROLL_SPEED]            = u.scrollSpeed
        p[Keys.ACCEL_CURVE]             = u.accelerationCurve.name
        p[Keys.SHOW_TOUCHPAD_BUTTONS]   = u.showTouchpadButtons
        p[Keys.CLIPBOARD_SYNC]          = u.clipboardSync
        p[Keys.AUTO_SELECT_TRANSPORT]   = u.autoSelectTransport
        p[Keys.KEEP_ALIVE]              = u.keepConnectionAlive
        p[Keys.THEME_MODE]              = u.themeMode.name
        p[Keys.DYNAMIC_COLOR]           = u.dynamicColor
        p[Keys.ACCENT_COLOR]            = u.accentColor.name
        p[Keys.HAPTIC]                  = u.hapticFeedback
        p[Keys.DEBUG_LATENCY]           = u.debugLatencyOverlay
        p[Keys.ONBOARDING_SHOWN]        = u.onboardingShown
        p[Keys.TOUCHPAD_INTRO_SHOWN]    = u.touchpadIntroShown
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T =
        if (value == null) fallback
        else runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
