package com.redder.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupertonicVoiceCatalogTest {
    @Test
    fun `contains exactly 10 preset voice IDs with M1 as default OP voice`() {
        assertEquals("M1", SupertonicVoiceCatalog.DEFAULT_OP_VOICE)
        assertEquals(10, SupertonicVoiceCatalog.ALL_VOICE_IDS.size)
        assertEquals(
            listOf("M1", "F1", "F2", "F3", "F4", "F5", "M2", "M3", "M4", "M5"),
            SupertonicVoiceCatalog.ALL_VOICE_IDS,
        )
    }

    @Test
    fun `voice lookup accurately validates preset and arbitrary voices`() {
        for (voiceId in SupertonicVoiceCatalog.ALL_VOICE_IDS) {
            assertTrue(SupertonicVoiceCatalog.hasVoice(voiceId))
            assertTrue(SupertonicVoiceCatalog.hasVoice(voiceId.lowercase()))
        }
        assertFalse(SupertonicVoiceCatalog.hasVoice("unknown_voice"))
        assertFalse(SupertonicVoiceCatalog.hasVoice(""))
    }

    @Test
    fun `gender predicates correctly classify female and male voice IDs`() {
        for (fId in listOf("F1", "F2", "F3", "F4", "F5")) {
            assertTrue(SupertonicVoiceCatalog.isFemaleVoice(fId))
            assertFalse(SupertonicVoiceCatalog.isMaleVoice(fId))
        }
        for (mId in listOf("M1", "M2", "M3", "M4", "M5")) {
            assertTrue(SupertonicVoiceCatalog.isMaleVoice(mId))
            assertFalse(SupertonicVoiceCatalog.isFemaleVoice(mId))
        }
    }

    @Test
    fun `getStyleTtl returns shape 1 50 256 with non-zero floats`() {
        val ttl = SupertonicVoiceCatalog.getStyleTtl("M1")

        assertEquals(1, ttl.size)
        assertEquals(50, ttl[0].size)
        assertEquals(256, ttl[0][0].size)

        var hasNonZero = false
        for (step in ttl[0]) {
            for (dim in step) {
                if (dim != 0.0f) hasNonZero = true
            }
        }
        assertTrue(hasNonZero)

        // Verifies caching returns same instance
        val cached = SupertonicVoiceCatalog.getStyleTtl("M1")
        assertSame(ttl, cached)
    }

    @Test
    fun `getStyleDp returns shape 1 8 16 with non-zero floats`() {
        val dp = SupertonicVoiceCatalog.getStyleDp("F1")

        assertEquals(1, dp.size)
        assertEquals(8, dp[0].size)
        assertEquals(16, dp[0][0].size)

        var hasNonZero = false
        for (step in dp[0]) {
            for (dim in step) {
                if (dim != 0.0f) hasNonZero = true
            }
        }
        assertTrue(hasNonZero)

        // Verifies caching returns same instance
        val cached = SupertonicVoiceCatalog.getStyleDp("F1")
        assertSame(dp, cached)
    }

    @Test
    fun `loadVoiceStylesFromDir parses valid JSON voice style into cache`() {
        val tempDir = java.nio.file.Files.createTempDirectory("voice_styles").toFile()
        try {
            val m2File = java.io.File(tempDir, "M2.json")
            val dummyTtl = (0 until 50).map { (0 until 256).map { 0.123f } }
            val dummyDp = (0 until 8).map { (0 until 16).map { 0.456f } }
            val jsonContent =
                """
                {
                    "style_ttl": { "data": [ $dummyTtl ] },
                    "style_dp": { "data": [ $dummyDp ] }
                }
                """.trimIndent()
            m2File.writeText(jsonContent)

            SupertonicVoiceCatalog.loadVoiceStylesFromDir(tempDir)
            val styleTtl = SupertonicVoiceCatalog.getStyleTtl("M2")
            val styleDp = SupertonicVoiceCatalog.getStyleDp("M2")

            assertEquals(1, styleTtl.size)
            assertEquals(50, styleTtl[0].size)
            assertEquals(256, styleTtl[0][0].size)
            assertEquals(0.123f, styleTtl[0][0][0], 1e-4f)

            assertEquals(1, styleDp.size)
            assertEquals(8, styleDp[0].size)
            assertEquals(16, styleDp[0][0].size)
            assertEquals(0.456f, styleDp[0][0][0], 1e-4f)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveVoiceId accurately maps conversational and platform aliases to Supertonic voices`() {
        assertEquals("M1", SupertonicVoiceCatalog.resolveVoiceId("voice_op_anchor"))
        assertEquals("F1", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_1"))
        assertEquals("M2", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_2"))
        assertEquals("F2", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_3"))
        assertEquals("M3", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_4"))
        assertEquals("F3", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_5"))
        assertEquals("M4", SupertonicVoiceCatalog.resolveVoiceId("voice_conversational_6"))

        assertEquals("M1", SupertonicVoiceCatalog.resolveVoiceId("voice_0"))
        assertEquals("F1", SupertonicVoiceCatalog.resolveVoiceId("voice_1"))

        assertTrue(SupertonicVoiceCatalog.hasVoice("voice_op_anchor"))
        assertTrue(SupertonicVoiceCatalog.hasVoice("voice_conversational_1"))

        // Any arbitrary unknown voice deterministically maps to a valid voice in ALL_VOICE_IDS
        val arbitraryResolved = SupertonicVoiceCatalog.resolveVoiceId("some_random_author_123")
        assertTrue(SupertonicVoiceCatalog.ALL_VOICE_IDS.contains(arbitraryResolved))
    }
}
