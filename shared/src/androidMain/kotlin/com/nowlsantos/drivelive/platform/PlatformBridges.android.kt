package com.nowlsantos.drivelive.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Size
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
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
                return GeoCoordinate(loc.latitude, loc.longitude)
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