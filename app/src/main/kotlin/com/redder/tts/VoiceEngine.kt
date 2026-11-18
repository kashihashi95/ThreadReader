package com.redder.tts

import com.redder.sanitizer.SpeakerVoiceAllocator

/**
 * Voice engine of a thread; [prefValue] is what `pref_tts_engine` stores.
 *
 * @property chunkTimeoutMs Per-chunk synthesis timeout.
 * @property minStartBufferMs Least audio to have synthesized before playback starts. Supertonic
 *   starts on the first chunk; see [com.redder.playback.StartBufferPolicy].
 */
enum class VoiceEngine(
    val prefValue: String,
    val chunkTimeoutMs: Long,
    val minStartBufferMs: Long,
) {
    SUPERTONIC("supertonic", chunkTimeoutMs = 25_000L, minStartBufferMs = 0L),
    ;

    companion object {
        val DEFAULT: VoiceEngine = SUPERTONIC

        /** Parses a stored pref value; unknown or missing values mean [DEFAULT]. */
        fun fromPref(value: String?): VoiceEngine = entries.firstOrNull { it.prefValue.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** A voice offered in the narrator picker. */
data class CuratedVoice(
    val id: String,
    val label: String,
)

/**
 * Short, curated per-engine voice lists: the first entry is the default narrator (OP), and
 * commenters rotate through the other voices of the same engine.
 */
object EngineVoices {
    /**
     * The voices Supertonic already used before the picker existed (OP anchor plus six
     * conversational aliases, in alias order), so the default allocation is unchanged.
     */
    val SUPERTONIC: List<CuratedVoice> =
        listOf(
            CuratedVoice("M1", "Male 1"),
            CuratedVoice("F1", "Female 1"),
            CuratedVoice("M2", "Male 2"),
            CuratedVoice("F2", "Female 2"),
            CuratedVoice("M3", "Male 3"),
            CuratedVoice("F3", "Female 3"),
            CuratedVoice("M4", "Male 4"),
        )

    fun voices(engine: VoiceEngine): List<CuratedVoice> =
        when (engine) {
            VoiceEngine.SUPERTONIC -> SUPERTONIC
        }

    fun defaultNarrator(engine: VoiceEngine): String = voices(engine).first().id

    /** [stored] when it is one of [engine]'s curated voices, else the default narrator. */
    fun narratorOrDefault(
        engine: VoiceEngine,
        stored: String?,
    ): String = voices(engine).firstOrNull { it.id == stored }?.id ?: defaultNarrator(engine)

    /**
     * Allocator mapping the thread's speakers onto [engine]'s voices: the OP gets [narrator], every
     * commenter one of the other curated voices. Supertonic with its default narrator keeps the
     * original [SpeakerVoiceAllocator] so its voice assignment is exactly as before.
     */
    fun allocator(
        engine: VoiceEngine,
        narrator: String,
    ): SpeakerVoiceAllocator {
        val op = narratorOrDefault(engine, narrator)
        if (engine == VoiceEngine.SUPERTONIC && op == defaultNarrator(engine)) return SpeakerVoiceAllocator()
        return SpeakerVoiceAllocator(availableVoiceIds = voices(engine).map { it.id }, opAnchorVoice = op)
    }
}
