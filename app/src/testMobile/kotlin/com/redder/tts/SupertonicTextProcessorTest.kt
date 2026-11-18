package com.redder.tts

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupertonicTextProcessorTest {
    private val processor = SupertonicTextProcessor()

    @Test
    fun `filters out unicode emojis from various unicode ranges`() {
        val input = "Hello 😀 world! 🚀 Testing 🐱 emojis ⚡ and flags 🇺🇸 and symbols ⚽."
        val stripped = processor.filterEmojis(input)

        assertFalse(stripped.contains("😀"))
        assertFalse(stripped.contains("🚀"))
        assertFalse(stripped.contains("🐱"))
        assertFalse(stripped.contains("⚡"))
        assertFalse(stripped.contains("⚽"))
        assertTrue(stripped.contains("Hello"))
        assertTrue(stripped.contains("world!"))
        assertTrue(stripped.contains("Testing"))
        assertTrue(stripped.contains("emojis"))
    }

    @Test
    fun `normalizes whitespace and smart punctuation`() {
        val input = "  “Hello ‘world’”—this… is a test!   \n\n  Multiple    spaces.  "
        val normalized = processor.normalize(input)

        // Upstream removes the space before an apostrophe, so an opening single quote is glued on.
        assertEquals("\"Hello'world'\"-this... is a test! Multiple spaces.", normalized)
    }

    @Test
    fun `normalizes NFKD unicode, abbreviations, duplicate quotes, and appends period if missing`() {
        // Fullwidth letters decompose via NFKD
        assertEquals("Hello world.", processor.normalize("Ｈｅｌｌｏ ｗｏｒｌｄ"))

        // @ -> at
        assertEquals("Meet at noon.", processor.normalize("Meet @ noon"))

        // e.g., and i.e.,
        assertEquals("Animals (for example, dogs, cats)", processor.normalize("Animals (e.g., dogs, cats)"))
        assertEquals("Mammals, that is, warm-blooded.", processor.normalize("Mammals, i.e., warm-blooded"))

        // Duplicate quotes collapsed
        assertEquals("\"Clean quotes.\"", processor.normalize("\"\"Clean quotes.\"\""))
        assertEquals("'Clean single.'", processor.normalize("''Clean single.''"))

        // Ending punctuation preserved
        assertEquals("Question?", processor.normalize("Question?"))
        assertEquals("Exclamation!", processor.normalize("Exclamation!"))
        assertEquals("Parenthesis)", processor.normalize("Parenthesis)"))
    }

    @Test
    fun `mapTextToTokens produces valid token sequence for English dialogue wrapped in en tags`() {
        val text = "Hello world"
        val tokens = processor.mapTextToTokens(text)

        // "<en>Hello world.</en>" has length 21
        assertEquals(21, tokens.size)
        assertTrue(tokens.all { it >= 0L })
    }

    @Test
    fun `mapTextToTokens returns empty array for blank text`() {
        val tokens = processor.mapTextToTokens("   \t\n  ")
        assertEquals(0, tokens.size)
    }

    @Test
    fun `createTextMask generates expected shape 1 1 seqLen with 1_0f values`() {
        val seqLen = 7
        val mask = processor.createTextMask(seqLen)

        assertEquals(1, mask.size)
        assertEquals(1, mask[0].size)
        assertEquals(seqLen, mask[0][0].size)
        for (i in 0 until seqLen) {
            assertEquals(1.0f, mask[0][0][i], 1e-6f)
        }
    }

    @Test
    fun `process returns consistent tokenIds and textMask`() {
        val text = "Supertonic 3 ONNX TTS 🚀"
        val result = processor.process(text)

        val normalized = processor.normalize(text)
        val expectedSeqLen = "<en>$normalized</en>".length
        assertEquals(expectedSeqLen, result.tokenIds.size)
        assertEquals(1, result.textMask.size)
        assertEquals(1, result.textMask[0].size)
        assertEquals(result.tokenIds.size, result.textMask[0][0].size)
        assertTrue(result.textMask[0][0].all { it == 1.0f })
    }

    @Test
    fun `custom vocabulary mapping works properly`() {
        val customVocab =
            mapOf(
                '<' to 1L,
                'e' to 2L,
                'n' to 3L,
                '>' to 4L,
                '/' to 5L,
                'a' to 10L,
                ' ' to 0L,
                'b' to 20L,
                '.' to 30L,
            )
        val customProcessor = SupertonicTextProcessor(vocab = customVocab)

        // "a b" -> normalized is "a b." -> wrapped in "<en>a b.</en>"
        val tokens = customProcessor.mapTextToTokens("a b")
        val expected = longArrayOf(1L, 2L, 3L, 4L, 10L, 0L, 20L, 30L, 1L, 5L, 2L, 3L, 4L)
        assertArrayEquals(expected, tokens)
    }

    @Test
    fun `parseIndexer and fromFile correctly map characters to token IDs`() {
        val tempFile = java.io.File.createTempFile("indexer", ".json")
        try {
            // Indexer mapping tags, 'H' -> 42, 'i' -> 99, and unmapped char '!' -> -1 (dropped)
            val indexerData = IntArray(200) { -1 }
            indexerData['<'.code] = 10
            indexerData['e'.code] = 11
            indexerData['n'.code] = 12
            indexerData['>'.code] = 13
            indexerData['/'.code] = 14
            indexerData['H'.code] = 42
            indexerData['i'.code] = 99
            tempFile.writeText(indexerData.joinToString(prefix = "[", postfix = "]", separator = ", "))

            val processorFromFile = SupertonicTextProcessor.fromFile(tempFile)
            val tokens = processorFromFile.mapTextToTokens("Hi!")

            // "<en>Hi!</en>"
            // '<' -> 10, 'e' -> 11, 'n' -> 12, '>' -> 13, 'H' -> 42, 'i' -> 99, '!' dropped, '<' -> 10, '/' -> 14, 'e' -> 11, 'n' -> 12, '>' -> 13
            val expected = longArrayOf(10L, 11L, 12L, 13L, 42L, 99L, 10L, 14L, 11L, 12L, 13L)
            assertArrayEquals(expected, tokens)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `applies upstream symbol replacements and spacing fixes`() {
        assertEquals("a b c d e f g h.", processor.normalize("a_b [c] d|e/f #g \u2192 h"))
        assertEquals("Wait, what? Yes!", processor.normalize("Wait , what ? Yes !"))
        // Upstream also glues a space-preceded apostrophe; the model was trained on that text.
        assertEquals("on their'For You Page' then.", processor.normalize("on their 'For You Page' then"))
        assertEquals("Hearts gone.", processor.normalize("Hearts \u2665gone\u00A9"))
    }

    @Test
    fun `expands only the comma forms of e g and i e like upstream`() {
        assertEquals("Pets, for example, dogs.", processor.normalize("Pets, e.g., dogs"))
        assertEquals("Pets e.g. dogs.", processor.normalize("Pets e.g. dogs"))
    }

    @Test
    fun `does not append a period after an ellipsis or closing guillemet`() {
        assertEquals("\u00ABQuoted\u00BB", processor.normalize("\u00ABQuoted\u00BB"))
        assertEquals("Well...", processor.normalize("Well\u2026"))
    }

    @Test
    fun `drops characters the indexer has no embedding for`() {
        val ascii = IntArray(128) { if (it in 32..126) it else -1 }
        val withIndexer = SupertonicTextProcessor(indexer = ascii)

        // NFKD splits the e-acute into e plus a combining accent, which has no embedding; the
        // keycap emoji leaves a variation selector and an enclosing keycap, neither of which has one.
        assertArrayEquals(
            withIndexer.mapTextToTokens("cafe 1 ok"),
            withIndexer.mapTextToTokens("caf\u00E9 1\uFE0F\u20E3 ok"),
        )
        assertTrue(withIndexer.mapTextToTokens("caf\u00E9").all { it >= 0L })
    }
}
