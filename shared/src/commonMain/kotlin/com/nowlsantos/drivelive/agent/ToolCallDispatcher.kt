package com.nowlsantos.drivelive.agent

import com.nowlsantos.drivelive.platform.DriveStorage
import com.nowlsantos.drivelive.platform.GeoCoordinate
import com.nowlsantos.drivelive.platform.LaunchResult
import com.nowlsantos.drivelive.platform.LocationProvider
import com.nowlsantos.drivelive.platform.MediaControlAction
import com.nowlsantos.drivelive.platform.MediaLauncher
import com.nowlsantos.drivelive.platform.NavigationApp
import com.nowlsantos.drivelive.platform.NavigationLauncher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

class ToolCallDispatcher(
    private val navigationLauncher: NavigationLauncher,
    private val mediaLauncher: MediaLauncher,
    private val locationProvider: LocationProvider,
    private val driveStorage: DriveStorage,
    private val getSessionElapsedMinutes: () -> Long,
    private val isFocusModeEnabled: () -> Boolean,
    private val onSetFocusMode: (Boolean) -> Unit,
    private val onRequestCameraBurst: (reason: String, durationSeconds: Int) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun dispatch(toolName: String, argsJson: String): JsonObject {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

        return when (toolName) {
            "navigate_to" -> handleNavigateTo(args)
            "play_music" -> handlePlayMusic(args)
            "control_media_playback" -> handleMediaControl(args)
            "inspect_road_view" -> handleInspectRoadView(args)
            "get_trip_telemetry" -> handleTripTelemetry()
            "save_parking_spot" -> handleSaveParkingSpot(args)
            "find_my_car" -> handleFindMyCar(args)
            "save_voice_note" -> handleSaveVoiceNote(args)
            "recall_notes" -> handleRecallNotes()
            "clear_notes" -> handleClearNotes()
            "set_focus_mode" -> handleSetFocusMode(args)
            else -> buildJsonObject {
                put("status", "error")
                put("message", "Unknown tool: $toolName")
            }
        }
    }

    private suspend fun handleNavigateTo(args: JsonObject): JsonObject {
        val destination = args["destination"]?.jsonPrimitive?.content?.trim().orEmpty()
        val appRaw = args["preferred_app"]?.jsonPrimitive?.content ?: "DEFAULT"
        val preferredApp = runCatching { NavigationApp.valueOf(appRaw) }
            .getOrDefault(NavigationApp.DEFAULT)

        val currentCoord = locationProvider.getCurrentLocation()
        val result = navigationLauncher.navigateTo(
            destination = destination,
            coordinate = currentCoord,
            preferredApp = preferredApp
        )

        return when (result) {
            is LaunchResult.Success -> buildJsonObject {
                put("status", "launched")
                put("destination", destination.ifEmpty { "current_map" })
                put("app", preferredApp.name)
            }
            is LaunchResult.Failure -> buildJsonObject {
                put("status", "error")
                put("message", result.reason)
            }
        }
    }

    private suspend fun handlePlayMusic(args: JsonObject): JsonObject {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val result = if (query.isBlank() || query.equals("spotify", ignoreCase = true)) {
            mediaLauncher.openSpotifyUri("spotify://")
        } else {
            mediaLauncher.searchSpotifyFallback(query)
        }
        return when (result) {
            is LaunchResult.Success -> buildJsonObject {
                put("status", "launched_spotify")
                put("query", query.ifEmpty { "home" })
            }
            is LaunchResult.Failure -> buildJsonObject {
                put("status", "error")
                put("message", result.reason)
            }
        }
    }

    private suspend fun handleMediaControl(args: JsonObject): JsonObject {
        val actionRaw = args["action"]?.jsonPrimitive?.content ?: "PLAY_PAUSE"
        val action = runCatching { MediaControlAction.valueOf(actionRaw) }
            .getOrDefault(MediaControlAction.PLAY_PAUSE)

        return when (val result = mediaLauncher.controlPlayback(action)) {
            is LaunchResult.Success -> buildJsonObject {
                put("status", "executed")
                put("action", action.name)
            }
            is LaunchResult.Failure -> buildJsonObject {
                put("status", "error")
                put("message", result.reason)
            }
        }
    }

    private fun handleInspectRoadView(args: JsonObject): JsonObject {
        val reason = args["reason"]?.jsonPrimitive?.content ?: "General road inspection"
        onRequestCameraBurst(reason, 8)
        return buildJsonObject {
            put("status", "camera_active")
            put("duration_seconds", 8)
            put("reason", reason)
        }
    }

    private suspend fun handleTripTelemetry(): JsonObject {
        val coord = locationProvider.getCurrentLocation()
        val elapsedMins = getSessionElapsedMinutes()
        return buildJsonObject {
            put("status", "ok")
            put("drive_duration_minutes", elapsedMins)
            put("focus_mode_enabled", isFocusModeEnabled())
            if (coord != null) {
                put("latitude", coord.latitude)
                put("longitude", coord.longitude)
                coord.speedKmh?.let { put("speed_kmh", it.roundToInt()) }
                coord.bearingDegrees?.let { put("heading_degrees", it.roundToInt()) }
            } else {
                put("gps_available", false)
            }
        }
    }

    private suspend fun handleSaveParkingSpot(args: JsonObject): JsonObject {
        val note = args["note"]?.jsonPrimitive?.content?.trim().orEmpty()
        val coord = locationProvider.getCurrentLocation()
        if (coord == null && note.isBlank()) {
            return buildJsonObject {
                put("status", "error")
                put("message", "No GPS location or parking note available to save.")
            }
        }

        if (coord != null) {
            driveStorage.putString(KEY_PARKING_LAT, coord.latitude.toString())
            driveStorage.putString(KEY_PARKING_LON, coord.longitude.toString())
        } else {
            driveStorage.remove(KEY_PARKING_LAT)
            driveStorage.remove(KEY_PARKING_LON)
        }
        driveStorage.putString(KEY_PARKING_NOTE, note)

        return buildJsonObject {
            put("status", "saved")
            put("note", note.ifEmpty { "GPS pin saved" })
            put("has_gps", coord != null)
        }
    }

    private suspend fun handleFindMyCar(args: JsonObject): JsonObject {
        val lat = driveStorage.getString(KEY_PARKING_LAT)?.toDoubleOrNull()
        val lon = driveStorage.getString(KEY_PARKING_LON)?.toDoubleOrNull()
        val note = driveStorage.getString(KEY_PARKING_NOTE).orEmpty()

        if (lat == null && lon == null && note.isBlank()) {
            return buildJsonObject {
                put("status", "not_found")
                put("message", "No saved parking spot found.")
            }
        }

        val appRaw = args["preferred_app"]?.jsonPrimitive?.content ?: "DEFAULT"
        val preferredApp = runCatching { NavigationApp.valueOf(appRaw) }
            .getOrDefault(NavigationApp.DEFAULT)

        var launchedMap = false
        if (lat != null && lon != null) {
            val result = navigationLauncher.navigateTo(
                destination = "",
                coordinate = GeoCoordinate(lat, lon),
                preferredApp = preferredApp
            )
            launchedMap = result is LaunchResult.Success
        }

        return buildJsonObject {
            put("status", "found")
            put("note", note)
            put("map_launched", launchedMap)
        }
    }

    private fun handleSaveVoiceNote(args: JsonObject): JsonObject {
        val note = args["note"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (note.isBlank()) {
            return buildJsonObject {
                put("status", "error")
                put("message", "Note text was empty.")
            }
        }

        val existing = driveStorage.getString(KEY_VOICE_NOTES)
            ?.split(NOTES_DELIMITER)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val updated = (existing + note).takeLast(20)
        driveStorage.putString(KEY_VOICE_NOTES, updated.joinToString(NOTES_DELIMITER))

        return buildJsonObject {
            put("status", "saved")
            put("note", note)
            put("total_notes", updated.size)
        }
    }

    private fun handleRecallNotes(): JsonObject {
        val notes = driveStorage.getString(KEY_VOICE_NOTES)
            ?.split(NOTES_DELIMITER)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return buildJsonObject {
            put("status", "ok")
            put("count", notes.size)
            put("notes", notes.joinToString(" | "))
        }
    }

    private fun handleClearNotes(): JsonObject {
        driveStorage.remove(KEY_VOICE_NOTES)
        return buildJsonObject {
            put("status", "cleared")
        }
    }

    private fun handleSetFocusMode(args: JsonObject): JsonObject {
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        onSetFocusMode(enabled)
        return buildJsonObject {
            put("status", "ok")
            put("focus_mode_enabled", enabled)
        }
    }

    private companion object {
        const val KEY_PARKING_LAT = "kai_parking_lat"
        const val KEY_PARKING_LON = "kai_parking_lon"
        const val KEY_PARKING_NOTE = "kai_parking_note"
        const val KEY_VOICE_NOTES = "kai_voice_notes"
        const val NOTES_DELIMITER = "\n---KAI_NOTE---\n"
    }
}