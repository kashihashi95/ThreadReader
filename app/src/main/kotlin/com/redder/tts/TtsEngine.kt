package com.redder.tts

import com.redder.audio.WavUtil

/**
 * The engine cannot work at all right now, so the chunks after this one would fail too. Unlike any other
 * synthesis failure this stops generation instead of skipping the chunk; [message] is shown to the listener.
 */
class TtsUnavailableException(message: String) : Exception(message)

/**
 * Common abstraction for text-to-speech synthesis engines.
 * Implementations generate raw 16-bit Little-Endian PCM audio data from input text.
 */
interface TtsEngine {
    /**
     * Synthesizes raw 16-bit Little-Endian PCM audio bytes from input text using the specified voice.
     *
     * @param text The input dialogue or narration text to synthesize.
     * @param voiceId The voice identifier specifying the model or speaker characteristics.
     * @return Raw 16-bit Little-Endian PCM audio byte array.
     */
    suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray

    val sampleRate: Int
        get() = WavUtil.DEFAULT_SAMPLE_RATE

    /**
     * Synthesizes packaged RIFF WAV audio bytes from input text using the specified voice.
     * Default implementation wraps [synthesizeToPcm] and encodes it into a standard WAV container.
     *
     * @param text The input dialogue or narration text to synthesize.
     * @param voiceId The voice identifier specifying the model or speaker characteristics.
     * @return Packaged RIFF WAV audio byte array.
     */
    suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
    ): ByteArray {
        val pcm = synthesizeToPcm(text, voiceId)
        return WavUtil.pcmToWav(pcm, sampleRate)
    }

    /**
     * Synthesizes WAV like [synthesizeToWav], but lets the caller cap the engine's quality/diffusion
     * steps for this one utterance (e.g. to start playback quickly). Engines without a step setting
     * ignore [maxQualitySteps].
     */
    suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
        maxQualitySteps: Int?,
    ): ByteArray = synthesizeToWav(text, voiceId)

    /**
     * Runs a tiny throwaway inference so one-time costs (model session creation, first-run kernel
     * setup) are paid before real synthesis. Must never throw for ordinary failures. No-op by default.
     */
    suspend fun warmUp() {}

    /**
     * Releases any held native resources, models, or worker threads.
     * Once released, subsequent synthesis requests must throw an [IllegalStateException].
     */
    fun release()
}
