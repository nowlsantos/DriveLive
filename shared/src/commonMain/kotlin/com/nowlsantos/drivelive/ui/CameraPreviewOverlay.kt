package com.nowlsantos.drivelive.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-native camera viewfinder rendered inside the PiP overlay during CameraBurst.
 */
@Composable
expect fun CameraPreviewOverlay(modifier: Modifier = Modifier)