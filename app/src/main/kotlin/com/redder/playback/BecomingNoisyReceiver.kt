package com.redder.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

interface ReceiverRegistrar {
    fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    )

    fun unregisterReceiver(receiver: BroadcastReceiver)
}

class ContextReceiverRegistrar(
    private val context: Context,
) : ReceiverRegistrar {
    override fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        context.unregisterReceiver(receiver)
    }
}

class BecomingNoisyReceiver(
    private val onNoiseDetected: () -> Unit,
    private val registrar: ReceiverRegistrar? = null,
    private val filterProvider: () -> IntentFilter = { createFilter() },
) : BroadcastReceiver() {
    var isRegistered: Boolean = false
        private set

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        onActionReceived(intent.action)
    }

    fun onActionReceived(action: String?) {
        if (action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            onNoiseDetected()
        }
    }

    fun register(context: Context) {
        if (isRegistered) return
        val activeRegistrar = registrar ?: ContextReceiverRegistrar(context)
        activeRegistrar.registerReceiver(this, filterProvider())
        isRegistered = true
    }

    fun unregister(context: Context) {
        if (!isRegistered) return
        val activeRegistrar = registrar ?: ContextReceiverRegistrar(context)
        try {
            activeRegistrar.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            // Ignore if already unregistered in framework
        }
        isRegistered = false
    }

    companion object {
        fun createFilter(): IntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }
}
