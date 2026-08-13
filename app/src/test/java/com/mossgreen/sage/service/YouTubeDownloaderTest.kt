package com.mossgreen.sage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDownloaderTest {

    @Test
    fun extractYouTubeUrl_standardVideoUrl() {
        val text = "Check out this video https://www.youtube.com/watch?v=dQw4w9WgXcQ ?yt"
        val extracted = YouTubeDownloader.extractYouTubeUrl(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun extractYouTubeUrl_shortenedUrl() {
        val text = "https://youtu.be/dQw4w9WgXcQ ?yt"
        val extracted = YouTubeDownloader.extractYouTubeUrl(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun extractYouTubeUrl_musicUrl() {
        val text = "Song: https://music.youtube.com/watch?v=dQw4w9WgXcQ ?yt"
        val extracted = YouTubeDownloader.extractYouTubeUrl(text)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun extractYouTubeUrl_noMatch() {
        val text = "Check out https://example.com/watch?v=12345 ?yt"
        val extracted = YouTubeDownloader.extractYouTubeUrl(text)
        assertNull(extracted)
    }

    @Test
    fun isAudioOnlyUrl_detection() {
        assertTrue(YouTubeDownloader.isAudioOnlyUrl("https://music.youtube.com/watch?v=12345"))
        assertFalse(YouTubeDownloader.isAudioOnlyUrl("https://www.youtube.com/watch?v=12345"))
        assertFalse(YouTubeDownloader.isAudioOnlyUrl("https://youtu.be/12345"))
    }
}
