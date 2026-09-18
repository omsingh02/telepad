package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.ThemeMode
import com.omsingh.telepad.ui.theme.CyanDark
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.GreenDark
import com.omsingh.telepad.ui.theme.OrangeDark
import com.omsingh.telepad.ui.theme.PurpleDark
import com.omsingh.telepad.ui.theme.RedDark
import com.omsingh.telepad.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val prefs by viewModel.preferences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            item {
                SectionTitle("Theme")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.themeMode == mode,
                            onClick = { viewModel.updatePreferences { it.copy(themeMode = mode) } },
                            label = {
                                Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                ToggleRow(
                    "Dynamic color (Material You)",
                    "Use system wallpaper-derived palette on Android 12+",
                    prefs.dynamicColor,
                ) { v -> viewModel.updatePreferences { it.copy(dynamicColor = v) } }
            }
            item {
                SectionTitle("Accent color")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                ) {
                    AccentColor.entries.forEach { accent ->
                        AccentSwatch(
                            color = accent.swatchColor,
                            selected = prefs.accentColor == accent,
                            onClick = { viewModel.updatePreferences { it.copy(accentColor = accent) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    "Ignored when Dynamic color is on",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

private val AccentColor.swatchColor: Color
    get() = when (this) {
        AccentColor.CYAN   -> CyanDark
        AccentColor.PURPLE -> PurpleDark
        AccentColor.GREEN  -> GreenDark
        AccentColor.ORANGE -> OrangeDark
        AccentColor.RED    -> RedDark
    }

@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(40.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                )
            }
        }
    }
}
