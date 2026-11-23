package com.nowlsantos.drivelive.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MichaelStateMachine {

    private val _state = MutableStateFlow<MichaelSessionState>(MichaelSessionState.Idle)
    val state: StateFlow<MichaelSessionState> = _state.asStateFlow()

    fun onEvent(event: MichaelEvent) {
        _state.update { current -> reduce(current, event) }
    }

    private fun reduce(
        current: MichaelSessionState,
        event: MichaelEvent
    ): MichaelSessionState = when (event) {
        is MichaelEvent.StartSession -> MichaelSessionState.Connecting

        is MichaelEvent.StopSession -> MichaelSessionState.Idle

        is MichaelEvent.HandshakeComplete -> MichaelSessionState.Listening()

        is MichaelEvent.MicAudioFrame -> when (current) {
            is MichaelSessionState.Listening -> current.copy(micAmplitude = event.amplitude)
            else -> current
        }

        is MichaelEvent.InterimUserSpeech -> when (current) {
            is MichaelSessionState.Listening -> current.copy(interimTranscript = event.text)
            else -> current
        }

        is MichaelEvent.ServerAudioChunk -> {
            if (current is MichaelSessionState.CameraBurst) {
                current
            } else {
                val prevTranscript = (current as? MichaelSessionState.Speaking)?.outputTranscript.orEmpty()
                val updatedTranscript = event.transcript ?: prevTranscript
                MichaelSessionState.Speaking(
                    voiceAmplitude = event.amplitude,
                    outputTranscript = updatedTranscript
                )
            }
        }

        is MichaelEvent.BargeInDetected -> MichaelSessionState.Listening()

        is MichaelEvent.TurnComplete -> when (current) {
            is MichaelSessionState.Speaking,
            is MichaelSessionState.ExecutingTool -> MichaelSessionState.Listening()
            else -> current
        }

        is MichaelEvent.ToolCallReceived -> MichaelSessionState.ExecutingTool(
            toolName = event.toolName,
            summary = formatToolSummary(event.toolName)
        )

        is MichaelEvent.ToolCallCompleted -> when (current) {
            is MichaelSessionState.ExecutingTool -> MichaelSessionState.Listening()
            else -> current
        }

        is MichaelEvent.TriggerCameraBurst -> MichaelSessionState.CameraBurst(
            remainingSeconds = event.durationSeconds,
            reason = event.reason
        )

        is MichaelEvent.CameraBurstTick -> when (current) {
            is MichaelSessionState.CameraBurst -> current.copy(remainingSeconds = event.remainingSeconds)
            else -> current
        }

        is MichaelEvent.CameraBurstFinished -> MichaelSessionState.Listening()

        is MichaelEvent.ConnectionDropped -> {
            if (current is MichaelSessionState.Idle) {
                MichaelSessionState.Idle
            } else {
                val nextAttempt = (current as? MichaelSessionState.Reconnecting)?.attempt?.plus(1) ?: 1
                MichaelSessionState.Reconnecting(
                    attempt = nextAttempt,
                    resumptionHandle = event.resumptionHandle
                )
            }
        }

        is MichaelEvent.SessionFailed -> MichaelSessionState.Error(
            message = event.error,
            isRecoverable = event.recoverable
        )
    }

    private fun formatToolSummary(toolName: String): String = when (toolName) {
        "navigate_to" -> "Launching navigation..."
        "play_music" -> "Opening Spotify..."
        "inspect_road_view" -> "Activating camera..."
        else -> "Executing $toolName..."
    }
}