package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.SettingsViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit,
) {
    val prefs by viewModel.preferences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Touchpad") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = Dimens.ScreenHorizontalPadding,
                vertical = Dimens.ScreenVerticalPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            item {
                SliderRow(
                    title = "Sensitivity",
                    value = prefs.sensitivity,
                    range = 0.5f..5.0f,
                    onChange = { v -> viewModel.updatePreferences { it.copy(sensitivity = v) } }
                )
            }
            item {
                SliderRow(
                    title = "Scroll speed",
                    value = prefs.scrollSpeed,
                    range = 0.5f..5.0f,
                    onChange = { v -> viewModel.updatePreferences { it.copy(scrollSpeed = v) } }
                )
            }
            item {
                AccelCurvePicker(
                    current = prefs.accelerationCurve,
                    onSelect = { v -> viewModel.updatePreferences { it.copy(accelerationCurve = v) } }
                )
            }
            item {
                ToggleRow("Tap to click", null, prefs.tapToClick) { v ->
                    viewModel.updatePreferences { it.copy(tapToClick = v) }
                }
            }
            item {
                ToggleRow(
                    "Natural scrolling",
                    "Content tracks finger movement",
                    prefs.naturalScrolling,
                ) { v -> viewModel.updatePreferences { it.copy(naturalScrolling = v) } }
            }
            item {
                ToggleRow("Double-tap drag", null, prefs.doubleTapDrag) { v ->
                    viewModel.updatePreferences { it.copy(doubleTapDrag = v) }
                }
            }
            item {
                ToggleRow("Two-finger right click", null, prefs.twoFingerRightClick) { v ->
                    viewModel.updatePreferences { it.copy(twoFingerRightClick = v) }
                }
            }
            item {
                ToggleRow("Long-press right click", null, prefs.longPressRightClick) { v ->
                    viewModel.updatePreferences { it.copy(longPressRightClick = v) }
                }
            }
            item {
                ToggleRow(
                    "Show on-screen click buttons",
                    "Adds Left/Right click buttons below the touchpad",
                    prefs.showTouchpadButtons,
                ) { v -> viewModel.updatePreferences { it.copy(showTouchpadButtons = v) } }
            }
            item {
                ToggleRow("Haptic feedback", null, prefs.hapticFeedback) { v ->
                    viewModel.updatePreferences { it.copy(hapticFeedback = v) }
                }
            }
            item {
                OutlinedButton(
                    onClick = onOpenCalibration,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.ItemSpacing),
                ) {
                    Text("Calibrate (live preview)")
                }
            }
        }
    }
}

@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = ((range.endInclusive - range.start) * 2).toInt() - 1,
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AccelCurvePicker(
    current: AccelerationCurve,
    onSelect: (AccelerationCurve) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text("Acceleration curve", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How pointer speed scales with finger speed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
        ) {
            AccelerationCurve.entries.forEach { curve ->
                val selected = curve == current
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(curve) },
                    label = {
                        Text(curve.name.lowercase().replaceFirstChar(Char::uppercase))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(curve) }
                        )
                )
            }
        }
    }
}
