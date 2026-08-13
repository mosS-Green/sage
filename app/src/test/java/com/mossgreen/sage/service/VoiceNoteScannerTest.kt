package com.mossgreen.sage.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceNoteScannerTest {

    @Test
    fun parseDurationToSeconds_validFormats() {
        assertEquals(83L, VoiceNoteScanner.parseDurationToSeconds("01:23"))
        assertEquals(45L, VoiceNoteScanner.parseDurationToSeconds("0:45"))
        assertEquals(600L, VoiceNoteScanner.parseDurationToSeconds("10:00"))
        assertEquals(0L, VoiceNoteScanner.parseDurationToSeconds("00:00"))
    }

    @Test
    fun parseDurationToSeconds_invalidFormats() {
        assertNull(VoiceNoteScanner.parseDurationToSeconds("invalid"))
        assertNull(VoiceNoteScanner.parseDurationToSeconds("1:2:3"))
        assertNull(VoiceNoteScanner.parseDurationToSeconds("ab:cd"))
        assertNull(VoiceNoteScanner.parseDurationToSeconds(""))
    }

    @Test
    fun extractDurationSeconds_tests() {
        assertEquals(83L, VoiceNoteScanner.extractDurationSeconds("01:23?tr"))
        assertEquals(83L, VoiceNoteScanner.extractDurationSeconds("transcribe 01:23 ?tr"))
        assertEquals(45L, VoiceNoteScanner.extractDurationSeconds("?tr 0:45"))
        assertNull(VoiceNoteScanner.extractDurationSeconds("no duration ?tr"))
    }
}
