package com.nowlsantos.drivelive.network

import com.nowlsantos.drivelive.agent.MichaelAgentConfig
import com.nowlsantos.drivelive.state.MichaelEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.sqrt

@OptIn(ExperimentalEncodingApi::class)
class MichaelLiveClient(
    private val httpClient: HttpClient,
    private val apiKey: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null
    private var latestResumptionHandle: String? = null

    private val _events = MutableSharedFlow<MichaelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MichaelEvent> = _events.asSharedFlow()

    suspend fun connect(scope: CoroutineScope, resumptionHandle: String? = latestResumptionHandle) {
        disconnect()
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty() || trimmedKey.startsWith("$(")) {
            _events.tryEmit(
                MichaelEvent.SessionFailed(
                    error = "Missing GEMINI_API_KEY. Set GEMINI_API_KEY in local.properties or Config.xcconfig.",
                    recoverable = false
                )
            )
            return
        }

        val endpoint =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$trimmedKey"

        try {
            val ws = httpClient.webSocketSession(urlString = endpoint)
            session = ws

            val setupPayload = json.encodeToString(
                MichaelAgentConfig.buildSetupMessage(resumptionHandle)
            )
            ws.send(Frame.Text(setupPayload))

            receiveJob = scope.launch {
                listenToServer(ws)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val closeReason = runCatching { session?.closeReason?.await()?.message }.getOrNull()
            val detail = closeReason?.takeIf { it.isNotBlank() } ?: e.message ?: "Failed to connect to Gemini Live"
            _events.tryEmit(
                MichaelEvent.SessionFailed(
                    error = detail,
                    recoverable = true
                )
            )
        }
    }

    private suspend fun listenToServer(ws: DefaultClientWebSocketSession) {
        var receivedAnyFrame = false
        try {
            for (frame in ws.incoming) {
                receivedAnyFrame = true
                val rawText = when (frame) {
                    is Frame.Text -> frame.readText()
                    is Frame.Binary -> frame.readBytes().decodeToString()
                    else -> continue
                }

                val message = json.decodeFromString<ServerMessage>(rawText)
                handleServerMessage(message)
            }
            val closeMsg = runCatching { ws.closeReason.await()?.message }.getOrNull()
            if (!receivedAnyFrame && !closeMsg.isNullOrBlank()) {
                _events.tryEmit(MichaelEvent.SessionFailed(error = closeMsg, recoverable = false))
            } else {
                _events.tryEmit(MichaelEvent.ConnectionDropped(latestResumptionHandle))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val closeMsg = runCatching { ws.closeReason.await()?.message }.getOrNull()
            if (!receivedAnyFrame && !closeMsg.isNullOrBlank()) {
                _events.tryEmit(MichaelEvent.SessionFailed(error = closeMsg, recoverable = false))
            } else {
                _events.tryEmit(MichaelEvent.ConnectionDropped(latestResumptionHandle))
            }
        }
    }

    private fun handleServerMessage(msg: ServerMessage) {
        if (msg.setupComplete != null) {
            _events.tryEmit(MichaelEvent.HandshakeComplete)
        }

        msg.sessionResumptionUpdate?.newHandle?.let { handle ->
            latestResumptionHandle = handle
        }

        msg.toolCall?.functionCalls?.forEach { call ->
            _events.tryEmit(
                MichaelEvent.ToolCallReceived(
                    callId = call.id,
                    toolName = call.name,
                    argsJson = call.args.toString()
                )
            )
        }

        msg.serverContent?.let { content ->
            if (content.interrupted == true) {
                _events.tryEmit(MichaelEvent.BargeInDetected)
            }

            content.inputTranscription?.text?.let { userText ->
                if (userText.isNotBlank()) {
                    _events.tryEmit(MichaelEvent.InterimUserSpeech(userText))
                }
            }

            val outputTranscript = content.outputTranscription?.text
            var emittedAudioChunk = false
            content.modelTurn?.parts?.forEach { part ->
                part.inlineData?.let { blob ->
                    if (blob.mimeType.startsWith("audio/pcm")) {
                        val pcmBytes = Base64.decode(blob.data)
                        val amplitude = calculateRmsAmplitude(pcmBytes)
                        emittedAudioChunk = true
                        _events.tryEmit(
                            MichaelEvent.ServerAudioChunk(
                                pcm24k = pcmBytes,
                                amplitude = amplitude,
                                transcript = outputTranscript
                            )
                        )
                    }
                }
            }

            if (!emittedAudioChunk && !outputTranscript.isNullOrBlank()) {
                _events.tryEmit(
                    MichaelEvent.ServerAudioChunk(
                        pcm24k = ByteArray(0),
                        amplitude = 0f,
                        transcript = outputTranscript
                    )
                )
            }

            if (content.turnComplete == true) {
                _events.tryEmit(MichaelEvent.TurnComplete)
            }
        }
    }

    suspend fun sendPcm16kAudio(pcmBytes: ByteArray) {
        val ws = session ?: return
        if (!ws.isActive) return

        val payload = ClientRealtimeInputMessage(
            realtimeInput = RealtimeInputPayload(
                audio = BlobData(
                    mimeType = "audio/pcm;rate=16000",
                    data = Base64.encode(pcmBytes)
                )
            )
        )
        ws.send(Frame.Text(json.encodeToString(payload)))
    }

    suspend fun sendVideoJpegFrame(jpegBytes: ByteArray) {
        val ws = session ?: return
        if (!ws.isActive) return

        val payload = ClientRealtimeInputMessage(
            realtimeInput = RealtimeInputPayload(
                video = BlobData(
                    mimeType = "image/jpeg",
                    data = Base64.encode(jpegBytes)
                )
            )
        )
        ws.send(Frame.Text(json.encodeToString(payload)))
    }

    suspend fun sendClientTurn(text: String) {
        val ws = session ?: return
        if (!ws.isActive) return

        val payload = ClientContentMessage(
            clientContent = ClientContentPayload(
                turns = listOf(
                    ContentPayload(
                        role = "user",
                        parts = listOf(ContentPart(text = text))
                    )
                ),
                turnComplete = true
            )
        )
        ws.send(Frame.Text(json.encodeToString(payload)))
    }

    suspend fun sendToolResponse(callId: String, toolName: String, result: JsonObject) {
        val ws = session ?: return
        if (!ws.isActive) return

        val payload = ClientToolResponseMessage(
            toolResponse = ToolResponsePayload(
                functionResponses = listOf(
                    FunctionResponseItem(
                        id = callId,
                        name = toolName,
                        response = result
                    )
                )
            )
        )
        ws.send(Frame.Text(json.encodeToString(payload)))
        _events.tryEmit(MichaelEvent.ToolCallCompleted(callId))
    }

    suspend fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }

    private fun calculateRmsAmplitude(pcm16BitLittleEndian: ByteArray): Float {
        if (pcm16BitLittleEndian.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = pcm16BitLittleEndian.size / 2
        for (i in 0 until sampleCount) {
            val low = pcm16BitLittleEndian[i * 2].toInt() and 0xFF
            val high = pcm16BitLittleEndian[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / sampleCount).toFloat().coerceIn(0f, 1f)
    }
}