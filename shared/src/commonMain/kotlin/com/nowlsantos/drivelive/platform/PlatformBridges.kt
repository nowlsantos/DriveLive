package com.nowlsantos.drivelive.platform

import kotlinx.coroutines.flow.Flow

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
)

enum class NavigationApp {
    GOOGLE_MAPS,
    WAZE,
    APPLE_MAPS,
    DEFAULT
}

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class Failure(val reason: String) : LaunchResult
}

expect class AudioEngine() {
    fun startCapture(): Flow<Pair<ByteArray, Float>>
    suspend fun playPcm24k(pcmData: ByteArray)
    fun flushPlayback()
    fun stopAll()
}

expect class CameraFrameStreamer {
    fun streamFrames(durationSeconds: Int): Flow<ByteArray>
    fun stop()
}

expect class NavigationLauncher {
    suspend fun navigateTo(
        destination: String,
        coordinate: GeoCoordinate?,
        preferredApp: NavigationApp = NavigationApp.DEFAULT
    ): LaunchResult
}

expect class MediaLauncher {
    suspend fun openSpotifyUri(spotifyUri: String): LaunchResult
    suspend fun searchSpotifyFallback(query: String): LaunchResult
}

expect class LocationProvider {
    suspend fun getCurrentLocation(): GeoCoordinate?
}

expect class DisplayController {
    fun setKeepScreenOn(enabled: Boolean)
}
