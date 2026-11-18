package com.redder.flavor

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.redder.R
import com.redder.deck.NowPlayingActivity
import com.redder.deck.PlaylistCard
import com.redder.deck.PlaylistSource
import com.redder.local.LocalPlaylist
import com.redder.local.PlaylistListActivity
import com.redder.local.ThermalGate
import com.redder.playback.GenerationGate
import com.redder.playlist.PlaylistBackend
import com.redder.tts.SupertonicAssetManager
import com.redder.tts.SupertonicEngineFactory
import com.redder.tts.TtsEngine
import com.redder.tts.TtsUnavailableException
import com.redder.tts.VoiceEngine
import com.redder.ui.VoicePackStatus

/** The mobile build (ADR-009): everything on the phone, Supertonic the only voice. */
object CurrentFlavor : AppFlavor {
    override val fixedVoiceEngine: VoiceEngine? get() = VoiceEngine.SUPERTONIC

    /** Reads two small files and, on a first run, fills the sub list before the dashboard looks at it. Starts nothing. */
    override fun onProcessStart(context: Context) {
        LocalPlaylist.forContext(context)
    }

    override fun voicesReady(context: Context): Boolean = SupertonicAssetManager.isModelAvailable(context)

    override fun prepareVoices(context: Context) {
        SupertonicAssetManager.unpackAssetsIfMissing(context)
    }

    override fun voicePackStatus(
        context: Context?,
        engine: VoiceEngine,
    ): VoicePackStatus = VoicePackStatus.READY

    override fun isWarmUpAllowed(
        context: Context,
        engine: VoiceEngine,
    ): Boolean = SupertonicAssetManager.isModelAvailable(context)

    /** No fallback voice: a failure says so rather than turning into another engine. */
    override fun createTtsEngine(
        context: Context,
        engine: VoiceEngine,
        narrator: String,
    ): TtsEngine {
        if (!SupertonicAssetManager.isModelAvailable(context) && !SupertonicAssetManager.unpackAssetsIfMissing(context)) {
            throw TtsUnavailableException(context.getString(R.string.voice_pack_not_installed))
        }
        return SupertonicEngineFactory.create(context) { null }
    }

    override fun generationGate(context: Context): GenerationGate? = ThermalGate.forContext(context)

    override fun playlistBackend(context: Context): PlaylistBackend? = LocalPlaylist.forContext(context)

    override fun nowPlayingIntent(context: Context): Intent? = NowPlayingActivity.intent(context)

    fun playlistSource(context: Context): PlaylistSource = LocalPlaylist.forContext(context)
}

/** The playlist, with a way to the whole list. */
@Composable
fun FlavorPlaylistCard() {
    val context = LocalContext.current
    PlaylistCard(onSeeAll = { context.startActivity(PlaylistListActivity.intent(context)) })
}
