package com.nowlsantos.drivelive.agent

import com.nowlsantos.drivelive.network.*

object MichaelAgentConfig {

    const val MODEL_ID = "models/gemini-2.5-flash-native-audio-preview-12-2025"

    const val SYSTEM_INSTRUCTION = """
        You are KAI (Knight Automotive Intelligence), an advanced, calm, and highly capable in-car AI co-pilot inside the DriveLive system, inspired by KITT from Knight Rider.
        Speak in a composed, articulate, and subtly witty tone—concise and direct, keeping responses to 1–3 short spoken sentences so the driver stays focused on the road.
        You understand and respond naturally in English, Tagalog, or Taglish depending on how the driver addresses you.
        When asked to navigate, play music, inspect the road or dashboard via camera, or save a note, execute the corresponding tool immediately and confirm briefly.
        Never fabricate road conditions, navigation routes, or vehicle diagnostics.
    """

    fun buildSetupMessage(resumptionHandle: String? = null): ClientSetupMessage {
        return ClientSetupMessage(
            setup = BidiGenerateContentSetup(
                model = MODEL_ID,
                generationConfig = LiveGenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Charon")
                        )
                    )
                ),
                systemInstruction = ContentPayload(
                    parts = listOf(ContentPart(text = SYSTEM_INSTRUCTION.trimIndent()))
                ),
                tools = listOf(ToolDeclaration(functionDeclarations = registeredTools())),
                sessionResumption = SessionResumptionConfig(handle = resumptionHandle)
            )
        )
    }

    private fun registeredTools(): List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            name = "navigate_to",
            description = "Open Google Maps, Waze, or Apple Maps, or launch turn-by-turn navigation to a destination.",
            parameters = SchemaObject(
                properties = mapOf(
                    "destination" to SchemaProperty(
                        type = "STRING",
                        description = "Name, address, or search query for the destination (leave empty if user just asks to open the map app)."
                    ),
                    "preferred_app" to SchemaProperty(
                        type = "STRING",
                        description = "Navigation app to open.",
                        enum = listOf("GOOGLE_MAPS", "WAZE", "APPLE_MAPS", "DEFAULT")
                    )
                ),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "play_music",
            description = "Open Spotify or search and play a song, artist, album, or playlist on Spotify.",
            parameters = SchemaObject(
                properties = mapOf(
                    "query" to SchemaProperty(
                        type = "STRING",
                        description = "Song title, artist name, genre, or mood (leave empty if user just asks to open Spotify)."
                    ),
                    "media_type" to SchemaProperty(
                        type = "STRING",
                        description = "Type of Spotify content.",
                        enum = listOf("track", "artist", "album", "playlist")
                    )
                ),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "inspect_road_view",
            description = "Activate the dashboard camera for 8 seconds to inspect road signs, parking rules, traffic ahead, or dashboard warning lights.",
            parameters = SchemaObject(
                properties = mapOf(
                    "reason" to SchemaProperty(
                        type = "STRING",
                        description = "What KAI should look for in the camera feed."
                    )
                ),
                required = listOf("reason")
            )
        )
    )
}