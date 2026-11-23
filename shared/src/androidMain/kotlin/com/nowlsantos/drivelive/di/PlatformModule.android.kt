package com.nowlsantos.drivelive.di

import com.nowlsantos.drivelive.platform.AudioEngine
import com.nowlsantos.drivelive.platform.CalendarProvider
import com.nowlsantos.drivelive.platform.CameraFrameStreamer
import com.nowlsantos.drivelive.platform.DisplayController
import com.nowlsantos.drivelive.platform.DriveStorage
import com.nowlsantos.drivelive.platform.LocalVoiceSpeaker
import com.nowlsantos.drivelive.platform.LocationProvider
import com.nowlsantos.drivelive.platform.MediaLauncher
import com.nowlsantos.drivelive.platform.NavigationLauncher
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { AudioEngine() }
    single { CameraFrameStreamer(androidContext()) }
    single { NavigationLauncher(androidContext()) }
    single { MediaLauncher(androidContext()) }
    single { LocationProvider(androidContext()) }
    single { DisplayController() }
    single { DriveStorage(androidContext()) }
    single { LocalVoiceSpeaker(androidContext()) }
    single { CalendarProvider(androidContext()) }
}