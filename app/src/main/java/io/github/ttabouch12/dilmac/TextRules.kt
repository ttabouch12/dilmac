package io.github.ttabouch12.dilmac

/** Small pure text rules the engine uses to decide where sentences start and stop. */
object TextRules {
    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** A line ending in . ? ! … is complete; anything else may be a hesitation that continues. */
    fun endsSentence(text: String) = text.trimEnd().lastOrNull()?.let { it in ".?!…؟。" } ?: false

    /**
     * Some recognisers repeat earlier segments of the same session at the start of new results.
     * Removes [said] from the front of [text] if it is there (case and punctuation ignored).
     */
    fun stripRepeat(text: String, said: String): String {
        val a = words(said)
        val b = words(text)
        if (a.isEmpty() || b.size < a.size) return text
        for (i in a.indices) if (norm(a[i]) != norm(b[i])) return text
        return b.drop(a.size).joinToString(" ")
    }

    /** Words both partial results agree on, from the start: these are unlikely to change any more. */
    fun stablePrefix(prev: List<String>, now: List<String>): Int {
        var i = 0
        while (i < prev.size && i < now.size && norm(prev[i]) == norm(now[i])) i++
        return i
    }

    /**
     * Where to cut a long sentence that is still being spoken, so its first part can be translated
     * and read out while listening goes on. Cuts after a comma when there is one, otherwise only
     * once [hard] stable words have piled up. Returns the number of words to send, or 0 to wait.
     */
    fun chunkEnd(words: List<String>, sent: Int, stable: Int, soft: Int = 8, hard: Int = 14): Int {
        if (stable - sent < soft) return 0
        // Last comma that still leaves a chunk of at least 4 words.
        for (i in stable - 1 downTo sent + 3) if (words[i].endsWith(",") || words[i].endsWith(";")) return i + 1
        return if (stable - sent >= hard) stable - 2 else 0 // keep the last two words back: they may still change
    }

    /** True if [heard] looks like something we just said out loud ourselves (the mic caught our speaker). */
    fun isEcho(heard: String, spoken: List<String>): Boolean {
        val h = words(heard).map(::norm).toSet()
        if (h.size < 2) return false
        return spoken.any { s ->
            val w = words(s).map(::norm).toSet()
            w.isNotEmpty() && h.count { it in w }.toFloat() / h.size >= 0.6f
        }
    }

    private fun norm(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }
}
