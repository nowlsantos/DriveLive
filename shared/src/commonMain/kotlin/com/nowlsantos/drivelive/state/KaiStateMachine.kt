package com.nowlsantos.drivelive.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class KaiStateMachine {

    private val _state = MutableStateFlow<KaiSessionState>(KaiSessionState.Idle)
    val state: StateFlow<KaiSessionState> = _state.asStateFlow()

    fun onEvent(event: KaiEvent) {
        _state.update { current -> reduce(current, event) }
    }

    private fun reduce(
        current: KaiSessionState,
        event: KaiEvent
    ): KaiSessionState = when (event) {
        is KaiEvent.StartSession -> KaiSessionState.Connecting

        is KaiEvent.StopSession -> KaiSessionState.Idle

        is KaiEvent.HandshakeComplete -> KaiSessionState.Listening()

        is KaiEvent.MicAudioFrame -> when (current) {
            is KaiSessionState.Listening -> current.copy(micAmplitude = event.amplitude)
            else -> current
        }

        is KaiEvent.InterimUserSpeech -> when (current) {
            is KaiSessionState.Listening -> current.copy(interimTranscript = event.text)
            else -> current
        }

        is KaiEvent.ServerAudioChunk -> {
            if (current is KaiSessionState.CameraBurst) {
                current
            } else {
                val prevTranscript = (current as? KaiSessionState.Speaking)?.outputTranscript.orEmpty()
                val updatedTranscript = event.transcript ?: prevTranscript
                KaiSessionState.Speaking(
                    voiceAmplitude = event.amplitude,
                    outputTranscript = updatedTranscript
                )
            }
        }

        is KaiEvent.BargeInDetected -> KaiSessionState.Listening()

        is KaiEvent.TurnComplete -> when (current) {
            is KaiSessionState.Speaking,
            is KaiSessionState.ExecutingTool -> KaiSessionState.Listening()
            else -> current
        }

        is KaiEvent.ToolCallReceived -> KaiSessionState.ExecutingTool(
            toolName = event.toolName,
            summary = formatToolSummary(event.toolName)
        )

        is KaiEvent.ToolCallCompleted -> when (current) {
            is KaiSessionState.ExecutingTool -> KaiSessionState.Listening()
            else -> current
        }

        is KaiEvent.TriggerCameraBurst -> KaiSessionState.CameraBurst(
            remainingSeconds = event.durationSeconds,
            reason = event.reason
        )

        is KaiEvent.CameraBurstTick -> when (current) {
            is KaiSessionState.CameraBurst -> current.copy(remainingSeconds = event.remainingSeconds)
            else -> current
        }

        is KaiEvent.CameraBurstFinished -> KaiSessionState.Listening()

        is KaiEvent.ConnectionDropped -> {
            if (current is KaiSessionState.Idle) {
                KaiSessionState.Idle
            } else {
                val nextAttempt = (current as? KaiSessionState.Reconnecting)?.attempt?.plus(1) ?: 1
                KaiSessionState.Reconnecting(
                    attempt = nextAttempt,
                    resumptionHandle = event.resumptionHandle
                )
            }
        }

        is KaiEvent.SessionFailed -> KaiSessionState.Error(
            message = event.error,
            isRecoverable = event.recoverable
        )
    }

    private fun formatToolSummary(toolName: String): String = when (toolName) {
        "navigate_to" -> "Launching navigation..."
        "play_music" -> "Opening Spotify..."
        "control_media_playback" -> "Controlling media playback..."
        "inspect_road_view" -> "Activating camera..."
        "get_trip_telemetry" -> "Reading trip telemetry..."
        "save_parking_spot" -> "Saving parking pin..."
        "find_my_car" -> "Locating parked vehicle..."
        "save_voice_note" -> "Saving voice note..."
        "recall_notes" -> "Reading saved notes..."
        "clear_notes" -> "Clearing saved notes..."
        "set_focus_mode" -> "Updating Focus Mode..."
        else -> "Executing $toolName..."
    }
}
