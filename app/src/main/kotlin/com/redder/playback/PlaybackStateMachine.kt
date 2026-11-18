package com.redder.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class PlaybackState {
    data object Idle : PlaybackState()

    data object Loading : PlaybackState()

    data object Buffering : PlaybackState()

    data object Playing : PlaybackState()

    data object Paused : PlaybackState()

    data object Ducked : PlaybackState()

    data object Seeking : PlaybackState()

    data class Error(val cause: Throwable) : PlaybackState()

    data object Released : PlaybackState()
}

sealed class PlaybackEvent {
    data object Load : PlaybackEvent()

    data object BufferingStarted : PlaybackEvent()

    data object FirstChunkReady : PlaybackEvent()

    data object Pause : PlaybackEvent()

    data object Resume : PlaybackEvent()

    data object Duck : PlaybackEvent()

    data object Unduck : PlaybackEvent()

    data object Seek : PlaybackEvent()

    data object SeekComplete : PlaybackEvent()

    data class Fail(val cause: Throwable) : PlaybackEvent()

    data object Release : PlaybackEvent()
}

class IllegalStateTransitionException(
    from: PlaybackState,
    event: PlaybackEvent,
) : IllegalStateException("Illegal transition: ${from::class.simpleName} + ${event::class.simpleName}")

class PlaybackStateMachine {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun transition(event: PlaybackEvent) {
        synchronized(this) {
            val current = _state.value
            val next = computeNext(current, event)
            _state.value = next
        }
    }

    fun reset() {
        synchronized(this) {
            val current = _state.value
            if (current is PlaybackState.Error || current is PlaybackState.Released) {
                _state.value = PlaybackState.Idle
            } else {
                throw IllegalStateTransitionException(current, PlaybackEvent.Load)
            }
        }
    }

    private fun computeNext(
        current: PlaybackState,
        event: PlaybackEvent,
    ): PlaybackState {
        // Release is valid from any state except Released
        if (event is PlaybackEvent.Release) {
            return when (current) {
                is PlaybackState.Released -> throw IllegalStateTransitionException(current, event)
                else -> PlaybackState.Released
            }
        }

        return when (current) {
            is PlaybackState.Idle ->
                when (event) {
                    is PlaybackEvent.Load -> PlaybackState.Loading
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Loading ->
                when (event) {
                    is PlaybackEvent.BufferingStarted -> PlaybackState.Buffering
                    is PlaybackEvent.Fail -> PlaybackState.Error(event.cause)
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Buffering ->
                when (event) {
                    is PlaybackEvent.FirstChunkReady -> PlaybackState.Playing
                    is PlaybackEvent.Fail -> PlaybackState.Error(event.cause)
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Playing ->
                when (event) {
                    is PlaybackEvent.Pause -> PlaybackState.Paused
                    is PlaybackEvent.Duck -> PlaybackState.Ducked
                    is PlaybackEvent.Seek -> PlaybackState.Seeking
                    is PlaybackEvent.Fail -> PlaybackState.Error(event.cause)
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Paused ->
                when (event) {
                    is PlaybackEvent.Resume -> PlaybackState.Playing
                    is PlaybackEvent.Fail -> PlaybackState.Error(event.cause)
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Ducked ->
                when (event) {
                    is PlaybackEvent.Unduck -> PlaybackState.Playing
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Seeking ->
                when (event) {
                    is PlaybackEvent.SeekComplete -> PlaybackState.Playing
                    else -> throw IllegalStateTransitionException(current, event)
                }
            is PlaybackState.Error -> throw IllegalStateTransitionException(current, event)
            is PlaybackState.Released -> throw IllegalStateTransitionException(current, event)
        }
    }
}
