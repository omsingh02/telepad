package com.omsingh.telepad.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.core.media.NowPlayingState
import com.omsingh.telepad.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * "Now playing on your PC" card for the Controls screen.
 *
 * Renders title/artist with a smoothly-advancing progress bar. The
 * progress is locally extrapolated between server polls (every ~2s) using
 * [NowPlayingState.extrapolatedPositionMs], so the UI looks live without
 * spamming the network.
 *
 * Auto-hidden when there's no metadata (empty state) — the card disappears
 * rather than showing "—" placeholders. Keeps the Controls screen clean for
 * users who don't play media on their PC.
 */
@Composable
fun NowPlayingCard(
    state: NowPlayingState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.hasContent,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        ElevatedCard(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.CardCornerRadius),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
            Column(
                modifier = Modifier.padding(Dimens.CardInternalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacingSmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.size(Dimens.ItemSpacing))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title ?: "Unknown track",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        val sub = listOfNotNull(state.artist, state.album, state.sourceApp)
                            .joinToString(" • ")
                        if (sub.isNotEmpty()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Live-extrapolated progress bar — recomposes ~60 Hz while playing.
                if (state.durationMs != null && state.positionMs != null && state.durationMs > 0) {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(state.isPlaying) {
                        if (state.isPlaying) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(250)
                            }
                        }
                    }
                    val pos = state.extrapolatedPositionMs(now) ?: state.positionMs
                    val progress = (pos.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        strokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatMs(pos),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMs(state.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Tiny formatter: 192345 ms → "3:12". */
private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
