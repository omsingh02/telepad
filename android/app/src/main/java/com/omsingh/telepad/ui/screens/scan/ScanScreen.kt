package com.omsingh.telepad.ui.screens.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.omsingh.telepad.R
import com.omsingh.telepad.core.wifi.PairingInvite
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.rememberHaptics
import kotlinx.coroutines.delay

/** How the camera permission stands, as far as this screen is concerned. */
enum class CameraAccess {
    /** Allowed: the camera is shown. */
    GRANTED,

    /** Not asked yet (or being asked now): the reason is shown. */
    NEEDED,

    /** Refused: the way to change it is shown. */
    DENIED,
}

/** Something the camera saw that was not a code to pair with. */
enum class ScanProblem(val message: Int) {
    NOT_TELEPAD(R.string.scan_problem_not_telepad),
    NEEDS_NEWER_APP(R.string.scan_problem_newer),
    DAMAGED(R.string.scan_problem_damaged),
}

/**
 * Scanning the QR code on the PC's screen. A code that is a valid invitation is handed to [onInvite],
 * once; anything else the camera sees is explained for a moment and ignored.
 */
@Composable
fun ScanRoute(
    onInvite: (PairingInvite) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val platform = LocalPlatformActions.current
    val haptics = rememberHaptics(enabled = true)

    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraAllowed()) }
    var answered by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        answered = true
    }
    // A person who tapped "Scan QR code" wants the camera: ask at once, with the reason on screen behind.
    LaunchedEffect(Unit) {
        if (!granted && !answered) request.launch(Manifest.permission.CAMERA)
    }
    // Coming back from the settings, where the camera may have been turned on.
    LifecycleResumeEffect(Unit) {
        granted = cameraAllowed()
        onPauseOrDispose { }
    }

    var problem by remember { mutableStateOf<ScanProblem?>(null) }
    LaunchedEffect(problem) {
        if (problem != null) {
            delay(PROBLEM_SHOWN_MS)
            problem = null
        }
    }
    var finished by remember { mutableStateOf(false) }
    var noCamera by remember { mutableStateOf(false) }

    ScanScreen(
        access = when {
            granted -> CameraAccess.GRANTED
            answered -> CameraAccess.DENIED
            else -> CameraAccess.NEEDED
        },
        problem = problem,
        noCamera = noCamera,
        onAllow = { request.launch(Manifest.permission.CAMERA) },
        onOpenSettings = platform::openAppSettings,
        onClose = onClose,
        modifier = modifier,
        camera = { cameraModifier ->
            QrCamera(
                modifier = cameraModifier,
                onUnavailable = { noCamera = true },
                onText = { text ->
                    if (finished) return@QrCamera
                    when (val read = PairingInvite.parse(text)) {
                        is PairingInvite.Read.Valid -> {
                            finished = true
                            haptics.confirm()
                            onInvite(read.invite)
                        }
                        PairingInvite.Read.NotTelepad -> problem = ScanProblem.NOT_TELEPAD
                        PairingInvite.Read.NeedsNewerApp -> problem = ScanProblem.NEEDS_NEWER_APP
                        PairingInvite.Read.Damaged -> problem = ScanProblem.DAMAGED
                    }
                },
            )
        },
    )
}

private const val PROBLEM_SHOWN_MS = 3_000L

/**
 * The scanner's screen, apart from the camera and the permission: what is shown in each state. The
 * camera is a slot, so that this can be drawn and tested without one.
 */
@Composable
fun ScanScreen(
    access: CameraAccess,
    problem: ScanProblem?,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    camera: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    noCamera: Boolean = false,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when {
            noCamera -> Explanation(
                title = stringResource(R.string.scan_title),
                body = stringResource(R.string.scan_no_camera),
                button = null,
                onButton = {},
            )
            access == CameraAccess.GRANTED -> {
                camera(Modifier.fillMaxSize())
                Viewfinder(problem)
            }
            access == CameraAccess.NEEDED -> Explanation(
                title = stringResource(R.string.scan_camera_title),
                body = stringResource(R.string.scan_camera_body),
                button = stringResource(R.string.scan_camera_allow),
                onButton = onAllow,
            )
            else -> Explanation(
                title = stringResource(R.string.scan_camera_denied_title),
                body = stringResource(R.string.scan_camera_denied_body),
                button = stringResource(R.string.scan_open_settings),
                onButton = onOpenSettings,
            )
        }

        // The way out is always there, over the picture or the explanation.
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(Spacing.sm)
                .align(Alignment.TopStart),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.scan_close),
                tint = if (access == CameraAccess.GRANTED && !noCamera) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Why the camera is not showing, and what to do about it. */
@Composable
private fun Explanation(title: String, body: String, button: String?, onButton: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Rounded.QrCodeScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (button != null) {
            Button(onClick = onButton) { Text(button) }
        }
    }
}

/** The square to put the code in, with what to do written below it. */
@Composable
private fun Viewfinder(problem: ScanProblem?) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.68f
            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f - size.height * 0.04f)
            val corner = CornerRadius(28.dp.toPx())
            val hole = RoundRect(topLeft.x, topLeft.y, topLeft.x + side, topLeft.y + side, corner)

            // Everything outside the square is dimmed, so the eye goes to the middle.
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addRoundRect(hole)
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.55f))
            drawRoundRect(
                color = Color.White,
                topLeft = topLeft,
                size = Size(side, side),
                cornerRadius = corner,
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = problem != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        text = problem?.let { stringResource(it.message) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                stringResource(R.string.scan_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.scan_where),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
