package com.nowlsantos.drivelive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nowlsantos.drivelive.di.initDriveLiveKoin
import com.nowlsantos.drivelive.platform.CameraFrameStreamer
import com.nowlsantos.drivelive.platform.DisplayController
import com.nowlsantos.drivelive.ui.DriveLiveScreen
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (GlobalContext.getOrNull() == null) {
            initDriveLiveKoin(geminiApiKey = BuildConfig.GEMINI_API_KEY) {
                androidContext(this@MainActivity)
            }
        }

        val koin = getKoin()
        koin.get<DisplayController>().attachActivity(this)
        koin.get<CameraFrameStreamer>().attachLifecycleOwner(this)

        setContent {
            var hasPermissions by remember {
                mutableStateOf(checkCorePermissionsGranted())
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                hasPermissions = results[Manifest.permission.RECORD_AUDIO] == true
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                DriveLiveScreen(
                    hasPermissions = hasPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredPermissions)
                    }
                )
            }
        }
    }

    private fun checkCorePermissionsGranted(): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        return hasMic && hasBluetooth
    }
}