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

private data class PresetStationEntry(
    val station: RadioStation,
    val keywords: List<String>
)

class RadioDirectoryClient(
    private val httpClient: HttpClient
) {
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    private val metroManilaPresets: List<PresetStationEntry> = listOf(
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dzmb-907",
                name = "90.7 Love Radio (DZMB-FM)",
                urlResolved = "https://azura.loveradio.com.ph/listen/love_radio_manila/radio.mp3",
                tags = "opm,masa,filipino,manila,pop",
                country = "Philippines",
                countrycode = "PH",
                codec = "MP3",
                bitrate = 128,
                lastcheckok = 1
            ),
            keywords = listOf("90.7", "love radio", "loveradio", "dzmb", "masa", "opm", "filipino")
        ),
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dwrk-963",
                name = "96.3 Easy Rock (DWRK-FM)",
                urlResolved = "https://azura.easyrock.com.ph/listen/easy_rock_manila/radio.mp3",
                tags = "soft rock,adult contemporary,love songs,opm,manila",
                country = "Philippines",
                countrycode = "PH",
                codec = "MP3",
                bitrate = 128,
                lastcheckok = 1
            ),
            keywords = listOf("96.3", "easy rock", "easyrock", "dwrk", "love songs", "soft rock", "adult contemporary", "opm")
        ),
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dwls-971",
                name = "Barangay LS 97.1 (DWLS-FM)",
                urlResolved = "https://gsattv.akamaized.net/live/media0/radiodwls/Fairplay/radiodwls.m3u8",
                tags = "opm,masa,contemporary,filipino,manila",
                country = "Philippines",
                countrycode = "PH",
                codec = "AAC",
                bitrate = 96,
                lastcheckok = 1
            ),
            keywords = listOf("97.1", "barangay", "barangay ls", "dwls", "masa", "opm", "filipino")
        ),
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dwtm-899",
                name = "Magic 89.9 (DWTM-FM)",
                urlResolved = "https://playerservices.streamtheworld.com/api/livestream-redirect/LIN_MAGIC899AAC_SC",
                tags = "top 40,chr,pop,hits,english,manila",
                country = "Philippines",
                countrycode = "PH",
                codec = "AAC+",
                bitrate = 64,
                lastcheckok = 1
            ),
            keywords = listOf("89.9", "magic", "magic 89.9", "dwtm", "top 40", "chr", "pop hits")
        ),
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dwrx-931",
                name = "Monster RX 93.1 (DWRX-FM)",
                urlResolved = "https://in-icecast.eradioportal.com:8443/monsterrrx",
                tags = "urban contemporary,pop,opm,hits,manila",
                country = "Philippines",
                countrycode = "PH",
                codec = "MP3",
                bitrate = 128,
                lastcheckok = 1
            ),
            keywords = listOf("93.1", "monster", "rx 93.1", "rx931", "dwrx", "urban", "pop", "opm")
        ),
        PresetStationEntry(
            station = RadioStation(
                stationuuid = "preset-dwky-915",
                name = "91.5 Win Radio (DWKY-FM)",
                urlResolved = "https://stream.zeno.fm/2ss1hgnu6hhvv",
                tags = "filipino,opm,masa,contemporary,manila",
                country = "Philippines",
                countrycode = "PH",
                codec = "MP3",
                bitrate = 192,
                lastcheckok = 1
            ),
            keywords = listOf("91.5", "win radio", "winradio", "dwky", "masa", "opm", "filipino")
        )
    )

    suspend fun searchStations(query: String, limit: Int = 10): List<RadioStation> {
        val cleaned = query.trim()
        val lower = cleaned.lowercase()

        val matchedPresets = findMatchingPresets(lower)
        if (cleaned.isBlank() ||
            lower == "music" ||
            lower == "radio" ||
            lower == "fm" ||
            lower == "anything" ||
            lower.contains("manila") ||
            lower.contains("philippines") ||
            lower.contains("pinoy") ||
            lower == "opm" ||
            lower == "tagalog" ||
            lower == "masa"
        ) {
            val orderedPresets = if (matchedPresets.isNotEmpty()) {
                (matchedPresets + metroManilaPresets.map { it.station }).distinctBy { it.streamUrl }
            } else {
                metroManilaPresets.map { it.station }
            }
            val apiExtra = filterPlayable(queryMirror(tag = "opm", name = null, limit = limit))
            return (orderedPresets + apiExtra)
                .distinctBy { it.streamUrl }
                .take(limit)
        }

        if (matchedPresets.isNotEmpty()) {
            val apiFallback = filterPlayable(queryMirror(tag = null, name = cleaned, limit = limit))
            val remainingPresets = metroManilaPresets.map { it.station }
            return (matchedPresets + apiFallback + remainingPresets)
                .distinctBy { it.streamUrl }
                .take(limit)
        }

        val isSingleOrGenre = (cleaned.split(" ").size <= 2) && !cleaned.any { it.isDigit() }
        val results = mutableListOf<RadioStation>()

        if (isSingleOrGenre) {
            val tagTerm = normalizeGenreTag(cleaned)
            results += queryMirror(tag = tagTerm, name = null, limit = limit * 2)
        }

        if (results.size < limit) {
            results += queryMirror(tag = null, name = cleaned, limit = limit * 2)
        }

        if (results.isEmpty()) {
            cleaned.split(" ").firstOrNull { it.length > 2 }?.let { firstWord ->
                results += queryMirror(tag = firstWord.lowercase(), name = null, limit = limit * 2)
            }
        }

        return filterPlayable(results)
            .distinctBy { it.streamUrl }
            .take(limit)
    }

    private fun findMatchingPresets(lowerQuery: String): List<RadioStation> {
        if (lowerQuery.isBlank()) return emptyList()
        val scored = metroManilaPresets.mapNotNull { entry ->
            val score = entry.keywords.count { kw ->
                lowerQuery.contains(kw) || kw.contains(lowerQuery)
            } + if (entry.station.name.lowercase().contains(lowerQuery)) 3 else 0
            if (score > 0) entry.station to score else null
        }
        return scored.sortedByDescending { it.second }.map { it.first }
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
