package com.redder.audio

import android.media.AudioFocusRequest
import android.media.AudioManager
import com.redder.playback.IllegalStateTransitionException
import com.redder.playback.PlaybackEvent
import com.redder.playback.PlaybackState
import com.redder.playback.PlaybackStateMachine
import java.util.logging.Logger

class AudioFocusLostException(message: String) : Exception(message)

interface AudioFocusRequestController {
    fun requestFocus(listener: AudioManager.OnAudioFocusChangeListener): Boolean

    fun abandonFocus()
}

class DefaultAudioFocusRequestController(
    private val audioManager: AudioManager,
) : AudioFocusRequestController {
    private var focusRequest: AudioFocusRequest? = null

    override fun requestFocus(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}

class AudioFocusManager(
    private val stateMachine: PlaybackStateMachine,
    private val onDuck: () -> Unit,
    private val onUnduck: () -> Unit,
    private val controller: AudioFocusRequestController,
) {
    private val logger = Logger.getLogger(AudioFocusManager::class.java.name)

    constructor(
        stateMachine: PlaybackStateMachine,
        onDuck: () -> Unit,
        onUnduck: () -> Unit,
        audioManager: AudioManager,
    ) : this(stateMachine, onDuck, onUnduck, DefaultAudioFocusRequestController(audioManager))

    internal val focusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> handleGain()
                AudioManager.AUDIOFOCUS_LOSS -> handleLoss()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handleLossTransient()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> handleDuck()
            }
        }

    fun requestFocus(): Boolean = controller.requestFocus(focusChangeListener)

    fun abandonFocus() {
        controller.abandonFocus()
    }

    private fun safeTransition(event: PlaybackEvent) {
        try {
            stateMachine.transition(event)
        } catch (e: IllegalStateTransitionException) {
            logger.warning("Ignoring illegal audio focus transition: ${e.message}")
        }
    }

    private fun handleGain() {
        when (stateMachine.state.value) {
            is PlaybackState.Ducked -> {
                onUnduck()
                safeTransition(PlaybackEvent.Unduck)
            }
            is PlaybackState.Paused -> safeTransition(PlaybackEvent.Resume)
            else -> Unit
        }
    }

    private fun handleLoss() {
        abandonFocus()
        if (stateMachine.state.value is PlaybackState.Ducked) {
            onUnduck()
            safeTransition(PlaybackEvent.Unduck)
        }
        safeTransition(PlaybackEvent.Fail(AudioFocusLostException("Permanent audio focus loss")))
    }

    private fun handleLossTransient() {
        when (stateMachine.state.value) {
            is PlaybackState.Playing -> safeTransition(PlaybackEvent.Pause)
            is PlaybackState.Ducked -> {
                onUnduck()
                safeTransition(PlaybackEvent.Unduck)
                safeTransition(PlaybackEvent.Pause)
            }
            else -> Unit
        }
    }

    private fun handleDuck() {
        if (stateMachine.state.value is PlaybackState.Playing) {
            onDuck()
            safeTransition(PlaybackEvent.Duck)
        }
    }
}
