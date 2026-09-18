package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val prefs by viewModel.preferences.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = Dimens.ScreenHorizontalPadding,
                vertical = Dimens.ScreenVerticalPadding
            ),
        ) {
            item {
                ToggleRow(
                    "Auto-select transport",
                    "Pick Wi-Fi or Bluetooth automatically. When off, every connection asks which to use.",
                    prefs.autoSelectTransport,
                ) { v -> viewModel.updatePreferences { it.copy(autoSelectTransport = v) } }
            }
            item {
                ToggleRow(
                    "Keep connection alive in background",
                    "Shows a persistent notification while connected. Costs a small amount of battery.",
                    prefs.keepConnectionAlive,
                ) { v -> viewModel.updatePreferences { it.copy(keepConnectionAlive = v) } }
            }
        }
    }
}
