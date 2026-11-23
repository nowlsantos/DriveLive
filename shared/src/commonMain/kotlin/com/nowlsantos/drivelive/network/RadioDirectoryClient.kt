package com.nowlsantos.drivelive.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RadioStation(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val tags: String = "",
    val country: String = "",
    val countrycode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val lastcheckok: Int = 1,
    val clickcount: Int = 0
) {
    val streamUrl: String
        get() = urlResolved.ifBlank { url }.trim()
}

class RadioDirectoryClient(
    private val httpClient: HttpClient
) {
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    suspend fun searchStations(query: String, limit: Int = 10): List<RadioStation> {
        val cleaned = query.trim()
        if (cleaned.isBlank() ||
            cleaned.equals("music", ignoreCase = true) ||
            cleaned.equals("radio", ignoreCase = true) ||
            cleaned.equals("anything", ignoreCase = true)
        ) {
            return filterPlayable(queryMirror(tag = "pop", name = null, limit = limit * 2))
                .take(limit)
        }

        // First try matching by tag/genre if the query looks like a mood/genre/holiday,
        // and also try matching by station name, then merge unique playable results.
        val isSingleOrGenre = cleaned.split(" ").size <= 2 && !cleaned.any { it.isDigit() }
        val results = mutableListOf<RadioStation>()

        if (isSingleOrGenre) {
            val tagTerm = normalizeGenreTag(cleaned)
            results += queryMirror(tag = tagTerm, name = null, limit = limit * 2)
        }

        if (results.size < limit) {
            results += queryMirror(tag = null, name = cleaned, limit = limit * 2)
        }

        if (results.isEmpty()) {
            // Broaden using the first meaningful keyword as a tag
            val firstWord = cleaned.split(" ").firstOrNull { it.length > 2 }
            if (firstWord != null) {
                results += queryMirror(tag = firstWord.lowercase(), name = null, limit = limit * 2)
            }
        }

        return filterPlayable(results)
            .distinctBy { it.streamUrl }
            .take(limit)
    }

    private suspend fun queryMirror(
        tag: String?,
        name: String?,
        limit: Int
    ): List<RadioStation> {
        for (baseUrl in mirrors) {
            val stations = runCatching {
                httpClient.get("$baseUrl/json/stations/search") {
                    header("User-Agent", "DriveLive/1.0")
                    parameter("limit", limit)
                    parameter("hidebroken", "true")
                    parameter("order", "clickcount")
                    parameter("reverse", "true")
                    if (!tag.isNullOrBlank()) parameter("tag", tag)
                    if (!name.isNullOrBlank()) parameter("name", name)
                }.body<List<RadioStation>>()
            }.getOrNull()

            if (stations != null) {
                return stations
            }
        }
        return emptyList()
    }

    private fun filterPlayable(stations: List<RadioStation>): List<RadioStation> {
        return stations
            .filter { station ->
                val url = station.streamUrl.lowercase()
                station.lastcheckok == 1 &&
                    station.name.isNotBlank() &&
                    (url.startsWith("https://") || url.startsWith("http://")) &&
                    !url.endsWith(".pls") &&
                    !url.endsWith(".m3u") &&
                    !url.endsWith(".asx")
            }
            .sortedWith(
                compareByDescending<RadioStation> { it.streamUrl.startsWith("https://") }
                    .thenByDescending { it.clickcount }
                    .thenByDescending { it.bitrate }
            )
    }

    private fun normalizeGenreTag(raw: String): String {
        val lower = raw.lowercase().trim()
        return when {
            lower.contains("lofi") || lower.contains("lo-fi") -> "lofi"
            lower.contains("xmas") || lower.contains("christmas") -> "christmas"
            lower.contains("opm") || lower.contains("tagalog") || lower.contains("pinoy") -> "opm"
            lower.contains("chill") -> "chillout"
            lower.contains("hip hop") || lower.contains("hip-hop") -> "hiphop"
            lower.contains("r&b") -> "rnb"
            else -> lower
        }
    }
}
