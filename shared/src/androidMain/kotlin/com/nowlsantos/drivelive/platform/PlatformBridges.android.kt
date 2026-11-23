package com.nowlsantos.drivelive.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.LocationManager
import android.provider.CalendarContract
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.scale

actual class AudioEngine actual constructor() {
    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null
    private var activeBluetoothDevice: AudioDeviceInfo? = null

    private val inputSampleRate = 16000
    private val outputSampleRate = 24000

    constructor(context: Context) : this() {
        attachContext(context)
    }

    fun attachContext(context: Context) {
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @SuppressLint("MissingPermission")
    actual fun startCapture(): Flow<Pair<ByteArray, Float>> = flow {
        enableBluetoothCarAudioRouting()

        val minBuffer = AudioRecord.getMinBufferSize(
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        audioRecord = recorder

        activeBluetoothDevice?.let { btDevice ->
            recorder.preferredDevice = btDevice
        }

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                enabled = true
            }
        }

        val buffer = ByteArray(1280)
        recorder.startRecording()

        try {
            while (currentCoroutineContext().isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readBytes = recorder.read(buffer, 0, buffer.size)
                if (readBytes > 0) {
                    val chunk = buffer.copyOf(readBytes)
                    emit(chunk to computeRms(chunk))
                }
            }
        } finally {
            stopCaptureInternal()
        }
    }.flowOn(Dispatchers.IO)

    actual suspend fun playPcm24k(pcmData: ByteArray): Unit = withContext(Dispatchers.IO) {
        val track = ensureAudioTrack()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        track.write(pcmData, 0, pcmData.size)
    }

    actual fun flushPlayback() {
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    actual fun stopAll() {
        stopCaptureInternal()
        audioTrack?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
        audioTrack = null
        disableBluetoothCarAudioRouting()
    }

    @Suppress("DEPRECATION")
    private fun enableBluetoothCarAudioRouting() {
        val manager = audioManager ?: return
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bluetoothDevice = manager.availableCommunicationDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bluetoothDevice != null) {
                    val routed = manager.setCommunicationDevice(bluetoothDevice)
                    if (routed) {
                        activeBluetoothDevice = bluetoothDevice
                    }
                }
            } else {
                val hasScoDevice = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (hasScoDevice && manager.isBluetoothScoAvailableOffCall) {
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun disableBluetoothCarAudioRouting() {
        val manager = audioManager ?: return
        try {
            activeBluetoothDevice = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else if (manager.isBluetoothScoOn) {
                manager.isBluetoothScoOn = false
                manager.stopBluetoothSco()
            }
            manager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun ensureAudioTrack(): AudioTrack {
        audioTrack?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                activeBluetoothDevice?.let { btDevice ->
                    track.preferredDevice = btDevice
                }
                audioTrack = track
            }
    }

    private fun stopCaptureInternal() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        audioRecord?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null
    }

    private fun computeRms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        val count = pcm.size / 2
        for (i in 0 until count) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val norm = sample / 32768.0
            sum += norm * norm
        }
        return sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }
}

actual class CameraFrameStreamer(private val context: Context) {
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    private var activeProvider: ProcessCameraProvider? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun attachLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwnerRef = WeakReference(owner)
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        surfaceProvider = provider
    }

    actual fun streamFrames(durationSeconds: Int): Flow<ByteArray> = callbackFlow {
        val owner = lifecycleOwnerRef?.get()
        if (owner == null) {
            close(IllegalStateException("LifecycleOwner not attached to CameraFrameStreamer"))
            return@callbackFlow
        }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var lastEmittedTimestampMs = 0L

        val timeoutJob = launch {
            delay((durationSeconds * 1000L).milliseconds)
            close()
        }

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                activeProvider = cameraProvider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(768, 768),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmittedTimestampMs >= 1000L) {
                            lastEmittedTimestampMs = now
                            val jpegBytes = imageProxy.toScaledJpeg(maxDimension = 768, quality = 80)
                            if (jpegBytes != null) {
                                trySend(jpegBytes)
                            }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                val previewUseCase = surfaceProvider?.let { provider ->
                    Preview.Builder().build().apply {
                        setSurfaceProvider(provider)
                    }
                }

                cameraProvider.unbindAll()
                if (previewUseCase != null) {
                    cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        imageAnalysis
                    )
                } else {
                    cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageAnalysis
                    )
                }
            } catch (e: Exception) {
                close(e)
            }
        }, mainExecutor)

        awaitClose {
            timeoutJob.cancel()
            mainExecutor.execute {
                try {
                    activeProvider?.unbindAll()
                } catch (_: Exception) {
                }
            }
        }
    }

    actual fun stop() {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            try {
                activeProvider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    private fun ImageProxy.toScaledJpeg(maxDimension: Int, quality: Int): ByteArray? {
        val rawBitmap = toBitmap()
        val rotationDegrees = imageInfo.rotationDegrees

        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                .also { if (it !== rawBitmap) rawBitmap.recycle() }
        } else {
            rawBitmap
        }

        val longestEdge = max(rotatedBitmap.width, rotatedBitmap.height)
        val finalBitmap = if (longestEdge > maxDimension) {
            val scale = maxDimension.toFloat() / longestEdge.toFloat()
            val targetW = (rotatedBitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (rotatedBitmap.height * scale).toInt().coerceAtLeast(1)
            rotatedBitmap.scale(targetW, targetH)
                .also { if (it !== rotatedBitmap) rotatedBitmap.recycle() }
        } else {
            rotatedBitmap
        }

        return ByteArrayOutputStream().use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            finalBitmap.recycle()
            out.toByteArray()
        }
    }
}

actual class NavigationLauncher(private val context: Context) {
    actual suspend fun navigateTo(
        destination: String,
        coordinate: GeoCoordinate?,
        preferredApp: NavigationApp
    ): LaunchResult {
        val encodedDest = Uri.encode(destination)
        val uri = when (preferredApp) {
            NavigationApp.WAZE -> Uri.parse("https://waze.com/ul?q=$encodedDest&navigate=yes")
            else -> {
                if (coordinate != null && destination.isBlank()) {
                    Uri.parse("google.navigation:q=${coordinate.latitude},${coordinate.longitude}")
                } else {
                    Uri.parse("google.navigation:q=$encodedDest")
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (preferredApp == NavigationApp.GOOGLE_MAPS) {
                setPackage("com.google.android.apps.maps")
            }
        }

        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedDest")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                LaunchResult.Success
            } catch (fallbackEx: Exception) {
                LaunchResult.Failure(fallbackEx.message ?: "No navigation app found")
            }
        }
    }
}

actual class MediaLauncher(private val context: Context) {
    actual suspend fun openSpotifyUri(spotifyUri: String): LaunchResult {
        return launchUri(spotifyUri)
    }

    actual suspend fun searchSpotifyFallback(query: String): LaunchResult {
        val encoded = Uri.encode(query)
        return launchUri("spotify:search:$encoded")
    }

    actual suspend fun controlPlayback(action: MediaControlAction): LaunchResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return LaunchResult.Failure("AudioManager unavailable")
        val keyCode = when (action) {
            MediaControlAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaControlAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaControlAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaControlAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        return try {
            val eventTime = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Failure(e.message ?: "Failed to dispatch media control")
        }
    }

    private fun launchUri(uriString: String): LaunchResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Failure(e.message ?: "Spotify is not installed")
        }
    }
}

actual class RadioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var stationName: String? = null
    private var playingState = false
    private var userVolume: Float = 0.65f
    private var isDucked: Boolean = false

    actual suspend fun playStream(url: String, stationName: String): LaunchResult =
        withContext(Dispatchers.Main) {
            stopInternal()
            val result = withTimeoutOrNull(8000.milliseconds) {
                suspendCancellableCoroutine<LaunchResult> { continuation ->
                    val player = MediaPlayer()
                    try {
                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        player.setDataSource(url)
                        player.setOnPreparedListener { mp ->
                            this@RadioPlayer.mediaPlayer = mp
                            this@RadioPlayer.stationName = stationName
                            this@RadioPlayer.playingState = true
                            applyEffectiveVolume(mp)
                            mp.start()
                            if (continuation.isActive) {
                                continuation.resume(LaunchResult.Success)
                            }
                        }
                        player.setOnErrorListener { mp, what, extra ->
                            runCatching { mp.release() }
                            if (this@RadioPlayer.mediaPlayer === mp) {
                                this@RadioPlayer.mediaPlayer = null
                                this@RadioPlayer.playingState = false
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    LaunchResult.Failure("Stream error ($what/$extra)")
                                )
                            }
                            true
                        }
                        continuation.invokeOnCancellation {
                            runCatching { player.release() }
                        }
                        player.prepareAsync()
                    } catch (e: Exception) {
                        runCatching { player.release() }
                        if (continuation.isActive) {
                            continuation.resume(
                                LaunchResult.Failure(e.message ?: "Failed to load radio stream")
                            )
                        }
                    }
                }
            }
            result ?: run {
                stopInternal()
                LaunchResult.Failure("Timed out connecting to $stationName")
            }
        }

    actual fun pause() {
        playingState = false
        runCatching {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        }
    }

    actual fun resume(): Boolean {
        val mp = mediaPlayer ?: return false
        if (stationName == null) return false
        return runCatching {
            applyEffectiveVolume(mp)
            mp.start()
            playingState = true
            true
        }.getOrDefault(false)
    }

    actual fun stop() {
        stopInternal()
    }

    actual fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0.05f, 1.0f)
        mediaPlayer?.let { applyEffectiveVolume(it) }
    }

    actual fun setDucked(ducked: Boolean) {
        if (isDucked == ducked) return
        isDucked = ducked
        mediaPlayer?.let { applyEffectiveVolume(it) }
    }

    actual fun isPlaying(): Boolean = playingState && (runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false))

    actual fun currentStationName(): String? = stationName

    private fun applyEffectiveVolume(mp: MediaPlayer) {
        val effective = if (isDucked) (userVolume * 0.25f) else userVolume
        runCatching { mp.setVolume(effective, effective) }
    }

    private fun stopInternal() {
        playingState = false
        stationName = null
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
    }
}

actual class LocationProvider(private val context: Context) {
    @SuppressLint("MissingPermission")
    actual suspend fun getCurrentLocation(): GeoCoordinate? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
            if (loc != null) {
                return GeoCoordinate(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null,
                    bearingDegrees = if (loc.hasBearing()) loc.bearing else null
                )
            }
        }
        return null
    }
}

actual class DisplayController(activity: Activity? = null) {
    private var activityRef: WeakReference<Activity>? = activity?.let { WeakReference(it) }

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    actual fun setKeepScreenOn(enabled: Boolean) {
        val window = activityRef?.get()?.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

actual class DriveStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drivelive_kai_prefs", Context.MODE_PRIVATE)

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual class LocalVoiceSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var isReady = false
    private var pendingUtterance: String? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
                pendingUtterance?.let { queued ->
                    pendingUtterance = null
                    tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "kai_local_tts")
                }
            }
        }
    }

    actual fun speak(text: String) {
        if (text.isBlank()) return
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kai_local_tts")
        } else {
            pendingUtterance = text
        }
    }

    actual fun stop() {
        pendingUtterance = null
        tts?.stop()
    }

    actual fun shutdown() {
        pendingUtterance = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}

actual class CalendarProvider(private val context: Context) {
    actual suspend fun getUpcomingEvents(hoursAhead: Int): List<CalendarEventItem> =
        withContext(Dispatchers.IO) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return@withContext emptyList()

            val startMillis = System.currentTimeMillis()
            val endMillis = startMillis + (hoursAhead.coerceAtLeast(1) * 3_600_000L)
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.EVENT_LOCATION
            )

            val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
            val results = mutableListOf<CalendarEventItem>()

            try {
                context.contentResolver.query(
                    builder.build(),
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                    while (cursor.moveToNext() && results.size < 10) {
                        val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
                        if (title.isBlank()) continue
                        val beginMs = if (beginIdx >= 0) cursor.getLong(beginIdx) else startMillis
                        val location = if (locIdx >= 0) cursor.getString(locIdx)?.takeIf { it.isNotBlank() } else null
                        results.add(
                            CalendarEventItem(
                                title = title,
                                startTimeLabel = formatter.format(Date(beginMs)),
                                location = location
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            results
        }
}