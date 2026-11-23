package com.nowlsantos.drivelive.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nowlsantos.drivelive.platform.CameraFrameStreamer
import org.koin.compose.koinInject

@Composable
actual fun CameraPreviewOverlay(modifier: Modifier) {
    val cameraStreamer: CameraFrameStreamer = koinInject()

    DisposableEffect(Unit) {
        onDispose {
            cameraStreamer.setSurfaceProvider(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraStreamer.setSurfaceProvider(surfaceProvider)
            }
        }
    )
}