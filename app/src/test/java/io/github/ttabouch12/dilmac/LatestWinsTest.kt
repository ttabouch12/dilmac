package io.github.ttabouch12.dilmac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsTest {
    @Test
    fun staleTranslationIsDropped() {
        val o = LatestWins()
        val partial = o.next()
        val final = o.next()
        assertTrue(o.accept(1, final))
        assertFalse(o.accept(1, partial)) // arrived late, must not overwrite the final text
    }

    @Test
    fun linesAreIndependent() {
        val o = LatestWins()
        val a = o.next()
        val b = o.next()
        assertTrue(o.accept(2, b))
        assertTrue(o.accept(1, a))
    }
}
