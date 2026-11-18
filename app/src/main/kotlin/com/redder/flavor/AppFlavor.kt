package com.redder.flavor

import android.content.Context
import android.content.Intent
import com.redder.playback.GenerationGate
import com.redder.playlist.PlaylistBackend
import com.redder.tts.TtsEngine
import com.redder.tts.VoiceEngine
import com.redder.ui.VoicePackStatus

/**
 * What the build provides to shared code: voices, the playlist and its screens. The `mobile` source set
 * has the one `CurrentFlavor`; shared code uses only this.
 */
interface AppFlavor {
    /** The one voice engine of this build; null when the listener picks it on the dashboard. */
    val fixedVoiceEngine: VoiceEngine?

    /** Called from each process entry point before voices are looked at. */
    fun onProcessStart(context: Context)

    /** True when the voice models need no unpacking. */
    fun voicesReady(context: Context): Boolean

    /** Unpacks voice models that are not on disk yet. Blocking. */
    fun prepareVoices(context: Context)

    fun voicePackStatus(
        context: Context?,
        engine: VoiceEngine,
    ): VoicePackStatus

    fun isWarmUpAllowed(
        context: Context,
        engine: VoiceEngine,
    ): Boolean

    /** Builds the playback engine for a pick; the narrator is the voice its warm-up loads. */
    fun createTtsEngine(
        context: Context,
        engine: VoiceEngine,
        narrator: String,
    ): TtsEngine

    /** Checked before each chunk is generated; null when nothing but the look-ahead holds generation back. */
    fun generationGate(context: Context): GenerationGate? = null

    /** The playlist behind Done, events and position (ADR-008, ADR-009); null for the plain subreddit queue. */
    fun playlistBackend(context: Context): PlaylistBackend? = null

    /** Opens this build's own now-playing screen; null in a build without one. */
    fun nowPlayingIntent(context: Context): Intent? = null
}
