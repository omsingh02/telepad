package com.omsingh.telepad.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.KeyboardSession

/**
 * Says which modifier keys are still on, on the screens that have no keyboard of their own.
 *
 * A modifier latched on the Keys tab stays on until a key uses it. Left on and forgotten, it would
 * turn the next thing typed into a shortcut, so it is shown wherever the person goes, and one tap
 * lets go of all of them.
 */
@Composable
fun ModifierHud(host: HostProfile, keyboard: KeyboardSession, modifier: Modifier = Modifier) {
    // Read in the body so the indicator redraws when a modifier changes (see KeyBar).
    val version by keyboard.version.collectAsState()
    val names = remember(version, host) {
        val mods = keyboard.modifiers()
        buildList {
            if (mods.leftCtrl) add(host.ctrl.symbol)
            if (mods.leftAlt) add(host.alt.symbol)
            if (mods.leftShift) add(host.shift.symbol)
            if (mods.leftMeta) add(host.meta.symbol)
        }
    }
    val release = stringResource(R.string.keys_release_modifiers)

    AnimatedVisibility(
        visible = names.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
        Surface(
            onClick = { keyboard.releaseModifiers() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.semantics { contentDescription = release },
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.keys_chord_active, names.joinToString(" + ")),
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}
