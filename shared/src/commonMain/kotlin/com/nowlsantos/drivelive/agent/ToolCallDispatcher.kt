package com.nowlsantos.drivelive.agent

import com.nowlsantos.drivelive.platform.LaunchResult
import com.nowlsantos.drivelive.platform.LocationProvider
import com.nowlsantos.drivelive.platform.MediaLauncher
import com.nowlsantos.drivelive.platform.NavigationApp
import com.nowlsantos.drivelive.platform.NavigationLauncher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolCallDispatcher(
    private val navigationLauncher: NavigationLauncher,
    private val mediaLauncher: MediaLauncher,
    private val locationProvider: LocationProvider,
    private val onRequestCameraBurst: (reason: String, durationSeconds: Int) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun dispatch(toolName: String, argsJson: String): JsonObject {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

        return when (toolName) {
            "navigate_to" -> handleNavigateTo(args)
            "play_music" -> handlePlayMusic(args)
            "inspect_road_view" -> handleInspectRoadView(args)
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

    private fun handleInspectRoadView(args: JsonObject): JsonObject {
        val reason = args["reason"]?.jsonPrimitive?.content ?: "General road inspection"
        onRequestCameraBurst(reason, 8)
        return buildJsonObject {
            put("status", "camera_active")
            put("duration_seconds", 8)
            put("reason", reason)
        }
    }
}