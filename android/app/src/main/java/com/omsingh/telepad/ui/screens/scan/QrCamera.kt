package com.omsingh.telepad.ui.screens.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.core.ImageAnalysis
import com.omsingh.telepad.core.wifi.QrDecoder
import java.util.concurrent.Executors

/**
 * The camera's view, looking for QR codes. Every code it reads is passed to [onText] (on the main
 * thread), as often as the camera sees it: what to do about it is the caller's business.
 *
 * It lives only as long as it is on screen: the camera is let go of the moment this leaves the
 * composition, not when the app does.
 *
 * [onUnavailable] is called if this phone has no camera to scan with.
 */
@Composable
internal fun QrCamera(
    onText: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrDecoder() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // The preview is always on; reading the codes is the only other thing wanted.
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setImageAnalysisAnalyzer(analysisExecutor) { image ->
                try {
                    readCode(image, decoder)?.let { text -> mainExecutor.execute { latestOnText(text) } }
                } finally {
                    image.close()
                }
            }
        }
    }

    DisposableEffect(controller, lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (_: Exception) {
            onUnavailable()
        }
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
    )
}

/** The text of the QR code in a camera frame, if there is one. */
private fun readCode(image: ImageProxy, decoder: QrDecoder): String? {
    // The first plane is the brightness, which is all a QR code needs.
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    return decoder.decode(data, image.width, image.height, plane.rowStride)
}
