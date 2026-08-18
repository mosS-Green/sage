package com.mossgreen.sage.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LastFmTrackInfo(
    val trackName: String,
    val artistName: String,
    val isNowPlaying: Boolean,
    val playCount: Int,
    val timeAgo: String
)

class LastFmClient {

    companion object {
        private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

        fun formatTimeAgo(utsSeconds: Long): String {
            if (utsSeconds <= 0) return ""
            val nowSeconds = System.currentTimeMillis() / 1000
            val diffSeconds = (nowSeconds - utsSeconds).coerceAtLeast(0)
            val days = diffSeconds / 86400
            val hours = (diffSeconds % 86400) / 3600
            val minutes = (diffSeconds % 3600) / 60
            return when {
                days > 0 -> "$days days ago"
                hours > 0 -> "$hours hours ago"
                minutes > 0 -> "$minutes minutes ago"
                else -> "just now"
            }
        }

        fun formatOutput(
            shownName: String,
            verb: String,
            trackInfo: LastFmTrackInfo
        ): String {
            val cleanVerb = verb.trim().ifEmpty { "listening" }
            val cleanName = shownName.trim().ifEmpty { "I" }

            val verbConjugated = if (cleanVerb.endsWith("ing", ignoreCase = true)) cleanVerb else "${cleanVerb}ing"

            val header = if (trackInfo.isNowPlaying) {
                val copula = if (cleanName.equals("I", ignoreCase = true)) "am" else "is"
                "$cleanName $copula $verbConjugated to"
            } else {
                val timeAgoStr = if (trackInfo.timeAgo.isNotEmpty()) " (${trackInfo.timeAgo})" else ""
                "$cleanName was last $verbConjugated to$timeAgoStr"
            }

            val playsText = if (trackInfo.playCount == 1) "1 play" else "${trackInfo.playCount} plays"

            return "$header\n> *${trackInfo.trackName}* by _${trackInfo.artistName}_\n♫ $playsText"
        }
    }

    suspend fun getRecentTrack(username: String, apiKey: String): Result<LastFmTrackInfo> = withContext(Dispatchers.IO) {
        if (username.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Last.fm username and API key are required"))
        }

        try {
            val encodedUser = URLEncoder.encode(username, "UTF-8")
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val recentTracksUrl = "$BASE_URL?method=user.getrecenttracks&user=$encodedUser&api_key=$encodedKey&format=json&limit=1"

            val recentJson = fetchJson(recentTracksUrl) ?: return@withContext Result.failure(Exception("Empty response from Last.fm"))

            if (recentJson.has("error")) {
                val message = recentJson.optString("message", "Last.fm API error")
                return@withContext Result.failure(Exception(message))
            }

            val recentTracksObj = recentJson.optJSONObject("recenttracks")
                ?: return@withContext Result.failure(Exception("Invalid Last.fm response format"))

            val trackOpt = recentTracksObj.opt("track")
            val firstTrack = when (trackOpt) {
                is JSONArray -> if (trackOpt.length() > 0) trackOpt.optJSONObject(0) else null
                is JSONObject -> trackOpt
                else -> null
            } ?: return@withContext Result.failure(Exception("No scrobbled tracks found for $username"))

            val trackName = firstTrack.optString("name", "Unknown Track")
            val artistObj = firstTrack.optJSONObject("artist")
            val artistName = artistObj?.optString("#text") ?: firstTrack.optString("artist", "Unknown Artist")
            val isNowPlaying = firstTrack.optJSONObject("@attr")?.optString("nowplaying") == "true"
            val uts = firstTrack.optJSONObject("date")?.optLong("uts") ?: 0L
            val timeAgo = if (!isNowPlaying) formatTimeAgo(uts) else ""

            // Fetch user play count
            val playCount = fetchPlayCount(artistName, trackName, username, apiKey)

            Result.success(
                LastFmTrackInfo(
                    trackName = trackName,
                    artistName = artistName,
                    isNowPlaying = isNowPlaying,
                    playCount = playCount,
                    timeAgo = timeAgo
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchPlayCount(artist: String, track: String, username: String, apiKey: String): Int {
        return try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTrack = URLEncoder.encode(track, "UTF-8")
            val encodedUser = URLEncoder.encode(username, "UTF-8")
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val trackInfoUrl = "$BASE_URL?method=track.getInfo&api_key=$encodedKey&artist=$encodedArtist&track=$encodedTrack&username=$encodedUser&format=json"

            val json = fetchJson(trackInfoUrl) ?: return 0
            val trackObj = json.optJSONObject("track") ?: return 0
            val countStr = trackObj.optString("userplaycount", "0")
            countStr.toIntOrNull() ?: trackObj.optInt("userplaycount", 0)
        } catch (_: Exception) {
            0
        }
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: return null
            JSONObject(responseText)
        } finally {
            connection?.disconnect()
        }
    }
}
