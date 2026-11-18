package com.redder.settings

import android.content.SharedPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.VoiceEngine

/**
 * Shared preference file and keys used by the dashboard, the floating HUD and the audio service.
 */
object RedderPreferences {
    const val PREFS_NAME: String = "redder_prefs"
    const val KEY_PLAYBACK_SPEED: String = "pref_playback_speed"
    const val KEY_QUALITY_STEPS: String = "pref_quality_steps"
    const val KEY_READ_USERNAMES: String = "pref_read_usernames"

    /** Voice engine ([VoiceEngine.prefValue]); read when each thread starts. */
    const val KEY_TTS_ENGINE: String = "pref_tts_engine"

    /** Prefix of the per-engine narrator (OP) voice keys, e.g. `pref_voice_op_supertonic`. */
    const val KEY_VOICE_OP_PREFIX: String = "pref_voice_op_"

    /** Where the minimized floating player's bubble was left, in pixels; absent until it is moved. */
    const val KEY_BUBBLE_X: String = "pref_bubble_x"
    const val KEY_BUBBLE_Y: String = "pref_bubble_y"

    const val DEFAULT_PLAYBACK_SPEED: Float = 1.0f
    const val DEFAULT_QUALITY_STEPS: Int = 8

    /** Diffusion steps for the "Fast" voice quality option. */
    const val QUALITY_STEPS_FAST: Int = 4

    /** Diffusion steps for the "Best" voice quality option. */
    const val QUALITY_STEPS_BEST: Int = 8

    /** Playback speeds offered on the dashboard. */
    val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** Formats a speed multiplier compactly, e.g. 1.0 -> "1x", 1.25 -> "1.25x". */
    fun formatPlaybackSpeed(speed: Float): String = java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "x"

    /** Key holding [engine]'s narrator voice id. */
    fun narratorKey(engine: VoiceEngine): String = KEY_VOICE_OP_PREFIX + engine.prefValue

    /** Saved voice engine, [VoiceEngine.DEFAULT] when unset, unknown or unreadable. */
    fun readVoiceEngine(prefs: SharedPreferences?): VoiceEngine =
        VoiceEngine.fromPref(runCatching { prefs?.getString(KEY_TTS_ENGINE, null) }.getOrNull())

    /** Saved narrator voice of [engine], falling back to its default when unset or not curated. */
    fun readNarrator(
        prefs: SharedPreferences?,
        engine: VoiceEngine,
    ): String = EngineVoices.narratorOrDefault(engine, runCatching { prefs?.getString(narratorKey(engine), null) }.getOrNull())
}
