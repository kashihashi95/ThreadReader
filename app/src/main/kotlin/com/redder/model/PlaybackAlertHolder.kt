package com.redder.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why playback stopped, for the dashboard and the HUD, such as a voice that cannot run. It outlives the
 * audio service, so the reason is still there when the listener comes back; the next thread clears it.
 */
object PlaybackAlertHolder {
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun show(text: String) {
        mutableMessage.value = text
    }

    fun clear() {
        mutableMessage.value = null
    }
}
