package com.redder.tts

import java.io.File
import java.text.Normalizer

/**
 * Result data class containing token IDs and binary text mask for Supertonic TTS inference.
 *
 * @property tokenIds Array of mapped token IDs.
 * @property textMask 3D binary float mask of shape [1, 1, seqLen] where each valid token is 1.0f.
 */
data class SupertonicProcessedText(
    val tokenIds: LongArray,
    val textMask: Array<Array<FloatArray>>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SupertonicProcessedText
        if (!tokenIds.contentEquals(other.tokenIds)) return false
        if (textMask.size != other.textMask.size) return false
        for (i in textMask.indices) {
            if (textMask[i].size != other.textMask[i].size) return false
            for (j in textMask[i].indices) {
                if (!textMask[i][j].contentEquals(other.textMask[i][j])) return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = tokenIds.contentHashCode()
        result = 31 * result + textMask.contentDeepHashCode()
        return result
    }
}

/**
 * Text preprocessing and tokenization engine for Supertonic 3 ONNX TTS.
 *
 * Handles whitespace and punctuation normalization, Unicode emoji stripping,
 * character-level vocabulary indexing, and generation of binary attention masks.
 */
class SupertonicTextProcessor(
    val vocab: Map<Char, Long> = DEFAULT_VOCAB,
    val indexer: IntArray? = null,
) : TokenMapper {
    /**
     * Checks if a Unicode code point falls within the emoji ranges stripped by upstream Supertonic.
     */
    fun isEmoji(codePoint: Int): Boolean {
        return when (codePoint) {
            in 0x1F600..0x1F64F -> true // Emoticons
            in 0x1F300..0x1F5FF -> true // Misc Symbols and Pictographs
            in 0x1F680..0x1F6FF -> true // Transport and Map
            in 0x1F700..0x1F77F -> true // Alchemical Symbols
            in 0x1F780..0x1F7FF -> true // Geometric Shapes Extended
            in 0x1F800..0x1F8FF -> true // Supplemental Arrows-C
            in 0x1F900..0x1F9FF -> true // Supplemental Symbols and Pictographs
            in 0x1FA00..0x1FA6F -> true // Chess Symbols
            in 0x1FA70..0x1FAFF -> true // Symbols and Pictographs Extended-A
            in 0x2600..0x26FF -> true // Misc Symbols
            in 0x2700..0x27BF -> true // Dingbats
            in 0x1F1E6..0x1F1FF -> true // Regional Indicator Symbols (Flags)
            else -> false
        }
    }

    /**
     * Strips all Unicode emojis from the input text.
     */
    fun filterEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!isEmoji(cp)) {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString()
    }

    /**
     * Normalizes text exactly as upstream Supertonic `UnicodeProcessor._preprocess_text` does (minus
     * the language tags): NFKD, emoji removal, symbol replacements, spacing fixes before punctuation,
     * duplicate-quote collapsing, whitespace collapsing and a final period when the text does not
     * already end in punctuation. App-specific clean-up belongs in the text sanitizer, not here, so
     * the model sees the same text distribution it was trained on.
     */
    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = filterEmojis(t)
        for ((from, to) in REPLACEMENTS) {
            t = t.replace(from, to)
        }
        t = SPECIAL_SYMBOLS_REGEX.replace(t, "")
        for ((from, to) in EXPRESSIONS) {
            t = t.replace(from, to)
        }
        for (punct in SPACE_BEFORE_PUNCTUATION) {
            t = t.replace(" $punct", "$punct")
        }
        for (dup in DUPLICATE_QUOTES) {
            while (dup in t) {
                t = t.replace(dup, dup.substring(1))
            }
        }
        t = WHITESPACE_REGEX.replace(t, " ").trim()
        if (t.isEmpty()) {
            return ""
        }
        return if (t.last() in END_PUNCTUATION) t else "$t."
    }

    /**
     * Maps the input text to a sequence of token IDs using the configured vocabulary.
     * Backwards-compatible single-argument overload using default language "en".
     */
    override fun mapTextToTokens(text: String): LongArray {
        return mapTextToTokens(text, DEFAULT_LANG)
    }

    /**
     * Maps the input text to a sequence of token IDs wrapped in `<$lang>...</$lang>`.
     */
    fun mapTextToTokens(
        text: String,
        lang: String,
    ): LongArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return LongArray(0)
        }
        val targetLang = if (lang.isBlank()) DEFAULT_LANG else lang
        val wrapped = "<$targetLang>$normalized</$targetLang>"
        if (indexer != null) {
            // Characters the model has no embedding for are dropped. Upstream would look up index -1
            // (or a wrapped 16-bit code point), which silently reads an unrelated embedding row.
            val tokens = ArrayList<Long>(wrapped.length)
            for (cp in wrapped.codePoints().toArray()) {
                if (cp in indexer.indices && indexer[cp] >= 0) {
                    tokens.add(indexer[cp].toLong())
                }
            }
            return tokens.toLongArray()
        }
        val tokens = LongArray(wrapped.length)
        for (i in wrapped.indices) {
            val ch = wrapped[i]
            val id =
                vocab[ch]
                    ?: vocab[ch.lowercaseChar()]
                    ?: UNK_TOKEN_ID
            tokens[i] = id
        }
        return tokens
    }

    /**
     * Generates a 3D binary float mask with shape [1, 1, seqLen] where each valid token is 1.0f.
     */
    fun createTextMask(seqLen: Int): Array<Array<FloatArray>> {
        return Array(1) {
            Array(1) {
                FloatArray(seqLen) { 1.0f }
            }
        }
    }

    /**
     * Full processing pipeline: normalizes input text, converts to token IDs wrapped in `<$lang>...</$lang>`,
     * and creates the binary text mask.
     */
    fun process(
        text: String,
        lang: String = DEFAULT_LANG,
    ): SupertonicProcessedText {
        val tokens = mapTextToTokens(text, lang)
        val mask = createTextMask(tokens.size)
        return SupertonicProcessedText(tokenIds = tokens, textMask = mask)
    }

    companion object {
        const val DEFAULT_LANG: String = "en"
        const val PAD_TOKEN_ID: Long = 0L
        const val UNK_TOKEN_ID: Long = 1L

        /** Upstream's final-punctuation class: . ! ? ; : , ' " ) ] } and the ellipsis and CJK closers. */
        val END_PUNCTUATION: Set<Char> =
            setOf(
                '.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}',
                '\u2026', '\u3002', '\u300D', '\u300F', '\u3011', '\u3009', '\u300B', '\u203A', '\u00BB',
            )

        /** Upstream's symbol replacement table, applied in order. */
        private val REPLACEMENTS: List<Pair<String, String>> =
            listOf(
                "\u2013" to "-",
                "\u2011" to "-",
                "\u2014" to "-",
                "_" to " ",
                "\u201C" to "\"",
                "\u201D" to "\"",
                "\u2018" to "'",
                "\u2019" to "'",
                "\u00B4" to "'",
                "`" to "'",
                "[" to " ",
                "]" to " ",
                "|" to " ",
                "/" to " ",
                "#" to " ",
                "\u2192" to " ",
                "\u2190" to " ",
            )

        /** Removed outright: hearts, stars, the copyright sign and backslash. */
        private val SPECIAL_SYMBOLS_REGEX = Regex("""[\u2665\u2606\u2661\u00A9\\]""")

        private val EXPRESSIONS: List<Pair<String, String>> =
            listOf(
                "@" to " at ",
                "e.g.," to "for example, ",
                "i.e.," to "that is, ",
            )

        private val SPACE_BEFORE_PUNCTUATION: List<Char> = listOf(',', '.', '!', '?', ';', ':', '\'')

        private val DUPLICATE_QUOTES: List<String> = listOf("\"\"", "''", "``")

        /** Python's `\s`: Unicode whitespace, including the ASCII separators 0x1C-0x1F. */
        private val WHITESPACE_REGEX = Regex("""[\s\p{Z}\x1C-\x1F\x85]+""")

        val DEFAULT_VOCAB: Map<Char, Long> =
            buildMap {
                put(' ', 0L)
                put('<', 1L)
                put('>', 2L)
                var id = 3L
                val printable = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
                for (ch in printable) {
                    if (!containsKey(ch)) {
                        put(ch, id++)
                    }
                }
            }

        /**
         * Parses a bracketed comma-separated integer array (e.g. from `unicode_indexer.json`).
         */
        fun parseIndexer(text: String): IntArray {
            val list = ArrayList<Int>(65536)
            var num = 0
            var sign = 1
            var hasNum = false
            for (i in 0 until text.length) {
                val c = text[i]
                if (c == '-') {
                    sign = -1
                } else if (c in '0'..'9') {
                    num = num * 10 + (c - '0')
                    hasNum = true
                } else {
                    if (hasNum) {
                        list.add(sign * num)
                        num = 0
                        sign = 1
                        hasNum = false
                    }
                }
            }
            if (hasNum) {
                list.add(sign * num)
            }
            return list.toIntArray()
        }

        /**
         * Creates a [SupertonicTextProcessor] configured with token mappings from `unicode_indexer.json`.
         */
        fun fromFile(file: File): SupertonicTextProcessor {
            return if (file.exists() && file.isFile) {
                val indexer = parseIndexer(file.readText())
                SupertonicTextProcessor(indexer = indexer)
            } else {
                SupertonicTextProcessor()
            }
        }
    }
}
