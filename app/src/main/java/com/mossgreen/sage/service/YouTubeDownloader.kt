package com.mossgreen.sage.service

import com.mossgreen.sage.manager.StoragePermissionManager
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.File
import java.io.FileOutputStream
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
     * Downloads YouTube video or audio directly using NewPipeExtractor and saves to Downloads/sage/.
     * Returns Result with downloaded filename or error.
     */
    fun download(ytUrl: String): Result<String> {
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

            val title = extractor.name?.replace(Regex("[^a-zA-Z0-9._-]"), "_")?.take(40)
                ?: "yt_${System.currentTimeMillis()}"

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
            Result.success(outputFile.name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFile(downloadUrl: String, outputFile: File) {
        val request = okhttp3.Request.Builder().url(downloadUrl).build()
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
