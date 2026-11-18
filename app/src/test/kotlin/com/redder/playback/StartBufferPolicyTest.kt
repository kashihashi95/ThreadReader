package com.redder.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartBufferPolicyTest {
    /** 100 chars -> 6 s of audio, synthesized at [rtf]. */
    private fun policyWithChunks(
        rtf: Double,
        minBufferMs: Long = 12_000L,
        chunks: Int = 3,
    ) = StartBufferPolicy(minBufferMs).apply {
        repeat(chunks) { record(textChars = 100, audioMs = 6_000L, synthMs = (6_000L * rtf).toLong()) }
    }

    @Test
    fun `fast device starts at the minimum buffer`() {
        val policy = policyWithChunks(rtf = 0.4)

        assertEquals(12_000L, policy.requiredBufferMs(remainingChars = 50_000))
        assertFalse(policy.shouldStart(bufferedMs = 11_999L, remainingChars = 50_000, waitedMs = 5_000L))
        assertTrue(policy.shouldStart(bufferedMs = 12_000L, remainingChars = 50_000, waitedMs = 5_000L))
    }

    @Test
    fun `an engine at exactly real time needs only the minimum`() {
        assertEquals(5_000L, policyWithChunks(rtf = 1.0, minBufferMs = 5_000L).requiredBufferMs(remainingChars = 10_000))
    }

    @Test
    fun `slow device waits in proportion to the projected shortfall`() {
        val policy = policyWithChunks(rtf = 1.5)
        // 1000 chars left = 60 s of audio; synthesizing it takes 90 s, so 30 s must be buffered.
        assertEquals(30_000L, policy.requiredBufferMs(remainingChars = 1_000))
        // Twice the remaining text, twice the buffer.
        assertEquals(60_000L, policy.requiredBufferMs(remainingChars = 2_000))
        // Slower still: rtf 2 doubles the shortfall of rtf 1.5.
        assertEquals(60_000L, policyWithChunks(rtf = 2.0).requiredBufferMs(remainingChars = 1_000))
        assertFalse(policy.shouldStart(bufferedMs = 29_000L, remainingChars = 1_000, waitedMs = 40_000L))
        assertTrue(policy.shouldStart(bufferedMs = 30_000L, remainingChars = 1_000, waitedMs = 40_000L))
    }

    @Test
    fun `the shortfall shrinks as the remaining text shrinks`() {
        val policy = policyWithChunks(rtf = 3.0)
        assertTrue(policy.requiredBufferMs(remainingChars = 500) < policy.requiredBufferMs(remainingChars = 1_000))
        assertEquals(12_000L, policy.requiredBufferMs(remainingChars = 0))
    }

    @Test
    fun `rtf is the mean over all chunks so far`() {
        val policy = StartBufferPolicy(minBufferMs = 0L)
        policy.record(textChars = 100, audioMs = 5_000L, synthMs = 15_000L)
        policy.record(textChars = 100, audioMs = 5_000L, synthMs = 5_000L)
        assertEquals(2.0, policy.rtf, 1e-9)
        assertEquals(50.0, policy.msPerChar, 1e-9)
    }

    @Test
    fun `faster playback speed raises the effective rtf`() {
        val policy = policyWithChunks(rtf = 1.0, minBufferMs = 0L)
        assertEquals(0L, policy.requiredBufferMs(remainingChars = 1_000, speed = 1f))
        // At 1.5x, 60 s of audio plays in 40 s but takes 60 s to make: 30 s of audio must be buffered.
        assertEquals(30_000L, policy.requiredBufferMs(remainingChars = 1_000, speed = 1.5f))
    }

    @Test
    fun `required buffer is capped`() {
        val policy = policyWithChunks(rtf = 3.0)
        assertEquals(StartBufferPolicy.DEFAULT_MAX_BUFFER_MS, policy.requiredBufferMs(remainingChars = 100_000))
        assertTrue(policy.shouldStart(bufferedMs = 180_000L, remainingChars = 100_000, waitedMs = 0L))
    }

    @Test
    fun `wall-clock wait is capped`() {
        val policy = policyWithChunks(rtf = 3.0)
        assertFalse(policy.shouldStart(bufferedMs = 20_000L, remainingChars = 100_000, waitedMs = 179_999L))
        assertTrue(policy.shouldStart(bufferedMs = 20_000L, remainingChars = 100_000, waitedMs = 180_000L))
    }

    @Test
    fun `before any chunk only the minimum applies and empty chunks are ignored`() {
        val policy = StartBufferPolicy(minBufferMs = 5_000L)
        policy.record(textChars = 10, audioMs = 0L, synthMs = 1_000L)
        assertEquals(0.0, policy.rtf)
        assertEquals(5_000L, policy.requiredBufferMs(remainingChars = 10_000))
    }
}
