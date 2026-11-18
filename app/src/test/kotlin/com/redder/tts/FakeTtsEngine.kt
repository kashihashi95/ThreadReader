package com.redder.tts

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Deterministic, in-memory [TtsEngine] test implementation for unit and pipeline tests.
 *
 * Simulates neural inference latency via cooperative coroutine delays and synthesizes
 * deterministic 16-bit Little-Endian PCM audio waveforms without external model weights.
 *
 * @param delayMs Simulated neural synthesis latency in milliseconds (default: 100ms).
 * @param sampleRate Output sampling rate in Hz (default: 22,050 Hz).
 * @param samplesPerWord Number of PCM samples generated per word (~0.1s at 22,050 Hz).
 */
class FakeTtsEngine(
    val delayMs: Long = DEFAULT_DELAY_MS,
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val samplesPerWord: Int = DEFAULT_SAMPLES_PER_WORD,
) : TtsEngine {
    init {
        require(delayMs >= 0L) { "delayMs must be non-negative: $delayMs" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(samplesPerWord >= 0) { "samplesPerWord must be non-negative: $samplesPerWord" }
    }

    var synthesizedCount: Int = 0
    var lastSynthesizedText: String? = null
    var lastVoiceId: String? = null
    var isReleased: Boolean = false
    var failureToThrow: Throwable? = null

    /**
     * Synthesizes deterministic 16-bit Little-Endian PCM audio for [text] using [voiceId].
     *
     * Invariants:
     * - Throws [IllegalStateException] if [isReleased] is true.
     * - Throws [failureToThrow] if non-null, leaving state counters unchanged.
     * - Suspends cooperatively via [delay] for [delayMs].
     * - On successful completion, updates [synthesizedCount], [lastSynthesizedText], and [lastVoiceId].
     */
    override suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray {
        if (isReleased) {
            throw IllegalStateException("TtsEngine has been released")
        }
        failureToThrow?.let { throw it }

        delay(delayMs)

        val wordCount =
            if (text.isBlank()) {
                1
            } else {
                text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }.size
            }

        val totalSamples = wordCount * samplesPerWord
        val pcmBytes = ByteArray(totalSamples * 2)
        val twoPi = 2.0 * Math.PI

        for (i in 0 until totalSamples) {
            val angle = twoPi * SINE_FREQUENCY_HZ * i / sampleRate
            val sample = (sin(angle) * SINE_AMPLITUDE).toInt().toShort()
            val offset = i * 2
            pcmBytes[offset] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[offset + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        synthesizedCount++
        lastSynthesizedText = text
        lastVoiceId = voiceId

        return pcmBytes
    }

    /**
     * Marks the engine as released. Subsequent synthesis invocations will fail with [IllegalStateException].
     */
    override fun release() {
        isReleased = true
    }

    companion object {
        const val DEFAULT_DELAY_MS: Long = 100L
        const val DEFAULT_SAMPLE_RATE: Int = 22050
        const val DEFAULT_SAMPLES_PER_WORD: Int = 2205
        private const val SINE_FREQUENCY_HZ: Double = 440.0
        private const val SINE_AMPLITUDE: Double = 16000.0
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
