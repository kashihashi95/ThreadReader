package com.redder.playback

/**
 * Decides when the engine has buffered enough audio to start playback without stalling later.
 *
 * Each synthesized chunk is fed to [record]. The smoothed real-time factor (RTF, synthesis time over
 * audio time) is the ratio of the totals so far, i.e. the duration-weighted mean of the chunks. The
 * remaining audio is estimated from the remaining text length times the observed audio per character.
 * Playback may start once the buffered audio B satisfies
 *
 *     B >= max(minBufferMs, min(maxBufferMs, remainingAudio * (rtf * speed - 1)))
 *
 * where the shortfall term is 0 when the engine keeps up (rtf * speed <= 1). [speed] is the playback
 * speed: at 1.5x the listener drains audio 1.5x faster. Waiting also ends after [maxWaitMs] of wall
 * time, so a very slow device still starts eventually (and catches up with the "Generating…" stalls).
 */
class StartBufferPolicy(
    val minBufferMs: Long,
    val maxBufferMs: Long = DEFAULT_MAX_BUFFER_MS,
    val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
) {
    private var totalSynthMs = 0L
    private var totalAudioMs = 0L
    private var totalChars = 0L

    /** Adds one synthesized chunk: its text length, playback length and synthesis time. */
    fun record(
        textChars: Int,
        audioMs: Long,
        synthMs: Long,
    ) {
        if (audioMs <= 0L) return
        totalAudioMs += audioMs
        totalSynthMs += synthMs.coerceAtLeast(0L)
        totalChars += textChars.coerceAtLeast(0)
    }

    /** Smoothed RTF of the chunks so far; 0 before any chunk. */
    val rtf: Double get() = if (totalAudioMs > 0L) totalSynthMs.toDouble() / totalAudioMs else 0.0

    /** Observed audio milliseconds per character of text; 0 before any chunk. */
    val msPerChar: Double get() = if (totalChars > 0L) totalAudioMs.toDouble() / totalChars else 0.0

    /** Audio to have buffered before starting, given [remainingChars] of text still to synthesize. */
    fun requiredBufferMs(
        remainingChars: Int,
        speed: Float = 1f,
    ): Long {
        val effectiveRtf = rtf * speed.coerceAtLeast(MIN_SPEED)
        val remainingAudioMs = remainingChars.coerceAtLeast(0) * msPerChar
        val shortfallMs = if (effectiveRtf > 1.0) remainingAudioMs * (effectiveRtf - 1.0) else 0.0
        val capped = shortfallMs.coerceAtMost(maxBufferMs.toDouble()).toLong()
        return maxOf(minBufferMs, capped)
    }

    /** True once [bufferedMs] covers [requiredBufferMs] or the wait reached [maxWaitMs]. */
    fun shouldStart(
        bufferedMs: Long,
        remainingChars: Int,
        waitedMs: Long,
        speed: Float = 1f,
    ): Boolean = waitedMs >= maxWaitMs || bufferedMs >= requiredBufferMs(remainingChars, speed)

    companion object {
        /** Most audio ever waited for before starting. */
        const val DEFAULT_MAX_BUFFER_MS: Long = 180_000L

        /** Longest wall-clock wait before starting regardless of the buffer. */
        const val DEFAULT_MAX_WAIT_MS: Long = 180_000L

        private const val MIN_SPEED: Float = 0.1f
    }
}
