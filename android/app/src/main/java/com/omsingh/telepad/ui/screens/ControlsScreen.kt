package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.components.NowPlayingCard
import com.omsingh.telepad.ui.components.PresentationModePad
import com.omsingh.telepad.ui.components.StatusBar
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.viewmodel.MainViewModel

/**
 * Controls screen — the "remote" surface.
 *
 * Top to bottom:
 *  1. Status bar.
 *  2. **Now playing card** — auto-hides if PC isn't playing anything.
 *  3. Media transport (prev/play-pause/next).
 *  4. Volume (down/mute/up).
 *  5. **Quick launchers** — Browser / File manager / Screenshot.
 *  6. **Presentation mode** toggle that swaps the lower half for the giant
 *     tap zones from [PresentationModePad].
 *  7. System: lock screen.
 *
 * Now-playing polling starts when this screen is shown and stops when left —
 * so it costs nothing when the user isn't looking.
 */
@Composable
fun ControlsScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val nowPlaying by viewModel.nowPlayingState.collectAsState()
    val haptic = LocalHapticFeedback.current

    var presentationMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.nowPlaying.start()
    }

    fun emit(event: InputEvent) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.onInputEvent(event)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        StatusBar(state = connectionState, onDisconnect = { viewModel.disconnect() })

        if (!presentationMode) {
            NowPlayingCard(state = nowPlaying)

            // Media row
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.CardInternalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Media", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { emit(InputEvent.MediaCommand(InputEvent.MediaAction.PREV)) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(32.dp)) }

                        IconButton(
                            onClick = { emit(InputEvent.MediaCommand(InputEvent.MediaAction.PLAY_PAUSE)) },
                            modifier = Modifier.size(80.dp),
                        ) { Icon(Icons.Filled.PlayArrow, "Play/Pause", modifier = Modifier.size(48.dp)) }

                        IconButton(
                            onClick = { emit(InputEvent.MediaCommand(InputEvent.MediaAction.NEXT)) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(32.dp)) }
                    }
                }
            }

            // Volume row
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.CardInternalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Volume", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { emit(InputEvent.VolumeCommand(InputEvent.VolumeDirection.DOWN)) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", modifier = Modifier.size(32.dp)) }
                        IconButton(
                            onClick = { emit(InputEvent.VolumeCommand(InputEvent.VolumeDirection.MUTE)) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.VolumeMute, "Mute", modifier = Modifier.size(32.dp)) }
                        IconButton(
                            onClick = { emit(InputEvent.VolumeCommand(InputEvent.VolumeDirection.UP)) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", modifier = Modifier.size(32.dp)) }
                    }
                }
            }

            // Quick launchers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
            ) {
                LauncherCard(
                    icon = Icons.Filled.Public,
                    label = "Browser",
                    onClick = { emit(InputEvent.LaunchAction(InputEvent.SystemAction.BROWSER)) },
                    modifier = Modifier.weight(1f),
                )
                LauncherCard(
                    icon = Icons.Filled.Folder,
                    label = "Files",
                    onClick = { emit(InputEvent.LaunchAction(InputEvent.SystemAction.FILE_MANAGER)) },
                    modifier = Modifier.weight(1f),
                )
                LauncherCard(
                    icon = Icons.Filled.Screenshot,
                    label = "Screenshot",
                    onClick = { emit(InputEvent.LaunchAction(InputEvent.SystemAction.SCREENSHOT)) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Presentation toggle
            Card(
                onClick = { presentationMode = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            ) {
                Row(
                    modifier = Modifier.padding(Dimens.CardInternalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PresentToAll, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.size(Dimens.ItemSpacing))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Presentation mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Giant tap zones for advancing slides",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        } else {
            // Presentation pad — fills the rest of the screen.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { presentationMode = false },
                shape = RoundedCornerShape(Dimens.CardCornerRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Row(
                    modifier = Modifier.padding(Dimens.CardInternalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Computer, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(Dimens.ItemSpacing))
                    Text("Tap to exit presentation",
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                PresentationModePad(onInputEvent = viewModel::onInputEvent)
            }
        }

        // Lock screen — always visible.
        Card(
            onClick = { emit(InputEvent.LockScreen) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
            Row(
                modifier = Modifier.padding(Dimens.CardInternalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(Dimens.ItemSpacing))
                Text("Lock screen",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(Dimens.ScreenVerticalPadding))
    }
}

@Composable
private fun LauncherCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
