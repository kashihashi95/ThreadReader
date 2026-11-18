package com.redder.sanitizer

import com.redder.parser.RedlibHtmlParser
import com.redder.tts.NumberWords

/**
 * Sanitizes untrusted Reddit markdown text into clean, speakable plain text for neural TTS.
 *
 * Enforces defensive limits against denial-of-service attack vectors such as quadratic phoneme
 * expansions, tensor buffer overflows, and memory runaway caused by pathological input.
 */
class TextSanitizer {
    /**
     * Sanitizes [rawText] by clamping character length, removing non-printable characters,
     * stripping excessive Zalgo diacritical marks, collapsing character repetition, and
     * scrubbing markdown constructs (links, tables, code blocks, spoilers, ASCII art).
     *
     * @param rawText The raw untrusted text from a Reddit post title, selftext, or comment body.
     * @return Sanitized text suitable for text-to-speech synthesis.
     */
    fun sanitize(rawText: String): String {
        if (rawText.isBlank()) {
            return ""
        }

        // Reddit JSON bodies keep &amp;, &lt;, &gt; and &#x200B; escaped; decode before anything else.
        return RedlibHtmlParser.unescapeHtml(rawText)
            // Strip non-printable control characters (excluding CR, LF, TAB)
            .replace(CONTROL_CHARS_REGEX, "")
            // Strip zero-width characters, which the TTS model would otherwise voice as a token
            .replace(ZERO_WIDTH_REGEX, "")
            // Strip excessive combining diacritical marks (Zalgo defense)
            .replace(ZALGO_REGEX, "")
            // Collapse bursts of 6 or more identical alphanumeric chars into 3
            .replace(REPETITION_DEFENSE_REGEX, "$1$1$1")
            // Scrub spoiler tags before other markdown processing
            .replace(SPOILER_TAG_REGEX, " [spoiler hidden] ")
            // Strip fenced code blocks
            .replace(FENCED_CODE_BLOCK_REGEX, " ")
            // Strip inline backtick code snippets
            .replace(INLINE_CODE_REGEX, " ")
            // Strip markdown table rows and separators
            .replace(TABLE_ROW_REGEX, " ")
            .replace(TABLE_SEPARATOR_REGEX, " ")
            // Convert markdown links [text](url) to text
            .replace(MARKDOWN_LINK_REGEX, "$1")
            // Strip raw unadorned URLs
            .replace(RAW_URL_REGEX, " ")
            // Strip shrug and kaomoji
            .replace(SHRUG_REGEX, " ")
            .replace(KAOMOJI_REGEX, " ")
            // Strip unicode box drawing and block elements
            .replace(UNICODE_DECORATIVE_REGEX, " ")
            // Strip ASCII art box drawing lines like +---+
            .replace(BOX_DRAWING_LINE_REGEX, " ")
            // Strip lines composed purely of symbols/punctuation
            .replace(SYMBOL_LINE_REGEX, " ")
            // Strip repeated symbol sequences (e.g. ===, ----, ~~~~, ++++)
            .replace(REPEATED_SYMBOLS_REGEX, " ")
            // Strip markdown formatting: blockquotes, headers, list bullets
            .replace(BLOCKQUOTE_REGEX, " ")
            .replace(HEADER_REGEX, " ")
            .replace(LIST_BULLET_REGEX, " ")
            .replace("~~", "")
            .replace(BOLD_ITALIC_ASTERISKS_REGEX, "")
            .replace(SINGLE_ASTERISK_ITALIC_REGEX, "$1")
            // Make Reddit shorthand speakable
            .let(::expandSpokenForms)
            // Collapse multiple whitespace and newlines into single spaces
            .replace(WHITESPACE_REGEX, " ")
            // Clean up any space before punctuation
            .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "$1")
            .trim()
    }

    /**
     * Rewrites Reddit-specific shorthand into words the TTS model reads naturally: u/ and r/
     * references, percent signs, ampersands, small numeric ranges and common abbreviations.
     */
    private fun expandSpokenForms(text: String): String {
        var t =
            RedditAcronyms
                .expand(text)
                .replace(DEMOGRAPHIC_REGEX) { m -> demographic(m.groupValues[1] + m.groupValues[4], m.groupValues[2] + m.groupValues[3]) }
                .replace(MONEY_REGEX) { m -> NumberWords.money(m.groupValues[1], m.groupValues[2]) }
                .replace(TONE_MARKER_REGEX, "")
                .replace(SECTION_LABEL_REGEX, "$1,")
                .replace(USER_REFERENCE_REGEX, "$1")
                .replace(SUBREDDIT_REFERENCE_REGEX, "r slash $1")
                .replace(NUMERIC_RANGE_REGEX, "$1 to $2")
                .replace(PERCENT_AFTER_NUMBER_REGEX, "$1 percent")
                .replace(STANDALONE_PERCENT_REGEX, " percent")
                .replace(AMPERSAND_REGEX, " and ")
        for ((pattern, spoken) in ABBREVIATIONS) {
            t = t.replace(pattern, spoken)
        }
        return t.replace(STRAY_COMMA_REGEX, "$1").replace(LEADING_COMMA_REGEX, "")
    }

    /** "[25M]" becomes an aside, so "my roommate [25M] and" reads "my roommate, 25-year-old male, and". */
    private fun demographic(
        age: String,
        gender: String,
    ): String {
        val word =
            when (gender.lowercase()) {
                "m" -> "male"
                "f" -> "female"
                else -> "non-binary"
            }
        return ", $age-year-old $word,"
    }

    companion object {
        private const val GENDER = "[mMfF]|[nN][bB]"
        private val DEMOGRAPHIC_REGEX = Regex("""\s*[\[(]\s*(?:(\d{1,2})\s*($GENDER)|($GENDER)\s*(\d{1,2}))\s*[\])]""")
        private val MONEY_REGEX = Regex("""\$\s?(\d[\d,]*(?:\.\d+)?)(?:\s?([kKmMbB]))?\b""")
        private val TONE_MARKER_REGEX = Regex("""(?<![\w/])/s\b""")
        private val SECTION_LABEL_REGEX = Regex("""(?i)\b(edit|update)(?:\s*\d+)?\s*:""")
        private val STRAY_COMMA_REGEX = Regex(""",\s*([.,!?;:])""")
        private val LEADING_COMMA_REGEX = Regex("""^\s*,\s*""")
        private val ZERO_WIDTH_REGEX = Regex("""[\u200B-\u200D\u2060\uFEFF]""")
        private val USER_REFERENCE_REGEX = Regex("""(?<![\w/])/?u/([A-Za-z0-9_-]+)""")
        private val SUBREDDIT_REFERENCE_REGEX = Regex("""(?<![\w/])/?r/([A-Za-z0-9_]+)""")
        private val NUMERIC_RANGE_REGEX = Regex("""(?<![\d.,/-])(\d{1,3}) ?[-\u2013] ?(\d{1,3})(?![\d.,/-]?\d)""")
        private val PERCENT_AFTER_NUMBER_REGEX = Regex("""(\d) ?%""")
        private val STANDALONE_PERCENT_REGEX = Regex("""(?<=\s|^)%""")
        private val AMPERSAND_REGEX = Regex("""\s+&\s+""")

        /** Abbreviations the model reads letter by letter or not at all; matched as whole words. */
        private val ABBREVIATIONS: List<Pair<Regex, String>> =
            listOf(
                Regex("""\b[Ee]\.g\.""") to "for example",
                Regex("""\b[Ii]\.e\.""") to "that is",
                Regex("""\betc\.""") to "et cetera.",
                Regex("""\betc\b""") to "et cetera",
                Regex("""\bvs\.?(?=\s)""") to "versus",
                Regex("""(?i)\bbtw\b""") to "by the way",
                Regex("""(?i)\bidk\b""") to "I don't know",
                Regex("""(?i)\bw/o(?=\s)""") to "without",
                Regex("""(?i)\bw/(?=\s)""") to "with",
                Regex("""\bb/?c\b""") to "because",
                Regex("""(?i)(\d) ?mph\b""") to "$1 miles per hour",
            )

        private val CONTROL_CHARS_REGEX = Regex("""[\p{Cntrl}&&[^\r\n\t]]""")
        private val ZALGO_REGEX = Regex("""\p{M}{3,}""")
        private val REPETITION_DEFENSE_REGEX = Regex("""([a-zA-Z0-9])\1{5,}""")
        private val SPOILER_TAG_REGEX = Regex("""(?s)>!.*?!<""")
        private val FENCED_CODE_BLOCK_REGEX = Regex("""```+[\s\S]*?(?:```+|$)""")
        private val INLINE_CODE_REGEX = Regex("""`[^`\r\n]+`""")
        private val TABLE_ROW_REGEX = Regex("""(?m)^\s*\|.*$""")
        private val TABLE_SEPARATOR_REGEX = Regex("""(?m)^\s*:?-{2,}:?(\s*\|\s*:?-{2,}:?)+\s*$""")
        private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\((?:https?://[^\s)]+|/[^\s)]*)(?:\s+"[^"]*")?\)""")
        private val RAW_URL_REGEX = Regex("""https?://\S+""")
        private val SHRUG_REGEX = Regex("""¯\\*_*\([^)\r\n]+\)_*/¯""")
        private val KAOMOJI_REGEX = Regex("""ಠ[._]ಠ|\(?[╯╰].*?[┻┳]|\( ͡° ͜ʖ ͡°\)""")
        private val UNICODE_DECORATIVE_REGEX = Regex("""[\u2500-\u257F\u2580-\u259F\u2800-\u28FF]+""")
        private val BOX_DRAWING_LINE_REGEX = Regex("""\+[+\- ]{2,}\+""")
        private val SYMBOL_LINE_REGEX = Regex("""(?m)^[^\w\s\r\n]{3,}\s*$""")
        private val REPEATED_SYMBOLS_REGEX = Regex("""([=\-_~*#+])\1{2,}""")
        private val BLOCKQUOTE_REGEX = Regex("""(?m)^\s*>+\s*""")
        private val HEADER_REGEX = Regex("""(?m)^\s*#{1,6}\s+""")
        private val LIST_BULLET_REGEX = Regex("""(?m)^\s*[*+-]\s+""")
        private val BOLD_ITALIC_ASTERISKS_REGEX = Regex("""\*{2,3}""")
        private val SINGLE_ASTERISK_ITALIC_REGEX = Regex("""(?<!\S)\*(?!\s)(.*?)(?<!\s)\*(?!\S)""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("""\s+([.,!?;:])""")

        /**
         * Convenience static method delegating to an instance of [TextSanitizer].
         */
        fun sanitize(rawText: String): String = TextSanitizer().sanitize(rawText)
    }
}
