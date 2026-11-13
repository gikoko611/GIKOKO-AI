package com.ikoko.gikokoai

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptParserTest {
    @Test fun parsesTranscriptionText() {
        assertEquals("Hello world", TranscriptParser.parse("""{"text":"Hello world"}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEmptyTranscription() {
        TranscriptParser.parse("""{"text":""}""")
    }
}
