package com.redder.settings

import android.content.SharedPreferences
import com.redder.tts.VoiceEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RedderPreferencesTest {
    private fun prefs(values: Map<String, String?>): SharedPreferences {
        val prefs = mock(SharedPreferences::class.java)
        values.forEach { (key, value) -> `when`(prefs.getString(key, null)).thenReturn(value) }
        return prefs
    }

    @Test
    fun `engine and narrator keys match the documented names`() {
        assertEquals("redder_prefs", RedderPreferences.PREFS_NAME)
        assertEquals("pref_tts_engine", RedderPreferences.KEY_TTS_ENGINE)
        assertEquals("pref_voice_op_supertonic", RedderPreferences.narratorKey(VoiceEngine.SUPERTONIC))
    }

    @Test
    fun `voice engine defaults to Supertonic when unset, unknown or unreadable`() {
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(null))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(emptyMap())))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(mapOf("pref_tts_engine" to "espeak"))))
        val throwing = mock(SharedPreferences::class.java)
        `when`(throwing.getString("pref_tts_engine", null)).thenThrow(ClassCastException("not a string"))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(throwing))
    }

    @Test
    fun `a saved voice engine is read, and a removed engine from an older install means Supertonic`() {
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(mapOf("pref_tts_engine" to "supertonic"))))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(mapOf("pref_tts_engine" to "piper"))))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(mapOf("pref_tts_engine" to "kokoro"))))
        assertEquals(VoiceEngine.SUPERTONIC, RedderPreferences.readVoiceEngine(prefs(mapOf("pref_tts_engine" to "server"))))
    }

    @Test
    fun `narrator is read per engine and falls back to the engine default`() {
        assertEquals("F2", RedderPreferences.readNarrator(prefs(mapOf("pref_voice_op_supertonic" to "F2")), VoiceEngine.SUPERTONIC))
        assertEquals("M1", RedderPreferences.readNarrator(prefs(mapOf("pref_voice_op_supertonic" to "kokoro:am_michael")), VoiceEngine.SUPERTONIC))
        assertEquals("M1", RedderPreferences.readNarrator(prefs(emptyMap()), VoiceEngine.SUPERTONIC))
        assertEquals("M1", RedderPreferences.readNarrator(null, VoiceEngine.SUPERTONIC))
    }
}
