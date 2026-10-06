package com.omsingh.telepad.ui.screens.remote

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.components.SectionHeader
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Media keys, volume, slides and the PC's own actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaTab(
    state: RemoteUiState,
    actions: RemoteActions,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    // Ask the PC what is playing only while this tab is on screen.
    DisposableEffect(Unit) {
        actions.mediaVisible(true)
        onDispose { actions.mediaVisible(false) }
    }

    val send = { event: InputEvent -> haptics.key(); actions.send(event) }
    val tap = { usage: Int ->
        haptics.key()
        actions.send(InputEvent.KeyPress(usage))
        actions.send(InputEvent.KeyRelease(usage))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (state.hostInfo.capabilities.nowPlaying) NowPlayingCard(state.nowPlaying)

        // ── Transport ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { send(InputEvent.MediaCommand(InputEvent.MediaAction.PREV)) },
                modifier = Modifier.size(64.dp),
            ) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.media_previous), Modifier.size(32.dp)) }
            FilledIconButton(
                onClick = { send(InputEvent.MediaCommand(InputEvent.MediaAction.PLAY_PAUSE)) },
                modifier = Modifier.size(84.dp),
            ) {
                Icon(
                    if (state.nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(R.string.media_play_pause),
                    Modifier.size(44.dp),
                )
            }
            FilledTonalIconButton(
                onClick = { send(InputEvent.MediaCommand(InputEvent.MediaAction.NEXT)) },
                modifier = Modifier.size(64.dp),
            ) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.media_next), Modifier.size(32.dp)) }
        }

        // ── Volume ──
        Section(stringResource(R.string.media_volume)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepeatButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeDown,
                    label = stringResource(R.string.media_quieter),
                    description = stringResource(R.string.media_volume_down),
                    onFire = { send(InputEvent.VolumeCommand(InputEvent.VolumeDirection.DOWN)) },
                    modifier = Modifier.weight(1f),
                )
                RepeatButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    label = stringResource(R.string.media_mute),
                    description = stringResource(R.string.media_mute),
                    onFire = { send(InputEvent.VolumeCommand(InputEvent.VolumeDirection.MUTE)) },
                    repeat = false,
                    modifier = Modifier.weight(1f),
                )
                RepeatButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    label = stringResource(R.string.media_louder),
                    description = stringResource(R.string.media_volume_up),
                    onFire = { send(InputEvent.VolumeCommand(InputEvent.VolumeDirection.UP)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Slides ──
        Section(stringResource(R.string.media_slides)) {
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SlideButton(
                    icon = Icons.Rounded.ChevronLeft,
                    label = stringResource(R.string.media_slide_previous),
                    onTap = { tap(HidKeyCodes.LEFT) },
                    modifier = Modifier.weight(1f),
                )
                SlideButton(
                    icon = Icons.Rounded.ChevronRight,
                    label = stringResource(R.string.media_slide_next),
                    onTap = { tap(HidKeyCodes.RIGHT) },
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for ((label, usage) in listOf(
                    R.string.media_slide_start to HidKeyCodes.F5,
                    R.string.media_slide_blank to HidKeyCodes.B,
                    R.string.media_slide_end to HidKeyCodes.ESC,
                )) {
                    AssistChip(
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = null,
                        onClick = { tap(usage) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }

        // ── The PC itself ──
        val available = systemActions(state)
        Section(stringResource(R.string.media_system)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (action in available) {
                    FilledTonalButton(onClick = { send(action.event) }) {
                        Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(action.label(state.host), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title, horizontalPadding = 0.dp)
        content()
    }
}

/** A button that fires once when pressed and, if held, keeps firing: volume goes up as long as you hold it. */
@Composable
private fun RepeatButton(
    icon: ImageVector,
    label: String,
    description: String,
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
    repeat: Boolean = true,
) {
    val fire by rememberUpdatedState(onFire)
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(72.dp)
            .pointerInput(repeat) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        fire()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { fire(); true }
            },
        shape = MaterialTheme.shapes.large,
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (pressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }

    if (repeat && pressed) {
        LaunchedEffect(Unit) {
            delay(HOLD_BEFORE_REPEAT_MS)
            while (true) {
                fire()
                delay(REPEAT_MS)
            }
        }
    }
}

private const val HOLD_BEFORE_REPEAT_MS = 400L
private const val REPEAT_MS = 110L

@Composable
private fun SlideButton(
    icon: ImageVector,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Surface(
        onClick = onTap,
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (emphasised) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

// ── System actions ───────────────────────────────────────────────────

private class SystemActionItem(
    val icon: ImageVector,
    val event: InputEvent,
    private val labelRes: (HostOs) -> Int,
) {
    @Composable
    fun label(host: HostProfile): String = stringResource(labelRes(host.os))
}

/**
 * The actions this connection can perform. Over Wi-Fi the server does all of them; over
 * Bluetooth only those the host OS has a keyboard shortcut for can be sent.
 */
private fun systemActions(state: RemoteUiState): List<SystemActionItem> {
    val host = state.host
    val everything = state.overWifi
    fun available(action: InputEvent.SystemAction) = everything || host.systemChord(action) != null

    return buildList {
        if (available(InputEvent.SystemAction.SHOW_DESKTOP)) {
            add(SystemActionItem(Icons.Rounded.DesktopWindows, InputEvent.LaunchAction(InputEvent.SystemAction.SHOW_DESKTOP)) { R.string.action_show_desktop })
        }
        if (available(InputEvent.SystemAction.TASK_VIEW)) {
            add(
                SystemActionItem(Icons.Rounded.Dashboard, InputEvent.LaunchAction(InputEvent.SystemAction.TASK_VIEW)) {
                    when (it) {
                        HostOs.MACOS -> R.string.action_task_view_macos
                        HostOs.LINUX -> R.string.action_task_view_linux
                        else -> R.string.action_task_view_windows
                    }
                },
            )
        }
        if (available(InputEvent.SystemAction.TASK_MANAGER)) {
            add(
                SystemActionItem(Icons.Rounded.Speed, InputEvent.LaunchAction(InputEvent.SystemAction.TASK_MANAGER)) {
                    when (it) {
                        HostOs.MACOS -> R.string.action_task_manager_macos
                        HostOs.LINUX -> R.string.action_task_manager_linux
                        else -> R.string.action_task_manager_windows
                    }
                },
            )
        }
        if (available(InputEvent.SystemAction.SCREENSHOT)) {
            add(SystemActionItem(Icons.Rounded.Screenshot, InputEvent.LaunchAction(InputEvent.SystemAction.SCREENSHOT)) { R.string.action_screenshot })
        }
        if (available(InputEvent.SystemAction.FILE_MANAGER)) {
            add(
                SystemActionItem(Icons.Rounded.Folder, InputEvent.LaunchAction(InputEvent.SystemAction.FILE_MANAGER)) {
                    when (it) {
                        HostOs.MACOS -> R.string.action_file_manager_macos
                        HostOs.LINUX -> R.string.action_file_manager_linux
                        else -> R.string.action_file_manager_windows
                    }
                },
            )
        }
        if (available(InputEvent.SystemAction.BROWSER)) {
            add(SystemActionItem(Icons.Rounded.Public, InputEvent.LaunchAction(InputEvent.SystemAction.BROWSER)) { R.string.action_browser })
        }
        add(SystemActionItem(Icons.Rounded.Lock, InputEvent.LockScreen) { R.string.action_lock })
    }
}
