package com.omsingh.telepad.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.ui.components.IconTile
import com.omsingh.telepad.ui.theme.Spacing
import kotlinx.coroutines.delay

/** What the PC is playing, with a progress bar that keeps moving between updates. */
@Composable
fun NowPlayingCard(state: NowPlayingState, modifier: Modifier = Modifier) {
    var position by remember(state) { mutableStateOf(state.positionMs) }
    LaunchedEffect(state) {
        position = state.extrapolatedPositionMs(System.currentTimeMillis())
        // Only a track that is playing moves; a paused one stays where it stopped.
        while (state.isPlaying) {
            delay(1_000)
            position = state.extrapolatedPositionMs(System.currentTimeMillis())
        }
    }
    val duration = state.durationMs

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    Icons.Rounded.MusicNote,
                    container = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 56.dp,
                    iconSize = 28.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title ?: stringResource(R.string.media_nothing_playing),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(state.artist, state.sourceApp).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (position != null && duration != null && duration > 0) {
                LinearProgressIndicator(
                    progress = { (position!!.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(position!!), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(clock(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

/** `3:07`, or `1:02:09` for long media. */
internal fun clock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
