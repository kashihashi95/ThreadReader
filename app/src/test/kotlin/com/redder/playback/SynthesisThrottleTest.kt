package com.redder.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SynthesisThrottleTest {
    private fun kotlinx.coroutines.test.TestScope.waiter(throttle: SynthesisThrottle) = backgroundScope.launch { throttle.awaitTurn() }

    @Test
    fun `before playback starts neither the bound nor a pause holds synthesis back`() =
        runTest {
            val throttle = SynthesisThrottle(maxAheadMs = 1_000L)
            throttle.setPaused(true)
            repeat(5) { throttle.onSynthesized(it, 10_000L) }
            val waiter = waiter(throttle)
            runCurrent()
            assertTrue(waiter.isCompleted)
        }

    @Test
    fun `at the bound synthesis waits until the playhead moves`() =
        runTest {
            val throttle = SynthesisThrottle(maxAheadMs = 15_000L)
            throttle.playbackStarted()
            (0..3).forEach { throttle.onSynthesized(it, 5_000L) }
            assertEquals(15_000L, throttle.aheadMs, "the current card does not count")

            val waiter = waiter(throttle)
            runCurrent()
            assertFalse(waiter.isCompleted)

            throttle.onPlayhead(1)
            runCurrent()
            assertEquals(10_000L, throttle.aheadMs)
            assertTrue(waiter.isCompleted)
        }

    @Test
    fun `a rewind counts the cards after the new playhead`() =
        runTest {
            val throttle = SynthesisThrottle(maxAheadMs = 15_000L, startIndex = 2)
            throttle.playbackStarted()
            (2..4).forEach { throttle.onSynthesized(it, 5_000L) }
            assertEquals(10_000L, throttle.aheadMs)
            throttle.onPlayhead(2)
            throttle.onPlayhead(4)
            assertEquals(0L, throttle.aheadMs)
        }

    @Test
    fun `a pause holds synthesis below the bound and resuming releases it`() =
        runTest {
            val throttle = SynthesisThrottle()
            throttle.playbackStarted()
            throttle.setPaused(true)
            val waiter = waiter(throttle)
            runCurrent()
            assertFalse(waiter.isCompleted)
            throttle.setPaused(false)
            runCurrent()
            assertTrue(waiter.isCompleted)
        }

    @Test
    fun `the bound applies to every engine as a constant of two to three minutes`() {
        assertTrue(SynthesisThrottle.MAX_LOOK_AHEAD_MS in 120_000L..180_000L)
    }
}
