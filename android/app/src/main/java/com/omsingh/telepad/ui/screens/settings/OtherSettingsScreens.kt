package com.omsingh.telepad.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omsingh.telepad.BuildConfig
import com.omsingh.telepad.R
import com.omsingh.telepad.core.crypto.Fingerprint
import com.omsingh.telepad.core.trust.PairedDevice
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.settings.AccentColor
import com.omsingh.telepad.settings.HostOsChoice
import com.omsingh.telepad.settings.ThemeMode
import com.omsingh.telepad.settings.UserPreferences
import com.omsingh.telepad.ui.components.ChoiceRow
import com.omsingh.telepad.ui.components.IconTile
import com.omsingh.telepad.ui.components.MessageCard
import com.omsingh.telepad.ui.components.SettingsGroup
import com.omsingh.telepad.ui.components.SwitchRow
import com.omsingh.telepad.ui.components.osIcon
import com.omsingh.telepad.ui.theme.MonoStyle
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.update.UpdateActions
import com.omsingh.telepad.update.UpdateUi
import com.omsingh.telepad.ui.theme.seed
import com.omsingh.telepad.core.host.HostOs

// ── Keyboard and clipboard ───────────────────────────────────────────

@Composable
fun KeyboardSettingsScreen(
    preferences: UserPreferences,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.settings_keyboard), onBack = onBack, modifier = modifier) {
        SwitchRow(
            title = stringResource(R.string.keyboard_clipboard),
            subtitle = stringResource(R.string.keyboard_clipboard_subtitle),
            checked = preferences.clipboardSync,
            onCheckedChange = { v -> actions.update { it.copy(clipboardSync = v) } },
        )
        ChoiceRow(
            title = stringResource(R.string.keyboard_host_title),
            subtitle = stringResource(R.string.keyboard_host_subtitle),
            options = HostOsChoice.values().toList(),
            selected = preferences.assumedHostOs,
            onSelect = { v -> actions.update { it.copy(assumedHostOs = v) } },
            label = { choice ->
                stringResource(
                    when (choice) {
                        HostOsChoice.WINDOWS -> R.string.remote_os_windows
                        HostOsChoice.MACOS -> R.string.remote_os_macos
                        HostOsChoice.LINUX -> R.string.remote_os_linux
                    },
                )
            },
        )
    }
}

// ── Connection ───────────────────────────────────────────────────────

@Composable
fun ConnectionSettingsScreen(
    preferences: UserPreferences,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPlatformActions.current
    SettingsScaffold(title = stringResource(R.string.settings_connection), onBack = onBack, modifier = modifier) {
        SwitchRow(
            title = stringResource(R.string.connection_auto),
            subtitle = stringResource(R.string.connection_auto_subtitle),
            checked = preferences.autoConnect,
            onCheckedChange = { v -> actions.update { it.copy(autoConnect = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.connection_keep_alive),
            subtitle = stringResource(R.string.connection_keep_alive_subtitle),
            checked = preferences.keepConnectionAlive,
            onCheckedChange = { v ->
                // Android 13 and later wants permission before a notification is shown.
                if (v) platform.requestNotificationPermission()
                actions.update { it.copy(keepConnectionAlive = v) }
            },
        )
    }
}

// ── Appearance ───────────────────────────────────────────────────────

@Composable
fun AppearanceSettingsScreen(
    preferences: UserPreferences,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack, modifier = modifier) {
        ChoiceRow(
            title = stringResource(R.string.appearance_theme),
            options = ThemeMode.values().toList(),
            selected = preferences.themeMode,
            onSelect = { v -> actions.update { it.copy(themeMode = v) } },
            label = { mode ->
                stringResource(
                    when (mode) {
                        ThemeMode.SYSTEM -> R.string.appearance_theme_system
                        ThemeMode.LIGHT -> R.string.appearance_theme_light
                        ThemeMode.DARK -> R.string.appearance_theme_dark
                    },
                )
            },
        )
        SettingsGroup(stringResource(R.string.appearance_accent)) {
            AccentPicker(
                selected = preferences.accentColor,
                enabled = !(preferences.dynamicColor && dynamicAvailable),
                onSelect = { v -> actions.update { it.copy(accentColor = v) } },
            )
        }
        SwitchRow(
            title = stringResource(R.string.appearance_dynamic),
            subtitle = stringResource(if (dynamicAvailable) R.string.appearance_dynamic_subtitle else R.string.appearance_dynamic_unavailable),
            checked = preferences.dynamicColor && dynamicAvailable,
            enabled = dynamicAvailable,
            onCheckedChange = { v -> actions.update { it.copy(dynamicColor = v) } },
        )
    }
}

@Composable
private fun AccentPicker(selected: AccentColor, enabled: Boolean, onSelect: (AccentColor) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        for (accent in AccentColor.values()) {
            val name = stringResource(
                when (accent) {
                    AccentColor.CYAN -> R.string.accent_cyan
                    AccentColor.PURPLE -> R.string.accent_purple
                    AccentColor.GREEN -> R.string.accent_green
                    AccentColor.ORANGE -> R.string.accent_orange
                    AccentColor.RED -> R.string.accent_red
                },
            )
            val isSelected = accent == selected
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(accent.seed()).copy(alpha = if (enabled) 1f else 0.4f))
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                    )
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(accent) }
                    .semantics {
                        contentDescription = name
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}

// ── Privacy and security ─────────────────────────────────────────────

@Composable
fun PrivacySettingsScreen(
    devices: List<PairedDevice>,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var forgetting by remember { mutableStateOf<PairedDevice?>(null) }
    var confirmForgetAll by remember { mutableStateOf(false) }
    var confirmIdentity by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_privacy), onBack = onBack, modifier = modifier) {
        MessageCard(
            icon = Icons.Rounded.Shield,
            title = stringResource(R.string.privacy_how_title),
            body = stringResource(R.string.privacy_how_body),
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        )

        SettingsGroup(stringResource(R.string.privacy_paired_title)) {
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.privacy_paired_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                )
            } else {
                for (device in devices) PairedDeviceRow(device, onForget = { forgetting = device })
                TextButton(onClick = { confirmForgetAll = true }, modifier = Modifier.padding(horizontal = Spacing.sm)) {
                    Text(stringResource(R.string.privacy_forget_all))
                }
            }
        }

        SettingsGroup(stringResource(R.string.privacy_identity_title)) {
            Text(
                stringResource(R.string.privacy_identity_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
            OutlinedButton(
                onClick = { confirmIdentity = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md),
            ) { Text(stringResource(R.string.privacy_identity_reset)) }
        }
    }

    forgetting?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.devices_forget_title, device.name),
            body = stringResource(R.string.devices_forget_body),
            confirm = stringResource(R.string.action_forget),
            onConfirm = { forgetting = null; actions.forget(device) },
            onDismiss = { forgetting = null },
        )
    }
    if (confirmForgetAll) {
        ConfirmDialog(
            title = stringResource(R.string.privacy_forget_all_title),
            body = stringResource(R.string.privacy_forget_all_body),
            confirm = stringResource(R.string.privacy_forget_all),
            onConfirm = { confirmForgetAll = false; actions.forgetAll() },
            onDismiss = { confirmForgetAll = false },
        )
    }
    if (confirmIdentity) {
        ConfirmDialog(
            title = stringResource(R.string.privacy_identity_reset_title),
            body = stringResource(R.string.privacy_identity_reset_body),
            confirm = stringResource(R.string.privacy_identity_reset),
            onConfirm = { confirmIdentity = false; actions.resetIdentity() },
            onDismiss = { confirmIdentity = false },
        )
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDevice, onForget: () -> Unit) {
    val os = device.osName?.let { name -> runCatching { HostOs.valueOf(name) }.getOrNull() } ?: HostOs.UNKNOWN
    val fingerprint = remember(device.publicKey) {
        runCatching { Fingerprint.format(Fingerprint.ofBase64(device.publicKey)) }.getOrDefault("")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(osIcon(os))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fingerprint.isNotEmpty()) {
                Text(stringResource(R.string.privacy_fingerprint, fingerprint), style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onForget) { Text(stringResource(R.string.action_forget)) }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ── About ────────────────────────────────────────────────────────────

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    updates: UpdateUi = UpdateUi.None,
    updateActions: UpdateActions = UpdateActions.None,
) {
    val platform = LocalPlatformActions.current
    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack, modifier = modifier) {
        Column(
            Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateSection(updates, updateActions)
            Button(onClick = { platform.openUrl(Links.REPOSITORY) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_github))
            }
            OutlinedButton(onClick = { platform.openUrl(Links.ISSUES) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_issues))
            }
            OutlinedButton(onClick = { platform.openUrl(Links.PRIVACY) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_privacy))
            }
            Text(stringResource(R.string.about_libraries_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md))
            Text(stringResource(R.string.about_libraries), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onLicenses, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_licenses))
            }
        }
    }
}
