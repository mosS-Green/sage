package com.mossgreen.sage.service

import android.media.MediaMetadataRetriever
import android.os.Environment
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class DetectedVoiceNote(
    val file: File,
    val username: String,
    val chatName: String,
    val durationSeconds: Long,
    val formattedDuration: String,
    val formattedTimestamp: String,
    val detectedAt: Long = System.currentTimeMillis()
)

data class WhatsAppNotificationInfo(
    val username: String,
    val chatName: String,
    val timestamp: Long = System.currentTimeMillis()
)

class WhatsAppVoiceNoteDetector {

    companion object {
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf("opus", "ogg", "m4a", "mp3", "aac", "wav", "amr", "3gp")
        private const val NOTIFICATION_MATCH_WINDOW_MS = 20_000L
        private const val NOTIFICATION_RETENTION_MS = 60_000L
        private val VOICE_KEYWORDS = listOf("voice message", "audio message", "voice note", "ptt", "audio (", "🎤", "audio")
    }

    private val seenFiles = ConcurrentHashMap.newKeySet<String>()
    private var initialized = false
    private val notificationBuffer = ConcurrentLinkedQueue<WhatsAppNotificationInfo>()

    fun getWatchDirs(): List<File> {
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            return emptyList()
        }

        return listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Audio")
        )
    }

    /**
     * Called when an AccessibilityEvent with TYPE_NOTIFICATION_STATE_CHANGED arrives.
     */
    fun onNotificationEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val textList = event.text.mapNotNull { it?.toString() }
        val fullText = textList.joinToString(" ")
        val isVoice = VOICE_KEYWORDS.any { fullText.contains(it, ignoreCase = true) }
        if (!isVoice && textList.isEmpty()) return

        val (username, chatName) = extractSenderAndChat(textList)
        val now = System.currentTimeMillis()
        notificationBuffer.add(WhatsAppNotificationInfo(username, chatName, now))

        // Prune old notifications
        val cutoff = now - NOTIFICATION_RETENTION_MS
        notificationBuffer.removeIf { it.timestamp < cutoff }
    }

    /**
     * Extracts (Username, ChatName) from notification text lines.
     */
    fun extractSenderAndChat(lines: List<String>): Pair<String, String> {
        if (lines.isEmpty()) return Pair("Unknown", "WhatsApp")

        val title = lines.firstOrNull()?.trim() ?: "WhatsApp"
        val contentLines = if (lines.size > 1) lines.subList(1, lines.size) else lines

        for (line in contentLines.reversed()) {
            if (!line.contains(":")) continue
            val parts = line.split(":", limit = 2)
            val senderPart = parts[0].trim()
            val msgPart = parts[1].trim().lowercase()

            val isMsgVoice = VOICE_KEYWORDS.any { msgPart.contains(it) }
            val isSenderVoice = VOICE_KEYWORDS.any { senderPart.lowercase().contains(it) }

            if (senderPart.isNotEmpty() && isMsgVoice && !isSenderVoice) {
                if (title.isNotEmpty() && !title.equals(senderPart, ignoreCase = true)) {
                    // Group chat: sender + group title
                    return Pair(senderPart, title)
                }
                return Pair(senderPart, senderPart)
            }
        }

        return Pair(title, title)
    }

    /**
     * Scans watched directories for newly added audio files.
     */
    fun scanForNewVoiceNotes(): List<DetectedVoiceNote> {
        val newAudioFiles = mutableListOf<File>()
        val dirs = getWatchDirs()

        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                collectAudioFiles(dir, newAudioFiles)
            }
        }

        if (!initialized) {
            // First run: index existing files so we only alert on newly created files
            for (file in newAudioFiles) {
                seenFiles.add(file.absolutePath)
            }
            initialized = true
            return emptyList()
        }

        val newlyDiscovered = mutableListOf<DetectedVoiceNote>()
        val now = System.currentTimeMillis()

        for (file in newAudioFiles) {
            val path = file.absolutePath
            if (!seenFiles.contains(path)) {
                seenFiles.add(path)

                // Only consider files modified within the last 2 minutes
                val ageMs = now - file.lastModified()
                if (ageMs < 120_000L) {
                    val durationSec = getAudioDurationSeconds(file)
                    val (user, chat) = matchNotification(now)
                    val formattedDuration = String.format(Locale.US, "%02d:%02d", durationSec / 60, durationSec % 60)
                    val formattedTimestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified().takeIf { it > 0 } ?: now))

                    newlyDiscovered.add(
                        DetectedVoiceNote(
                            file = file,
                            username = user,
                            chatName = chat,
                            durationSeconds = durationSec,
                            formattedDuration = formattedDuration,
                            formattedTimestamp = formattedTimestamp,
                            detectedAt = now
                        )
                    )
                }
            }
        }

        return newlyDiscovered
    }

    private fun matchNotification(detectedAt: Long): Pair<String, String> {
        val cutoff = detectedAt - NOTIFICATION_MATCH_WINDOW_MS
        val candidate = notificationBuffer
            .filter { it.timestamp >= cutoff }
            .minByOrNull { Math.abs(detectedAt - it.timestamp) }

        return if (candidate != null) {
            Pair(candidate.username, candidate.chatName)
        } else {
            val last = notificationBuffer.lastOrNull()
            if (last != null && detectedAt - last.timestamp < NOTIFICATION_RETENTION_MS) {
                Pair(last.username, last.chatName)
            } else {
                Pair("Unknown", "WhatsApp")
            }
        }
    }

    private fun collectAudioFiles(dir: File, result: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectAudioFiles(file, result)
            } else if (file.isFile && file.length() > 0 && SUPPORTED_AUDIO_EXTENSIONS.contains(file.extension.lowercase())) {
                result.add(file)
            }
        }
    }

    private fun getAudioDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (durationMsStr?.toLongOrNull() ?: 0L) / 1000L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}
