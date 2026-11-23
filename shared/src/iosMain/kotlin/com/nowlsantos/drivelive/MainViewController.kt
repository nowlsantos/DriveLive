package com.nowlsantos.drivelive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.nowlsantos.drivelive.di.initDriveLiveKoin
import com.nowlsantos.drivelive.ui.DriveLiveScreen
import org.koin.mp.KoinPlatform
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

fun MainViewController(geminiApiKey: String): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) {
        initDriveLiveKoin(geminiApiKey = geminiApiKey)
    }

    return ComposeUIViewController {
        var hasPermissions by remember {
            mutableStateOf(
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) == AVAuthorizationStatusAuthorized
            )
        }

        val locationManager = remember { CLLocationManager() }

        MaterialTheme(colorScheme = darkColorScheme()) {
            DriveLiveScreen(
                hasPermissions = hasPermissions,
                onRequestPermissions = {
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { micGranted ->
                        dispatch_async(dispatch_get_main_queue()) {
                            hasPermissions = micGranted
                            if (micGranted) {
                                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ -> }
                                locationManager.requestWhenInUseAuthorization()
                            }
                        }
                    }
                }
            )
        }
    }
}
