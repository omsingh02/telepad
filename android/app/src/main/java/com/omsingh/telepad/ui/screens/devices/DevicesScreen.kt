package com.omsingh.telepad.ui.screens.devices

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.omsingh.telepad.R
import com.omsingh.telepad.connection.PairingUiState
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.ui.components.ConnectionHero
import com.omsingh.telepad.ui.components.DeviceRow
import com.omsingh.telepad.ui.components.HeroIllustration
import com.omsingh.telepad.ui.components.RowState
import com.omsingh.telepad.ui.components.Scene
import com.omsingh.telepad.ui.components.SectionHeader
import com.omsingh.telepad.ui.components.StepRow
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.rememberReducedMotion

/** The device list wired to its view model. Discovery runs only while this is on screen. */
@Composable
fun DevicesRoute(
    viewModel: DevicesViewModel,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Searching for PCs costs battery, so it follows the screen: on while it is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onShown()
                Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onShown()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    DevicesScreen(state = state, actions = viewModel, onOpenRemote = onOpenRemote, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    actions: DevicesActions,
    onOpenRemote: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    val platform = LocalPlatformActions.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // The pull-to-refresh spinner belongs to the pull. A search that starts by itself when the
    // screen opens shows only the turning refresh icon, not a spinner floating over the list.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(pulled) {
        if (pulled) {
            delay(PULL_INDICATOR_MS)
            pulled = false
        }
    }
    var addSheet by remember { mutableStateOf<AddTab?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<DeviceEntry?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { helpOpen = true }) {
                        Icon(Icons.Rounded.HelpOutline, contentDescription = stringResource(R.string.devices_not_seeing))
                    }
                    RefreshButton(searching = state.searching, onClick = actions::refresh)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addSheet = AddTab.ADDRESS },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.devices_add)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                actions.refresh()
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            DeviceList(
                state = state,
                actions = actions,
                onOpenRemote = onOpenRemote,
                onAddOther = { addSheet = AddTab.BLUETOOTH },
                onForget = { forgetting = it },
                onHelp = { helpOpen = true },
                nowMs = nowMs,
            )
        }
    }

    // ── Overlays ─────────────────────────────────────────────────────

    state.pairing?.let { pairing ->
        PairingSheet(
            state = pairing,
            onConfirm = actions::confirmPairing,
            onDismiss = actions::dismissPairing,
            onRetry = actions::retry,
        )
    }

    addSheet?.let { tab ->
        AddDeviceSheet(
            initialTab = tab,
            bluetoothDevices = state.bluetoothDevices,
            bluetooth = state.bluetooth,
            onConnectAddress = { host, port ->
                addSheet = null
                actions.connectToAddress(host, port)
            },
            onConnectBluetooth = { device ->
                addSheet = null
                actions.connectBluetooth(device)
            },
            onDismiss = { addSheet = null },
        )
    }

    if (helpOpen) HelpSheet(onDismiss = { helpOpen = false }, onGetDesktop = { platform.openUrl(Links.RELEASES) })

    forgetting?.let { entry ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.devices_forget_title, entry.name)) },
            text = { Text(stringResource(R.string.devices_forget_body)) },
            confirmButton = {
                TextButton(onClick = { forgetting = null; actions.forget(entry) }) { Text(stringResource(R.string.action_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private const val PULL_INDICATOR_MS = 1_200L

@Composable
private fun RefreshButton(searching: Boolean, onClick: () -> Unit) {
    val reduced = rememberReducedMotion()
    // The icon turns while searching, unless animations are off.
    val turn = if (searching && !reduced) {
        val transition = rememberInfiniteTransition(label = "refresh")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        )
        angle
    } else {
        0f
    }
    IconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.devices_search_again),
            modifier = Modifier.rotate(turn),
        )
    }
}

@Composable
private fun DeviceList(
    state: DevicesUiState,
    actions: DevicesActions,
    onOpenRemote: () -> Unit,
    onAddOther: () -> Unit,
    onForget: (DeviceEntry) -> Unit,
    onHelp: () -> Unit,
    nowMs: Long,
) {
    val connection = state.connection
    val rowStateOf: (DeviceEntry) -> RowState = { entry ->
        if (entry.id != state.activeId) {
            RowState.IDLE
        } else {
            when (connection) {
                is ConnectionState.Connected -> RowState.CONNECTED
                is ConnectionState.Connecting, is ConnectionState.Reconnecting -> RowState.CONNECTING
                else -> RowState.IDLE
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (connection !is ConnectionState.Disconnected) {
            item(key = "hero") {
                ConnectionHero(
                    state = connection,
                    onOpenRemote = onOpenRemote,
                    onDisconnect = actions::disconnect,
                    onRetry = actions::retry,
                    onDismiss = actions::disconnect,
                    onFixBluetooth = { reason ->
                        // The two Bluetooth failures a person can fix are fixed in the sheet.
                        if (reason == FailureReason.BLUETOOTH_PERMISSION || reason == FailureReason.BLUETOOTH_DISABLED) {
                            onAddOther()
                        }
                    },
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                )
            }
        }

        if (state.devices.isEmpty()) {
            item(key = "empty") { EmptyDevices(searching = state.searching, onAddOther = onAddOther) }
        } else {
            if (state.paired.isNotEmpty()) {
                item(key = "header-paired") { SectionHeader(stringResource(R.string.devices_section_yours)) }
                items(state.paired, key = { "paired-" + it.id }) { entry ->
                    DeviceRow(entry, rowStateOf(entry), onClick = { actions.connect(entry) }, onForget = { onForget(entry) }, nowMs = nowMs)
                }
            }
            if (state.nearby.isNotEmpty()) {
                item(key = "header-nearby") { SectionHeader(stringResource(R.string.devices_section_nearby)) }
                items(state.nearby, key = { "nearby-" + it.id }) { entry ->
                    DeviceRow(entry, rowStateOf(entry), onClick = { actions.connect(entry) }, nowMs = nowMs)
                }
            }
            item(key = "help") {
                TextButton(onClick = onHelp, modifier = Modifier.padding(horizontal = Spacing.sm)) {
                    Text(stringResource(R.string.devices_not_seeing))
                }
            }
        }
    }
}

/** What to show before anything has been found: where to begin, not an apology. */
@Composable
private fun EmptyDevices(searching: Boolean, onAddOther: () -> Unit) {
    val platform = LocalPlatformActions.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        HeroIllustration(Scene.PHONE_AND_PC, Modifier.fillMaxWidth(0.7f))
        Text(stringResource(R.string.devices_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.devices_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StepRow(1, stringResource(R.string.devices_empty_step_install))
            StepRow(2, stringResource(R.string.devices_empty_step_network))
            StepRow(3, stringResource(R.string.devices_empty_step_run))
        }
        if (searching) {
            Text(
                stringResource(R.string.devices_searching),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(onClick = { platform.openUrl(Links.RELEASES) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.devices_get_desktop))
        }
        TextButton(onClick = onAddOther) { Text(stringResource(R.string.devices_other_ways)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit, onGetDesktop: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(stringResource(R.string.devices_help_title), style = MaterialTheme.typography.headlineSmall)
            StepRow(1, stringResource(R.string.devices_help_1))
            StepRow(2, stringResource(R.string.devices_help_2))
            StepRow(3, stringResource(R.string.devices_help_3))
            StepRow(4, stringResource(R.string.devices_help_4))
            StepRow(5, stringResource(R.string.devices_help_5))
            OutlinedButton(onClick = onGetDesktop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.devices_get_desktop))
            }
        }
    }
}
