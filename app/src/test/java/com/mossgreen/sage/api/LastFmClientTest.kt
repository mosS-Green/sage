package com.mossgreen.sage.api

import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmClientTest {

    @Test
    fun formatTimeAgo_variousIntervals() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("", LastFmClient.formatTimeAgo(0))
        assertEquals("just now", LastFmClient.formatTimeAgo(now - 10))
        assertEquals("5 minutes ago", LastFmClient.formatTimeAgo(now - 300))
        assertEquals("2 hours ago", LastFmClient.formatTimeAgo(now - 7200))
        assertEquals("3 days ago", LastFmClient.formatTimeAgo(now - 259200))
    }

    @Test
    fun formatOutput_nowPlaying() {
        val track = LastFmTrackInfo(
            trackName = "Bohemian Rhapsody",
            artistName = "Queen",
            isNowPlaying = true,
            playCount = 42,
            timeAgo = ""
        )

        val output = LastFmClient.formatOutput("Leaf", "listening", track)
        val expected = "Leaf is listening to\n> *Bohemian Rhapsody* by _Queen_\n♫ 42 plays"
        assertEquals(expected, output)
    }

    @Test
    fun formatOutput_wasPlaying_singlePlay() {
        val track = LastFmTrackInfo(
            trackName = "Cruel Summer",
            artistName = "Taylor Swift",
            isNowPlaying = false,
            playCount = 1,
            timeAgo = "15 minutes ago"
        )

        val output = LastFmClient.formatOutput("Leaf", "listening", track)
        val expected = "Leaf was last listening to (15 minutes ago)\n> *Cruel Summer* by _Taylor Swift_\n♫ 1 play"
        assertEquals(expected, output)
    }

    @Test
    fun formatOutput_customVerb_vibing() {
        val track = LastFmTrackInfo(
            trackName = "Starboy",
            artistName = "The Weeknd",
            isNowPlaying = true,
            playCount = 10,
            timeAgo = ""
        )

        val output = LastFmClient.formatOutput("Alice", "vibing", track)
        val expected = "Alice is vibing to\n> *Starboy* by _The Weeknd_\n♫ 10 plays"
        assertEquals(expected, output)
    }

    @Test
    fun formatOutput_firstPersonI() {
        val track = LastFmTrackInfo(
            trackName = "Blinding Lights",
            artistName = "The Weeknd",
            isNowPlaying = true,
            playCount = 5,
            timeAgo = ""
        )

        val output = LastFmClient.formatOutput("I", "listening", track)
        val expected = "I am listening to\n> *Blinding Lights* by _The Weeknd_\n♫ 5 plays"
        assertEquals(expected, output)
    }
}
