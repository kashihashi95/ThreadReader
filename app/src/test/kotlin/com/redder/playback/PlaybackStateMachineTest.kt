package com.redder.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackStateMachineTest {
    // -------------------------------------------------------------------------
    // Helper: build a machine in a given state via the canonical transition path
    // -------------------------------------------------------------------------

    private fun machineInLoading(): PlaybackStateMachine =
        PlaybackStateMachine().apply {
            transition(PlaybackEvent.Load)
        }

    private fun machineInBuffering(): PlaybackStateMachine =
        machineInLoading().apply {
            transition(PlaybackEvent.BufferingStarted)
        }

    private fun machineInPlaying(): PlaybackStateMachine =
        machineInBuffering().apply {
            transition(PlaybackEvent.FirstChunkReady)
        }

    private fun machineInPaused(): PlaybackStateMachine =
        machineInPlaying().apply {
            transition(PlaybackEvent.Pause)
        }

    private fun machineInDucked(): PlaybackStateMachine =
        machineInPlaying().apply {
            transition(PlaybackEvent.Duck)
        }

    private fun machineInSeeking(): PlaybackStateMachine =
        machineInPlaying().apply {
            transition(PlaybackEvent.Seek)
        }

    private fun machineInError(): PlaybackStateMachine =
        machineInPlaying().apply {
            transition(PlaybackEvent.Fail(RuntimeException("err")))
        }

    // -------------------------------------------------------------------------
    // 28. StateFlow initial value is Idle
    // -------------------------------------------------------------------------

    @Test
    fun `initial state is Idle`() {
        val machine = PlaybackStateMachine()
        assertEquals(PlaybackState.Idle, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 29. StateFlow emits states
    // -------------------------------------------------------------------------

    @Test
    fun `stateFlow emits Loading after Load event`() =
        runTest {
            val machine = PlaybackStateMachine()
            machine.transition(PlaybackEvent.Load)
            val emitted = machine.state.first()
            assertEquals(PlaybackState.Loading, emitted)
        }

    // -------------------------------------------------------------------------
    // 1. Idle -> Load -> Loading
    // -------------------------------------------------------------------------

    @Test
    fun `transition 1 Idle plus Load yields Loading`() {
        val machine = PlaybackStateMachine()
        machine.transition(PlaybackEvent.Load)
        assertEquals(PlaybackState.Loading, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 2. Loading -> BufferingStarted -> Buffering
    // -------------------------------------------------------------------------

    @Test
    fun `transition 2 Loading plus BufferingStarted yields Buffering`() {
        val machine = machineInLoading()
        machine.transition(PlaybackEvent.BufferingStarted)
        assertEquals(PlaybackState.Buffering, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 3. Loading -> Fail -> Error
    // -------------------------------------------------------------------------

    @Test
    fun `transition 3 Loading plus Fail yields Error`() {
        val machine = machineInLoading()
        val cause = RuntimeException("load failure")
        machine.transition(PlaybackEvent.Fail(cause))
        assertInstanceOf(PlaybackState.Error::class.java, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 4. Buffering -> FirstChunkReady -> Playing
    // -------------------------------------------------------------------------

    @Test
    fun `transition 4 Buffering plus FirstChunkReady yields Playing`() {
        val machine = machineInBuffering()
        machine.transition(PlaybackEvent.FirstChunkReady)
        assertEquals(PlaybackState.Playing, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 5. Buffering -> Fail -> Error
    // -------------------------------------------------------------------------

    @Test
    fun `transition 5 Buffering plus Fail yields Error`() {
        val machine = machineInBuffering()
        machine.transition(PlaybackEvent.Fail(RuntimeException("buffer failure")))
        assertInstanceOf(PlaybackState.Error::class.java, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 6. Playing -> Pause -> Paused
    // -------------------------------------------------------------------------

    @Test
    fun `transition 6 Playing plus Pause yields Paused`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Pause)
        assertEquals(PlaybackState.Paused, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 7. Playing -> Duck -> Ducked
    // -------------------------------------------------------------------------

    @Test
    fun `transition 7 Playing plus Duck yields Ducked`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Duck)
        assertEquals(PlaybackState.Ducked, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 8. Playing -> Seek -> Seeking
    // -------------------------------------------------------------------------

    @Test
    fun `transition 8 Playing plus Seek yields Seeking`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Seek)
        assertEquals(PlaybackState.Seeking, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 9. Playing -> Fail -> Error
    // -------------------------------------------------------------------------

    @Test
    fun `transition 9 Playing plus Fail yields Error`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Fail(RuntimeException("play failure")))
        assertInstanceOf(PlaybackState.Error::class.java, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 10. Paused -> Resume -> Playing
    // -------------------------------------------------------------------------

    @Test
    fun `transition 10 Paused plus Resume yields Playing`() {
        val machine = machineInPaused()
        machine.transition(PlaybackEvent.Resume)
        assertEquals(PlaybackState.Playing, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 11. Paused -> Fail -> Error
    // -------------------------------------------------------------------------

    @Test
    fun `transition 11 Paused plus Fail yields Error`() {
        val machine = machineInPaused()
        machine.transition(PlaybackEvent.Fail(RuntimeException("paused failure")))
        assertInstanceOf(PlaybackState.Error::class.java, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 12. Ducked -> Unduck -> Playing
    // -------------------------------------------------------------------------

    @Test
    fun `transition 12 Ducked plus Unduck yields Playing`() {
        val machine = machineInDucked()
        machine.transition(PlaybackEvent.Unduck)
        assertEquals(PlaybackState.Playing, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 13. Seeking -> SeekComplete -> Playing
    // -------------------------------------------------------------------------

    @Test
    fun `transition 13 Seeking plus SeekComplete yields Playing`() {
        val machine = machineInSeeking()
        machine.transition(PlaybackEvent.SeekComplete)
        assertEquals(PlaybackState.Playing, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 14. ANY -> Release -> Released  (all 7 source states)
    // -------------------------------------------------------------------------

    @Test
    fun `transition 14a Idle plus Release yields Released`() {
        val machine = PlaybackStateMachine()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14b Loading plus Release yields Released`() {
        val machine = machineInLoading()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14c Buffering plus Release yields Released`() {
        val machine = machineInBuffering()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14d Playing plus Release yields Released`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14e Paused plus Release yields Released`() {
        val machine = machineInPaused()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14f Ducked plus Release yields Released`() {
        val machine = machineInDucked()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    @Test
    fun `transition 14g Seeking plus Release yields Released`() {
        val machine = machineInSeeking()
        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 15. Released + Release -> IllegalStateTransitionException (double-release)
    // -------------------------------------------------------------------------

    @Test
    fun `double Release from Released throws IllegalStateTransitionException`() {
        val machine = PlaybackStateMachine()
        machine.transition(PlaybackEvent.Release)
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Release)
        }
    }

    // -------------------------------------------------------------------------
    // 16–24. Illegal transitions
    // -------------------------------------------------------------------------

    @Test
    fun `illegal 16 Idle plus Pause throws`() {
        val machine = PlaybackStateMachine()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Pause)
        }
    }

    @Test
    fun `illegal 17 Loading plus Seek throws`() {
        val machine = machineInLoading()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Seek)
        }
    }

    @Test
    fun `illegal 18 Buffering plus Pause throws`() {
        val machine = machineInBuffering()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Pause)
        }
    }

    @Test
    fun `illegal 19 Playing plus Load throws`() {
        val machine = machineInPlaying()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Load)
        }
    }

    @Test
    fun `illegal 20 Paused plus Duck throws`() {
        val machine = machineInPaused()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Duck)
        }
    }

    @Test
    fun `illegal 21 Ducked plus Pause throws`() {
        val machine = machineInDucked()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Pause)
        }
    }

    @Test
    fun `illegal 22 Seeking plus Pause throws`() {
        val machine = machineInSeeking()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Pause)
        }
    }

    @Test
    fun `illegal 23 Error plus Load throws`() {
        val machine = machineInError()
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Load)
        }
    }

    @Test
    fun `illegal 24 Released plus Load throws`() {
        val machine = PlaybackStateMachine()
        machine.transition(PlaybackEvent.Release)
        assertThrows<IllegalStateTransitionException> {
            machine.transition(PlaybackEvent.Load)
        }
    }

    // -------------------------------------------------------------------------
    // 25. reset() from Error -> Idle
    // -------------------------------------------------------------------------

    @Test
    fun `reset 25 from Error yields Idle`() {
        val machine = machineInError()
        assertInstanceOf(PlaybackState.Error::class.java, machine.state.value)
        machine.reset()
        assertEquals(PlaybackState.Idle, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 26. reset() from Released -> Idle
    // -------------------------------------------------------------------------

    @Test
    fun `reset 26 from Released yields Idle`() {
        val machine = PlaybackStateMachine()
        machine.transition(PlaybackEvent.Release)
        machine.reset()
        assertEquals(PlaybackState.Idle, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 27. reset() from Playing -> throws
    // -------------------------------------------------------------------------

    @Test
    fun `reset 27 from Playing throws IllegalStateTransitionException`() {
        val machine = machineInPlaying()
        assertThrows<IllegalStateTransitionException> {
            machine.reset()
        }
    }

    // -------------------------------------------------------------------------
    // 30. Error carries cause reference
    // -------------------------------------------------------------------------

    @Test
    fun `error state carries exact cause reference`() {
        val machine = machineInPlaying()
        val cause = RuntimeException("specific cause")
        machine.transition(PlaybackEvent.Fail(cause))
        val errorState = machine.state.value as PlaybackState.Error
        assertSame(cause, errorState.cause)
    }

    // -------------------------------------------------------------------------
    // 31. Thread-safety: 10 threads calling Release concurrently
    // -------------------------------------------------------------------------

    @Test
    fun `thread safety only one Release succeeds among 10 concurrent threads`() {
        val machine = machineInPlaying()
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    machine.transition(PlaybackEvent.Release)
                    successCount.incrementAndGet()
                } catch (_: IllegalStateTransitionException) {
                    // Expected for all but the first
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads did not finish in time")
        executor.shutdown()

        assertEquals(1, successCount.get(), "Exactly one Release should succeed")
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // 32. Full happy-path sequence
    // -------------------------------------------------------------------------

    @Test
    fun `full happy path sequence Idle to Released`() {
        val machine = PlaybackStateMachine()

        machine.transition(PlaybackEvent.Load)
        assertEquals(PlaybackState.Loading, machine.state.value)

        machine.transition(PlaybackEvent.BufferingStarted)
        assertEquals(PlaybackState.Buffering, machine.state.value)

        machine.transition(PlaybackEvent.FirstChunkReady)
        assertEquals(PlaybackState.Playing, machine.state.value)

        machine.transition(PlaybackEvent.Pause)
        assertEquals(PlaybackState.Paused, machine.state.value)

        machine.transition(PlaybackEvent.Resume)
        assertEquals(PlaybackState.Playing, machine.state.value)

        machine.transition(PlaybackEvent.Duck)
        assertEquals(PlaybackState.Ducked, machine.state.value)

        machine.transition(PlaybackEvent.Unduck)
        assertEquals(PlaybackState.Playing, machine.state.value)

        machine.transition(PlaybackEvent.Seek)
        assertEquals(PlaybackState.Seeking, machine.state.value)

        machine.transition(PlaybackEvent.SeekComplete)
        assertEquals(PlaybackState.Playing, machine.state.value)

        machine.transition(PlaybackEvent.Release)
        assertEquals(PlaybackState.Released, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // Extra: reset() after Error allows replaying full happy path
    // -------------------------------------------------------------------------

    @Test
    fun `reset after Error allows replaying from Idle`() {
        val machine = machineInPlaying()
        machine.transition(PlaybackEvent.Fail(RuntimeException("transient")))
        machine.reset()
        assertEquals(PlaybackState.Idle, machine.state.value)

        // Confirm full cycle works again after reset
        machine.transition(PlaybackEvent.Load)
        machine.transition(PlaybackEvent.BufferingStarted)
        machine.transition(PlaybackEvent.FirstChunkReady)
        assertEquals(PlaybackState.Playing, machine.state.value)
    }

    // -------------------------------------------------------------------------
    // Extra: exception message contains state and event names
    // -------------------------------------------------------------------------

    @Test
    fun `exception message contains state and event class names`() {
        val machine = PlaybackStateMachine()
        val ex =
            assertThrows<IllegalStateTransitionException> {
                machine.transition(PlaybackEvent.Pause)
            }
        assertTrue(ex.message?.contains("Idle") == true)
        assertTrue(ex.message?.contains("Pause") == true)
    }
}
