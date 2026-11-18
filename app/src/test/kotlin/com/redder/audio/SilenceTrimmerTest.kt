package com.redder.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

class SilenceTrimmerTest {
    private val rate = 44100

    private fun samples(ms: Int) = SilenceTrimmer.msToSamples(ms, rate)

    private fun tone(ms: Int): FloatArray = FloatArray(samples(ms)) { (0.5 * sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }

    private fun silence(ms: Int): FloatArray = FloatArray(samples(ms))

    @Test
    fun `removes leading and trailing silence keeping lead-in and trailing guard`() {
        val input = silence(500) + tone(1000) + silence(600)

        val out = SilenceTrimmer.trim(input, rate)

        val expected = samples(SilenceTrimmer.LEAD_IN_MS) + samples(1000) + samples(SilenceTrimmer.TRAILING_GUARD_MS)
        // Frame quantisation (10 ms) can widen the kept region by at most one frame on each side.
        assertTrue(abs(out.size - expected) <= 2 * samples(SilenceTrimmer.FRAME_MS), "size=${out.size} expected~$expected")
        assertTrue(out.size < input.size - samples(900))
    }

    @Test
    fun `keeps the quiet onset within the lead-in so soft consonants are not clipped`() {
        val input = silence(500) + tone(1000) + silence(500)
        val out = SilenceTrimmer.trim(input, rate)

        val leadIn = samples(SilenceTrimmer.LEAD_IN_MS)
        // The lead-in before the first loud frame is preserved as near-silence...
        for (i in samples(SilenceTrimmer.FADE_MS) until leadIn - samples(SilenceTrimmer.FRAME_MS)) {
            assertEquals(0.0f, out[i], 1e-6f)
        }
        // ...and the tone itself starts intact right after it.
        val peakAfterLeadIn = (leadIn until leadIn + samples(20)).maxOf { abs(out[it]) }
        assertTrue(peakAfterLeadIn > 0.4f)
    }

    @Test
    fun `fades the cut edges in and out`() {
        // A low hum below the threshold stands in for room noise the cut lands on.
        val hum = 0.003f
        val input = FloatArray(samples(300)) { hum } + FloatArray(samples(500)) { 0.5f } + FloatArray(samples(300)) { hum }
        val out = SilenceTrimmer.trim(input, rate)
        val fade = samples(SilenceTrimmer.FADE_MS)

        assertEquals(0.0f, out.first(), 1e-7f)
        assertEquals(hum / 2, out[fade / 2], hum * 0.05f)
        assertEquals(hum, out[fade], 1e-7f)
        assertEquals(0.0f, out.last(), 1e-7f)
        assertEquals(hum / 2, out[out.size - 1 - fade / 2], hum * 0.05f)
        assertEquals(hum, out[out.size - 1 - fade], 1e-7f)
    }

    @Test
    fun `an edge that was not cut is not faded`() {
        val input = silence(200) + FloatArray(samples(500)) { 0.5f }
        val out = SilenceTrimmer.trim(input, rate)
        assertTrue(out.size < input.size)
        assertEquals(0.5f, out.last(), 1e-6f)
    }

    @Test
    fun `audio without silence is returned unchanged`() {
        val input = tone(800)
        assertSame(input, SilenceTrimmer.trim(input, rate))
    }

    @Test
    fun `all-silent input is never trimmed to zero length`() {
        val input = silence(700)
        val out = SilenceTrimmer.trim(input, rate)
        assertArrayEquals(input, out)
    }

    @Test
    fun `very short input is returned unchanged`() {
        val input = floatArrayOf(0.0f, 0.1f, 0.0f, -0.2f)
        assertSame(input, SilenceTrimmer.trim(input, rate))
        assertEquals(0, SilenceTrimmer.trim(FloatArray(0), rate).size)
    }

    @Test
    fun `a short blip in long silence keeps a non-empty chunk`() {
        val input = silence(400) + tone(15) + silence(400)
        val out = SilenceTrimmer.trim(input, rate)
        assertTrue(out.size >= samples(15))
        assertTrue(out.size < input.size)
    }
}
