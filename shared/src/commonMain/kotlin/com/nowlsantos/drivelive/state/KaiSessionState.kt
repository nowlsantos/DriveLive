package com.nowlsantos.drivelive.state

sealed interface KaiSessionState {
    data object Idle : KaiSessionState
    data object Connecting : KaiSessionState
    data class Listening(
        val micAmplitude: Float = 0f,
        val interimTranscript: String = ""
    ) : KaiSessionState
    data class ExecutingTool(
        val toolName: String,
        val summary: String
    ) : KaiSessionState
    data class Speaking(
        val voiceAmplitude: Float = 0f,
        val outputTranscript: String = ""
    ) : KaiSessionState
    data class CameraBurst(
        val remainingSeconds: Int,
        val reason: String
    ) : KaiSessionState
    data class Reconnecting(
        val attempt: Int,
        val resumptionHandle: String?
    ) : KaiSessionState
    data class Error(
        val message: String,
        val isRecoverable: Boolean = true
    ) : KaiSessionState
}

sealed interface KaiEvent {
    data object StartSession : KaiEvent
    data object StopSession : KaiEvent
    data object HandshakeComplete : KaiEvent
    data class MicAudioFrame(val pcm16k: ByteArray, val amplitude: Float) : KaiEvent
    data class InterimUserSpeech(val text: String) : KaiEvent
    data class ServerAudioChunk(val pcm24k: ByteArray, val amplitude: Float, val transcript: String?) : KaiEvent
    data object BargeInDetected : KaiEvent
    data object TurnComplete : KaiEvent
    data class ToolCallReceived(val callId: String, val toolName: String, val argsJson: String) : KaiEvent
    data class ToolCallCompleted(val callId: String) : KaiEvent
    data class TriggerCameraBurst(val reason: String, val durationSeconds: Int = 8) : KaiEvent
    data class CameraBurstTick(val remainingSeconds: Int) : KaiEvent
    data object CameraBurstFinished : KaiEvent
    data class ConnectionDropped(val resumptionHandle: String?) : KaiEvent
    data class SessionFailed(val error: String, val recoverable: Boolean = true) : KaiEvent
}
