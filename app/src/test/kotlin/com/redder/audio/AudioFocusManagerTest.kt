package com.redder.audio

import android.media.AudioManager
import com.redder.playback.PlaybackEvent
import com.redder.playback.PlaybackState
import com.redder.playback.PlaybackStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class FakeAudioFocusRequestController : AudioFocusRequestController {
    var shouldGrantFocus: Boolean = true
    var focusRequested: Boolean = false
    var focusAbandoned: Boolean = false
    var listener: AudioManager.OnAudioFocusChangeListener? = null

    override fun requestFocus(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
        this.focusRequested = true
        this.listener = listener
        return shouldGrantFocus
    }

    override fun abandonFocus() {
        this.focusAbandoned = true
    }
}

class AudioFocusManagerTest {
    private lateinit var controller: FakeAudioFocusRequestController
    private lateinit var stateMachine: PlaybackStateMachine
    private var duckCalled = false
    private var unduckCalled = false
    private lateinit var manager: AudioFocusManager

    @BeforeEach
    fun setUp() {
        controller = FakeAudioFocusRequestController()
        stateMachine = PlaybackStateMachine()
        duckCalled = false
        unduckCalled = false
        manager =
            AudioFocusManager(
                stateMachine = stateMachine,
                onDuck = { duckCalled = true },
                onUnduck = { unduckCalled = true },
                controller = controller,
            )
    }

    private fun goToLoading() {
        stateMachine.transition(PlaybackEvent.Load)
    }

    private fun goToBuffering() {
        goToLoading()
        stateMachine.transition(PlaybackEvent.BufferingStarted)
    }

    private fun goToPlaying() {
        goToBuffering()
        stateMachine.transition(PlaybackEvent.FirstChunkReady)
    }

    private fun goToDucked() {
        goToPlaying()
        stateMachine.transition(PlaybackEvent.Duck)
    }

    private fun goToPaused() {
        goToPlaying()
        stateMachine.transition(PlaybackEvent.Pause)
    }

    private fun goToSeeking() {
        goToPlaying()
        stateMachine.transition(PlaybackEvent.Seek)
    }

    private fun goToReleased() {
        stateMachine.transition(PlaybackEvent.Release)
    }

    // 1. requestFocus returns true when granted
    @Test
    fun `requestFocus returns true when granted`() {
        controller.shouldGrantFocus = true
        val result = manager.requestFocus()
        assertTrue(result)
        assertTrue(controller.focusRequested)
    }

    // 2. requestFocus returns false when denied
    @Test
    fun `requestFocus returns false when denied`() {
        controller.shouldGrantFocus = false
        val result = manager.requestFocus()
        assertFalse(result)
        assertTrue(controller.focusRequested)
    }

    // 3. requestFocus registers listener with controller
    @Test
    fun `requestFocus registers listener with controller`() {
        manager.requestFocus()
        assertSame(manager.focusChangeListener, controller.listener)
    }

    // 4. abandonFocus calls controller abandonFocus
    @Test
    fun `abandonFocus calls controller abandonFocus`() {
        manager.requestFocus()
        assertFalse(controller.focusAbandoned)
        manager.abandonFocus()
        assertTrue(controller.focusAbandoned)
    }

    // 5. abandonFocus without prior request does not throw
    @Test
    fun `abandonFocus without prior request does not throw`() {
        manager.abandonFocus()
        assertTrue(controller.focusAbandoned)
    }

    // 6. gain from Ducked calls onUnduck and transitions to Playing
    @Test
    fun `gain from Ducked calls onUnduck and transitions to Playing`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(unduckCalled)
        assertEquals(PlaybackState.Playing, stateMachine.state.value)
    }

    // 7. gain from Ducked does not call onDuck
    @Test
    fun `gain from Ducked does not call onDuck`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(duckCalled)
    }

    // 8. gain from Paused transitions to Playing
    @Test
    fun `gain from Paused transitions to Playing`() {
        goToPaused()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Playing, stateMachine.state.value)
    }

    // 9. gain from Paused does not call onUnduck
    @Test
    fun `gain from Paused does not call onUnduck`() {
        goToPaused()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(unduckCalled)
    }

    // 10. gain from Playing is no-op
    @Test
    fun `gain from Playing is no-op`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Playing, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 11. gain from Idle is no-op
    @Test
    fun `gain from Idle is no-op`() {
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Idle, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 12. gain from Loading is no-op
    @Test
    fun `gain from Loading is no-op`() {
        goToLoading()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Loading, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 13. gain from Buffering is no-op
    @Test
    fun `gain from Buffering is no-op`() {
        goToBuffering()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Buffering, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 14. gain from Seeking is no-op
    @Test
    fun `gain from Seeking is no-op`() {
        goToSeeking()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Seeking, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 15. gain from Released does not throw
    @Test
    fun `gain from Released does not throw`() {
        goToReleased()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(PlaybackState.Released, stateMachine.state.value)
    }

    // 16. permanent loss from Playing transitions to Error
    @Test
    fun `permanent loss from Playing transitions to Error`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
    }

    // 17. permanent loss cause is AudioFocusLostException
    @Test
    fun `permanent loss cause is AudioFocusLostException`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        val state = stateMachine.state.value as PlaybackState.Error
        assertInstanceOf(AudioFocusLostException::class.java, state.cause)
    }

    // 18. permanent loss exception has correct message
    @Test
    fun `permanent loss exception has correct message`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        val state = stateMachine.state.value as PlaybackState.Error
        assertEquals("Permanent audio focus loss", state.cause.message)
    }

    // 19. permanent loss calls abandonFocus
    @Test
    fun `permanent loss calls abandonFocus`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(controller.focusAbandoned)
    }

    // 20. permanent loss from Ducked transitions to Error
    @Test
    fun `permanent loss from Ducked transitions to Error`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
    }

    // 21. permanent loss from Paused transitions to Error
    @Test
    fun `permanent loss from Paused transitions to Error`() {
        goToPaused()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
    }

    // 22. permanent loss from Released does not throw
    @Test
    fun `permanent loss from Released does not throw`() {
        goToReleased()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(PlaybackState.Released, stateMachine.state.value)
    }

    // 23. permanent loss from Idle does not throw
    @Test
    fun `permanent loss from Idle does not throw`() {
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(PlaybackState.Idle, stateMachine.state.value)
    }

    // 24. loss transient from Playing transitions to Paused
    @Test
    fun `loss transient from Playing transitions to Paused`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    // 25. loss transient from Ducked calls onUnduck and transitions to Paused
    @Test
    fun `loss transient from Ducked calls onUnduck and transitions to Paused`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(unduckCalled)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    // 26. loss transient from Ducked does not call onDuck
    @Test
    fun `loss transient from Ducked does not call onDuck`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(duckCalled)
    }

    // 27. loss transient from Idle is no-op
    @Test
    fun `loss transient from Idle is no-op`() {
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlaybackState.Idle, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 28. loss transient from Paused is no-op
    @Test
    fun `loss transient from Paused is no-op`() {
        goToPaused()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 29. loss transient from Released does not throw
    @Test
    fun `loss transient from Released does not throw`() {
        goToReleased()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlaybackState.Released, stateMachine.state.value)
    }

    // 30. loss transient can duck from Playing calls onDuck and transitions to Ducked
    @Test
    fun `loss transient can duck from Playing calls onDuck and transitions to Ducked`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(duckCalled)
        assertEquals(PlaybackState.Ducked, stateMachine.state.value)
    }

    // 31. loss transient can duck from Playing does not call onUnduck
    @Test
    fun `loss transient can duck from Playing does not call onUnduck`() {
        goToPlaying()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(unduckCalled)
    }

    // 32. loss transient can duck from Ducked is no-op
    @Test
    fun `loss transient can duck from Ducked is no-op`() {
        goToDucked()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackState.Ducked, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 33. loss transient can duck from Paused is no-op
    @Test
    fun `loss transient can duck from Paused is no-op`() {
        goToPaused()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackState.Paused, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 34. loss transient can duck from Idle is no-op
    @Test
    fun `loss transient can duck from Idle is no-op`() {
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackState.Idle, stateMachine.state.value)
        assertFalse(duckCalled)
        assertFalse(unduckCalled)
    }

    // 35. loss transient can duck from Released does not throw
    @Test
    fun `loss transient can duck from Released does not throw`() {
        goToReleased()
        manager.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackState.Released, stateMachine.state.value)
    }

    // 36. audioFocusLostException is Exception subclass
    @Test
    fun `audioFocusLostException is Exception subclass`() {
        val ex = AudioFocusLostException("test")
        assertEquals(Exception::class.java, ex.javaClass.superclass)
    }

    // 37. audioFocusLostException stores message
    @Test
    fun `audioFocusLostException stores message`() {
        val msg = "Permanent audio focus loss"
        val ex = AudioFocusLostException(msg)
        assertEquals(msg, ex.message)
        assertNotNull(ex)
    }
}
