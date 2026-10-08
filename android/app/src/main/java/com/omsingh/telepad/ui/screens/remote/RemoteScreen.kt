package com.omsingh.telepad.ui.screens.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omsingh.telepad.R
import com.omsingh.telepad.connection.Notice
import com.omsingh.telepad.core.host.HostOs
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.KeyboardSession
import com.omsingh.telepad.ui.components.HeroIllustration
import com.omsingh.telepad.ui.components.Scene
import com.omsingh.telepad.ui.components.StatusDot
import com.omsingh.telepad.ui.components.failureBody
import com.omsingh.telepad.ui.components.failureTitle
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.ui.theme.rememberHaptics

enum class RemoteMode(val icon: ImageVector, val label: Int) {
    PAD(Icons.Rounded.Mouse, R.string.remote_mode_pad),
    KEYS(Icons.Rounded.Keyboard, R.string.remote_mode_keys),
    MEDIA(Icons.Rounded.PlayCircle, R.string.remote_mode_media),
}

/** The remote control wired to its view model, with the brief messages it shows. */
@Composable
fun RemoteRoute(
    viewModel: RemoteViewModel,
    onGoToDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val sent = stringResource(R.string.notice_clipboard_sent)
    val empty = stringResource(R.string.notice_clipboard_empty)
    val disabled = stringResource(R.string.notice_clipboard_disabled)
    val fromPc = stringResource(R.string.notice_clipboard_from_pc)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val text = when (notice) {
                Notice.ClipboardSent -> sent
                Notice.ClipboardEmpty -> empty
                Notice.ClipboardDisabled -> disabled
                Notice.ClipboardFromPc -> fromPc
                is Notice.UntypeableText -> resources.getQuantityString(R.plurals.notice_untypeable, notice.count, notice.count)
            }
            snackbar.showSnackbar(text)
        }
    }

    RemoteScreen(
        state = state,
        actions = viewModel,
        keyboard = viewModel.keyboard,
        snackbar = snackbar,
        onGoToDevices = onGoToDevices,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    onGoToDevices: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var mode by rememberSaveable { mutableStateOf(RemoteMode.PAD) }
    val keyboardUi = rememberKeyboardUiState()
    var details by remember { mutableStateOf(false) }
    var gestures by remember { mutableStateOf(false) }
    val haptics = rememberHaptics(state.preferences.hapticFeedback)

    val usable = state.connection is ConnectionState.Connected || state.connection is ConnectionState.Reconnecting

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Without a connection there is nothing to control: show why, and the way to fix it,
        // instead of the chrome of a remote that cannot be used.
        if (!usable) {
            Box(Modifier.padding(padding).fillMaxSize().statusBarsPadding()) {
                NotConnected(state.connection, onGoToDevices)
            }
            return@Scaffold
        }
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val landscape = maxWidth > maxHeight
            // Turning the phone changes which keyboard fits it, so a choice made before the turn is forgotten.
            // This is here, not in the Keys tab, which is rebuilt every time it is opened.
            LaunchedEffect(landscape) { keyboardUi.chosenPcLayout = null }
            val content: @Composable (Modifier) -> Unit = { contentModifier ->
                RemoteContent(
                    state = state,
                    actions = actions,
                    keyboard = keyboard,
                    keyboardUi = keyboardUi,
                    mode = mode,
                    haptics = haptics,
                    modifier = contentModifier,
                )
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .padding(vertical = Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ConnectionChip(state.connection, compact = true, onClick = { details = true })
                        ModeRail(mode, onSelect = { mode = it })
                    }
                    content(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(end = Spacing.screen, top = Spacing.md, bottom = Spacing.md),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The chip is as wide as its words, not as wide as the screen.
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            ConnectionChip(state.connection, compact = false, onClick = { details = true })
                        }
                        if (mode == RemoteMode.PAD && state.connected) {
                            IconButton(onClick = { gestures = true }) {
                                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = stringResource(R.string.pad_gestures_title))
                            }
                        }
                    }
                    ModeSwitcher(mode, onSelect = { mode = it })
                    content(Modifier.weight(1f).fillMaxWidth().padding(bottom = Spacing.md))
                }
            }
        }
    }

    if (details) ConnectionDetailsSheet(state, onDisconnect = { details = false; actions.disconnect() }, onSwitch = { details = false; onGoToDevices() }, onDismiss = { details = false })
    if (gestures) GestureHelpSheet(onDismiss = { gestures = false })
}

@Composable
private fun RemoteContent(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    keyboardUi: KeyboardUiState,
    mode: RemoteMode,
    haptics: com.omsingh.telepad.ui.theme.Haptics,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // The Keys tab shows its modifiers itself. Anywhere else, one that is still on has to be
            // seen, and it takes its own room rather than covering a control.
            if (mode != RemoteMode.KEYS) {
                ModifierHud(state.host, keyboard, Modifier.align(Alignment.CenterHorizontally).padding(bottom = Spacing.sm))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "remoteMode",
                ) { current ->
                    when (current) {
                        RemoteMode.PAD -> TouchpadTab(state, actions, haptics)
                        RemoteMode.KEYS -> KeyboardTab(state, actions, keyboard, haptics, ui = keyboardUi)
                        RemoteMode.MEDIA -> MediaTab(state, actions, haptics)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.connection is ConnectionState.Reconnecting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            ReconnectingOverlay()
        }
    }
}

/** Covers the controls while the link is rebuilt, and swallows touches so nothing is sent into the void. */
@Composable
private fun ReconnectingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), MaterialTheme.shapes.extraLarge)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.remote_reconnecting), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NotConnected(connection: ConnectionState, onGoToDevices: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        HeroIllustration(Scene.PHONE_AND_PC, Modifier.padding(horizontal = Spacing.xl))
        when (connection) {
            is ConnectionState.Failed -> {
                Text(failureTitle(connection.reason, connection.deviceName), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    failureBody(connection.reason, connection.deviceName, connection.hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is ConnectionState.Connecting -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.devices_connecting_to, connection.deviceName), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            else -> {
                Text(stringResource(R.string.remote_not_connected_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.remote_not_connected_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(onClick = onGoToDevices) { Text(stringResource(R.string.remote_go_to_devices)) }
    }
}

// ── Chrome ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitcher(mode: RemoteMode, onSelect: (RemoteMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RemoteMode.values().forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, RemoteMode.values().size),
                icon = { SegmentedButtonDefaults.Icon(active = mode == item) { Icon(item.icon, contentDescription = null, modifier = Modifier.width(18.dp)) } },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

/** The vertical equivalent for landscape, where height is precious. */
@Composable
private fun ModeRail(mode: RemoteMode, onSelect: (RemoteMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
        for (item in RemoteMode.values()) {
            val selected = mode == item
            Surface(
                onClick = { onSelect(item) },
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier
                    .width(72.dp)
                    .semantics { role = Role.Tab; this.selected = selected },
            ) {
                Column(
                    Modifier.padding(vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Icon(item.icon, contentDescription = null)
                    Text(stringResource(item.label), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Who the phone is connected to, always in view, and tappable for the details. */
@Composable
private fun ConnectionChip(
    connection: ConnectionState,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extended = TelepadTheme.extended
    val (color, name, detail) = when (connection) {
        is ConnectionState.Connected -> Triple(
            if (connection.unstable) extended.warning else extended.success,
            connection.deviceName,
            connection.latencyMs?.let { stringResource(R.string.devices_latency, it) },
        )
        is ConnectionState.Reconnecting -> Triple(extended.warning, connection.deviceName, null)
        is ConnectionState.Connecting -> Triple(extended.warning, connection.deviceName, null)
        is ConnectionState.Failed -> Triple(MaterialTheme.colorScheme.error, connection.deviceName.orEmpty(), null)
        ConnectionState.Disconnected -> Triple(MaterialTheme.colorScheme.outline, stringResource(R.string.remote_not_connected_title), null)
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color)
            if (!compact) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(Icons.Rounded.Wifi, contentDescription = name, modifier = Modifier.width(18.dp))
            }
        }
    }
}

// ── Sheets ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDetailsSheet(
    state: RemoteUiState,
    onDisconnect: () -> Unit,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val connection = state.connection
            val name = when (connection) {
                is ConnectionState.Connected -> connection.deviceName
                is ConnectionState.Reconnecting -> connection.deviceName
                is ConnectionState.Connecting -> connection.deviceName
                else -> stringResource(R.string.generic_your_pc)
            }
            Text(name, style = MaterialTheme.typography.headlineSmall)
            val os = when (state.host.os) {
                HostOs.WINDOWS -> stringResource(R.string.remote_os_windows)
                HostOs.MACOS -> stringResource(R.string.remote_os_macos)
                HostOs.LINUX -> stringResource(R.string.remote_os_linux)
                HostOs.UNKNOWN -> stringResource(R.string.remote_os_unknown)
            }
            val via = when (state.transport) {
                ConnectionState.Transport.BLUETOOTH -> stringResource(R.string.devices_via_bluetooth)
                else -> stringResource(R.string.devices_via_wifi)
            }
            val latency = (connection as? ConnectionState.Connected)?.latencyMs?.let { stringResource(R.string.devices_latency, it) }
            Text(
                listOfNotNull(via, latency, stringResource(R.string.remote_connection_os, os)).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.remote_switch_device)) }
                Button(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.devices_disconnect)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.pad_gestures_title), style = MaterialTheme.typography.headlineSmall)
            for (line in listOf(
                R.string.pad_gesture_move,
                R.string.pad_gesture_click,
                R.string.pad_gesture_drag,
                R.string.pad_gesture_scroll,
                R.string.pad_gesture_right,
                R.string.pad_gesture_middle,
                R.string.pad_gesture_buttons,
            )) {
                Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
