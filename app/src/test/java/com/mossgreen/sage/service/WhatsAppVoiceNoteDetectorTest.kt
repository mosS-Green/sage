package com.mossgreen.sage.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppVoiceNoteDetectorTest {

    @Test
    fun extractSenderAndChat_directChat() {
        val detector = WhatsAppVoiceNoteDetector()
        val lines = listOf("Alice", "Alice: 🎤 Voice message (0:15)")
        val (sender, chat) = detector.extractSenderAndChat(lines)

        assertEquals("Alice", sender)
        assertEquals("Alice", chat)
    }

    @Test
    fun extractSenderAndChat_groupChat() {
        val detector = WhatsAppVoiceNoteDetector()
        val lines = listOf("Project Team", "Bob: 🎤 Voice message")
        val (sender, chat) = detector.extractSenderAndChat(lines)

        assertEquals("Bob", sender)
        assertEquals("Project Team", chat)
    }

    @Test
    fun extractSenderAndChat_emptyLines_fallback() {
        val detector = WhatsAppVoiceNoteDetector()
        val (sender, chat) = detector.extractSenderAndChat(emptyList())

        assertEquals("Unknown", sender)
        assertEquals("WhatsApp", chat)
    }

    @Test
    fun extractSenderAndChat_audioMessageKeyword() {
        val detector = WhatsAppVoiceNoteDetector()
        val lines = listOf("Family Group", "Mom: Audio message (1:20)")
        val (sender, chat) = detector.extractSenderAndChat(lines)

        assertEquals("Mom", sender)
        assertEquals("Family Group", chat)
    }
}
