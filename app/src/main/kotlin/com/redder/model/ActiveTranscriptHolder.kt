package com.redder.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory registry holding the active thread's linearized transcript [DialogueItem]s
 * and the active playback utterance index.
 *
 * Facilitates loose coupling between [com.redder.playback.RedderAudioService] and
 * [com.redder.ui.FloatingOverlayService] running within the same application process.
 */
object ActiveTranscriptHolder {
    private val _transcript = MutableStateFlow<List<DialogueItem>>(emptyList())
    val transcript: StateFlow<List<DialogueItem>> = _transcript.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /**
     * Updates the full dialogue transcript items and resets the active utterance index.
     */
    fun setTranscript(
        items: List<DialogueItem>,
        initialIndex: Int = 0,
    ) {
        _transcript.value = items
        _currentIndex.value = initialIndex
    }

    /**
     * Updates the current active utterance index.
     */
    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
    }

    /**
     * Clears the active transcript and resets index to 0.
     */
    fun clear() {
        _transcript.value = emptyList()
        _currentIndex.value = 0
    }
}
