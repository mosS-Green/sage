package com.mossgreen.sage.service

import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File

object VoiceNoteScanner {

    private val AUDIO_EXTENSIONS = setOf("opus", "m4a", "aac", "mp3", "ogg", "wav", "3gp")

    private fun getWhatsAppVoiceNotePaths(): List<File> = try {
        val root = Environment.getExternalStorageDirectory()
        listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes")
        )
    } catch (_: Exception) {
        emptyList()
    }

    fun parseDurationToSeconds(durationStr: String): Long? {
        val parts = durationStr.trim().split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        return minutes * 60 + seconds
    }

    /**
     * Scans the 5 latest voice note audio files and returns the one closest to [targetDurationSec].
     */
    fun findClosestVoiceNote(targetDurationSec: Long): File? {
        val allAudioFiles = mutableListOf<File>()

        for (baseDir in getWhatsAppVoiceNotePaths()) {
            if (baseDir.exists() && baseDir.isDirectory) {
                collectAudioFiles(baseDir, allAudioFiles)
            }
        }

        if (allAudioFiles.isEmpty()) return null

        // Pick 5 latest files by lastModified
        val latest5Files = allAudioFiles.sortedByDescending { it.lastModified() }.take(5)

        var closestFile: File? = null
        var minDiff = Long.MAX_VALUE

        for (file in latest5Files) {
            val durationSec = getAudioDurationSeconds(file)
            val diff = Math.abs(durationSec - targetDurationSec)
            if (diff < minDiff) {
                minDiff = diff
                closestFile = file
            }
        }

        return closestFile
    }

    private fun collectAudioFiles(dir: File, result: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectAudioFiles(file, result)
            } else if (file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase())) {
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
