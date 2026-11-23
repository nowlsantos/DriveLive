package com.nowlsantos.drivelive.state

sealed interface MichaelSessionState {
    data object Idle : MichaelSessionState
    data object Connecting : MichaelSessionState
    data class Listening(
        val micAmplitude: Float = 0f,
        val interimTranscript: String = ""
    ) : MichaelSessionState
    data class ExecutingTool(
        val toolName: String,
        val summary: String
    ) : MichaelSessionState
    data class Speaking(
        val voiceAmplitude: Float = 0f,
        val outputTranscript: String = ""
    ) : MichaelSessionState
    data class CameraBurst(
        val remainingSeconds: Int,
        val reason: String
    ) : MichaelSessionState
    data class Reconnecting(
        val attempt: Int,
        val resumptionHandle: String?
    ) : MichaelSessionState
    data class Error(
        val message: String,
        val isRecoverable: Boolean = true
    ) : MichaelSessionState
}

sealed interface MichaelEvent {
    data object StartSession : MichaelEvent
    data object StopSession : MichaelEvent
    data object HandshakeComplete : MichaelEvent
    data class MicAudioFrame(val pcm16k: ByteArray, val amplitude: Float) : MichaelEvent
    data class InterimUserSpeech(val text: String) : MichaelEvent
    data class ServerAudioChunk(val pcm24k: ByteArray, val amplitude: Float, val transcript: String?) : MichaelEvent
    data object BargeInDetected : MichaelEvent
    data object TurnComplete : MichaelEvent
    data class ToolCallReceived(val callId: String, val toolName: String, val argsJson: String) : MichaelEvent
    data class ToolCallCompleted(val callId: String) : MichaelEvent
    data class TriggerCameraBurst(val reason: String, val durationSeconds: Int = 8) : MichaelEvent
    data class CameraBurstTick(val remainingSeconds: Int) : MichaelEvent
    data object CameraBurstFinished : MichaelEvent
    data class ConnectionDropped(val resumptionHandle: String?) : MichaelEvent
    data class SessionFailed(val error: String, val recoverable: Boolean = true) : MichaelEvent
}