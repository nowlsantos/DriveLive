package com.nowlsantos.drivelive.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClientSetupMessage(
    val setup: BidiGenerateContentSetup
)

@Serializable
data class BidiGenerateContentSetup(
    val model: String = "models/gemini-2.5-flash-native-audio-preview-12-2025",
    val generationConfig: LiveGenerationConfig = LiveGenerationConfig(),
    val systemInstruction: ContentPayload,
    val tools: List<ToolDeclaration> = emptyList(),
    val inputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig(),
    val outputAudioTranscription: AudioTranscriptionConfig? = AudioTranscriptionConfig(),
    val contextWindowCompression: ContextWindowCompressionConfig? = ContextWindowCompressionConfig(),
    val sessionResumption: SessionResumptionConfig? = SessionResumptionConfig()
)

@Serializable
data class LiveGenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig = SpeechConfig()
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig = VoiceConfig()
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Charon")
)

@Serializable
data class PrebuiltVoiceConfig(val voiceName: String)

@Serializable
data class ContentPayload(
    val role: String? = "user",
    val parts: List<ContentPart>
)

@Serializable
data class ContentPart(
    val text: String? = null,
    val inlineData: BlobData? = null
)

@Serializable
data class BlobData(val mimeType: String, val data: String)

@Serializable
class AudioTranscriptionConfig

@Serializable
data class ContextWindowCompressionConfig(
    val slidingWindow: SlidingWindowConfig = SlidingWindowConfig()
)

@Serializable
data class SlidingWindowConfig(val targetTokens: Int = 16000)

@Serializable
data class SessionResumptionConfig(val handle: String? = null)

@Serializable
data class ToolDeclaration(val functionDeclarations: List<FunctionDeclaration>)

@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val behavior: String = "NON_BLOCKING",
    val parameters: SchemaObject
)

@Serializable
data class SchemaObject(
    val type: String = "OBJECT",
    val properties: Map<String, SchemaProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class SchemaProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

@Serializable
data class ClientRealtimeInputMessage(val realtimeInput: RealtimeInputPayload)

@Serializable
data class RealtimeInputPayload(
    val audio: BlobData? = null,
    val video: BlobData? = null,
    val text: String? = null
)

@Serializable
data class ClientToolResponseMessage(val toolResponse: ToolResponsePayload)

@Serializable
data class ToolResponsePayload(val functionResponses: List<FunctionResponseItem>)

@Serializable
data class FunctionResponseItem(
    val id: String,
    val name: String,
    val response: JsonObject
)

@Serializable
data class ServerMessage(
    val setupComplete: JsonElement? = null,
    val serverContent: ServerContentPayload? = null,
    val toolCall: ServerToolCallPayload? = null,
    val toolCallCancellation: ToolCallCancellationPayload? = null,
    val sessionResumptionUpdate: SessionResumptionUpdatePayload? = null,
    val goAway: GoAwayPayload? = null
)

@Serializable
data class ServerContentPayload(
    val modelTurn: ContentPayload? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val inputTranscription: TranscriptionText? = null,
    val outputTranscription: TranscriptionText? = null
)

@Serializable
data class TranscriptionText(val text: String? = null)

@Serializable
data class ServerToolCallPayload(val functionCalls: List<FunctionCallItem> = emptyList())

@Serializable
data class FunctionCallItem(
    val id: String,
    val name: String,
    val args: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ToolCallCancellationPayload(val ids: List<String> = emptyList())

@Serializable
data class SessionResumptionUpdatePayload(
    val newHandle: String? = null,
    val resumable: Boolean? = null
)

@Serializable
data class GoAwayPayload(val timeLeft: String? = null)