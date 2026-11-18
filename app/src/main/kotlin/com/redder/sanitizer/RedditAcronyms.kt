package com.redder.sanitizer

/**
 * Reddit acronyms said in full before any model sees the text, so "OOP" is read as "original poster"
 * and never guessed at. Matches whole words only; a whitespace-delimited token holding "/", "_", "@", a URL or a
 * dotted name (u/ and r/ names, links, code) is never touched. Entries are data: the conditions below
 * are the only logic, and an acronym too ambiguous for them is left out (see ADR-005).
 */
internal object RedditAcronyms {
    /**
     * @property spoken What is read instead, lower case unless a proper name; capitalised at a sentence start.
     * @property ignoreCase Also matches "nta" and "Nta"; otherwise only the exact capitals.
     * @property after The word before must be one of these (lower case).
     * @property notAfter The word before must not be one of these.
     * @property notBefore The word after must not be one of these.
     * @property unlessText Left alone when the same text mentions any of these words.
     * @property notShouted Left alone next to another all-capitals word, as in "SO HARD".
     * @property colon Only in the "ETA:" form.
     * @property opening Only as the first word of the text, as a judgement ("INFO: did you...").
     */
    class Entry(
        val key: String,
        val spoken: String,
        val ignoreCase: Boolean = false,
        val after: Set<String>? = null,
        val notAfter: Set<String> = emptySet(),
        val notBefore: Set<String> = emptySet(),
        val unlessText: Regex? = null,
        val notShouted: Boolean = false,
        val colon: Boolean = false,
        val opening: Boolean = false,
    )

    private val DETERMINERS = setOf("my", "your", "his", "her", "our", "their", "the", "other", "op's", "oop's")
    private val FIRST_PERSON = setOf("my", "our")
    private val PLACE_WORDS =
        setOf("in", "from", "to", "near", "at", "across", "around", "outside", "through", "of", "visit", "visiting", "rural", "coastal")
    private val INTENSIFIED =
        setOf(
            "much", "many", "bad", "badly", "hard", "good", "well", "far", "long", "often", "little", "fast", "happy", "sad",
            "angry", "mad", "excited", "proud", "sorry", "glad", "done", "cute", "funny", "sweet", "tired", "close", "lucky",
        )
    private val PROGRAMMING =
        Regex("""(?i)\b(programming|programmer|code|coding|class|classes|inheritance|java|python|javascript|developer)\b""")
    private val GAMING = Regex("""(?i)\b(nintendo|3ds|game|games|gaming|console|pokemon)\b""")
    private val MEDIA_PLAYER = Regex("""(?i)\b(player|media|video|videos|software|app|download|install)\b""")
    private val BUILDING = Regex("""(?i)\b(contractor|contractors|construction|renovation|remodel|permit|builder|garbage)\b""")

    val ENTRIES: List<Entry> =
        listOf(
            Entry("OP", "original poster"),
            Entry("OOP", "original poster", unlessText = PROGRAMMING),
            Entry("BORU", "Best of Redditor Updates"),
            Entry("AITA", "am I the asshole", ignoreCase = true),
            Entry("AITAH", "am I the asshole", ignoreCase = true),
            Entry("WIBTA", "would I be the asshole", ignoreCase = true),
            Entry("NTA", "not the asshole", ignoreCase = true),
            Entry("YTA", "you're the asshole", ignoreCase = true),
            Entry("ESH", "everyone sucks here"),
            Entry("NAH", "no assholes here"),
            Entry("INFO", "more information needed", opening = true),
            Entry("TIFU", "today I fucked up", ignoreCase = true),
            Entry("TL;DR", "too long; didn't read", ignoreCase = true),
            Entry("TLDR", "too long; didn't read", ignoreCase = true),
            Entry("SO", "significant other", after = DETERMINERS, notBefore = INTENSIFIED, notShouted = true),
            Entry("DSO", "dear significant other"),
            Entry("DH", "dear husband", notShouted = true),
            Entry("DW", "dear wife", notShouted = true),
            Entry("DS", "dear son", after = FIRST_PERSON, unlessText = GAMING),
            Entry("DD", "dear daughter", after = FIRST_PERSON),
            Entry("DF", "dear fiancé", after = FIRST_PERSON),
            Entry("MIL", "mother-in-law"),
            Entry("FIL", "father-in-law"),
            Entry("SIL", "sister-in-law"),
            Entry("BIL", "brother-in-law"),
            Entry("SMIL", "stepmother-in-law"),
            Entry("SFIL", "stepfather-in-law"),
            Entry("JNMIL", "just-no mother-in-law"),
            Entry("LO", "little one", notShouted = true),
            Entry("NC", "no contact", notAfter = PLACE_WORDS, notShouted = true),
            Entry("LC", "low contact", notShouted = true),
            Entry("VLC", "very low contact", unlessText = MEDIA_PLAYER),
            Entry("GC", "group chat", unlessText = BUILDING, notShouted = true),
            Entry("BF", "boyfriend", ignoreCase = true, notShouted = true),
            Entry("GF", "girlfriend", ignoreCase = true, notShouted = true),
            Entry("IIRC", "if I remember correctly", ignoreCase = true),
            Entry("AFAIK", "as far as I know", ignoreCase = true),
            Entry("IMHO", "in my humble opinion", ignoreCase = true),
            Entry("IMO", "in my opinion", ignoreCase = true),
            Entry("TBH", "to be honest", ignoreCase = true),
            Entry("ETA", "edited to add", colon = true),
        )

    private val EXACT: Map<String, List<Entry>> = ENTRIES.filter { !it.ignoreCase }.groupBy { it.key }
    private val FOLDED: Map<String, List<Entry>> = ENTRIES.filter { it.ignoreCase }.groupBy { it.key.lowercase() }

    /** The full forms, for the check that the model did not undo them. */
    val SPOKEN: List<String> = ENTRIES.map { it.spoken }.distinct()

    private val CANDIDATE = Regex("""(?<![\w-])[A-Za-z]+(?:;[A-Za-z]+)?(?![\w]|-\w)""")
    private val PROTECTED_TOKEN = Regex("""://|www\.|[/_@#=<>{}\\|]|\(\)|\w\.\w""")
    private val WORD_BEFORE = Regex("""([A-Za-z']+)\s*$""")
    private val WORD_AFTER = Regex("""^\s*([A-Za-z']+)""")
    private val ASKED_ABOUT = Regex("""^\s+(?:stand|stands|mean|means)\b""")
    private val OPENING_END = Regex("""^(?:\s*[:.,!\-–—]|\s*$)""")
    private val LOWER_WORD_NEXT = Regex("""^\s+[a-z]""")
    private const val SPACE = " \t\n\r\u000B\u000C"

    fun expand(text: String): String =
        CANDIDATE.replace(text) { m ->
            val word = m.value
            val entries = EXACT[word] ?: FOLDED[word.lowercase()]
            val entry = entries?.firstOrNull { applies(it, text, m.range.first, m.range.last + 1) }
            if (entry == null) word else spoken(entry, text, m.range.first, m.range.last + 1)
        }

    private fun applies(
        e: Entry,
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val before = text.substring(0, start)
        val rest = text.substring(end)
        val wordBefore = WORD_BEFORE.find(before)?.groupValues?.get(1)
        val wordAfter = WORD_AFTER.find(rest)?.groupValues?.get(1)
        return when {
            isProtected(text, start, end) -> false
            ASKED_ABOUT.containsMatchIn(rest) -> false
            e.colon && !rest.startsWith(":") -> false
            e.opening && (before.isNotBlank() || !(OPENING_END.containsMatchIn(rest) || LOWER_WORD_NEXT.containsMatchIn(rest))) -> false
            e.after != null && (wordBefore == null || wordBefore.lowercase() !in e.after) -> false
            wordBefore != null && wordBefore.lowercase() in e.notAfter -> false
            wordAfter != null && wordAfter.lowercase() in e.notBefore -> false
            e.unlessText?.containsMatchIn(text) == true -> false
            e.notShouted && (isShouted(wordBefore) || isShouted(wordAfter)) -> false
            else -> true
        }
    }

    private fun isProtected(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        var from = start
        while (from > 0 && text[from - 1] !in SPACE) from--
        var to = end
        while (to < text.length && text[to] !in SPACE) to++
        return PROTECTED_TOKEN.containsMatchIn(text.substring(from, to))
    }

    /** All capitals and not an acronym from the list: the author is shouting, as in "SO HARD". */
    private fun isShouted(word: String?): Boolean =
        word != null && word.length >= 2 && word.all { it in 'A'..'Z' || it == '\'' } && word !in EXACT

    private fun spoken(
        e: Entry,
        text: String,
        start: Int,
        end: Int,
    ): String {
        val spoken = if (e.opening && LOWER_WORD_NEXT.containsMatchIn(text.substring(end))) "${e.spoken}," else e.spoken
        var i = start - 1
        while (i >= 0 && text[i] in SPACE && text[i] != '\n') i--
        val sentenceStart = i < 0 || text[i] in ".!?\n"
        return if (sentenceStart) spoken.replaceFirstChar { it.uppercaseChar() } else spoken
    }
}
