package com.omsingh.telepad.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omsingh.telepad.ui.theme.Dimens

/**
 * First-time touchpad coach mark.
 *
 * Shows a centered card with 4 gesture mini-explanations, dismissible by
 * tapping anywhere or the "Got it" button. Dismissal is reported up so the
 * caller can persist "touchpad_intro_shown = true" and never display again.
 *
 * Why a full-screen overlay rather than tooltips? Tooltips on a touchpad
 * surface are awkward — the user's finger covers them. A central card is
 * read-once-and-go, which suits the "five seconds of orientation" intent.
 */
@Composable
fun TouchpadIntroOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(Dimens.ScreenHorizontalPadding)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(Dimens.CardCornerRadiusLarge)
                        )
                        .padding(Dimens.CardInternalPaddingLarge)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                    ) {
                        Text(
                            text = "How to use the touchpad",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(4.dp))
                        GestureLine(Icons.Filled.TouchApp, "Tap to click")
                        GestureLine(Icons.Filled.PanTool, "Drag to move pointer")
                        GestureLine(Icons.Outlined.SwipeVertical, "Two fingers to scroll")
                        GestureLine(Icons.Filled.TouchApp, "Two-finger tap = right click")
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Got it")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureLine(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
