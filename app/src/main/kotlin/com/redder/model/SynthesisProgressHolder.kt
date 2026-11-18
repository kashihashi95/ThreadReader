package com.redder.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory registry holding the active thread's [SynthesisProgress].
 *
 * Facilitates loose coupling between [com.redder.playback.RedderAudioService] and
 * [com.redder.ui.FloatingOverlayService] running within the same application process.
 */
object SynthesisProgressHolder {
    private val _progress = MutableStateFlow(SynthesisProgress())
    val progress: StateFlow<SynthesisProgress> = _progress.asStateFlow()

    /**
     * Updates the current synthesis progress.
     */
    fun update(progress: SynthesisProgress) {
        _progress.value = progress
    }

    /**
     * Resets the synthesis progress to default IDLE state.
     */
    fun reset() {
        _progress.value = SynthesisProgress()
    }
}
