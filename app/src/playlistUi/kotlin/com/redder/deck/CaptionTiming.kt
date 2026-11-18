package com.redder.deck

/**
 * Which sentence is being spoken at a moment of a chunk's audio.
 * `server/testdata/caption_timing.json` holds it to the vector. Lengths are UTF-16 code units.
 */
object CaptionTiming {
    /** Silence the trimmer leaves before the voice ([com.redder.tts.SilenceTrimmer]). */
    private const val LEAD_IN_MS = 40L

    /** Silence it leaves after the voice, before the chunk's own pause. */
    private const val TRAILING_GUARD_MS = 50L
    private const val ENDERS = ".!?…"
    private const val CLOSERS = "\"')]*_”’"
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "vs", "etc", "eg", "ie", "jr", "sr", "prof", "no")

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' '

    /** "Dr." and "J." do not end a sentence, and neither does the last dot of "e.g.". */
    private fun isAbbreviation(
        text: String,
        dot: Int,
    ): Boolean {
        var start = dot
        while (start > 0 && !isSpace(text[start - 1])) start--
        val word = text.substring(start, dot).lowercase().dropWhile { it !in 'a'..'z' }
        return word.replace(".", "") in ABBREVIATIONS || word.length == 1
    }

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0

        fun cut(end: Int) {
            val line = text.substring(start, end).trim()
            if (line.isNotEmpty()) out.add(line)
            start = end
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                cut(i + 1)
            } else if (c in ENDERS) {
                var end = i + 1
                while (end < text.length && text[end] in ENDERS) end++
                val run = end - i
                while (end < text.length && text[end] in CLOSERS) end++
                val single = run == 1 && c == '.'
                if ((end == text.length || isSpace(text[end])) && !(single && isAbbreviation(text, i))) cut(end)
                i = end - 1
            }
            i++
        }
        cut(text.length)
        return out
    }

    /** Each sentence's start, by its share of the characters of the part of the audio that has voice in it. */
    fun starts(
        lines: List<String>,
        durationMs: Long,
        pauseMs: Long,
    ): List<Long> {
        val total = lines.sumOf { it.length.toLong() }
        val trail = TRAILING_GUARD_MS + pauseMs
        val span = (durationMs - LEAD_IN_MS - trail).coerceAtLeast(0L)
        val lead = if (durationMs > LEAD_IN_MS + trail) LEAD_IN_MS else 0L
        var before = 0L
        return lines.map { line ->
            val at = lead + if (total > 0L) span * before / total else 0L
            before += line.length
            at
        }
    }

    fun lineAt(
        starts: List<Long>,
        ms: Long,
    ): Int {
        var found = 0
        for (i in starts.indices) {
            if (starts[i] > ms) break
            found = i
        }
        return found
    }
}
