package com.ikoko.gikokoai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDetectTest {
    @Test
    fun detectsImageTtsAndVision() {
        val caps = Api.detect(listOf("gpt-4o-mini", "dall-e-3", "tts-1", "whisper-1"))
        assertEquals("dall-e-3", caps.imageModel)
        assertEquals("tts-1", caps.ttsModel)
        assertTrue(caps.vision)
    }

    @Test
    fun textOnlyProviderAdvertisesNothingExtra() {
        val caps = Api.detect(listOf("llama-3-8b", "mistral-7b"))
        assertNull(caps.imageModel)
        assertNull(caps.ttsModel)
        assertEquals(false, caps.vision)
    }
}
