package com.nowlsantos.drivelive.agent

import com.nowlsantos.drivelive.network.*
import kotlinx.serialization.json.JsonObject

object KaiAgentConfig {

    const val MODEL_ID = "models/gemini-2.5-flash-native-audio-preview-12-2025"

    const val SYSTEM_INSTRUCTION = """
        You are KAI (Knight Automotive Intelligence), an advanced, calm, and highly capable in-car AI co-pilot inside the DriveLive system, inspired by KITT from Knight Rider.
        Speak in a composed, articulate, and subtly witty tone—concise and direct, keeping responses to 1–3 short spoken sentences so the driver stays focused on the road.
        If Focus Mode is enabled, drop all banter and limit every response to a single ultra-short confirmation sentence.
        You understand and respond naturally in English, Tagalog, or Taglish depending on how the driver addresses you.
        When asked to navigate, control music or Spotify, inspect the road or dashboard via camera, check trip telemetry, check upcoming calendar events, log fuel/toll expenses, save or find a parking spot, manage an errand stop checklist, or manage voice notes, execute the corresponding tool immediately and confirm briefly.
        Use Google Search grounding for real-time weather, business hours, traffic news, or current facts.
        SAFETY GUARDRAILS: Never ask the driver to read long lists (offer at most 2 options). If camera frames are blurry, dark, or obstructed, state clearly that you cannot read them safely instead of guessing. If you see a red dashboard warning light (oil, brake, temperature), advise pulling over safely. Never fabricate road conditions, routes, speed, or vehicle diagnostics.
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
                tools = listOf(
                    ToolDeclaration(functionDeclarations = registeredTools()),
                    ToolDeclaration(googleSearch = JsonObject(emptyMap()))
                ),
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
            name = "control_media_playback",
            description = "Control current system music playback: play/pause, skip to next track, or go back to previous track.",
            parameters = SchemaObject(
                properties = mapOf(
                    "action" to SchemaProperty(
                        type = "STRING",
                        description = "Media playback action to execute.",
                        enum = listOf("PLAY_PAUSE", "NEXT", "PREVIOUS")
                    )
                ),
                required = listOf("action")
            )
        ),
        FunctionDeclaration(
            name = "inspect_road_view",
            description = "Activate the dashboard camera for 8 seconds to inspect road signs, parking rules, traffic ahead, fuel receipts, or dashboard warning lights.",
            parameters = SchemaObject(
                properties = mapOf(
                    "reason" to SchemaProperty(
                        type = "STRING",
                        description = "What KAI should look for in the camera feed."
                    )
                ),
                required = listOf("reason")
            )
        ),
        FunctionDeclaration(
            name = "get_trip_telemetry",
            description = "Read current GPS speed (km/h), compass heading, coordinates, active drive duration in minutes, and Focus Mode status.",
            parameters = SchemaObject(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "save_parking_spot",
            description = "Save the current GPS parking location and optional floor/pillar note so the driver can find the car later.",
            parameters = SchemaObject(
                properties = mapOf(
                    "note" to SchemaProperty(
                        type = "STRING",
                        description = "Optional floor, zone, or pillar description (e.g., 'Basement 2, Pillar C4')."
                    )
                ),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "find_my_car",
            description = "Retrieve the saved parking spot note and launch maps navigation back to the parked vehicle.",
            parameters = SchemaObject(
                properties = mapOf(
                    "preferred_app" to SchemaProperty(
                        type = "STRING",
                        description = "Navigation app to open for returning to the car.",
                        enum = listOf("GOOGLE_MAPS", "WAZE", "APPLE_MAPS", "DEFAULT")
                    )
                ),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "save_voice_note",
            description = "Save a short hands-free voice note, reminder, fuel expense, or errand item to persistent local storage.",
            parameters = SchemaObject(
                properties = mapOf(
                    "note" to SchemaProperty(
                        type = "STRING",
                        description = "The concise note, reminder, or expense log text to save."
                    )
                ),
                required = listOf("note")
            )
        ),
        FunctionDeclaration(
            name = "recall_notes",
            description = "Retrieve all saved driving voice notes and reminders.",
            parameters = SchemaObject(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "clear_notes",
            description = "Clear all saved driving voice notes and reminders after the driver confirms.",
            parameters = SchemaObject(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "set_focus_mode",
            description = "Enable or disable Focus Mode for heavy traffic or bad weather, making KAI's responses ultra-short.",
            parameters = SchemaObject(
                properties = mapOf(
                    "enabled" to SchemaProperty(
                        type = "BOOLEAN",
                        description = "True to turn Focus Mode on, false to return to normal conversational mode."
                    )
                ),
                required = listOf("enabled")
            )
        ),
        FunctionDeclaration(
            name = "log_fuel_or_toll",
            description = "Log a fuel fill-up, toll fee, or vehicle maintenance expense with optional liters, odometer km, and station or toll plaza name.",
            parameters = SchemaObject(
                properties = mapOf(
                    "category" to SchemaProperty(
                        type = "STRING",
                        description = "Expense category.",
                        enum = listOf("FUEL", "TOLL", "MAINTENANCE")
                    ),
                    "amount" to SchemaProperty(
                        type = "NUMBER",
                        description = "Total amount spent."
                    ),
                    "liters" to SchemaProperty(
                        type = "NUMBER",
                        description = "Optional fuel volume in liters."
                    ),
                    "odometer_km" to SchemaProperty(
                        type = "NUMBER",
                        description = "Optional odometer reading in kilometers."
                    ),
                    "station_or_location" to SchemaProperty(
                        type = "STRING",
                        description = "Optional gas station, toll plaza, or shop name."
                    )
                ),
                required = listOf("category", "amount")
            )
        ),
        FunctionDeclaration(
            name = "get_expense_summary",
            description = "Retrieve logged fuel, toll, and maintenance expenses and total spend.",
            parameters = SchemaObject(
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "check_calendar_briefing",
            description = "Read the driver's upcoming calendar events and locations for a hands-free schedule briefing.",
            parameters = SchemaObject(
                properties = mapOf(
                    "hours_ahead" to SchemaProperty(
                        type = "INTEGER",
                        description = "How many hours ahead to check (default 18)."
                    )
                ),
                required = emptyList()
            )
        ),
        FunctionDeclaration(
            name = "manage_errand_queue",
            description = "Manage an ordered multi-stop pre-drive checklist / errand queue (add stops, list remaining stops, check off a completed stop, get the next stop, or clear).",
            parameters = SchemaObject(
                properties = mapOf(
                    "action" to SchemaProperty(
                        type = "STRING",
                        description = "Queue operation to perform.",
                        enum = listOf("ADD", "LIST", "COMPLETE", "NEXT", "CLEAR")
                    ),
                    "stops" to SchemaProperty(
                        type = "STRING",
                        description = "Comma-separated errand stops when adding (e.g., 'ATM, Drop off package, Gas station')."
                    ),
                    "item" to SchemaProperty(
                        type = "STRING",
                        description = "Stop name or keyword to mark completed when action is COMPLETE (if omitted, completes the current next stop)."
                    )
                ),
                required = listOf("action")
            )
        )
    )
}
