package com.nowlsantos.drivelive.agent

import com.nowlsantos.drivelive.network.RadioDirectoryClient
import com.nowlsantos.drivelive.network.RadioStation
import com.nowlsantos.drivelive.platform.CalendarProvider
import com.nowlsantos.drivelive.platform.DriveStorage
import com.nowlsantos.drivelive.platform.GeoCoordinate
import com.nowlsantos.drivelive.platform.LaunchResult
import com.nowlsantos.drivelive.platform.LocationProvider
import com.nowlsantos.drivelive.platform.MediaControlAction
import com.nowlsantos.drivelive.platform.MediaLauncher
import com.nowlsantos.drivelive.platform.NavigationApp
import com.nowlsantos.drivelive.platform.NavigationLauncher
import com.nowlsantos.drivelive.platform.RadioPlayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

@Serializable
private data class ExpenseLogEntry(
    val category: String,
    val amount: Double,
    val liters: Double? = null,
    val odometerKm: Double? = null,
    val location: String? = null
)

@Serializable
private data class ErrandStop(
    val title: String,
    val done: Boolean = false
)

class ToolCallDispatcher(
    private val navigationLauncher: NavigationLauncher,
    private val mediaLauncher: MediaLauncher,
    private val radioDirectoryClient: RadioDirectoryClient,
    private val radioPlayer: RadioPlayer,
    private val locationProvider: LocationProvider,
    private val calendarProvider: CalendarProvider,
    private val driveStorage: DriveStorage,
    private val getSessionElapsedMinutes: () -> Long,
    private val isFocusModeEnabled: () -> Boolean,
    private val onSetFocusMode: (Boolean) -> Unit,
    private val onSetStandbyMode: (Boolean) -> Unit,
    private val onScheduleEndSession: () -> Unit,
    private val onRadioStateChanged: (stationName: String?, isPlaying: Boolean) -> Unit,
    private val onRequestCameraBurst: (reason: String, durationSeconds: Int) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedStations: List<RadioStation> = emptyList()
    private var currentStationIndex: Int = -1

    suspend fun dispatch(toolName: String, argsJson: String): JsonObject {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

        return when (toolName) {
            "navigate_to" -> handleNavigateTo(args)
            "play_music" -> handlePlayMusic(args)
            "control_media_playback" -> handleMediaControl(args)
            "set_radio_volume" -> handleSetRadioVolume(args)
            "inspect_road_view" -> handleInspectRoadView(args)
            "get_trip_telemetry" -> handleTripTelemetry()
            "save_parking_spot" -> handleSaveParkingSpot(args)
            "find_my_car" -> handleFindMyCar(args)
            "save_voice_note" -> handleSaveVoiceNote(args)
            "recall_notes" -> handleRecallNotes()
            "clear_notes" -> handleClearNotes()
            "set_focus_mode" -> handleSetFocusMode(args)
            "set_standby_mode" -> handleSetStandbyMode(args)
            "end_drive_session" -> handleEndDriveSession()
            "log_fuel_or_toll" -> handleLogExpense(args)
            "get_expense_summary" -> handleGetExpenseSummary()
            "check_calendar_briefing" -> handleCalendarBriefing(args)
            "manage_errand_queue" -> handleManageErrandQueue(args)
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
        val source = args["source"]?.jsonPrimitive?.content?.uppercase() ?: "RADIO"

        if (source == "SPOTIFY" || query.equals("spotify", ignoreCase = true)) {
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

        val stations = radioDirectoryClient.searchStations(query, limit = 10)
        if (stations.isEmpty()) {
            return buildJsonObject {
                put("status", "not_found")
                put("message", "No live radio stations matched '$query'. Try a broader genre like pop, jazz, rock, lofi, or christmas.")
            }
        }

        cachedStations = stations
        var lastError = "Unable to connect to radio stream"
        val attempts = minOf(stations.size, 4)
        for (idx in 0 until attempts) {
            val station = stations[idx]
            when (val playResult = radioPlayer.playStream(station.streamUrl, station.name)) {
                is LaunchResult.Success -> {
                    currentStationIndex = idx
                    onRadioStateChanged(station.name, true)
                    return buildJsonObject {
                        put("status", "playing_radio")
                        put("station", station.name)
                        put("country", station.country)
                        put("tags", station.tags)
                    }
                }
                is LaunchResult.Failure -> {
                    lastError = playResult.reason
                }
            }
        }

        onRadioStateChanged(null, false)
        return buildJsonObject {
            put("status", "error")
            put("message", lastError)
        }
    }

    private suspend fun handleMediaControl(args: JsonObject): JsonObject {
        val actionRaw = args["action"]?.jsonPrimitive?.content ?: "PLAY_PAUSE"
        val action = runCatching { MediaControlAction.valueOf(actionRaw) }
            .getOrDefault(MediaControlAction.PLAY_PAUSE)

        val activeStation = radioPlayer.currentStationName()
        if (activeStation != null) {
            return when (action) {
                MediaControlAction.STOP -> {
                    radioPlayer.stop()
                    onRadioStateChanged(null, false)
                    buildJsonObject {
                        put("status", "stopped_radio")
                        put("station", activeStation)
                    }
                }
                MediaControlAction.PLAY_PAUSE -> {
                    if (radioPlayer.isPlaying()) {
                        radioPlayer.pause()
                        onRadioStateChanged(activeStation, false)
                        buildJsonObject {
                            put("status", "paused_radio")
                            put("station", activeStation)
                        }
                    } else {
                        val resumed = radioPlayer.resume()
                        onRadioStateChanged(activeStation, resumed)
                        buildJsonObject {
                            put("status", if (resumed) "resumed_radio" else "error")
                            put("station", activeStation)
                        }
                    }
                }
                MediaControlAction.NEXT, MediaControlAction.PREVIOUS -> {
                    if (cachedStations.isEmpty()) {
                        cachedStations = radioDirectoryClient.searchStations("", limit = 10)
                    }
                    if (cachedStations.isEmpty()) {
                        return buildJsonObject {
                            put("status", "error")
                            put("message", "No stations available to switch to.")
                        }
                    }
                    val step = if (action == MediaControlAction.NEXT) 1 else -1
                    val total = cachedStations.size
                    var lastError = "Failed to switch radio station"
                    for (offset in 1..minOf(total, 3)) {
                        val candidateIdx = ((currentStationIndex + step * offset) % total + total) % total
                        val station = cachedStations[candidateIdx]
                        when (val res = radioPlayer.playStream(station.streamUrl, station.name)) {
                            is LaunchResult.Success -> {
                                currentStationIndex = candidateIdx
                                onRadioStateChanged(station.name, true)
                                return buildJsonObject {
                                    put("status", "switched_station")
                                    put("station", station.name)
                                    put("country", station.country)
                                }
                            }
                            is LaunchResult.Failure -> lastError = res.reason
                        }
                    }
                    buildJsonObject {
                        put("status", "error")
                        put("message", lastError)
                    }
                }
            }
        }

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

    private fun handleSetRadioVolume(args: JsonObject): JsonObject {
        val percent = args["percent"]?.jsonPrimitive?.intOrNull ?: 65
        val clamped = percent.coerceIn(5, 100)
        radioPlayer.setVolume(clamped / 100f)
        return buildJsonObject {
            put("status", "ok")
            put("volume_percent", clamped)
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

    private fun handleSetStandbyMode(args: JsonObject): JsonObject {
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        onSetStandbyMode(enabled)
        return buildJsonObject {
            put("status", "ok")
            put("standby_enabled", enabled)
        }
    }

    private fun handleEndDriveSession(): JsonObject {
        onScheduleEndSession()
        return buildJsonObject {
            put("status", "ending_session")
        }
    }

    private fun handleLogExpense(args: JsonObject): JsonObject {
        val category = args["category"]?.jsonPrimitive?.content?.uppercase() ?: "FUEL"
        val amount = args["amount"]?.jsonPrimitive?.doubleOrNull
            ?: return buildJsonObject {
                put("status", "error")
                put("message", "Expense amount is required.")
            }
        val liters = args["liters"]?.jsonPrimitive?.doubleOrNull
        val odometerKm = args["odometer_km"]?.jsonPrimitive?.doubleOrNull
        val location = args["station_or_location"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }

        val existing = loadExpenseLogs()
        val entry = ExpenseLogEntry(
            category = category,
            amount = amount,
            liters = liters,
            odometerKm = odometerKm,
            location = location
        )
        val updated = (existing + entry).takeLast(50)
        driveStorage.putString(KEY_EXPENSE_LOGS, json.encodeToString(updated))

        return buildJsonObject {
            put("status", "logged")
            put("category", category)
            put("amount", amount)
            liters?.let { put("liters", it) }
            odometerKm?.let { put("odometer_km", it) }
            location?.let { put("location", it) }
            put("total_entries", updated.size)
        }
    }

    private fun handleGetExpenseSummary(): JsonObject {
        val entries = loadExpenseLogs()
        val total = entries.sumOf { it.amount }
        val fuelTotal = entries.filter { it.category == "FUEL" }.sumOf { it.amount }
        val tollTotal = entries.filter { it.category == "TOLL" }.sumOf { it.amount }

        return buildJsonObject {
            put("status", "ok")
            put("entry_count", entries.size)
            put("total_amount", total)
            put("fuel_total", fuelTotal)
            put("toll_total", tollTotal)
            put(
                "recent_entries",
                buildJsonArray {
                    entries.takeLast(5).forEach { e ->
                        add(
                            buildJsonObject {
                                put("category", e.category)
                                put("amount", e.amount)
                                e.liters?.let { put("liters", it) }
                                e.odometerKm?.let { put("odometer_km", it) }
                                e.location?.let { put("location", it) }
                            }
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleCalendarBriefing(args: JsonObject): JsonObject {
        val hoursAhead = args["hours_ahead"]?.jsonPrimitive?.intOrNull ?: 18
        val events = calendarProvider.getUpcomingEvents(hoursAhead)
        return buildJsonObject {
            put("status", "ok")
            put("event_count", events.size)
            put(
                "events",
                buildJsonArray {
                    events.forEach { ev ->
                        add(
                            buildJsonObject {
                                put("title", ev.title)
                                put("start_time", ev.startTimeLabel)
                                ev.location?.let { put("location", it) }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun handleManageErrandQueue(args: JsonObject): JsonObject {
        val action = args["action"]?.jsonPrimitive?.content?.uppercase() ?: "LIST"
        val current = loadErrandQueue().toMutableList()

        when (action) {
            "ADD" -> {
                val rawStops = args["stops"]?.jsonPrimitive?.content.orEmpty()
                val newStops = rawStops.split(",", ";", " and ")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (newStops.isEmpty()) {
                    return buildJsonObject {
                        put("status", "error")
                        put("message", "No errand stops provided.")
                    }
                }
                newStops.forEach { title -> current.add(ErrandStop(title = title, done = false)) }
                saveErrandQueue(current)
            }
            "COMPLETE" -> {
                val keyword = args["item"]?.jsonPrimitive?.content?.trim().orEmpty()
                val targetIndex = if (keyword.isNotBlank()) {
                    current.indexOfFirst { !it.done && it.title.contains(keyword, ignoreCase = true) }
                } else {
                    current.indexOfFirst { !it.done }
                }
                if (targetIndex < 0) {
                    return buildJsonObject {
                        put("status", "not_found")
                        put("message", "No matching pending errand stop found.")
                    }
                }
                val completed = current[targetIndex]
                current[targetIndex] = completed.copy(done = true)
                saveErrandQueue(current)
            }
            "CLEAR" -> {
                current.clear()
                driveStorage.remove(KEY_ERRAND_QUEUE)
            }
        }

        val remaining = current.filter { !it.done }.map { it.title }
        return buildJsonObject {
            put("status", "ok")
            put("action", action)
            put("pending_count", remaining.size)
            put("next_stop", remaining.firstOrNull() ?: "")
            put(
                "pending_stops",
                buildJsonArray {
                    remaining.forEach { stop ->
                        add(kotlinx.serialization.json.JsonPrimitive(stop))
                    }
                }
            )
        }
    }

    private fun loadExpenseLogs(): List<ExpenseLogEntry> {
        val raw = driveStorage.getString(KEY_EXPENSE_LOGS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExpenseLogEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadErrandQueue(): List<ErrandStop> {
        val raw = driveStorage.getString(KEY_ERRAND_QUEUE) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ErrandStop>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveErrandQueue(items: List<ErrandStop>) {
        driveStorage.putString(KEY_ERRAND_QUEUE, json.encodeToString(items.takeLast(25)))
    }

    private companion object {
        const val KEY_PARKING_LAT = "kai_parking_lat"
        const val KEY_PARKING_LON = "kai_parking_lon"
        const val KEY_PARKING_NOTE = "kai_parking_note"
        const val KEY_VOICE_NOTES = "kai_voice_notes"
        const val KEY_EXPENSE_LOGS = "kai_expense_logs"
        const val KEY_ERRAND_QUEUE = "kai_errand_queue"
        const val NOTES_DELIMITER = "\n---KAI_NOTE---\n"
    }
}
