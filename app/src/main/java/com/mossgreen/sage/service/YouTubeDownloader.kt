package com.mossgreen.sage.service

import com.mossgreen.sage.manager.StoragePermissionManager
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONTokener
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object YouTubeDownloader {

    private val initialized = AtomicBoolean(false)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val httpMethod = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()

            val requestBody = if (dataToSend != null) {
                dataToSend.toRequestBody(null)
            } else if (httpMethod.equals("POST", ignoreCase = true)) {
                ByteArray(0).toRequestBody(null)
            } else {
                null
            }

            val builder = okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)

            headers?.forEach { (name, values) ->
                values.forEach { value -> builder.addHeader(name, value) }
            }

            val okHttpResponse = client.newCall(builder.build()).execute()
            val responseBody = okHttpResponse.body?.string() ?: ""

            return Response(
                okHttpResponse.code,
                okHttpResponse.message,
                okHttpResponse.headers.toMultimap(),
                responseBody,
                okHttpResponse.request.url.toString()
            )
        }
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(OkHttpDownloader(httpClient))
        }
    }

    private val YT_URL_PATTERN = Pattern.compile(
        "(https?://)?(www\\.|music\\.)?(youtube\\.com/(watch\\?v=|shorts/)|youtu\\.be/)[A-Za-z0-9_-]+[\\S]*",
        Pattern.CASE_INSENSITIVE
    )

    private val FB_URL_PATTERN = Pattern.compile(
        "(https?://)?((www\\.|m\\.|mbasic\\.|web\\.)?facebook\\.com/(watch/?\\?v=|reel/|share/[vr]/|[^\\s/]+/videos/|video\\.php\\?v=)|fb\\.watch/)[^\\s]+",
        Pattern.CASE_INSENSITIVE
    )

    fun extractYouTubeUrl(text: String): String? {
        val matcher = YT_URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    fun extractFacebookUrl(text: String): String? {
        val matcher = FB_URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    fun extractVideoUrl(text: String): String? {
        return extractYouTubeUrl(text) ?: extractFacebookUrl(text)
    }

    fun isFacebookUrl(url: String): Boolean {
        return FB_URL_PATTERN.matcher(url).find()
    }

    fun isAudioOnlyUrl(url: String): Boolean {
        return url.contains("music.youtube.com", ignoreCase = true)
    }

    /**
     * Downloads YouTube or public Facebook video/audio and saves to Downloads/sage/.
     * Returns Result with downloaded File or error.
     */
    fun download(url: String): Result<File> {
        return if (isFacebookUrl(url)) {
            downloadFacebook(url)
        } else {
            downloadYouTube(url)
        }
    }

    private fun downloadYouTube(ytUrl: String): Result<File> {
        return try {
            ensureInitialized()

            val isAudio = isAudioOnlyUrl(ytUrl)
            val sageDir = StoragePermissionManager.getSageDownloadDir()

            var cleanUrl = ytUrl.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://$cleanUrl"
            }

            val extractor = ServiceList.YouTube.getStreamExtractor(cleanUrl) as YoutubeStreamExtractor
            extractor.fetchPage()

            val rawName = try { extractor.name } catch (_: Exception) { "" }
            val title = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40).ifBlank {
                "yt_${System.currentTimeMillis()}"
            }

            val (directStreamUrl, fileExtension) = if (isAudio) {
                val audioStream = extractor.audioStreams?.maxByOrNull { it.averageBitrate }
                    ?: extractor.audioStreams?.firstOrNull()
                    ?: return Result.failure(Exception("No audio streams found for video"))
                val ext = when (audioStream.format?.name?.lowercase()) {
                    "m4a" -> "m4a"
                    "opus", "webm" -> "opus"
                    else -> "mp3"
                }
                audioStream.content to ext
            } else {
                val videoStream = extractor.videoStreams?.firstOrNull()
                    ?: extractor.videoOnlyStreams?.firstOrNull()
                    ?: return Result.failure(Exception("No video streams found for video"))
                val ext = if (videoStream.format?.name?.lowercase() == "webm") "webm" else "mp4"
                videoStream.content to ext
            }

            val fileName = "${title}_${System.currentTimeMillis()}.$fileExtension"
            val outputFile = File(sageDir, fileName)

            downloadFile(directStreamUrl, outputFile)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFacebook(fbUrl: String): Result<File> {
        return try {
            val sageDir = StoragePermissionManager.getSageDownloadDir()
            var cleanUrl = fbUrl.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = "https://$cleanUrl"
            }

            val html = fetchFacebookHtml(cleanUrl)
            var streamUrl = extractFacebookStreamUrl(html)

            // Fallback to mbasic.facebook.com if www didn't expose the stream directly
            if (streamUrl == null && !cleanUrl.contains("mbasic.facebook.com", ignoreCase = true)) {
                val mbasicUrl = cleanUrl
                    .replace(Regex("://(www\\.|m\\.|web\\.)?facebook\\.com", RegexOption.IGNORE_CASE), "://mbasic.facebook.com")
                if (mbasicUrl != cleanUrl) {
                    val mbasicHtml = try { fetchFacebookHtml(mbasicUrl) } catch (_: Exception) { "" }
                    if (mbasicHtml.isNotEmpty()) {
                        streamUrl = extractFacebookStreamUrl(mbasicHtml)
                    }
                }
            }

            if (streamUrl.isNullOrBlank()) {
                return Result.failure(Exception("Could not extract public Facebook video (may be private or unavailable)"))
            }

            val title = extractFacebookTitle(html)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "fb" }

            val fileName = "${title}_${System.currentTimeMillis()}.mp4"
            val outputFile = File(sageDir, fileName)

            downloadFile(streamUrl, outputFile)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFacebookHtml(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Facebook returned HTTP ${response.code}")
        }
        return response.body?.string() ?: ""
    }

    internal fun extractFacebookStreamUrl(html: String): String? {
        if (html.isBlank()) return null

        val jsonKeys = listOf(
            "browser_native_hd_url",
            "playable_url_quality_hd",
            "hd_src_no_ratelimit",
            "hd_src",
            "browser_native_sd_url",
            "playable_url",
            "sd_src_no_ratelimit",
            "sd_src"
        )
        for (key in jsonKeys) {
            val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
            val match = regex.find(html)
            if (match != null) {
                val decoded = unescapeJsonString(match.groupValues[1])
                if (decoded.startsWith("http")) return decoded
            }
        }

        // OpenGraph og:video fallback
        val ogRegex = Regex("""<meta[^>]+property=["']og:video(?::url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        ogRegex.find(html)?.let { match ->
            val decoded = match.groupValues[1].replace("&amp;", "&")
            if (decoded.startsWith("http")) return decoded
        }

        // mbasic video_redirect fallback
        val redirectRegex = Regex("""/video_redirect/\?src=([^"&]+)""")
        redirectRegex.find(html)?.let { match ->
            val decoded = try {
                URLDecoder.decode(match.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                match.groupValues[1]
            }
            if (decoded.startsWith("http")) return decoded
        }

        return null
    }

    private fun extractFacebookTitle(html: String): String {
        val ogTitle = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        if (!ogTitle.isNullOrBlank()) return ogTitle
        val titleTag = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        return titleTag?.replace(" | Facebook", "")?.trim() ?: ""
    }

    private fun unescapeJsonString(raw: String): String {
        return try {
            (JSONTokener("\"$raw\"").nextValue() as? String) ?: raw.replace("\\/", "/")
        } catch (_: Exception) {
            raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        }
    }

    private fun downloadFile(downloadUrl: String, outputFile: File) {
        val request = okhttp3.Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Download stream failed HTTP ${response.code}")
        }
        val body = response.body ?: throw Exception("Empty stream response body")
        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
