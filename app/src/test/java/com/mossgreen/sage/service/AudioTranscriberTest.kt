package com.mossgreen.sage.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTranscriberTest {

    @Test
    fun formatParagraphs_prefixesLinesWithGreaterSymbol() {
        val input = "Hello world\nThis is a test voice note.\n\nFinal paragraph."
        val expected = "> Hello world\n> This is a test voice note.\n> \n> Final paragraph."
        val actual = AudioTranscriber.formatParagraphs(input)
        assertEquals(expected, actual)
    }

    @Test
    fun formatParagraphs_singleLine() {
        val input = "Single paragraph transcription."
        val expected = "> Single paragraph transcription."
        val actual = AudioTranscriber.formatParagraphs(input)
        assertEquals(expected, actual)
    }
}
