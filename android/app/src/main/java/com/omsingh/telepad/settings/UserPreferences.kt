package com.omsingh.telepad.settings

import kotlinx.serialization.Serializable

/**
 * Single source of truth for user-configurable preferences.
 *
 * Persisted via [SettingsRepository] inside a Preferences DataStore. Every UI surface
 * and the gesture engine read from a flow of this type, so a change takes effect
 * immediately and everywhere without per-field plumbing.
 *
 * Adding a setting means adding a field here (with its default) and a key in
 * [SettingsRepository]; nothing else needs to change.
 */
@Serializable
data class UserPreferences(
    // ── Touchpad ─────────────────────────────────────────────────────
    val tapToClick: Boolean = true,
    val naturalScrolling: Boolean = true,
    /** Tap, then touch again and move: drag with the left button held. */
    val doubleTapDrag: Boolean = true,
    val twoFingerRightClick: Boolean = true,
    val threeFingerMiddleClick: Boolean = true,
    val longPressRightClick: Boolean = true,
    /** Keep scrolling for a moment after a quick two-finger flick. */
    val momentumScrolling: Boolean = true,
    val sensitivity: Float = 1.6f,
    val scrollSpeed: Float = 2.2f,
    val accelerationCurve: AccelerationCurve = AccelerationCurve.MACOS,
    val showTouchpadButtons: Boolean = true,
    /** The strip along the pad's right edge for one-finger scrolling. */
    val showScrollStrip: Boolean = true,

    // ── Keyboard & clipboard ─────────────────────────────────────────
    /** Offer to copy the clipboard between phone and PC (always explicit, never automatic). */
    val clipboardSync: Boolean = true,
    /** The OS to assume when the PC does not say (Bluetooth, or an older server). */
    val assumedHostOs: HostOsChoice = HostOsChoice.WINDOWS,

    // ── Connection ───────────────────────────────────────────────────
    /** Connect to the most recently used PC when the app opens and it is on the network. */
    val autoConnect: Boolean = true,
    /** Keep the connection (and a notification with controls) while the app is in the background. */
    val keepConnectionAlive: Boolean = false,

    // ── Appearance ───────────────────────────────────────────────────
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Use Material You colours from the wallpaper (Android 12+) instead of the accent. */
    val dynamicColor: Boolean = false,
    val accentColor: AccentColor = AccentColor.CYAN,

    // ── Feedback ─────────────────────────────────────────────────────
    val hapticFeedback: Boolean = true,

    // ── Updates ──────────────────────────────────────────────────────
    /**
     * Look for a newer version on GitHub when the app opens, at most once a day. It is one request for the
     * project's public list of releases; whether it is on by default is written down here, in one place.
     */
    val checkForUpdates: Boolean = true,

    // ── Onboarding & guides ──────────────────────────────────────────
    val onboardingShown: Boolean = false,
    val touchpadIntroShown: Boolean = false,
)

@Serializable
enum class AccelerationCurve { LINEAR, MACOS, WINDOWS, FLAT }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AccentColor { CYAN, PURPLE, GREEN, ORANGE, RED }

/** The operating systems a person can pick for a PC that does not report its own. */
@Serializable
enum class HostOsChoice { WINDOWS, MACOS, LINUX }
