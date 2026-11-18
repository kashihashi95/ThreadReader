package com.redder.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.TreeMap

/** A second check on generating ahead, for a build that watches something besides the look-ahead (ADR-009). */
interface GenerationGate {
    /** Suspends while no chunk should be generated. */
    suspend fun awaitClear()

    fun onSynthesized(
        index: Int,
        audioMs: Long,
    ) = Unit
}

/**
 * Keeps synthesis from running far ahead of the listener. Once playback has started, the producer
 * waits while the player is paused or while [maxAheadMs] of audio is already queued after the current
 * card. Before that it is always open, so the start buffer can fill; [StartBufferPolicy] bounds that
 * part. Waiting suspends on a state flow, so it costs no CPU.
 *
 * The playhead only moves between cards, so the bound ignores what is left of the current one.
 */
class SynthesisThrottle(
    private val maxAheadMs: Long = MAX_LOOK_AHEAD_MS,
    startIndex: Int = 0,
    private val log: (String) -> Unit = {},
) {
    private data class Gate(val started: Boolean = false, val paused: Boolean = false, val aheadMs: Long = 0L) {
        fun isOpen(maxAheadMs: Long): Boolean = !started || (!paused && aheadMs < maxAheadMs)
    }

    private val gate = MutableStateFlow(Gate())
    private val durations = TreeMap<Int, Long>()
    private var playhead = startIndex

    /** Audio queued after the current card, in milliseconds. */
    val aheadMs: Long get() = gate.value.aheadMs

    /** Suspends until the next chunk may be synthesized. */
    suspend fun awaitTurn() {
        val now = gate.value
        if (now.isOpen(maxAheadMs)) return
        log("LOOKAHEAD wait paused=${now.paused} ahead_ms=${now.aheadMs}")
        gate.first { it.isOpen(maxAheadMs) }
        log("LOOKAHEAD go ahead_ms=${gate.value.aheadMs}")
    }

    @Synchronized
    fun onSynthesized(
        index: Int,
        audioMs: Long,
    ) {
        durations[index] = audioMs
        publishAhead()
    }

    /** Playback moved to transcript card [index]. */
    @Synchronized
    fun onPlayhead(index: Int) {
        playhead = index
        publishAhead()
    }

    /** Playback has begun: the pause and look-ahead limits apply from now on. */
    fun playbackStarted() {
        gate.update { it.copy(started = true, paused = false) }
    }

    fun setPaused(paused: Boolean) {
        gate.update { it.copy(paused = paused) }
    }

    private fun publishAhead() {
        val ahead = durations.tailMap(playhead, false).values.sum()
        gate.update { it.copy(aheadMs = ahead) }
    }

    companion object {
        /** Most audio to keep synthesized ahead of the playhead. */
        const val MAX_LOOK_AHEAD_MS: Long = 150_000L
    }
}
