package com.redder.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Removes the dead air a TTS model leaves at both ends of an utterance.
 *
 * Supertonic pads every utterance with roughly half a second of near-silence at each end, so two
 * queued chunks are separated by about a second of nothing. [trim] finds the first and last analysis
 * frames whose RMS level is above [THRESHOLD_DBFS], keeps [LEAD_IN_MS] before the first one and
 * [TRAILING_GUARD_MS] after the last one (so soft onsets and decaying final consonants survive), and
 * fades the new edges in and out over [FADE_MS] so the cut never clicks.
 *
 * The pause that should follow a chunk depends on who speaks next, which only the caller knows, so
 * the caller appends that pause (see [WavUtil.appendSilence]).
 */
object SilenceTrimmer {
    /** Length of one RMS analysis frame. */
    const val FRAME_MS: Int = 10

    /** A frame whose RMS level relative to full scale is above this counts as sound, not silence. */
    const val THRESHOLD_DBFS: Double = -45.0

    /**
     * Audio kept before the first loud frame: a look-back margin so quiet onsets (s, f, h) are not
     * clipped, which doubles as the chunk's lead-in.
     */
    const val LEAD_IN_MS: Int = 40

    /** Audio kept after the last loud frame, so the final consonant and its decay are not clipped. */
    const val TRAILING_GUARD_MS: Int = 50

    /** Length of the linear fade applied at each edge that was cut. */
    const val FADE_MS: Int = 5

    /** Inputs shorter than this are returned unchanged; there is nothing meaningful to trim. */
    const val MIN_INPUT_MS: Int = 100

    /**
     * Returns [samples] with leading and trailing silence trimmed as described on [SilenceTrimmer].
     * Returns the input unchanged when it is shorter than [MIN_INPUT_MS] or has no frame above
     * [THRESHOLD_DBFS], so a chunk is never trimmed to zero length.
     *
     * @param samples Mono float samples in [-1, 1].
     * @param sampleRate Sample rate of [samples] in Hz.
     */
    fun trim(
        samples: FloatArray,
        sampleRate: Int,
    ): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        if (samples.size < msToSamples(MIN_INPUT_MS, sampleRate)) return samples

        val frame = max(1, msToSamples(FRAME_MS, sampleRate))
        val frameCount = (samples.size + frame - 1) / frame
        var firstLoud = -1
        var lastLoud = -1
        for (f in 0 until frameCount) {
            if (frameDbFs(samples, f * frame, min(samples.size, (f + 1) * frame)) > THRESHOLD_DBFS) {
                if (firstLoud < 0) firstLoud = f
                lastLoud = f
            }
        }
        if (firstLoud < 0) return samples

        val start = max(0, firstLoud * frame - msToSamples(LEAD_IN_MS, sampleRate))
        val end = min(samples.size, (lastLoud + 1) * frame + msToSamples(TRAILING_GUARD_MS, sampleRate))
        if (start == 0 && end == samples.size) return samples

        val out = samples.copyOfRange(start, end)
        val fade = min(msToSamples(FADE_MS, sampleRate), out.size / 2)
        if (fade > 0) {
            if (start > 0) {
                for (i in 0 until fade) out[i] *= i.toFloat() / fade
            }
            if (end < samples.size) {
                for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
            }
        }
        return out
    }

    /** Converts [ms] to a whole number of samples at [sampleRate]. */
    fun msToSamples(
        ms: Int,
        sampleRate: Int,
    ): Int = (ms.toLong() * sampleRate / 1000L).toInt()

    private fun frameDbFs(
        samples: FloatArray,
        from: Int,
        to: Int,
    ): Double {
        var sum = 0.0
        for (i in from until to) {
            val s = samples[i]
            if (!s.isNaN()) sum += s.toDouble() * s
        }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }
}
