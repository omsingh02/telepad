package com.omsingh.telepad.ui.screens

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.omsingh.telepad.core.input.SensitivityCurve
import com.omsingh.telepad.settings.AccelerationCurve
import com.omsingh.telepad.ui.theme.Dimens
import com.omsingh.telepad.ui.theme.TrackpadBorderDark
import com.omsingh.telepad.ui.theme.TrackpadSurfaceDark
import com.omsingh.telepad.viewmodel.SettingsViewModel
import java.util.Locale

/**
 * Calibration screen: live preview of acceleration curve & sensitivity.
 *
 * Two interactive sliders (sensitivity, scroll speed) plus a "test surface"
 * that visualises the actual cursor-delta you'd produce with the current
 * settings — a virtual cursor moves on screen so you can *feel* the curve
 * before committing.
 *
 * The cursor doesn't leave the test surface — it's clamped — so users can
 * pick a sensitivity that suits how they physically move their finger.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TouchpadCalibrationScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val prefs by viewModel.preferences.collectAsState()

    val sensitivity = remember { mutableFloatStateOf(prefs.sensitivity) }
    val scrollSpeed = remember { mutableFloatStateOf(prefs.scrollSpeed) }
    val curve = remember(prefs.accelerationCurve) {
        SensitivityCurve(
            baseSensitivity = sensitivity.floatValue,
            curve = when (prefs.accelerationCurve) {
                AccelerationCurve.LINEAR  -> SensitivityCurve.AccelCurve.LINEAR
                AccelerationCurve.MACOS   -> SensitivityCurve.AccelCurve.MACOS
                AccelerationCurve.WINDOWS -> SensitivityCurve.AccelCurve.WINDOWS
                AccelerationCurve.FLAT    -> SensitivityCurve.AccelCurve.FLAT
            }
        )
    }
    curve.baseSensitivity = sensitivity.floatValue

    var cursor by remember { mutableStateOf(Offset(150f, 150f)) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            TopAppBar(
                title = { Text("Calibrate touchpad") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Text(
                text = "Sensitivity",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(String.format(Locale.US, "%.1f", sensitivity.floatValue),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = sensitivity.floatValue,
                onValueChange = {
                    sensitivity.floatValue = it
                    viewModel.updatePreferences { p -> p.copy(sensitivity = it) }
                },
                valueRange = 0.5f..5.0f,
                steps = 9,
            )

            Text(
                text = "Scroll speed",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = scrollSpeed.floatValue,
                onValueChange = {
                    scrollSpeed.floatValue = it
                    viewModel.updatePreferences { p -> p.copy(scrollSpeed = it) }
                },
                valueRange = 0.5f..5.0f,
                steps = 9,
            )

            Text(
                text = "Try it — your finger draws a cursor in the box below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(TrackpadSurfaceDark, RoundedCornerShape(Dimens.TouchpadCornerRadius))
                    .border(Dimens.TouchpadBorderWidth, TrackpadBorderDark, RoundedCornerShape(Dimens.TouchpadCornerRadius))
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastX = event.x; lastY = event.y
                                cursor = Offset(event.x, event.y)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - lastX
                                val dy = event.y - lastY
                                lastX = event.x; lastY = event.y
                                val (sx, sy) = curve.apply(dx, dy)
                                cursor = Offset(
                                    (cursor.x + sx).coerceIn(0f, 1000f),
                                    (cursor.y + sy).coerceIn(0f, 1000f),
                                )
                            }
                        }
                        true
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 12.dp.toPx(),
                        center = cursor,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 3.dp.toPx(),
                        center = cursor,
                    )
                }
            }
        }
    }
}
