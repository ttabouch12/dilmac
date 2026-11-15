package io.github.ttabouch12.dilmac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersAndLanguagesTest {
    @Test
    fun similarVoicesShareASpeaker() {
        val t = SpeakerTracker(threshold = 0.8f)
        assertEquals(1, t.assign(floatArrayOf(1f, 0f, 0f)).first)
        assertEquals(2, t.assign(floatArrayOf(0f, 1f, 0f)).first)
        assertEquals(1, t.assign(floatArrayOf(0.95f, 0.1f, 0f)).first)
        assertEquals(2, t.assign(floatArrayOf(0.1f, 0.9f, 0.05f)).first)
        assertEquals(3, t.assign(floatArrayOf(0f, 0f, 1f)).first)
    }

    @Test
    fun capsSpeakerCount() {
        val t = SpeakerTracker(threshold = 0.99f, maxSpeakers = 2)
        t.assign(floatArrayOf(1f, 0f))
        t.assign(floatArrayOf(0f, 1f))
        assertTrue(t.assign(floatArrayOf(0.7f, 0.7f)).first <= 2)
    }

    @Test
    fun languagesNeedTranslationAndSpeech() {
        val langs = Languages.build(
            mlKit = setOf("en", "tr", "he", "ar"),
            installed = listOf("tr-TR", "en-US"),
            supported = listOf("en-GB", "en-US", "iw-IL", "xx-YY"),
        )
        assertEquals(listOf("en", "he", "tr"), langs.map { it.code }.sorted())
        assertEquals("en-US", langs.first { it.code == "en" }.tag) // installed variant wins
        assertTrue(langs.first { it.code == "tr" }.installed)
        assertTrue(!langs.first { it.code == "he" }.installed)
        assertEquals("Türkçe", langs.first { it.code == "tr" }.name)
    }
}
