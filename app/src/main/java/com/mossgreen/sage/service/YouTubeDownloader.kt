package com.mossgreen.sage.service

import com.mossgreen.sage.manager.StoragePermissionManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object YouTubeDownloader {

    private val YT_URL_PATTERN = Pattern.compile(
        "(https?://)?(www\\.|music\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/)[A-Za-z0-9_-]+[\\S]*",
        Pattern.CASE_INSENSITIVE
    )

    fun extractYouTubeUrl(text: String): String? {
        val matcher = YT_URL_PATTERN.matcher(text)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            null
        }
    }

    fun isAudioOnlyUrl(url: String): Boolean {
        return url.contains("music.youtube.com", ignoreCase = true)
    }

    /**
     * Downloads YouTube video or audio using Cobalt API and saves to Downloads/sage/.
     * Returns Result with downloaded filename or error string.
     */
    fun download(ytUrl: String): Result<String> {
        return try {
            val isAudio = isAudioOnlyUrl(ytUrl)
            val directDownloadUrl = fetchCobaltDownloadUrl(ytUrl, isAudio)
                ?: return Result.failure(Exception("Could not obtain download link from Cobalt API"))

            val sageDir = StoragePermissionManager.getSageDownloadDir()
            val fileExtension = if (isAudio) "mp3" else "mp4"
            val fileName = "yt_${System.currentTimeMillis()}.$fileExtension"
            val outputFile = File(sageDir, fileName)

            downloadFile(directDownloadUrl, outputFile)
            Result.success(outputFile.name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchCobaltDownloadUrl(targetUrl: String, isAudio: Boolean): String? {
        val apiConnection = URL("https://api.cobalt.tools/").openConnection() as HttpURLConnection
        apiConnection.requestMethod = "POST"
        apiConnection.setRequestProperty("Accept", "application/json")
        apiConnection.setRequestProperty("Content-Type", "application/json")
        apiConnection.connectTimeout = 15_000
        apiConnection.readTimeout = 20_000
        apiConnection.doOutput = true

        val requestBody = JSONObject().apply {
            put("url", targetUrl)
            if (isAudio) {
                put("downloadMode", "audio")
                put("audioFormat", "mp3")
            } else {
                put("downloadMode", "auto")
            }
        }

        apiConnection.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = apiConnection.responseCode
        if (responseCode !in 200..299) {
            val errorStream = apiConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("Cobalt API HTTP $responseCode: $errorStream")
        }

        val responseText = apiConnection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)

        val status = json.optString("status")
        if (status == "redirect" || status == "tunnel") {
            return json.optString("url").takeIf { it.isNotEmpty() }
        } else if (status == "picker") {
            val pickerArray = json.optJSONArray("picker")
            if (pickerArray != null && pickerArray.length() > 0) {
                val item = pickerArray.getJSONObject(0)
                return item.optString("url").takeIf { it.isNotEmpty() }
            }
        }

        val errorObj = json.optJSONObject("error")
        val errorMsg = errorObj?.optString("code") ?: "Unknown Cobalt status: $status"
        throw Exception("Cobalt error: $errorMsg")
    }

    private fun downloadFile(downloadUrl: String, outputFile: File) {
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.connect()

        if (conn.responseCode !in 200..299) {
            throw Exception("File download failed HTTP ${conn.responseCode}")
        }

        conn.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
