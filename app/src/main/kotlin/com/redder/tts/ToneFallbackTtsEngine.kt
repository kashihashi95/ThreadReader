package com.redder.tts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Fallback lightweight text-to-speech engine generating harmonic sine tones.
 *
 * Used when the on-device neural voices are unavailable or for offline testing, generating
 * valid 16-bit signed Little-Endian PCM audio at 22,050 Hz (Mono) with attack/decay
 * envelope shaping to eliminate audio clipping and pops.
 */
class ToneFallbackTtsEngine : TtsEngine {
    @Volatile
    private var isReleased: Boolean = false

    override suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray {
        if (isReleased) {
            throw IllegalStateException("ToneFallbackTtsEngine has been released")
        }

        val wordCount = text.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }.size
        val durationSeconds = maxOf(MIN_DURATION_SECONDS, wordCount * SECONDS_PER_WORD)
        val sampleCount = (durationSeconds * SAMPLE_RATE).toInt()

        val freq = BASE_FREQUENCY + abs(voiceId.hashCode() % FREQUENCY_SPREAD)
        val peakAmplitude = (Short.MAX_VALUE * PEAK_AMPLITUDE_RATIO).toInt()
        val envelopeSamples = (ENVELOPE_DURATION_SECONDS * SAMPLE_RATE).toInt()

        val pcmBytes = ByteArray(sampleCount * BYTES_PER_SAMPLE)

        for (i in 0 until sampleCount) {
            val envelope =
                when {
                    envelopeSamples > 0 && i < envelopeSamples -> i.toDouble() / envelopeSamples
                    envelopeSamples > 0 && i >= sampleCount - envelopeSamples -> (sampleCount - 1 - i).toDouble() / envelopeSamples
                    else -> 1.0
                }

            val rawSample = (envelope * peakAmplitude * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toInt()
            val clampedSample = rawSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            val byteOffset = i * BYTES_PER_SAMPLE
            pcmBytes[byteOffset] = (clampedSample.toInt() and 0xFF).toByte()
            pcmBytes[byteOffset + 1] = ((clampedSample.toInt() shr 8) and 0xFF).toByte()
        }

        return pcmBytes
    }

    override fun release() {
        isReleased = true
    }

    companion object {
        const val SAMPLE_RATE: Int = 22050
        const val BYTES_PER_SAMPLE: Int = 2
        const val MIN_DURATION_SECONDS: Double = 0.5
        const val SECONDS_PER_WORD: Double = 0.35
        const val BASE_FREQUENCY: Double = 220.0
        const val FREQUENCY_SPREAD: Int = 400
        const val PEAK_AMPLITUDE_RATIO: Double = 0.75
        const val ENVELOPE_DURATION_SECONDS: Double = 0.02
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
