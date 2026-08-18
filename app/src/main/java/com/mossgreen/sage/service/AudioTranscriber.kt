package com.mossgreen.sage.service

import android.util.Base64
import com.mossgreen.sage.api.ApiClientUtils
import com.mossgreen.sage.manager.KeyManager
import com.mossgreen.sage.model.GeminiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AudioTranscriber {

    fun formatParagraphs(text: String): String {
        return text.trim()
            .split("\n")
            .joinToString("\n") { line ->
                if (line.isBlank()) "> " else "> $line"
            }
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "opus", "ogg" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            "3gp" -> "audio/3gpp"
            else -> "audio/ogg"
        }
    }

    suspend fun transcribe(audioFile: File, keyManager: KeyManager): Result<String> = withContext(Dispatchers.IO) {
        val keys = keyManager.getKeys()
        if (keys.isEmpty()) {
            return@withContext Result.failure(Exception("No API keys configured"))
        }

        val key = keyManager.getNextKey()
            ?: return@withContext Result.failure(Exception("All API keys are benched or invalid"))

        val fileBytes = try {
            audioFile.readBytes()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Could not read audio file: ${e.message}"))
        }

        val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
        val mimeType = getMimeType(audioFile)
        val model = GeminiModels.DEFAULT

        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", mimeType)
                                    put("data", base64Data)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", "Transcribe this audio exactly as spoken. Do not translate it. The speech may be Hindi, English, Hinglish, or a mixture of Hindi and English. Write Hindi and other non-English speech using Latin/English characters (Romanized script), not Devanagari or other native scripts. Preserve English words as English. Do not paraphrase, summarize, correct wording, or add explanations. Return only the exact transcription.")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingLevel", "minimal")
                    })
                })
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseStr = ApiClientUtils.readResponseBounded(connection)
                val responseJson = JSONObject(responseStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            val formatted = formatParagraphs(text)
                            return@withContext Result.success(formatted)
                        }
                    }
                }
                Result.failure(Exception("Model returned empty transcription"))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                if (responseCode == 429) {
                    keyManager.reportRateLimit(key)
                }
                Result.failure(Exception("HTTP $responseCode: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
