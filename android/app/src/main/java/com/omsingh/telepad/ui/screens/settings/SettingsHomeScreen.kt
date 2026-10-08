package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.ui.components.NavigationRow
import androidx.compose.foundation.layout.padding

/** Where the settings pages are listed. */
@Composable
fun SettingsHomeScreen(
    actions: SettingsActions,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** A newer version is waiting: About says so. */
    updateAvailable: Boolean = false,
) {
    var confirmReset by remember { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = null, large = true, modifier = modifier) {
        Column {
            NavigationRow(Icons.Rounded.Mouse, stringResource(R.string.settings_touchpad), stringResource(R.string.settings_touchpad_subtitle), { onOpen(SettingsPage.TOUCHPAD) })
            NavigationRow(Icons.Rounded.Keyboard, stringResource(R.string.settings_keyboard), stringResource(R.string.settings_keyboard_subtitle), { onOpen(SettingsPage.KEYBOARD) })
            NavigationRow(Icons.Rounded.Wifi, stringResource(R.string.settings_connection), stringResource(R.string.settings_connection_subtitle), { onOpen(SettingsPage.CONNECTION) })
            NavigationRow(Icons.Rounded.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_subtitle), { onOpen(SettingsPage.APPEARANCE) })
            NavigationRow(Icons.Rounded.Security, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_subtitle), { onOpen(SettingsPage.PRIVACY) })
            NavigationRow(
                Icons.Rounded.Info,
                stringResource(R.string.settings_about),
                stringResource(if (updateAvailable) R.string.settings_about_update_available else R.string.settings_about_subtitle),
                { onOpen(SettingsPage.ABOUT) },
            )
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; actions.resetToDefaults() }) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The pages under Settings. */
enum class SettingsPage { TOUCHPAD, KEYBOARD, CONNECTION, APPEARANCE, PRIVACY, ABOUT }
