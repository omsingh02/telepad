package com.omsingh.telepad.settings

import kotlinx.serialization.Serializable

/**
 * Single source of truth for user-configurable preferences.
 *
 * Persisted via [SettingsRepository] inside a Preferences DataStore. Every
 * UI surface and the gesture processor read from a [StateFlow][kotlinx.coroutines.flow.StateFlow]
 * of this type, so changes propagate reactively without per-field plumbing.
 *
 * Fields are versioned implicitly via the keys in [SettingsRepository] —
 * adding a new field just means picking a new key and providing a default.
 */
@Serializable
data class UserPreferences(
    // ── Touchpad ─────────────────────────────────────────────────────
    val tapToClick: Boolean = true,
    val naturalScrolling: Boolean = true,
    val doubleTapDrag: Boolean = true,
    val twoFingerRightClick: Boolean = true,
    val longPressRightClick: Boolean = true,
    val sensitivity: Float = 1.6f,
    val scrollSpeed: Float = 2.2f,
    val accelerationCurve: AccelerationCurve = AccelerationCurve.MACOS,
    val showTouchpadButtons: Boolean = false,

    // ── Keyboard ─────────────────────────────────────────────────────
    val clipboardSync: Boolean = true,

    // ── Connection ───────────────────────────────────────────────────
    /** Auto-select Wi-Fi vs Bluetooth (true) or always present the choice (false). */
    val autoSelectTransport: Boolean = true,
    /** Keep the foreground service running so the connection survives backgrounding. */
    val keepConnectionAlive: Boolean = false,

    // ── Appearance ───────────────────────────────────────────────────
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use Material You dynamic color on Android 12+. */
    val dynamicColor: Boolean = true,
    val accentColor: AccentColor = AccentColor.CYAN,

    // ── Feedback ─────────────────────────────────────────────────────
    val hapticFeedback: Boolean = true,

    // ── Developer ────────────────────────────────────────────────────
    val debugLatencyOverlay: Boolean = false,
)

@Serializable
enum class AccelerationCurve { LINEAR, MACOS, WINDOWS, FLAT }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AccentColor { CYAN, PURPLE, GREEN, ORANGE, RED }
