package com.nowlsantos.drivelive.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.volume
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.URLQueryAllowedCharacterSet
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.MediaPlayer.MPMusicPlaybackState
import platform.MediaPlayer.MPMusicPlayerController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.sqrt

@OptIn(ExperimentalForeignApi::class)
actual class AudioEngine actual constructor() {
    private var engine: AVAudioEngine? = null
    private var playerNode: AVAudioPlayerNode? = null
    private var playbackFormat24k: AVAudioFormat? = null
    private var isCaptureActive = false

    private val inputSampleRate = 16000.0
    private val outputSampleRate = 24000.0

    actual fun startCapture(): Flow<Pair<ByteArray, Float>> = callbackFlow {
        configureVoiceChatAudioSession()

        val avEngine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        val format24k = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = outputSampleRate,
            channels = 1u,
            interleaved = true
        )

        val targetFormat16k = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = inputSampleRate,
            channels = 1u,
            interleaved = true
        )

        avEngine.attachNode(player)
        avEngine.connect(player, to = avEngine.mainMixerNode, format = format24k)

        val inputNode = avEngine.inputNode
        runCatching { inputNode.setVoiceProcessingEnabled(true, error = null) }

        val hardwareFormat = inputNode.outputFormatForBus(0u)
        val hardwareRate = if (hardwareFormat.sampleRate > 0.0) hardwareFormat.sampleRate else inputSampleRate
        val recordingFormat = if (hardwareFormat.sampleRate > 0.0) hardwareFormat else targetFormat16k

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = 1024u,
            format = recordingFormat
        ) { buffer, _ ->
            if (buffer == null) return@installTapOnBus

            val inFrames = buffer.frameLength.toInt()
            if (inFrames <= 0) return@installTapOnBus

            val step = (hardwareRate / inputSampleRate).coerceAtLeast(1.0)
            val outFrames = (inFrames / step).toInt().coerceAtLeast(1)
            val floatChannelData = buffer.floatChannelData
            val int16ChannelData = buffer.int16ChannelData

            if (floatChannelData != null) {
                val floatPtr = floatChannelData[0] ?: return@installTapOnBus
                val pcmBytes = ByteArray(outFrames * 2)
                var sumSquares = 0.0

                for (i in 0 until outFrames) {
                    val srcIdx = (i * step).toInt().coerceIn(0, inFrames - 1)
                    val sampleFloat = floatPtr[srcIdx].coerceIn(-1.0f, 1.0f)
                    val sampleInt16 = (sampleFloat * 32767.0f).toInt()
                    pcmBytes[i * 2] = (sampleInt16 and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((sampleInt16 shr 8) and 0xFF).toByte()
                    val norm = sampleFloat.toDouble()
                    sumSquares += norm * norm
                }

                val rms = sqrt(sumSquares / outFrames).toFloat().coerceIn(0f, 1f)
                trySend(pcmBytes to rms)
            } else if (int16ChannelData != null) {
                val samplesPtr = int16ChannelData[0] ?: return@installTapOnBus
                val pcmBytes = ByteArray(outFrames * 2)
                var sumSquares = 0.0

                for (i in 0 until outFrames) {
                    val srcIdx = (i * step).toInt().coerceIn(0, inFrames - 1)
                    val sample = samplesPtr[srcIdx].toInt()
                    pcmBytes[i * 2] = (sample and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    val norm = sample.toDouble() / 32768.0
                    sumSquares += norm * norm
                }

                val rms = sqrt(sumSquares / outFrames).toFloat().coerceIn(0f, 1f)
                trySend(pcmBytes to rms)
            }
        }

        avEngine.prepare()
        val started = runCatching { avEngine.startAndReturnError(null) }.getOrDefault(false)
        if (!started) {
            inputNode.removeTapOnBus(0u)
            close(IllegalStateException("AVAudioEngine failed to start"))
            return@callbackFlow
        }

        player.play()
        engine = avEngine
        playerNode = player
        playbackFormat24k = format24k
        isCaptureActive = true

        awaitClose {
            isCaptureActive = false
            runCatching { inputNode.removeTapOnBus(0u) }
            runCatching { player.stop() }
            runCatching { avEngine.stop() }
            engine = null
            playerNode = null
            playbackFormat24k = null
            deactivateAudioSession()
        }
    }

    actual suspend fun playPcm24k(pcmData: ByteArray) {
        val player = playerNode ?: return
        val format = playbackFormat24k ?: return
        if (pcmData.size < 2) return

        val frameCount = (pcmData.size / 2).toUInt()
        val pcmBuffer = AVAudioPCMBuffer(
            pCMFormat = format,
            frameCapacity = frameCount
        )
        pcmBuffer.frameLength = frameCount

        val int16Data = pcmBuffer.int16ChannelData ?: return
        val destPtr = int16Data[0] ?: return

        pcmData.usePinned { pinned ->
            memcpy(destPtr, pinned.addressOf(0), (frameCount * 2u).toULong())
        }

        if (!player.isPlaying()) {
            player.play()
        }
        player.scheduleBuffer(pcmBuffer, completionHandler = null)
    }

    actual fun flushPlayback() {
        val player = playerNode ?: return
        runCatching {
            player.stop()
            if (isCaptureActive) {
                player.play()
            }
        }
    }

    actual fun stopAll() {
        isCaptureActive = false
        runCatching {
            engine?.inputNode?.removeTapOnBus(0u)
            playerNode?.stop()
            engine?.stop()
        }
        engine = null
        playerNode = null
        playbackFormat24k = null
        deactivateAudioSession()
    }

    private fun configureVoiceChatAudioSession() {
        val session = AVAudioSession.sharedInstance()
        runCatching {
            session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeVoiceChat,
                options = AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = null
            )
            session.setPreferredIOBufferDuration(0.02, error = null)
            session.setActive(true, error = null)
        }
    }

    private fun deactivateAudioSession() {
        runCatching {
            AVAudioSession.sharedInstance().setActive(false, error = null)
        }
    }
}

actual class CameraFrameStreamer {
    private var previewLayerRef: AVCaptureVideoPreviewLayer? = null

    fun attachPreviewLayer(previewLayer: AVCaptureVideoPreviewLayer) {
        previewLayerRef = previewLayer
    }

    fun detachPreviewLayer() {
        previewLayerRef = null
    }

    actual fun streamFrames(durationSeconds: Int): Flow<ByteArray> = emptyFlow()
    actual fun stop() = Unit
}

actual class NavigationLauncher {
    actual suspend fun navigateTo(
        destination: String,
        coordinate: GeoCoordinate?,
        preferredApp: NavigationApp
    ): LaunchResult = suspendCancellableCoroutine { continuation ->
        val encodedDest = destination.encodeUrlComponent()
        val hasDest = destination.isNotBlank()
        val urlCandidates = when (preferredApp) {
            NavigationApp.WAZE -> if (hasDest) {
                listOf(
                    "waze://?q=$encodedDest&navigate=yes",
                    "https://waze.com/ul?q=$encodedDest&navigate=yes",
                    "http://maps.apple.com/?daddr=$encodedDest"
                )
            } else {
                listOf("waze://", "https://waze.com/ul", "http://maps.apple.com/")
            }
            NavigationApp.GOOGLE_MAPS -> if (hasDest) {
                listOf(
                    "comgooglemaps://?daddr=$encodedDest&directionsmode=driving",
                    "https://www.google.com/maps/dir/?api=1&destination=$encodedDest&travelmode=driving",
                    "http://maps.apple.com/?daddr=$encodedDest"
                )
            } else {
                listOf(
                    "comgooglemaps://",
                    "https://www.google.com/maps",
                    "http://maps.apple.com/"
                )
            }
            else -> listOf(
                if (coordinate != null && !hasDest) {
                    "http://maps.apple.com/?ll=${coordinate.latitude},${coordinate.longitude}"
                } else if (hasDest) {
                    "http://maps.apple.com/?daddr=$encodedDest"
                } else {
                    "http://maps.apple.com/"
                }
            )
        }

        dispatch_async(dispatch_get_main_queue()) {
            val app = UIApplication.sharedApplication
            var launched = false
            for (rawUrl in urlCandidates) {
                val nsUrl = NSURL.URLWithString(rawUrl) ?: continue
                if (app.canOpenURL(nsUrl)) {
                    app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
                    launched = true
                    break
                }
            }
            if (continuation.isActive) {
                if (launched) {
                    continuation.resume(LaunchResult.Success)
                } else {
                    continuation.resume(LaunchResult.Failure("No compatible maps app available on iOS"))
                }
            }
        }
    }
}

actual class MediaLauncher {
    actual suspend fun openSpotifyUri(spotifyUri: String): LaunchResult =
        suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                val app = UIApplication.sharedApplication
                val nsUrl = NSURL.URLWithString(spotifyUri)
                if (nsUrl != null && app.canOpenURL(nsUrl)) {
                    app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
                    if (continuation.isActive) continuation.resume(LaunchResult.Success)
                } else {
                    val webFallback = NSURL.URLWithString("https://open.spotify.com")
                    if (webFallback != null) {
                        app.openURL(webFallback, options = emptyMap<Any?, Any>(), completionHandler = null)
                        if (continuation.isActive) continuation.resume(LaunchResult.Success)
                    } else if (continuation.isActive) {
                        continuation.resume(LaunchResult.Failure("Spotify is not installed on this iPhone"))
                    }
                }
            }
        }

    actual suspend fun searchSpotifyFallback(query: String): LaunchResult {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            openSpotifyUri("spotify://")
        } else {
            val encoded = trimmed.encodeUrlComponent()
            openSpotifyUri("spotify:search:$encoded")
        }
    }

    actual suspend fun controlPlayback(action: MediaControlAction): LaunchResult =
        suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                val player = MPMusicPlayerController.systemMusicPlayer
                when (action) {
                    MediaControlAction.PLAY_PAUSE -> {
                        if (player.playbackState == MPMusicPlaybackState.MPMusicPlaybackStatePlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    }
                    MediaControlAction.NEXT -> player.skipToNextItem()
                    MediaControlAction.PREVIOUS -> player.skipToPreviousItem()
                    MediaControlAction.STOP -> player.pause()
                }
                if (continuation.isActive) {
                    continuation.resume(LaunchResult.Success)
                }
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
actual class RadioPlayer {
    private var player: AVPlayer? = null
    private var stationName: String? = null
    private var playingState = false
    private var userVolume: Float = 0.65f
    private var isDucked: Boolean = false

    actual suspend fun playStream(url: String, stationName: String): LaunchResult =
        suspendCancellableCoroutine { continuation ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                if (continuation.isActive) {
                    continuation.resume(LaunchResult.Failure("Invalid radio stream URL"))
                }
                return@suspendCancellableCoroutine
            }

            dispatch_async(dispatch_get_main_queue()) {
                val session = AVAudioSession.sharedInstance()
                if (session.category != AVAudioSessionCategoryPlayAndRecord) {
                    runCatching {
                        session.setCategory(
                            category = AVAudioSessionCategoryPlayback,
                            error = null
                        )
                        session.setActive(true, error = null)
                    }
                }

                val item = AVPlayerItem.playerItemWithURL(nsUrl)
                val avPlayer = player ?: AVPlayer().also { player = it }
                avPlayer.replaceCurrentItemWithPlayerItem(item)
                this@RadioPlayer.stationName = stationName
                playingState = true
                applyEffectiveVolume(avPlayer)
                avPlayer.play()

                if (continuation.isActive) {
                    continuation.resume(LaunchResult.Success)
                }
            }
        }

    actual fun pause() {
        playingState = false
        dispatch_async(dispatch_get_main_queue()) {
            player?.pause()
        }
    }

    actual fun resume(): Boolean {
        val current = player ?: return false
        if (stationName == null) return false
        playingState = true
        dispatch_async(dispatch_get_main_queue()) {
            applyEffectiveVolume(current)
            current.play()
        }
        return true
    }

    actual fun stop() {
        playingState = false
        stationName = null
        dispatch_async(dispatch_get_main_queue()) {
            player?.pause()
            player?.replaceCurrentItemWithPlayerItem(null)
        }
    }

    actual fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0.05f, 1.0f)
        dispatch_async(dispatch_get_main_queue()) {
            player?.let { applyEffectiveVolume(it) }
        }
    }

    actual fun setDucked(ducked: Boolean) {
        if (isDucked == ducked) return
        isDucked = ducked
        dispatch_async(dispatch_get_main_queue()) {
            player?.let { applyEffectiveVolume(it) }
        }
    }

    actual fun isPlaying(): Boolean = playingState

    actual fun currentStationName(): String? = stationName

    private fun applyEffectiveVolume(avPlayer: AVPlayer) {
        avPlayer.volume = if (isDucked) (userVolume * 0.25f) else userVolume
    }
}

@OptIn(ExperimentalForeignApi::class)
actual class LocationProvider {
    private val locationManager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyBestForNavigation
        startUpdatingLocation()
    }

    actual suspend fun getCurrentLocation(): GeoCoordinate? {
        locationManager.startUpdatingLocation()
        val loc = locationManager.location ?: return null
        var lat = 0.0
        var lon = 0.0
        loc.coordinate.useContents {
            lat = latitude
            lon = longitude
        }
        val speedKmh = if (loc.speed >= 0.0) (loc.speed * 3.6).toFloat() else null
        val bearing = if (loc.course >= 0.0) loc.course.toFloat() else null
        return GeoCoordinate(
            latitude = lat,
            longitude = lon,
            speedKmh = speedKmh,
            bearingDegrees = bearing
        )
    }
}

actual class DisplayController {
    actual fun setKeepScreenOn(enabled: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.idleTimerDisabled = enabled
        }
    }
}

actual class DriveStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun getString(key: String): String? = defaults.stringForKey(key)

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

actual class LocalVoiceSpeaker {
    private val synthesizer = AVSpeechSynthesizer()

    actual fun speak(text: String) {
        if (text.isBlank()) return
        dispatch_async(dispatch_get_main_queue()) {
            if (synthesizer.isSpeaking()) {
                synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            }
            val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
            synthesizer.speakUtterance(utterance)
        }
    }

    actual fun stop() {
        dispatch_async(dispatch_get_main_queue()) {
            if (synthesizer.isSpeaking()) {
                synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            }
        }
    }

    actual fun shutdown() {
        stop()
    }
}

actual class CalendarProvider {
    private val eventStore = EKEventStore()

    actual suspend fun getUpcomingEvents(hoursAhead: Int): List<CalendarEventItem> {
        val granted = ensureCalendarAccess()
        if (!granted) return emptyList()

        val startDate = NSDate()
        val endDate = NSDate.dateWithTimeIntervalSinceNow(hoursAhead.coerceAtLeast(1) * 3600.0)
        val predicate = eventStore.predicateForEventsWithStartDate(
            startDate = startDate,
            endDate = endDate,
            calendars = null
        )
        val formatter = NSDateFormatter().apply {
            dateFormat = "h:mm a"
        }

        val events = eventStore.eventsMatchingPredicate(predicate)
        return events.mapNotNull { item ->
            val ev = item as? EKEvent ?: return@mapNotNull null
            val title = ev.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = ev.startDate ?: startDate
            CalendarEventItem(
                title = title,
                startTimeLabel = formatter.stringFromDate(start),
                location = ev.location?.takeIf { it.isNotBlank() }
            )
        }.take(10)
    }

    private suspend fun ensureCalendarAccess(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
            if (status == EKAuthorizationStatusFullAccess || status == EKAuthorizationStatusAuthorized) {
                if (continuation.isActive) continuation.resume(true)
                return@suspendCancellableCoroutine
            }

            eventStore.requestFullAccessToEventsWithCompletion { granted, _ ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }
}

private fun String.encodeUrlComponent(): String {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val nsString = this as platform.Foundation.NSString
    return nsString.stringByAddingPercentEncodingWithAllowedCharacters(
        NSCharacterSet.URLQueryAllowedCharacterSet
    ) ?: this
}
