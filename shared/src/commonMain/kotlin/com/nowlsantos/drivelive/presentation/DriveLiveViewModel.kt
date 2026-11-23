package com.nowlsantos.drivelive.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nowlsantos.drivelive.agent.ToolCallDispatcher
import com.nowlsantos.drivelive.network.MichaelLiveClient
import com.nowlsantos.drivelive.platform.AudioEngine
import com.nowlsantos.drivelive.platform.CameraFrameStreamer
import com.nowlsantos.drivelive.platform.DisplayController
import com.nowlsantos.drivelive.platform.LocationProvider
import com.nowlsantos.drivelive.platform.MediaLauncher
import com.nowlsantos.drivelive.platform.NavigationLauncher
import com.nowlsantos.drivelive.state.MichaelEvent
import com.nowlsantos.drivelive.state.MichaelSessionState
import com.nowlsantos.drivelive.state.MichaelStateMachine
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class DriveLiveViewModel(
    private val stateMachine: MichaelStateMachine,
    private val liveClient: MichaelLiveClient,
    private val audioEngine: AudioEngine,
    private val cameraStreamer: CameraFrameStreamer,
    private val displayController: DisplayController,
    navigationLauncher: NavigationLauncher,
    mediaLauncher: MediaLauncher,
    locationProvider: LocationProvider
) : ViewModel() {

    val sessionState: StateFlow<MichaelSessionState> = stateMachine.state

    private var eventCollectorJob: Job? = null
    private var micStreamJob: Job? = null
    private var cameraBurstJob: Job? = null
    private var reconnectJob: Job? = null
    private var audioPlaybackJob: Job? = null
    private val audioPlaybackChannel = Channel<ByteArray>(capacity = 128)

    private val toolDispatcher = ToolCallDispatcher(
        navigationLauncher = navigationLauncher,
        mediaLauncher = mediaLauncher,
        locationProvider = locationProvider,
        onRequestCameraBurst = { reason, durationSeconds ->
            startCameraBurst(reason, durationSeconds)
        }
    )

    init {
        observeLiveEvents()
        startAudioPlaybackWorker()
    }

    private fun startAudioPlaybackWorker() {
        audioPlaybackJob?.cancel()
        audioPlaybackJob = viewModelScope.launch {
            for (chunk in audioPlaybackChannel) {
                audioEngine.playPcm24k(chunk)
            }
        }
    }

    fun toggleSession() {
        when (sessionState.value) {
            is MichaelSessionState.Idle,
            is MichaelSessionState.Error -> startDriveSession()
            else -> endDriveSession()
        }
    }

    private fun startDriveSession() {
        reconnectJob?.cancel()
        reconnectJob = null
        displayController.setKeepScreenOn(true)
        stateMachine.onEvent(MichaelEvent.StartSession)
        viewModelScope.launch {
            liveClient.connect(scope = viewModelScope)
        }
    }

    private fun endDriveSession() {
        reconnectJob?.cancel()
        reconnectJob = null

        micStreamJob?.cancel()
        micStreamJob = null

        cameraBurstJob?.cancel()
        cameraBurstJob = null

        while (audioPlaybackChannel.tryReceive().isSuccess) { }
        audioEngine.stopAll()
        displayController.setKeepScreenOn(false)

        viewModelScope.launch {
            liveClient.disconnect()
        }
        stateMachine.onEvent(MichaelEvent.StopSession)
    }

    fun triggerManualCameraInspect() {
        if (sessionState.value is MichaelSessionState.Idle) return
        startCameraBurst(reason = "Driver requested visual scan", durationSeconds = 8)
    }

    fun cancelCameraBurst() {
        cameraBurstJob?.cancel()
        cameraBurstJob = null
        cameraStreamer.stop()
        stateMachine.onEvent(MichaelEvent.CameraBurstFinished)
    }

    private fun observeLiveEvents() {
        eventCollectorJob?.cancel()
        eventCollectorJob = viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is MichaelEvent.HandshakeComplete -> {
                        stateMachine.onEvent(event)
                        startMicrophoneStream()
                    }

                    is MichaelEvent.ServerAudioChunk -> {
                        stateMachine.onEvent(event)
                        if (event.pcm24k.isNotEmpty()) {
                            audioPlaybackChannel.trySend(event.pcm24k)
                        }
                    }

                    is MichaelEvent.BargeInDetected -> {
                        // Drain any queued playback chunks immediately on driver interruption
                        while (audioPlaybackChannel.tryReceive().isSuccess) { }
                        audioEngine.flushPlayback()
                        stateMachine.onEvent(event)
                    }

                    is MichaelEvent.ToolCallReceived -> {
                        stateMachine.onEvent(event)
                        this@launch.launch {
                            val resultJson = toolDispatcher.dispatch(event.toolName, event.argsJson)
                            liveClient.sendToolResponse(event.callId, event.toolName, resultJson)
                        }
                    }

                    is MichaelEvent.TriggerCameraBurst -> {
                        startCameraBurst(event.reason, event.durationSeconds)
                    }

                    is MichaelEvent.ConnectionDropped -> {
                        micStreamJob?.cancel()
                        micStreamJob = null
                        reconnectWithBackoff(event.resumptionHandle)
                    }

                    is MichaelEvent.SessionFailed -> {
                        micStreamJob?.cancel()
                        micStreamJob = null
                        if (event.recoverable) {
                            reconnectWithBackoff(null)
                        } else {
                            stateMachine.onEvent(event)
                        }
                    }

                    else -> stateMachine.onEvent(event)
                }
            }
        }
    }

    private fun startMicrophoneStream() {
        micStreamJob?.cancel()
        micStreamJob = viewModelScope.launch {
            audioEngine.startCapture().collect { (pcmChunk, amplitude) ->
                stateMachine.onEvent(MichaelEvent.MicAudioFrame(pcmChunk, amplitude))
                liveClient.sendPcm16kAudio(pcmChunk)
            }
        }
    }

    private fun startCameraBurst(reason: String, durationSeconds: Int) {
        cameraBurstJob?.cancel()
        stateMachine.onEvent(MichaelEvent.TriggerCameraBurst(reason, durationSeconds))

        cameraBurstJob = viewModelScope.launch {
            val countdownJob = launch {
                for (remaining in (durationSeconds - 1) downTo 1) {
                    delay(1000L.milliseconds)
                    stateMachine.onEvent(MichaelEvent.CameraBurstTick(remaining))
                }
            }
            try {
                cameraStreamer.streamFrames(durationSeconds).collect { jpegBytes ->
                    liveClient.sendVideoJpegFrame(jpegBytes)
                }
            } finally {
                countdownJob.cancel()
                cameraStreamer.stop()
                stateMachine.onEvent(MichaelEvent.CameraBurstFinished)
            }
        }
    }

    private fun reconnectWithBackoff(resumptionHandle: String?) {
        stateMachine.onEvent(MichaelEvent.ConnectionDropped(resumptionHandle))
        val currentAttempt = (sessionState.value as? MichaelSessionState.Reconnecting)?.attempt ?: 1

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            if (currentAttempt > 5) {
                stateMachine.onEvent(
                    MichaelEvent.SessionFailed(
                        error = "Voice link disconnected. Please tap Engage KAI to retry.",
                        recoverable = false
                    )
                )
                return@launch
            }
            delay((1500L * currentAttempt).milliseconds)
            if (sessionState.value is MichaelSessionState.Reconnecting) {
                liveClient.connect(viewModelScope, resumptionHandle)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlaybackChannel.close()
        endDriveSession()
    }
}
