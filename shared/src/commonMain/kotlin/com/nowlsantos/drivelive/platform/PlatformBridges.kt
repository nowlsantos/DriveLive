package com.nowlsantos.drivelive.platform

import kotlinx.coroutines.flow.Flow

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float? = null,
    val bearingDegrees: Float? = null
)

enum class NavigationApp {
    GOOGLE_MAPS,
    WAZE,
    APPLE_MAPS,
    DEFAULT
}

enum class MediaControlAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS
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
    suspend fun controlPlayback(action: MediaControlAction): LaunchResult
}

expect class LocationProvider {
    suspend fun getCurrentLocation(): GeoCoordinate?
}

expect class DisplayController {
    fun setKeepScreenOn(enabled: Boolean)
}

data class CalendarEventItem(
    val title: String,
    val startTimeLabel: String,
    val location: String?
)

expect class CalendarProvider {
    suspend fun getUpcomingEvents(hoursAhead: Int = 18): List<CalendarEventItem>
}

expect class DriveStorage {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
}

expect class LocalVoiceSpeaker {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
