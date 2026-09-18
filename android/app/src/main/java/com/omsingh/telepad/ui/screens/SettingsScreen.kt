package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val prefs by viewModel.preferences.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        SettingsCategory("Trackpad")
        SliderSetting("Sensitivity", prefs.sensitivity, 0.5f..5.0f) { v ->
            viewModel.updatePreferences { it.copy(sensitivity = v) }
        }
        SliderSetting("Scroll speed", prefs.scrollSpeed, 0.5f..5.0f) { v ->
            viewModel.updatePreferences { it.copy(scrollSpeed = v) }
        }
        SwitchSetting("Tap to click", checked = prefs.tapToClick) { v ->
            viewModel.updatePreferences { it.copy(tapToClick = v) }
        }
        SwitchSetting(
            title = "Natural scrolling",
            subtitle = "Content follows finger movement",
            checked = prefs.naturalScrolling
        ) { v -> viewModel.updatePreferences { it.copy(naturalScrolling = v) } }
        SwitchSetting("Double-tap drag", checked = prefs.doubleTapDrag) { v ->
            viewModel.updatePreferences { it.copy(doubleTapDrag = v) }
        }
        SwitchSetting("Two-finger right click", checked = prefs.twoFingerRightClick) { v ->
            viewModel.updatePreferences { it.copy(twoFingerRightClick = v) }
        }
        AccelerationCurvePicker(
            current = prefs.accelerationCurve,
            onSelect = { v -> viewModel.updatePreferences { it.copy(accelerationCurve = v) } }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SettingsCategory("Feedback")
        SwitchSetting("Haptic feedback", checked = prefs.hapticFeedback) { v ->
            viewModel.updatePreferences { it.copy(hapticFeedback = v) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedButton(
            onClick = { viewModel.updatePreferences { UserPreferences() } },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) { Text("Reset to defaults") }
    }
}

@Composable
private fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
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
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) * 2).toInt() - 1
        )
    }
}

@Composable
private fun AccelerationCurvePicker(
    current: AccelerationCurve,
    onSelect: (AccelerationCurve) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Acceleration curve", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How quickly pointer speed scales with finger speed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AccelerationCurve.entries.forEach { curve ->
                val selected = curve == current
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = { onSelect(curve) },
                    label = { Text(curve.name.lowercase().replaceFirstChar(Char::uppercase)) },
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
