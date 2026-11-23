@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nowlsantos.drivelive.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.nowlsantos.drivelive.platform.CameraFrameStreamer
import kotlinx.cinterop.CValue
import org.koin.compose.koinInject
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIColor
import platform.UIKit.UIView

@Composable
actual fun CameraPreviewOverlay(modifier: Modifier) {
    val cameraStreamer: CameraFrameStreamer = koinInject()
    val containerView = remember { CameraPreviewContainerView() }

    DisposableEffect(cameraStreamer, containerView) {
        cameraStreamer.attachPreviewLayer(containerView.previewLayer)
        onDispose {
            cameraStreamer.detachPreviewLayer()
        }
    }

    UIKitView(
        factory = { containerView },
        modifier = modifier,
        update = { view ->
            view.setNeedsLayout()
        },
        onRelease = {
            cameraStreamer.detachPreviewLayer()
        }
    )
}

private class CameraPreviewContainerView(
    frame: CValue<CGRect> = CGRectMake(0.0, 0.0, 0.0, 0.0)
) : UIView(frame = frame) {

    val previewLayer: AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer().apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setValue(true, forKey = kCATransactionDisableActions)
        previewLayer.frame = bounds
        CATransaction.commit()
    }
}