package io.github.ttabouch12.dilmac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test
    fun sentenceEnds() {
        assertTrue(TextRules.endsSentence("Bu otobüs Kadıköy'e gidiyor mu?"))
        assertFalse(TextRules.endsSentence("Ben yarın"))
    }

    @Test
    fun repeatedSessionTextIsStripped() {
        assertEquals("okula gideceğim", TextRules.stripRepeat("Ben yarın okula gideceğim", "ben yarın"))
        assertEquals("başka bir şey", TextRules.stripRepeat("başka bir şey", "ben yarın"))
    }

    @Test
    fun stableWords() {
        val a = TextRules.words("bu akşam sinemaya gidelim mi")
        val b = TextRules.words("bu akşam sinemaya gidelim mi yoksa")
        assertEquals(5, TextRules.stablePrefix(a, b))
    }

    @Test
    fun chunksCutAtCommasOrWhenLong() {
        val w = TextRules.words("dün akşam eve geldiğimde annem yemek yapmıştı, babam da televizyon izliyordu ve")
        assertEquals(7, TextRules.chunkEnd(w, sent = 0, stable = 12)) // after "yapmıştı,"
        assertEquals(0, TextRules.chunkEnd(w, sent = 0, stable = 6)) // too short yet
        val noComma = TextRules.words("a b c d e f g h i j k l m n o p")
        assertEquals(14, TextRules.chunkEnd(noComma, sent = 0, stable = 16))
    }

    @Test
    fun ownVoiceIsRecognised() {
        assertTrue(TextRules.isEcho("Bu otobüs Kadıköy'e gidiyor mu", listOf("Bu otobüs Kadıköy'e gidiyor mu?")))
        assertFalse(TextRules.isEcho("Hayır on dört numaraya binin", listOf("Bu otobüs Kadıköy'e gidiyor mu?")))
    }
}
