package io.github.ttabouch12.dilmac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersAndLanguagesTest {
    @Test
    fun similarVoicesShareASpeaker() {
        val t = SpeakerTracker(join = 0.8f, split = 0.5f)
        assertEquals(1, t.assign(floatArrayOf(1f, 0f, 0f)).first)
        assertEquals(2, t.assign(floatArrayOf(0f, 1f, 0f)).first)
        assertEquals(1, t.assign(floatArrayOf(0.95f, 0.1f, 0f)).first)
        assertEquals(2, t.assign(floatArrayOf(0.1f, 0.9f, 0.05f)).first)
        assertEquals(3, t.assign(floatArrayOf(0f, 0f, 1f)).first)
    }

    @Test
    fun unclearVoiceDoesNotBecomeANewPerson() {
        val t = SpeakerTracker(join = 0.8f, split = 0.3f)
        t.assign(floatArrayOf(1f, 0f))
        assertEquals(1, t.assign(floatArrayOf(0.6f, 0.8f)).first) // similarity 0.6: between split and join
        assertEquals(2, t.assign(floatArrayOf(0f, 1f), canBeNew = true).first)
    }

    @Test
    fun capsSpeakerCount() {
        val t = SpeakerTracker(join = 0.99f, split = 0.99f, maxSpeakers = 2)
        t.assign(floatArrayOf(1f, 0f))
        t.assign(floatArrayOf(0f, 1f))
        assertTrue(t.assign(floatArrayOf(0.7f, 0.7f)).first <= 2)
    }

    @Test
    fun silenceIsDroppedBeforeFingerprinting() {
        val frame = MicFeed.RATE * 3 / 100
        val audio = FloatArray(frame * 10) // 10 frames: only frames 2 and 7 are loud
        for (i in 2 * frame until 3 * frame) audio[i] = 0.5f
        for (i in 7 * frame until 8 * frame) audio[i] = -0.4f
        assertEquals(frame * 2, MicFeed.voiceOnly(audio).size)
        assertEquals(0, MicFeed.voiceOnly(FloatArray(frame * 5)).size) // pure silence: nothing left
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
