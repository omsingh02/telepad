package com.omsingh.telepad.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesStateTest {

    private val saved = UserPreferences(onboardingShown = true)

    @Test
    fun `nothing is loaded until the saved preferences arrive, and the defaults stand in`() = runTest {
        val source = MutableSharedFlow<UserPreferences>()
        val state = PreferencesState(TestScope(StandardTestDispatcher(testScheduler)), source)
        advanceUntilIdle()

        assertFalse(state.loaded.value)
        assertFalse(state.preferences.value.onboardingShown)
    }

    @Test
    fun `once loaded, the preferences are the saved ones`() = runTest {
        val state = PreferencesState(TestScope(StandardTestDispatcher(testScheduler)), flow { emit(saved) })
        advanceUntilIdle()

        assertTrue(state.loaded.value)
        assertEquals(saved, state.preferences.value)
    }

    @Test
    fun `loaded is never seen as true while the preferences are still the defaults`() = runTest {
        // The bug this guards against: waiting for "loaded" and then reading the preferences
        // gave the defaults, so the first screen was chosen from them.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val state = PreferencesState(scope, flow { emit(saved) })
        val seenWhenLoaded = mutableListOf<Boolean>()

        // Look at the preferences at the moment loaded flips, as the UI does.
        val observer = scope.launch {
            state.loaded.collect { loaded -> if (loaded) seenWhenLoaded += state.preferences.value.onboardingShown }
        }
        advanceUntilIdle()
        observer.cancel()

        assertEquals(listOf(true), seenWhenLoaded)
    }

    @Test
    fun `later changes are passed on`() = runTest {
        val source = MutableSharedFlow<UserPreferences>(replay = 1)
        source.emit(saved)
        val state = PreferencesState(TestScope(StandardTestDispatcher(testScheduler)), source)
        advanceUntilIdle()

        source.emit(saved.copy(hapticFeedback = false))
        advanceUntilIdle()

        assertFalse(state.preferences.value.hapticFeedback)
        assertTrue(state.loaded.value)
    }
}
