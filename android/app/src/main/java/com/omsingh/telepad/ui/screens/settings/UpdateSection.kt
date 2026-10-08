package com.omsingh.telepad.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.omsingh.telepad.R
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.update.InstallSource
import com.omsingh.telepad.update.Problem
import com.omsingh.telepad.update.UpdateActions
import com.omsingh.telepad.update.UpdateState
import com.omsingh.telepad.update.UpdateUi

/**
 * Updates, on the About page: what is known, the one thing that makes sense to do about it, and the choice of
 * whether the app looks by itself. Nothing is installed without the button being pressed.
 */
@Composable
fun UpdateSection(ui: UpdateUi, actions: UpdateActions, modifier: Modifier = Modifier) {
    val platform = LocalPlatformActions.current
    val state = ui.state

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleSmall)
        val status = statusLine(ui)
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is UpdateState.CheckFailed || state is UpdateState.InstallFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // A copy that a store looks after, or a development build, is told so rather than offered a button.
        if (ui.release != null && !ui.canInstall) {
            Text(
                when (val source = ui.source) {
                    is InstallSource.Store -> stringResource(R.string.updates_from_store, source.name)
                    InstallSource.Direct -> stringResource(R.string.updates_development_build)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (state) {
            is UpdateState.Downloading -> {
                val total = state.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (state.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is UpdateState.Installing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is UpdateState.Checking -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_checking))
            }
            is UpdateState.Available ->
                if (ui.canInstall && state.apk != null) InstallButton(R.string.updates_install, actions::install)
            is UpdateState.NeedsPermission -> {
                Button(onClick = platform::openInstallPermissionSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.updates_allow))
                }
                OutlinedButton(onClick = actions::install, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.updates_install))
                }
            }
            is UpdateState.InstallFailed -> if (ui.canInstall) InstallButton(R.string.updates_retry, actions::install)
            UpdateState.Unknown, UpdateState.UpToDate, is UpdateState.CheckFailed ->
                OutlinedButton(onClick = actions::check, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.updates_check))
                }
        }

        ui.release?.let { release ->
            TextButton(onClick = { platform.openUrl(release.page) }) { Text(stringResource(R.string.updates_whats_new)) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = ui.checkAutomatically, role = Role.Switch, onValueChange = actions::setCheckAutomatically),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.updates_check_automatically), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.updates_check_automatically_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.checkAutomatically, onCheckedChange = null)
        }
    }
}

@Composable
private fun InstallButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}

/** One line on what is known. */
@Composable
private fun statusLine(ui: UpdateUi): String = when (val state = ui.state) {
    UpdateState.Unknown -> ""
    UpdateState.Checking -> stringResource(R.string.updates_checking)
    UpdateState.UpToDate -> stringResource(R.string.updates_up_to_date)
    is UpdateState.Available -> stringResource(R.string.updates_available, state.release.version.toString())
    is UpdateState.Downloading -> {
        val total = state.total
        if (total != null && total > 0) {
            stringResource(R.string.updates_downloading_percent, state.release.version.toString(), (state.done * 100 / total).coerceIn(0, 100).toInt())
        } else {
            stringResource(R.string.updates_downloading, state.release.version.toString())
        }
    }
    is UpdateState.NeedsPermission -> stringResource(R.string.updates_needs_permission)
    is UpdateState.Installing -> stringResource(R.string.updates_installing)
    is UpdateState.CheckFailed -> problemText(state.problem)
    is UpdateState.InstallFailed -> problemText(state.problem)
}

@Composable
private fun problemText(problem: Problem): String = when (problem) {
    Problem.Offline -> stringResource(R.string.updates_problem_offline)
    Problem.RateLimited -> stringResource(R.string.updates_problem_rate_limited)
    Problem.NoFile -> stringResource(R.string.updates_problem_no_file)
    Problem.ChecksumMismatch -> stringResource(R.string.updates_problem_checksum)
    is Problem.Other -> stringResource(R.string.updates_problem_other, problem.message ?: "?")
}
