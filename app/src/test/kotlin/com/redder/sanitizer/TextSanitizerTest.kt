package com.redder.sanitizer

import com.redder.parser.PolymorphicRedditParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class TextSanitizerTest {
    private lateinit var sanitizer: TextSanitizer
    private lateinit var parser: PolymorphicRedditParser

    @BeforeEach
    fun setUp() {
        sanitizer = TextSanitizer()
        parser = PolymorphicRedditParser()
    }

    private fun loadFixture(name: String): String {
        val candidateRoots =
            listOf(
                File("test-fixtures", name),
                File("../test-fixtures", name),
                File("../../test-fixtures", name),
            )
        val file =
            candidateRoots.firstOrNull { it.exists() }
                ?: error("Fixture $name not found in search paths: ${candidateRoots.map { it.absolutePath }}")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `sanitizes post selftext from pathological markdown fixture`() {
        val json = loadFixture("reddit_thread_pathological_markdown.json")
        val thread = parser.parseThread(json)
        val rawSelftext = thread.post.selftext

        val sanitized = sanitizer.sanitize(rawSelftext)

        // Verifies table is stripped
        assertFalse(sanitized.contains("|"))
        assertFalse(sanitized.contains("RTF"))
        assertFalse(sanitized.contains("Piper"))
        assertFalse(sanitized.contains("65MB"))

        // Verifies spoiler is converted to placeholder
        assertTrue(sanitized.contains("[spoiler hidden]"))
        assertFalse(sanitized.contains("Android 15 NPU support"))

        // Verifies fenced code block diagram is stripped
        assertFalse(sanitized.contains("+---+"))
        assertFalse(sanitized.contains("| A |-->| B |"))

        // Verifies raw unadorned URL is stripped
        assertFalse(sanitized.contains("https://"))
        assertFalse(sanitized.contains("reddit.com/r/Android"))

        // Verifies surrounding text is preserved
        assertTrue(sanitized.contains("Check out the full comparison table below:"))
        assertTrue(sanitized.contains("Here is a diagram:"))
        assertTrue(sanitized.contains("Visit for updates."))

        assertEquals(
            "Check out the full comparison table below: [spoiler hidden] Here is a diagram: Visit for updates.",
            sanitized,
        )
    }

    @Test
    fun `sanitizes comment with quotes links and code from pathological markdown fixture`() {
        val json = loadFixture("reddit_thread_pathological_markdown.json")
        val thread = parser.parseThread(json)
        val rawComment = thread.comments.first { it.id == "c_path1" }.body

        val sanitized = sanitizer.sanitize(rawComment)

        // Blockquote marker '>' stripped
        assertFalse(sanitized.startsWith(">"))
        assertTrue(sanitized.contains("Kokoro is 0.85x"))

        // Markdown link converted to link text, URL stripped
        assertTrue(sanitized.contains("Android Audio Latency Guide"))
        assertFalse(sanitized.contains("https://developer.android.com"))

        // Fenced code block stripped
        assertFalse(sanitized.contains("val x = 10"))
        assertFalse(sanitized.contains("```"))

        // Inline backtick code snippet stripped
        assertFalse(sanitized.contains("android.media.AudioTrack"))
        assertFalse(sanitized.contains("`"))

        assertEquals(
            "Kokoro is 0.85x That RTF is dangerous on mid-tier hardware! " +
                "Refer to Android Audio Latency Guide for details. Also check out.",
            sanitized,
        )
    }

    @Test
    fun `sanitizes comment with ascii art and styling from pathological markdown fixture`() {
        val json = loadFixture("reddit_thread_pathological_markdown.json")
        val thread = parser.parseThread(json)
        val rawComment = thread.comments.first { it.id == "c_path2" }.body

        val sanitized = sanitizer.sanitize(rawComment)

        // ASCII art shrug stripped
        assertFalse(sanitized.contains("¯"))
        assertFalse(sanitized.contains("ツ"))

        // Bold and italic asterisks stripped, text retained
        assertTrue(sanitized.contains("Bold and italic"))
        assertFalse(sanitized.contains("***"))

        // Strikethrough tildes stripped, text retained
        assertTrue(sanitized.contains("strikethrough"))
        assertFalse(sanitized.contains("~~"))

        // Bullet points stripped of markers, items retained
        assertTrue(sanitized.contains("Bullet 1"))
        assertTrue(sanitized.contains("Bullet 2"))
        assertTrue(sanitized.contains("Bullet 3"))
        assertFalse(sanitized.contains("*"))

        assertEquals(
            "Deal with it! Bold and italic formatting everywhere, plus strikethrough. Bullet 1 Bullet 2 Bullet 3",
            sanitized,
        )
    }

    @Test
    fun `strips zalgo text combining marks cleanly`() {
        // Zalgo string with 4 combining diacritics per character
        val zalgo = "H\u0300\u0301\u0302\u0303e\u0300\u0301\u0302\u0303l\u0300\u0301\u0302\u0303p\u0300\u0301\u0302\u0303"
        val sanitized = sanitizer.sanitize(zalgo)
        assertEquals("Help", sanitized)
    }

    @Test
    fun `preserves legitimate accented characters with fewer than three combining marks`() {
        val legitimateAccented = "Café, naïve, crème brûlée, Señor, Tiếng Việt"
        val sanitized = sanitizer.sanitize(legitimateAccented)
        assertEquals("Café, naïve, crème brûlée, Señor, Tiếng Việt", sanitized)
    }

    @Test
    fun `does not truncate when raw text length strictly exceeds 1200 characters`() {
        val baseText = "A".repeat(1201)
        val sanitized = sanitizer.sanitize(baseText)

        assertFalse(sanitized.contains("truncated"))
        // Repetition defense also collapses consecutive 'A's into 3
        assertEquals("AAA", sanitized)
    }

    @Test
    fun `preserves long comments without truncation marker when length is 1200 characters`() {
        val baseWord = "word "
        val repeatCount = 1200 / baseWord.length
        val rawText = baseWord.repeat(repeatCount) // exactly 1200 characters
        assertEquals(1200, rawText.length)

        val sanitized = sanitizer.sanitize(rawText)
        assertFalse(sanitized.contains("truncated"))
        assertTrue(sanitized.length > 500)
    }

    @Test
    fun `long arbitrary prose exceeding 1200 characters is preserved fully without truncation`() {
        val sentence = "The quick brown fox jumps over the lazy dog. "
        val longProse = sentence.repeat(30)
        assertTrue(longProse.length > 1200)

        val sanitized = sanitizer.sanitize(longProse)
        assertFalse(sanitized.contains("truncated"))
        assertTrue(sanitized.length > 1200)
    }

    @Test
    fun `collapses bursts of 6 or more identical alphanumeric characters into 3`() {
        val raw = "Woooooooow this is soooooo coooool, look at 9999999999 and aaaaaa and bbbbb"
        val sanitized = sanitizer.sanitize(raw)

        // "Woooooooow" (8 'o's) -> "Wooow" (3 'o's)
        assertTrue(sanitized.contains("Wooow"))
        // "soooooo" (6 'o's) -> "sooo" (3 'o's)
        assertTrue(sanitized.contains("sooo"))
        // "coooool" (5 'o's) < 6, stays "coooool"
        assertTrue(sanitized.contains("coooool"))
        // "9999999999" (10 '9's) -> "999"
        assertTrue(sanitized.contains("999"))
        // "aaaaaa" (6 'a's) -> "aaa"
        assertTrue(sanitized.contains("aaa"))
        // "bbbbb" (5 'b's) stays "bbbbb"
        assertTrue(sanitized.contains("bbbbb"))
    }

    @Test
    fun `scrubs markdown links keeping link text`() {
        val raw = "Check [Reddit Home](https://reddit.com) or [Android Guide](http://developer.android.com/guide)."
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("Check Reddit Home or Android Guide.", sanitized)
    }

    @Test
    fun `scrubs markdown tables completely`() {
        val table =
            """
            Table intro:
            | Header A | Header B |
            |---|---|
            | Value 1 | Value 2 |
            | Value 3 | Value 4 |
            Table conclusion.
            """.trimIndent()

        val sanitized = sanitizer.sanitize(table)
        assertEquals("Table intro: Table conclusion.", sanitized)
    }

    @Test
    fun `scrubs fenced code blocks and inline code snippets`() {
        val raw =
            """
            Before block
            ```python
            import os
            print("hello")
            ```
            After block with `System.exit(0)` here.
            """.trimIndent()

        val sanitized = sanitizer.sanitize(raw)
        assertEquals("Before block After block with here.", sanitized)
    }

    @Test
    fun `scrubs multiple spoiler tags`() {
        val raw = "First spoiler >!secret 1!< and then >!secret 2!< revealed."
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("First spoiler [spoiler hidden] and then [spoiler hidden] revealed.", sanitized)
    }

    @Test
    fun `strips non-printable control characters while preserving valid text`() {
        val raw = "Hello\u0000World\u0007This\u001BIs\u007FA Test"
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("HelloWorldThisIsA Test", sanitized)
    }

    @Test
    fun `strips repeated symbols and box drawing lines`() {
        val raw = "=== Section 1 ===\n+---+---+\n| Box |\n+---+---+\n----------------\nText goes here."
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("Section 1 Text goes here.", sanitized)
    }

    @Test
    fun `handles edge cases empty string and whitespace only`() {
        assertEquals("", sanitizer.sanitize(""))
        assertEquals("", sanitizer.sanitize("   "))
        assertEquals("", sanitizer.sanitize("\n\t\r  \n"))
    }

    @Test
    fun `preserves unicode emojis`() {
        val raw = "ThreadReader is listening! 🎧 🚀 👍 ❤️ 🔥 🤖"
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("ThreadReader is listening! 🎧 🚀 👍 ❤️ 🔥 🤖", sanitized)
    }

    @Test
    fun `companion object static sanitize method delegates correctly`() {
        val raw = "Static [call](https://reddit.com) test."
        val sanitized = TextSanitizer.sanitize(raw)
        assertEquals("Static call test.", sanitized)
    }

    @Test
    fun `collapses multiple whitespaces and cleans space before punctuation`() {
        val raw = "Word1    word2  ,  word3  .   Word4   !  Word5  ?  "
        val sanitized = sanitizer.sanitize(raw)
        assertEquals("Word1 word2, word3. Word4! Word5?", sanitized)
    }

    @Test
    fun `strips shrug variants with single or double backslash`() {
        val singleSlash = "¯\\_(ツ)_/¯ ok"
        val doubleSlash = "¯\\\\_(ツ)_/¯ ok"
        assertEquals("ok", sanitizer.sanitize(singleSlash))
        assertEquals("ok", sanitizer.sanitize(doubleSlash))
    }

    @ParameterizedTest
    @ValueSource(strings = ["# Title", "## Subtitle", "### Section", "###### Deep header"])
    fun `strips markdown header prefixes`(header: String) {
        val sanitized = sanitizer.sanitize("$header with content")
        assertTrue(sanitized.endsWith("with content"))
        assertFalse(sanitized.contains("#"))
    }

    @Test
    fun `empty sanitized text from 1200 plus characters of code returns empty string`() {
        val longCodeBlock = "```\n" + "x = 1\n".repeat(200) + "```"
        assertTrue(longCodeBlock.length > 1200)

        val sanitized = sanitizer.sanitize(longCodeBlock)
        assertEquals("", sanitized)
    }

    @Test
    fun `decodes html entities and strips zero-width characters left in Reddit JSON bodies`() {
        assertEquals("Fish and chips < 5 pounds", sanitizer.sanitize("Fish &amp; chips &lt; 5 pounds&#x200B;"))
        assertEquals("word", sanitizer.sanitize("wo\u200Brd"))
    }

    @Test
    fun `makes user and subreddit references speakable`() {
        assertEquals("ask some_user in r slash AskReddit", sanitizer.sanitize("ask u/some_user in /r/AskReddit"))
    }

    @Test
    fun `expands percent signs ampersands and small numeric ranges`() {
        assertEquals(
            "about 60 to 70 percent spend 4 to 5 hours, M and S agree, this percent is falling",
            sanitizer.sanitize("about 60-70% spend 4-5 hours, M & S agree, this % is falling"),
        )
        // Years, decimals and phone-like numbers are left alone
        assertEquals("2020-2021, 3.5-4, 555-1234", sanitizer.sanitize("2020-2021, 3.5-4, 555-1234"))
    }

    @Test
    fun `expands common abbreviations`() {
        assertEquals(
            "for example dogs, that is pets, et cetera. by the way, to be honest, in my opinion, I don't know, cats versus dogs",
            sanitizer.sanitize("e.g. dogs, i.e. pets, etc. btw, tbh, IMO, idk, cats vs dogs"),
        )
        assertEquals("tea with milk, coffee without sugar", sanitizer.sanitize("tea w/ milk, coffee w/o sugar"))
    }

    @Test
    fun `reddit shorthand from the clean-up prompt is handled by rules`() {
        val cases =
            mapOf(
                "AITAH for telling him he is NTA?" to "Am I the asshole for telling him he is not the asshole?",
                "ESH, honestly. Well nah I disagree." to "Everyone sucks here, honestly. Well nah I disagree.",
                "My roommate [25M] and her (19F) moved in." to "My roommate, 25-year-old male, and her, 19-year-old female, moved in.",
                "I (30NB) said no (M40)." to "I, 30-year-old non-binary, said no, 40-year-old male.",
                "My roommate [25M] and her (19F) left." to "My roommate, 25-year-old male, and her, 19-year-old female, left.",
                "Owes $5k plus $15.50 from May." to "Owes five thousand dollars plus fifteen dollars and fifty cents from May.",
                "\$5 boxes" to "five dollars boxes",
                "I left bc it was loud b/c of him." to "I left because it was loud because of him.",
                "It was founded in 300 BC." to "It was founded in 300 BC.",
                "He was going 100mph." to "He was going 100 miles per hour.",
                "Yeah that will work /s" to "Yeah that will work",
                "Yeah /sarcasm and /r/kotlin stay" to "Yeah /sarcasm and r slash kotlin stay",
                "Edit: thanks!" to "Edit, thanks!",
                "UPDATE 2: he left." to "UPDATE, he left.",
                "TL;DR: he left." to "Too long; didn't read: he left.",
                "He came (8) too." to "He came (8) too.",
            )
        cases.forEach { (raw, spoken) -> assertEquals(spoken, TextSanitizer.sanitize(raw), raw) }
    }
}
