package io.github.ttabouch12.dilmac

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiLiveTest {
    @Test
    fun setupCarriesTargetAndTranscription() {
        val gc = JSONObject(GeminiLive.setupJson("en")).getJSONObject("setup").getJSONObject("generationConfig")
        assertEquals("en", gc.getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertTrue(gc.has("inputAudioTranscription") && gc.has("outputAudioTranscription"))
    }

    @Test
    fun audioIsTrimmedToLength() {
        val sent = JSONObject(GeminiLive.audioJson(byteArrayOf(1, 2, 3, 4), 2))
            .getJSONObject("realtimeInput").getJSONObject("audio")
        assertArrayEquals(byteArrayOf(1, 2), Base64.getDecoder().decode(sent.getString("data")))
    }

    @Test
    fun parsesTranscriptsAndAudio() {
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(9, 8))
        val e = GeminiLive.parse(
            """{"serverContent":{"inputTranscription":{"text":"merhaba"},"outputTranscription":{"text":"hello"},
               "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}"""
        )
        assertEquals("merhaba", e.inputText)
        assertEquals("hello", e.outputText)
        assertArrayEquals(byteArrayOf(9, 8), e.audio.single())
        assertNull(e.error)
    }

    @Test
    fun parsesSetupCompleteAndErrors() {
        assertTrue(GeminiLive.parse("""{"setupComplete":{}}""").setupComplete)
        assertEquals("quota", GeminiLive.parse("""{"error":{"message":"quota"}}""").error)
    }
}
