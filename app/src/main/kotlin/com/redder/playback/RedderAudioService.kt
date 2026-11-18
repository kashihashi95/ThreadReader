package com.redder.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.redder.audio.AudioFocusManager
import com.redder.audio.WavUtil
import com.redder.flavor.CurrentFlavor
import com.redder.model.ActiveTranscriptHolder
import com.redder.model.DialogueItem
import com.redder.model.PlaybackAlertHolder
import com.redder.model.RedditThread
import com.redder.model.SubredditQueueHolder
import com.redder.model.SubredditQueueState
import com.redder.model.SynthesisProgress
import com.redder.model.SynthesisProgressHolder
import com.redder.model.SynthesisStage
import com.redder.network.RedditApiClient
import com.redder.network.RedditUrlResolver
import com.redder.orchestrator.PlaybackOrchestrator
import com.redder.orchestrator.SynthesizedChunk
import com.redder.parser.DialogueNode
import com.redder.parser.DialogueTreePruner
import com.redder.parser.ListingPost
import com.redder.parser.PolymorphicRedditParser
import com.redder.parser.RedlibHtmlParser
import com.redder.parser.SubredditListingParser
import com.redder.playlist.DoneButtonHolder
import com.redder.playlist.DoneButtonState
import com.redder.playlist.PlaylistBackend
import com.redder.playlist.ServerPost
import com.redder.queue.PostQueue
import com.redder.queue.QueueNext
import com.redder.queue.SubredditQueue
import com.redder.queue.SubredditStore
import com.redder.sanitizer.DialogueChunk
import com.redder.sanitizer.DialogueFormatter
import com.redder.sanitizer.TextSanitizer
import com.redder.settings.RedderPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.ReloadableTtsEngine
import com.redder.tts.ToneFallbackTtsEngine
import com.redder.tts.TtsEngine
import com.redder.tts.TtsUnavailableException
import com.redder.tts.VoiceEngine
import com.redder.ui.ShareReceiverActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller seam for audio player actions (play, pause, seek, volume, playlist).
 */
interface AudioPlayerController {
    fun play()

    fun pause()

    fun seekToNext()

    fun seekToPrevious()

    var volume: Float

    fun setPlaybackSpeed(speed: Float) {}

    fun release()

    /** Replaces the playlist with one item; [mediaId] is its [ChunkMediaId]. */
    fun setMediaItem(
        uri: Uri,
        title: String,
        artist: String,
        mediaId: String,
    )

    /** Appends an item; [mediaId] is its [ChunkMediaId]. */
    fun addMediaItem(
        uri: Uri,
        title: String,
        artist: String,
        mediaId: String,
    )

    fun prepare()

    fun clearMediaItems()

    /** Removes the playlist items from [fromIndex] (inclusive) to [toIndex] (exclusive). */
    fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) {}

    val currentMediaItemIndex: Int

    /** True when the player has played everything it has. */
    val isEnded: Boolean get() = false

    /** Playback position in the current item, in milliseconds. */
    val currentPositionMs: Long get() = 0L

    /** Seeks to [positionMs] in the playlist item at [mediaItemIndex]. */
    fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {}

    fun addListener(listener: Player.Listener)
}

/**
 * Abstraction seam encapsulating active [MediaSession] and [AudioPlayerController] lifecycle.
 */
interface MediaSessionHolder {
    val session: MediaSession?
    val playerController: AudioPlayerController?

    /**
     * Routes "previous" from every media controller (the HUD, the system media controls, Bluetooth)
     * to [handler] instead of the player's own seekToPrevious; null restores the default.
     */
    fun setPreviousHandler(handler: (() -> Unit)?) {}

    /**
     * Routes "next" from every media controller to [handler] and keeps it available on the last clip;
     * null restores the player's own seekToNext. Set while a subreddit queue runs.
     */
    fun setNextPostHandler(handler: (() -> Unit)?) {}

    /**
     * Offers Done to the system's media controls, which show the session's custom actions rather than
     * the notification's on current Android; null takes it away. Only a playlist queue uses this.
     */
    fun setDoneHandler(
        label: String,
        handler: (() -> Unit)?,
    ) {}

    fun release()
}

/**
 * Default Android platform implementation of [AudioPlayerController] wrapping a Media3 [Player].
 */
class DefaultAudioPlayerController(
    private val player: Player,
) : AudioPlayerController {
    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekToNext() {
        player.seekToNext()
    }

    override fun seekToPrevious() {
        player.seekToPrevious()
    }

    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun release() {
        player.release()
    }

    override fun setMediaItem(
        uri: Uri,
        title: String,
        artist: String,
        mediaId: String,
    ) {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build()
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        player.setMediaItem(mediaItem)
    }

    override fun addMediaItem(
        uri: Uri,
        title: String,
        artist: String,
        mediaId: String,
    ) {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build()
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        player.addMediaItem(mediaItem)
        // A slow engine can let the queue run dry; ExoPlayer then sits in STATE_ENDED and does not
        // move on to items appended later, so step onto the new item to carry on.
        if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    override fun prepare() {
        player.prepare()
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
    }

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) {
        player.removeMediaItems(fromIndex, toIndex)
    }

    override val currentMediaItemIndex: Int
        get() = player.currentMediaItemIndex

    override val currentPositionMs: Long
        get() = player.currentPosition

    override val isEnded: Boolean
        get() = player.playbackState == Player.STATE_ENDED

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        player.seekTo(mediaItemIndex, positionMs)
    }

    override fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }
}

/**
 * Resumption callback returning empty media items to prevent [UnsupportedOperationException]
 * during platform media session resumption queries.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // CommandButton, the custom layout
class ResumptionCallback(
    private val offersDone: Boolean = false,
) : MediaSession.Callback {
    @Volatile
    var doneHandler: (() -> Unit)? = null

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (!offersDone) return super.onConnect(session, controller)
        val commands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_DONE, android.os.Bundle.EMPTY))
                .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: android.os.Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == COMMAND_DONE) {
            doneHandler?.invoke()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L),
        )

    companion object {
        const val COMMAND_DONE: String = "com.redder.playback.command.DONE"
    }
}

/**
 * Default Android platform implementation of [MediaSessionHolder] using [ExoPlayer] and [MediaSession].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // ForwardingPlayer
class DefaultMediaSessionHolder(
    context: Context,
    existingPlayer: Player? = null,
    private val callback: MediaSession.Callback = ResumptionCallback(),
) : MediaSessionHolder {
    private val audioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
    private val exoPlayer: Player =
        existingPlayer ?: ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, false)
            .build()

    @Volatile
    private var previousHandler: (() -> Unit)? = null

    @Volatile
    private var nextPostHandler: (() -> Unit)? = null

    /** With a queue running, "next" is always possible: the next post comes after the last clip. */
    private fun withNextPost(commands: Player.Commands): Player.Commands =
        if (nextPostHandler == null) {
            commands
        } else {
            commands.buildUpon().add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        }

    /**
     * The player controllers see: "previous" goes to [previousHandler] (see [setPreviousHandler]) and
     * "next" to [nextPostHandler] while it is set (see [setNextPostHandler]).
     */
    private val sessionPlayer: Player =
        object : ForwardingPlayer(exoPlayer) {
            private val wrappedListeners = java.util.concurrent.ConcurrentHashMap<Player.Listener, Player.Listener>()

            override fun seekToNext() {
                nextPostHandler?.invoke() ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                nextPostHandler?.invoke() ?: super.seekToNextMediaItem()
            }

            override fun getAvailableCommands(): Player.Commands = withNextPost(super.getAvailableCommands())

            override fun isCommandAvailable(command: Int): Boolean = withNextPost(super.getAvailableCommands()).contains(command)

            // The session keeps the commands each change reports, so the change has to carry "next" too.
            // A proxy, because Kotlin's delegation would not forward Player.Listener's default methods.
            override fun addListener(listener: Player.Listener) {
                val wrapped =
                    java.lang.reflect.Proxy.newProxyInstance(
                        Player.Listener::class.java.classLoader,
                        arrayOf(Player.Listener::class.java),
                    ) { _, method, args ->
                        val arguments = args.orEmpty()
                        val first = arguments.firstOrNull()
                        val passed = if (first is Player.Commands) arrayOf<Any?>(withNextPost(first)) else arguments
                        try {
                            method.invoke(listener, *passed)
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                    } as Player.Listener
                wrappedListeners[listener] = wrapped
                super.addListener(wrapped)
            }

            override fun removeListener(listener: Player.Listener) {
                wrappedListeners.remove(listener)?.let { super.removeListener(it) }
            }

            override fun seekToPrevious() {
                previousHandler?.invoke() ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                previousHandler?.invoke() ?: super.seekToPreviousMediaItem()
            }
        }
    private val mediaSession: MediaSession =
        MediaSession.Builder(context, sessionPlayer)
            .setCallback(callback)
            .apply {
                CurrentFlavor.nowPlayingIntent(context)?.let { intent ->
                    setSessionActivity(
                        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            .build()
    private val defaultController = DefaultAudioPlayerController(exoPlayer)

    override val session: MediaSession get() = mediaSession
    override val playerController: AudioPlayerController get() = defaultController

    override fun setPreviousHandler(handler: (() -> Unit)?) {
        previousHandler = handler
    }

    override fun setNextPostHandler(handler: (() -> Unit)?) {
        nextPostHandler = handler
    }

    override fun setDoneHandler(
        label: String,
        handler: (() -> Unit)?,
    ) {
        val offering = callback as? ResumptionCallback ?: return
        offering.doneHandler = handler
        val buttons =
            if (handler == null) {
                emptyList()
            } else {
                listOf(
                    CommandButton.Builder()
                        .setDisplayName(label)
                        .setIconResId(android.R.drawable.checkbox_on_background)
                        .setSessionCommand(SessionCommand(ResumptionCallback.COMMAND_DONE, android.os.Bundle.EMPTY))
                        .build(),
                )
            }
        mediaSession.setCustomLayout(buttons)
    }

    override fun release() {
        mediaSession.release()
        exoPlayer.release()
    }
}

/**
 * Platform seam for host service interactions (foreground lifecycle, noisy receiver, stop).
 */
interface ServiceHost {
    fun startForeground(
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int,
    ): Boolean

    fun stopForeground(flags: Int)

    /**
     * Returns a running service to the foreground. Unlike [startForeground], a refusal leaves the
     * service running (audio may be playing).
     */
    fun resumeForeground(
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int,
    ): Boolean = startForeground(notificationId, notification, foregroundServiceType)

    fun stopSelf()

    fun registerNoisyReceiver()

    fun unregisterNoisyReceiver()

    fun isNoisyReceiverRegistered(): Boolean

    fun updateNotification(
        notificationId: Int,
        notification: Notification,
    )
}

/**
 * Default Android platform implementation of [ServiceHost].
 */
class DefaultServiceHost(
    private val service: Service? = null,
    private val noisyReceiverProvider: () -> BecomingNoisyReceiver?,
    private val foregroundStarter: (Int, Notification, Int) -> Unit = { id, notif, type ->
        val svc = service ?: error("Service required")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(svc, id, notif, type)
        } else {
            ServiceCompat.startForeground(svc, id, notif, 0)
        }
    },
    private val foregroundStopper: (Int) -> Unit = { flags ->
        val svc = service ?: error("Service required")
        ServiceCompat.stopForeground(svc, flags)
    },
    private val notificationCanceler: (Int) -> Unit = { id ->
        val svc = service ?: error("Service required")
        (svc.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id)
    },
    private val serviceStopper: () -> Unit = { service?.stopSelf() },
    private val notificationUpdater: (Int, Notification) -> Unit = { id, notif ->
        val svc = service ?: error("Service required")
        val manager = svc.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(id, notif)
    },
) : ServiceHost {
    override fun startForeground(
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int,
    ): Boolean {
        return try {
            foregroundStarter(notificationId, notification, foregroundServiceType)
            true
        } catch (e: SecurityException) {
            try {
                Log.e("RedderAudioService", "startForeground SecurityException", e)
            } catch (_: Throwable) {
            }
            handleForegroundStartFailure(notificationId)
            false
        } catch (e: IllegalStateException) {
            try {
                Log.e("RedderAudioService", "startForeground IllegalStateException", e)
            } catch (_: Throwable) {
            }
            handleForegroundStartFailure(notificationId)
            false
        } catch (e: IllegalArgumentException) {
            try {
                Log.e("RedderAudioService", "startForeground IllegalArgumentException", e)
            } catch (_: Throwable) {
            }
            handleForegroundStartFailure(notificationId)
            false
        }
    }

    override fun resumeForeground(
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int,
    ): Boolean =
        try {
            foregroundStarter(notificationId, notification, foregroundServiceType)
            true
        } catch (e: IllegalStateException) {
            runCatching { Log.w("RedderAudioService", "resumeForeground refused", e) }
            false
        } catch (e: SecurityException) {
            runCatching { Log.w("RedderAudioService", "resumeForeground refused", e) }
            false
        }

    private fun handleForegroundStartFailure(notificationId: Int) {
        try {
            notificationCanceler(notificationId)
        } catch (_: Throwable) {
            // Ignore cancellation errors during teardown
        }
        stopForeground(ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun stopForeground(flags: Int) {
        foregroundStopper(flags)
    }

    override fun stopSelf() {
        serviceStopper()
    }

    override fun registerNoisyReceiver() {
        service?.let { noisyReceiverProvider()?.register(it) }
    }

    override fun unregisterNoisyReceiver() {
        service?.let { noisyReceiverProvider()?.unregister(it) }
    }

    override fun isNoisyReceiverRegistered(): Boolean = noisyReceiverProvider()?.isRegistered ?: false

    override fun updateNotification(
        notificationId: Int,
        notification: Notification,
    ) {
        notificationUpdater(notificationId, notification)
    }
}

class RedderAudioServiceDelegate(
    val host: ServiceHost,
    val notificationManager: MediaNotificationManager,
    val sessionHolder: MediaSessionHolder? = null,
    val stateMachine: PlaybackStateMachine = PlaybackStateMachine(),
    var audioFocusManager: AudioFocusManager? = null,
    var noisyReceiver: BecomingNoisyReceiver? = null,
    var currentTitle: String = notificationManager.strings.defaultTitle,
    var currentText: String = notificationManager.strings.defaultText,
    val onSkipNext: () -> Unit = {},
    val onSkipPrevious: () -> Unit = {},
    val urlResolver: RedditUrlResolver = RedditUrlResolver(),
    val apiClient: RedditApiClient = RedditApiClient(),
    val parser: PolymorphicRedditParser = PolymorphicRedditParser(),
    val redlibParser: RedlibHtmlParser = RedlibHtmlParser(),
    val pruner: DialogueTreePruner = DialogueTreePruner(),
    val sanitizer: TextSanitizer = TextSanitizer(),
    val formatter: DialogueFormatter = DialogueFormatter(textSanitizer = sanitizer),
    val contextProvider: () -> Context? = { null },
    val prefsProvider: () -> SharedPreferences? = {
        try {
            contextProvider()?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    },
    val readUsernamesProvider: () -> Boolean = {
        try {
            prefsProvider()?.getBoolean(RedderPreferences.KEY_READ_USERNAMES, true) ?: true
        } catch (_: Throwable) {
            true
        }
    },
    val playbackSpeedProvider: () -> Float = {
        try {
            prefsProvider()?.getFloat(RedderPreferences.KEY_PLAYBACK_SPEED, RedderPreferences.DEFAULT_PLAYBACK_SPEED)
                ?: RedderPreferences.DEFAULT_PLAYBACK_SPEED
        } catch (_: Throwable) {
            RedderPreferences.DEFAULT_PLAYBACK_SPEED
        }
    },
    val formatChunks: (RedditThread, List<DialogueNode>) -> List<DialogueChunk> = { t, n ->
        formatter.format(t, n, readUsernamesProvider())
    },
    val ttsEngineProvider: (Context?) -> TtsEngine = { ToneFallbackTtsEngine() },
    val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val mainDispatcher: CoroutineDispatcher =
        try {
            Dispatchers.Main
        } catch (_: Throwable) {
            Dispatchers.Unconfined
        },
    val cacheDirProvider: () -> File? = { null },
    val uriConverter: (File) -> Uri = { Uri.fromFile(it) },
    /** Gate for [startWarmUp]; the service requires the neural model assets to be unpacked. */
    val warmUpAllowed: () -> Boolean = { true },
    val timing: TimeToFirstAudioLog = TimeToFirstAudioLog(),
    /** Voice engine picked on the dashboard; read when each thread starts. */
    val voiceEngineProvider: () -> VoiceEngine = { RedderPreferences.readVoiceEngine(prefsProvider()) },
    /** Narrator (OP) voice of a voice engine. */
    val narratorVoiceProvider: (VoiceEngine) -> String = { RedderPreferences.readNarrator(prefsProvider(), it) },
    /** Builds the TTS engine for a voice engine; the narrator is the voice its warm-up loads. */
    val voiceEngineFactory: (Context?, VoiceEngine, String) -> TtsEngine = { ctx, _, _ -> ttsEngineProvider(ctx) },
    /** Shows a short message to the user (a toast), such as [PlaybackStrings.notGeneratedYet]. */
    val showMessage: (String) -> Unit = {},
    /** Playlist-to-transcript mapping and chunk WAVs; played ones are deleted as playback moves on. */
    internal val chunkFiles: ChunkFileWindow = ChunkFileWindow(),
    /** Gate for [startWarmUp] per voice engine. */
    val warmUpAllowedFor: (VoiceEngine) -> Boolean = { warmUpAllowed() },
    /**
     * Formats a thread with the picked engine's voices. Supertonic with its default narrator goes
     * through [formatChunks] unchanged; other picks allocate from [EngineVoices].
     */
    val formatChunksForVoices: (RedditThread, List<DialogueNode>, VoiceEngine, String) -> List<DialogueChunk> = { t, n, engine, narrator ->
        if (engine == VoiceEngine.SUPERTONIC && narrator == EngineVoices.defaultNarrator(engine)) {
            formatChunks(t, n)
        } else {
            DialogueFormatter(textSanitizer = sanitizer, voiceAllocator = EngineVoices.allocator(engine, narrator))
                .format(t, n, readUsernamesProvider())
        }
    },
    /** How long a paused or finished player, or a service with nothing to do, waits before letting go. */
    val idleReleaseMs: Long = IDLE_RELEASE_MS,
    /** How long a warmed engine waits for a thread to start synthesizing before it is freed. */
    val warmUpTimeoutMs: Long = WARM_UP_TIMEOUT_MS,
    /** Audio to keep synthesized ahead of the playhead. */
    val maxLookAheadMs: Long = SynthesisThrottle.MAX_LOOK_AHEAD_MS,
    /** The saved subreddits and played history. */
    val subredditStoreProvider: () -> SubredditStore? = { null },
    /** Posts in a row that may fail to load before a queue gives up (a mirror outage, not one bad post). */
    val maxPostFailures: Int = MAX_CONSECUTIVE_POST_FAILURES,
    /** The playlist behind Done, events and position, when this build has one (ADR-009). */
    val playlistBackendProvider: () -> PlaylistBackend? = { null },
    /** How often the playlist hears where listening has got to. */
    val positionReportMs: Long = POSITION_REPORT_MS,
    /** Holds generation back for something besides the look-ahead, in a build that has one. */
    val generationGateProvider: () -> GenerationGate? = { null },
) {
    internal var isForegroundStarted: Boolean = true
    internal var processingJob: Job? = null

    @Volatile
    internal var activeOrchestrator: PlaybackOrchestrator? = null

    /**
     * TTS engine of the current voice engine (Supertonic holds ~400 MB of ONNX sessions).
     * Its models are only in memory while they are needed: they are freed when all audio
     * is synthesized, after [idleReleaseMs] paused, when a warm-up goes unused, and on stop, and they
     * load again on the next synthesis. Released in [onDestroy] or when the voice engine changes.
     */
    @Volatile
    internal var activeTtsEngine: ReloadableTtsEngine? = null

    /** Voice engine [activeTtsEngine] was built for; null until one is built (or when injected by tests). */
    @Volatile
    internal var activeVoiceEngine: VoiceEngine? = null

    /** Voice engine and narrator picked for the current (or next) thread; see [selectVoiceEngine]. */
    @Volatile
    internal var requestedVoiceEngine: VoiceEngine = VoiceEngine.DEFAULT

    @Volatile
    internal var requestedNarrator: String = EngineVoices.defaultNarrator(VoiceEngine.DEFAULT)

    /** Voice engine the last finished warm-up was for. */
    @Volatile
    internal var warmedVoiceEngine: VoiceEngine? = null

    /** Serializes engine use (warm-up and thread synthesis) so no two jobs ever run inference concurrently. */
    internal val processingMutex = Mutex()

    private val warmUpLock = Any()
    internal var warmUpJob: Job? = null

    /** True once the cached engine has been warmed up (or a warm-up attempt finished). */
    @Volatile
    internal var isEngineWarmedUp: Boolean = false

    /** The thread being played, kept so a cleaned-up card can be re-synthesized; null while none is. */
    @Volatile
    internal var activeThread: ActiveThread? = null

    internal class ActiveThread(
        val thread: RedditThread,
        val chunks: List<DialogueChunk>,
        val engine: VoiceEngine,
        val narrator: String,
        val serverPost: ServerPost? = null,
    )

    /** Synthesized clips of the current thread, kept so re-synthesizing a card does not redo them. */
    private val keptClips = KeptClips()

    /** Look-ahead and pause gate of the running pipeline. */
    @Volatile
    internal var throttle: SynthesisThrottle? = null

    /** True while a pipeline is synthesizing a thread (or waiting at the [throttle]). */
    @Volatile
    private var synthesisRunning: Boolean = false

    /** True once every chunk of the current thread has been through synthesis. */
    @Volatile
    private var generationFinished: Boolean = false

    /** True when the engine became unavailable: what was already made still plays, and no more is made. */
    @Volatile
    private var generationHalted: Boolean = false

    /** True once the player has also played all of it. */
    @Volatile
    private var threadFinished: Boolean = false

    @Volatile
    private var pausedMarked: Boolean = false

    @Volatile
    private var foregroundDropped: Boolean = false
    private var serviceForegroundType: Int = 0

    private val idleLock = Any()
    private var idleJob: Job? = null
    private var warmUpWatchdog: Job? = null

    /** The running subreddit queue; null for a single shared thread. See [handlePlaySubreddits]. */
    @Volatile
    internal var queueSession: QueueSession? = null

    /** The post a shared thread is, once known; recorded as played when it finishes. */
    @Volatile
    private var manualPost: ManualPost? = null

    private class ManualPost(val id: String, val sub: String)

    /** @property backend Set when the queue is a playlist. */
    internal class QueueSession(val queue: PostQueue, val backend: PlaylistBackend? = null) {
        var positionTicker: Job? = null

        /** The post being read. It counts as played only once it finishes or is skipped. */
        @Volatile
        var current: ListingPost? = null

        /** The next post's text, fetched near the end of the current one. */
        @Volatile
        var prefetch: Deferred<PreparedPost>? = null

        var consecutiveFailures = 0
    }

    /** @property thread The post's text when it was fetched ahead of time. */
    internal class PreparedPost(val next: QueueNext, val thread: RedditThread? = null, val serverPost: ServerPost? = null)

    /** How the post being read ended, as far as the playlist is concerned. */
    private enum class PostEnd { FINISHED, SKIPPED, REMOVED }

    private enum class PipelineOutcome { PLAYED, NOTHING_TO_READ, FAILED }

    private var registeredPrefs: SharedPreferences? = null
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RedderPreferences.KEY_PLAYBACK_SPEED) {
                applyPlaybackSpeed()
            }
        }

    fun onCreate(
        context: Context? = null,
        foregroundServiceType: Int? = null,
    ) {
        // Nothing plays yet, so chunk files left by an earlier session (for example a killed process) can go.
        runCatching { ChunkFileWindow.deleteLeftovers(cacheDirProvider()) }
        notificationManager.createNotificationChannel()

        val initialNotification =
            notificationManager.buildNotification(
                title = currentTitle,
                text = currentText,
                isPlaying = false,
                session = sessionHolder?.session,
            )

        val effectiveForegroundType =
            foregroundServiceType ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }

        serviceForegroundType = effectiveForegroundType
        val started =
            host.startForeground(
                MediaNotificationManager.NOTIFICATION_ID,
                initialNotification,
                effectiveForegroundType,
            )

        if (!started) {
            isForegroundStarted = false
            onDestroy()
            return
        }

        try {
            prefsProvider()?.let { prefs ->
                prefs.registerOnSharedPreferenceChangeListener(prefsListener)
                registeredPrefs = prefs
            }
        } catch (_: Throwable) {
            // Preferences unavailable (e.g. unit tests); the saved speed still applies on play
        }

        if (noisyReceiver == null) {
            noisyReceiver =
                BecomingNoisyReceiver(
                    onNoiseDetected = { handleNoisyDetected() },
                )
        }

        if (audioFocusManager == null && context != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                audioFocusManager =
                    AudioFocusManager(
                        stateMachine = stateMachine,
                        onDuck = { handleDuck() },
                        onUnduck = { handleUnduck() },
                        audioManager = audioManager,
                    )
            }
        }

        val controller = sessionHolder?.playerController
        controller?.addListener(
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    timing.onPlayerState(
                        isReady = player.playbackState == Player.STATE_READY,
                        isPlaying = player.isPlaying,
                    )
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    onPlayerPlayWhenReady(playWhenReady)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    onPlayerState(playbackState)
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    // The current card comes from the item's media ID: positions shift as played items are trimmed.
                    val transcriptIndex = ChunkMediaId.transcriptIndex(mediaItem?.mediaId)
                    if (transcriptIndex != null) {
                        ActiveTranscriptHolder.setCurrentIndex(transcriptIndex)
                        throttle?.onPlayhead(transcriptIndex)
                    }
                    val index = controller.currentMediaItemIndex
                    if (index >= 0) {
                        // Drop items played long ago from the playlist and the mapping, then their files.
                        chunkFiles.onCurrentIndex(index) { count -> controller.removeMediaItems(0, count) }
                    }
                    logNav(
                        "SERVICE_TRANSITION reason=$reason mediaId=${mediaItem?.mediaId} playlistIndex=$index " +
                            "transcriptIndex=$transcriptIndex playlist=${chunkFiles.transcriptIndices().summary()}",
                    )
                }
            },
        )

        sessionHolder?.setPreviousHandler { handleSkipPrevious() }

        selectVoiceEngine()
        // Nothing is loaded here: the service is also created by a controller binding to it or by a
        // restart with nothing to play. A thread loads what it needs in handleProcessRedditUrl.
        armIdleTimer { if (!hasWork()) handleStop() }
    }

    private fun hasWork(): Boolean = queueSession != null || activeThread != null || processingJob?.isActive == true

    private fun armIdleTimer(action: () -> Unit) {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob =
                coroutineScope.launch {
                    delay(idleReleaseMs)
                    withContext(mainDispatcher) { action() }
                }
        }
    }

    private fun cancelIdleTimer() {
        synchronized(idleLock) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun logIdle(message: String) {
        runCatching { Log.i(TIMING_TAG, "IDLE $message") }
    }

    /** Frees the TTS models; the next synthesis loads them again. */
    private suspend fun unloadTtsEngine() {
        synchronized(warmUpLock) {
            isEngineWarmedUp = false
            warmedVoiceEngine = null
            warmUpJob = null
        }
        activeTtsEngine?.unload()
    }

    private fun dropForeground(flags: Int) {
        host.stopForeground(flags)
        foregroundDropped = true
    }

    private fun restoreForeground() {
        if (foregroundDropped) enterForeground()
    }

    private fun enterForeground() {
        foregroundDropped = false
        host.resumeForeground(
            MediaNotificationManager.NOTIFICATION_ID,
            notificationManager.buildNotification(
                title = currentTitle,
                text = currentText,
                isPlaying = isPlaybackActive(),
                session = sessionHolder?.session,
            ),
            serviceForegroundType,
        )
    }

    /** The player was paused, by any route: stop generating, and let go of everything if it lasts. */
    private fun onPlaybackPaused() {
        if (pausedMarked) return
        pausedMarked = true
        throttle?.setPaused(true)
        armIdleTimer { onLongPause() }
    }

    private fun onLongPause() {
        if (!hasWork()) {
            handleStop()
            return
        }
        logIdle("release reason=long_pause")
        coroutineScope.launch { unloadTtsEngine() }
        dropForeground(ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun onPlaybackResumed() {
        pausedMarked = false
        throttle?.setPaused(false)
        if (hasWork() && !threadFinished) cancelIdleTimer()
        restoreForeground()
    }

    /**
     * Follows the player's own pause state. The HUD and the system media controls drive the player
     * directly through the media session, so this is how the service hears about their pauses.
     */
    internal fun onPlayerPlayWhenReady(playWhenReady: Boolean) {
        val state = stateMachine.state.value
        if (playWhenReady) {
            if (state is PlaybackState.Paused) {
                stateMachine.transition(PlaybackEvent.Resume)
                host.registerNoisyReceiver()
                updateNotification(isPlaying = true)
            }
            onPlaybackResumed()
        } else {
            if (state is PlaybackState.Playing || state is PlaybackState.Ducked) {
                if (state is PlaybackState.Ducked) {
                    handleUnduck()
                    stateMachine.transition(PlaybackEvent.Unduck)
                }
                stateMachine.transition(PlaybackEvent.Pause)
                host.unregisterNoisyReceiver()
                updateNotification(isPlaying = false)
            }
            onPlaybackPaused()
        }
    }

    internal fun onPlayerState(playbackState: Int) {
        when (playbackState) {
            Player.STATE_ENDED ->
                when {
                    generationFinished -> onThreadFinished()
                    // The post is not finished, so it is not marked played and a queue does not move on.
                    generationHalted -> armIdleTimer { handleStop() }
                }
            // A stop through the media session (system controls, Bluetooth): the player will not
            // play again by itself, so nothing should keep generating for it.
            Player.STATE_IDLE -> if (activeThread != null || queueSession != null) handleStop()
            Player.STATE_READY, Player.STATE_BUFFERING ->
                if (threadFinished) {
                    // A rewind or caption tap after the end: the thread is playing again.
                    threadFinished = false
                    cancelIdleTimer()
                    restoreForeground()
                }
        }
    }

    /** Everything has been played. The models went when synthesis ended; the service goes shortly. */
    private fun onThreadFinished() {
        if (threadFinished) return
        val session = queueSession
        if (session != null) {
            logIdle("queue_post_finished")
            moveToNextPost(session, PostEnd.FINISHED)
            return
        }
        threadFinished = true
        manualPost?.let { post ->
            subredditStoreProvider()?.markPlayed(post.id, post.sub)
            playlistBackendProvider()?.manualFinished(post.id, post.sub)
        }
        manualPost = null
        logIdle("finished")
        dropForeground(ServiceCompat.STOP_FOREGROUND_REMOVE)
        armIdleTimer { handleStop() }
    }

    /** A thread failed or has nothing to read: free what it loaded and wind down. */
    private suspend fun onNothingToPlay() {
        logIdle("release reason=nothing_to_play")
        unloadTtsEngine()
        armIdleTimer { if (activeThread == null) handleStop() }
    }

    /** Frees a warmed engine if no thread has started synthesizing with it in time. */
    private fun armWarmUpWatchdog() {
        warmUpWatchdog?.cancel()
        warmUpWatchdog =
            coroutineScope.launch {
                delay(warmUpTimeoutMs)
                if (!synthesisRunning) {
                    logIdle("release reason=warmup_timeout")
                    unloadTtsEngine()
                    if (!hasWork()) withContext(mainDispatcher) { handleStop() }
                }
            }
    }

    /**
     * Reads the picked voice engine and its narrator into [requestedVoiceEngine] and
     * [requestedNarrator].
     */
    internal fun selectVoiceEngine(): VoiceEngine {
        val engine =
            try {
                voiceEngineProvider()
            } catch (_: Throwable) {
                VoiceEngine.DEFAULT
            }
        requestedNarrator =
            try {
                EngineVoices.narratorOrDefault(engine, narratorVoiceProvider(engine))
            } catch (_: Throwable) {
                EngineVoices.defaultNarrator(engine)
            }
        requestedVoiceEngine = engine
        return engine
    }

    private fun isWarmedFor(engine: VoiceEngine): Boolean = isEngineWarmedUp && (warmedVoiceEngine == null || warmedVoiceEngine == engine)

    /**
     * Creates the cached TTS engine for [requestedVoiceEngine] and runs one tiny throwaway inference
     * in the background, so the first real utterance does not pay the one-time model initialization
     * cost. Runs under [processingMutex], so it never overlaps real synthesis. Idempotent: returns the
     * running or finished warm-up job, and only starts a new one if no warm-up has completed yet for
     * the requested engine (for example because the model assets were not unpacked when it was last
     * tried, or because the voice engine changed since).
     */
    fun startWarmUp(): Job {
        synchronized(warmUpLock) {
            val existing = warmUpJob
            if (existing != null && (existing.isActive || isWarmedFor(requestedVoiceEngine))) {
                return existing
            }
            return coroutineScope.launch {
                processingMutex.withLock {
                    val engine = requestedVoiceEngine
                    if (isWarmedFor(engine) || !isWarmUpAllowed(engine)) return@withLock
                    val startedAt = System.nanoTime()
                    try {
                        obtainTtsEngine(engine, requestedNarrator).warmUp()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        try {
                            Log.w("RedderAudioService", "TTS warm-up failed", e)
                        } catch (_: Throwable) {
                        }
                    }
                    isEngineWarmedUp = true
                    warmedVoiceEngine = engine
                    timing.warmUpDone((System.nanoTime() - startedAt) / 1_000_000L)
                    armWarmUpWatchdog()
                }
            }.also { warmUpJob = it }
        }
    }

    private fun isWarmUpAllowed(engine: VoiceEngine): Boolean =
        try {
            warmUpAllowedFor(engine)
        } catch (_: Throwable) {
            false
        }

    fun handlePlay() {
        val focusGranted = audioFocusManager?.requestFocus() ?: true
        if (!focusGranted) {
            return
        }

        when (stateMachine.state.value) {
            is PlaybackState.Idle -> {
                stateMachine.transition(PlaybackEvent.Load)
                stateMachine.transition(PlaybackEvent.BufferingStarted)
                stateMachine.transition(PlaybackEvent.FirstChunkReady)
            }
            is PlaybackState.Buffering -> {
                stateMachine.transition(PlaybackEvent.FirstChunkReady)
            }
            is PlaybackState.Paused -> {
                stateMachine.transition(PlaybackEvent.Resume)
            }
            is PlaybackState.Ducked -> {
                handleUnduck()
                stateMachine.transition(PlaybackEvent.Unduck)
            }
            is PlaybackState.Seeking -> {
                stateMachine.transition(PlaybackEvent.SeekComplete)
            }
            is PlaybackState.Playing -> Unit
            else -> Unit
        }

        host.registerNoisyReceiver()
        applyPlaybackSpeed()
        onPlaybackResumed()
        sessionHolder?.playerController?.play()
        updateNotification(isPlaying = true)
    }

    /**
     * Applies the saved playback speed preference to the player.
     */
    fun applyPlaybackSpeed() {
        val speed = playbackSpeedProvider()
        if (speed > 0f) {
            sessionHolder?.playerController?.setPlaybackSpeed(speed)
        }
    }

    /**
     * Returns the cached engine when it was built for [engine], else releases it (freeing its models
     * before the next engine loads) and builds one for [engine]. Call only while holding
     * [processingMutex].
     */
    private fun obtainTtsEngine(
        engine: VoiceEngine = requestedVoiceEngine,
        narrator: String = requestedNarrator,
    ): TtsEngine {
        val current = activeTtsEngine
        if (current != null) {
            if (activeVoiceEngine == engine) return current
            try {
                current.release()
            } catch (_: Throwable) {
            }
            activeTtsEngine = null
            isEngineWarmedUp = false
            warmedVoiceEngine = null
        }
        return ReloadableTtsEngine { voiceEngineFactory(contextProvider(), engine, narrator) }.also {
            activeTtsEngine = it
            activeVoiceEngine = engine
        }
    }

    fun handlePause() {
        when (stateMachine.state.value) {
            is PlaybackState.Playing -> {
                stateMachine.transition(PlaybackEvent.Pause)
            }
            is PlaybackState.Ducked -> {
                // Restore the volume here: once the state leaves Ducked, a later focus gain no longer unducks.
                handleUnduck()
                stateMachine.transition(PlaybackEvent.Unduck)
                stateMachine.transition(PlaybackEvent.Pause)
            }
            else -> Unit
        }

        host.unregisterNoisyReceiver()
        sessionHolder?.playerController?.pause()
        updateNotification(isPlaying = false)
        onPlaybackPaused()
    }

    fun handleNoisyDetected() {
        if (stateMachine.state.value is PlaybackState.Playing ||
            stateMachine.state.value is PlaybackState.Ducked
        ) {
            handlePause()
        }
    }

    fun handleDuck() {
        sessionHolder?.playerController?.volume = DUCKED_VOLUME
    }

    fun handleUnduck() {
        sessionHolder?.playerController?.volume = NORMAL_VOLUME
    }

    /**
     * "Next", from the notification, the HUD's Next post button and every media controller: the next
     * post while a queue runs, and otherwise the next clip.
     */
    fun handleSkipNext() {
        val session = queueSession
        if (session != null) moveToNextPost(session, PostEnd.SKIPPED) else handleSkipNextClip()
    }

    /** The next clip of the post being read, whether or not a queue runs (the HUD's Next comment). */
    fun handleSkipNextClip() {
        onSkipNext()
        sessionHolder?.playerController?.seekToNext()
    }

    /**
     * Starts reading the hottest unplayed posts of the selected subs, each sub in turn, until none is
     * left. The queue lives here, so it needs no activity and carries on with the screen off.
     */
    fun handlePlaySubreddits(): Job? {
        val store = subredditStoreProvider()
        val subs = store?.selectedNames().orEmpty()
        val backend = playlistBackendProvider()
        // This came through startForegroundService, whatever happens next.
        cancelIdleTimer()
        enterForeground()
        leaveQueue()
        SubredditQueueHolder.update(SubredditQueueState(active = true))
        val session =
            QueueSession(
                backend?.newQueue()
                    ?: SubredditQueue(
                        subreddits = subs,
                        isPlayed = { store?.isPlayed(it) == true },
                        fetchPage = { sub, after -> SubredditListingParser.parse(apiClient.fetchSubredditListing(sub, after)) },
                    ),
                backend,
            )
        queueSession = session
        notificationManager.nextIsPost = true
        sessionHolder?.setNextPostHandler { queueSession?.let { moveToNextPost(it, PostEnd.SKIPPED) } }
        if (backend != null) {
            notificationManager.offersDone = true
            sessionHolder?.setDoneHandler(notificationManager.strings.actionDone) { handleDone() }
            DoneButtonHolder.update(DoneButtonState(available = true))
            session.positionTicker = coroutineScope.launch { reportPositions(session, backend) }
        }
        return startQueuePost(session)
    }

    /** The current post has ended: remember it, tell the playlist, and go on to the next one. */
    private fun moveToNextPost(
        session: QueueSession,
        end: PostEnd,
    ) {
        session.current?.let { post ->
            subredditStoreProvider()?.markPlayed(post.id, post.subreddit)
            when (end) {
                PostEnd.FINISHED -> session.backend?.finished(post)
                PostEnd.SKIPPED -> session.backend?.skipped(post)
                PostEnd.REMOVED -> Unit
            }
        }
        session.current = null
        startQueuePost(session)
    }

    /** Done on the post being read: it wins over everything, and the next post starts. */
    fun handleDone() {
        val session = queueSession ?: return
        val backend = session.backend ?: return
        val post = session.current ?: return
        backend.done(post)
        DoneButtonHolder.update(DoneButtonState(available = true, undoPostId = post.id))
        moveToNextPost(session, PostEnd.REMOVED)
    }

    /** Puts back the post just marked done. It returns to its place in the playlist; what plays now carries on. */
    fun handleUndoDone() {
        val backend = playlistBackendProvider() ?: return
        val postId = DoneButtonHolder.state.value.undoPostId ?: return
        backend.undone(postId)
        DoneButtonHolder.update(DoneButtonHolder.state.value.copy(undoPostId = null))
    }

    /** Keeps the playlist up to date on listening, and notices when the post was taken off elsewhere. */
    private suspend fun reportPositions(
        session: QueueSession,
        backend: PlaylistBackend,
    ) {
        while (coroutineScope.isActive && queueSession === session) {
            delay(positionReportMs)
            val post = session.current ?: continue
            val removed = backend.isRemoved(post.id)
            withContext(mainDispatcher) {
                if (queueSession !== session || session.current?.id != post.id) return@withContext
                if (removed) {
                    moveToNextPost(session, PostEnd.REMOVED)
                } else if (isPlaybackActive()) {
                    val ms = sessionHolder?.playerController?.currentPositionMs ?: 0L
                    backend.position(post, ActiveTranscriptHolder.currentIndex.value, ms)
                }
            }
        }
    }

    private fun startQueuePost(session: QueueSession): Job {
        cancelIdleTimer()
        pausedMarked = false
        threadFinished = false
        generationFinished = false
        manualPost = null
        restoreForeground()
        val previousJob = processingJob
        previousJob?.cancel()
        activeThread = null
        currentTitle = notificationManager.strings.defaultTitle
        // Silence now, not when the next post is ready.
        sessionHolder?.playerController?.clearMediaItems()
        chunkFiles.reset()
        keptClips.clear()
        prepareToLoadThread()
        selectVoiceEngine()
        startWarmUp()
        SubredditQueueHolder.update(SubredditQueueState(active = true))
        ActiveTranscriptHolder.clear()
        val job =
            coroutineScope.launch {
                previousJob?.cancelAndJoin()
                runQueue(session)
            }
        processingJob = job
        return job
    }

    /** Plays the next readable post. Returns once it is all synthesized; its end moves the queue on. */
    private suspend fun runQueue(session: QueueSession) {
        while (true) {
            timing.urlReceived()
            val prepared = takeNext(session)
            val post =
                when (val next = prepared.next) {
                    is QueueNext.Post -> next.post
                    QueueNext.Exhausted -> return endQueue(session, notificationManager.strings.queueNoNewPosts)
                    QueueNext.AllFailed -> return endQueue(session, notificationManager.strings.queueAllFailed)
                }
            if (session.backend?.isRemoved(post.id) == true) continue
            session.current = post
            SubredditQueueHolder.update(
                SubredditQueueState(active = true, subreddit = post.subreddit, title = post.title, postId = post.id),
            )
            withContext(mainDispatcher) {
                prepareToLoadThread()
                updateNotification(isPlaying = false, title = post.title, text = notificationManager.strings.loadingThread)
            }
            val engine = selectVoiceEngine()
            val narrator = requestedNarrator
            startWarmUp()
            val outcome =
                runPipeline(REDDIT_ORIGIN + post.permalink, engine, narrator, session, prepared.thread) {
                    prepared.serverPost ?: session.backend?.load(post)
                }
            logIdle("queue_post id=${post.id} outcome=$outcome")
            when (outcome) {
                PipelineOutcome.PLAYED -> {
                    session.consecutiveFailures = 0
                    return
                }
                PipelineOutcome.NOTHING_TO_READ -> session.current = null
                PipelineOutcome.FAILED -> {
                    session.current = null
                    session.consecutiveFailures++
                    if (session.consecutiveFailures >= maxPostFailures) {
                        return endQueue(session, notificationManager.strings.queueAllFailed)
                    }
                }
            }
        }
    }

    private suspend fun takeNext(session: QueueSession): PreparedPost {
        val fetched = session.prefetch
        if (fetched != null) {
            // Cleared only after the wait: if this is cancelled by a skip, the next attempt uses the result.
            val prepared = fetched.await()
            session.prefetch = null
            return prepared
        }
        return PreparedPost(session.queue.next())
    }

    /** Fetches the next post's text, not its audio, so the move to it is quick. */
    private fun prefetchNextPost(session: QueueSession) {
        if (session.prefetch != null) return
        session.prefetch =
            coroutineScope.async {
                val next = session.queue.next()
                var thread: RedditThread? = null
                var serverPost: ServerPost? = null
                if (next is QueueNext.Post) {
                    try {
                        serverPost = session.backend?.load(next.post)
                        if (serverPost == null) thread = loadThread(REDDIT_ORIGIN + next.post.permalink)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The move to this post tries again and handles the failure.
                        runCatching { Log.w("RedderAudioService", "Prefetch failed for ${next.post.id}", e) }
                    }
                }
                PreparedPost(next, thread, serverPost)
            }
    }

    /** The queue ran out or nothing could be loaded: say so and let go of everything. */
    private suspend fun endQueue(
        session: QueueSession,
        message: String,
    ) {
        withContext(mainDispatcher) {
            if (queueSession !== session) return@withContext
            logIdle("queue_end")
            leaveQueue()
            SubredditQueueHolder.update(SubredditQueueState(message = message))
            showMessage(message)
            handleStop()
        }
    }

    /** Forgets the queue; the post being read stays unplayed, so it comes round again. */
    private fun leaveQueue() {
        val session = queueSession ?: return
        queueSession = null
        session.prefetch?.cancel()
        session.positionTicker?.cancel()
        notificationManager.nextIsPost = false
        sessionHolder?.setNextPostHandler(null)
        if (session.backend != null) {
            notificationManager.offersDone = false
            sessionHolder?.setDoneHandler(notificationManager.strings.actionDone, null)
            DoneButtonHolder.update(DoneButtonState())
        }
        SubredditQueueHolder.update(SubredditQueueState())
    }

    /**
     * Rewind, from the notification, the HUD and the system media controls alike: restarts the clip
     * when more than [PlaylistNavigator.RESTART_THRESHOLD_MS] in, otherwise plays the previous clip
     * (re-synthesizing it when it was already cleaned up).
     */
    fun handleSkipPrevious() {
        onSkipPrevious()
        val controller = sessionHolder?.playerController ?: return
        val positionMs = controller.currentPositionMs
        val playlistIndex = controller.currentMediaItemIndex
        val target = PlaylistNavigator.previous(positionMs, playlistIndex, chunkFiles.transcriptIndices())
        logNav("SKIP_PREVIOUS positionMs=$positionMs playlistIndex=$playlistIndex -> $target")
        navigate(target, play = false)
    }

    /**
     * Jumps to transcript card [transcriptIndex] (a caption tap): seeks to its first part when it is
     * queued, and otherwise sends the generator there and plays as soon as that card is ready.
     */
    fun handleJumpToTranscriptIndex(
        transcriptIndex: Int,
        positionMs: Long = 0L,
    ) {
        val target =
            PlaylistNavigator.jumpTo(
                target = transcriptIndex,
                playlist = chunkFiles.transcriptIndices(),
                transcriptSize = activeThread?.chunks?.size ?: 0,
            )
        logNav("JUMP transcriptIndex=$transcriptIndex -> $target")
        navigate(target, play = true, positionMs = positionMs)
    }

    private fun navigate(
        target: NavTarget,
        play: Boolean,
        positionMs: Long = 0L,
    ) {
        val controller = sessionHolder?.playerController ?: return
        when (target) {
            is NavTarget.Seek -> controller.seekTo(target.playlistIndex, positionMs)
            NavTarget.RestartCurrent -> controller.seekTo(controller.currentMediaItemIndex.coerceAtLeast(0), positionMs)
            is NavTarget.Resynthesize -> {
                resynthesizeFrom(target.transcriptIndex)
                return
            }
            NavTarget.Ignore -> return
        }
        if (play && !isPlaybackActive()) handlePlay()
    }

    /**
     * Sends the generator to card [transcriptIndex] and plays from there as soon as that card is ready,
     * then carries on forward. Used when a rewind or caption tap reaches a card that is not in the
     * playlist, because it was cleaned up or is not synthesized yet. Clips of the old playlist are
     * kept in [keptClips] so they do not need synthesizing again.
     */
    internal fun resynthesizeFrom(transcriptIndex: Int): Job? {
        val active = activeThread ?: return null
        ActiveTranscriptHolder.setCurrentIndex(transcriptIndex)
        // The old clip must not keep talking under the "Generating" state.
        if (isPlaybackActive()) handlePause()
        // The listener is waiting for this card, so neither the pause nor the end of the thread counts as idle.
        cancelIdleTimer()
        threadFinished = false
        restoreForeground()
        SynthesisProgressHolder.update(
            SynthesisProgress(currentChunk = transcriptIndex, totalChunks = active.chunks.size, stage = SynthesisStage.SYNTHESIZING),
        )
        updateNotification(isPlaying = false, text = generatingText(transcriptIndex, active.chunks.size))
        logNav("REDIRECT transcriptIndex=$transcriptIndex")
        val previousJob = processingJob
        previousJob?.cancel()
        val job =
            coroutineScope.launch {
                previousJob?.cancelAndJoin()
                try {
                    startWarmUp().join()
                    processingMutex.withLock {
                        coroutineScope {
                            synthesizeAndPlay(
                                active.thread,
                                active.chunks,
                                this,
                                active.engine,
                                active.narrator,
                                transcriptIndex,
                                redirected = true,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    try {
                        Log.e("RedderAudioService", "Re-synthesis from card $transcriptIndex failed", t)
                    } catch (_: Throwable) {
                    }
                    updateNotification(isPlaying = false, text = notificationManager.strings.synthesisFailed)
                }
            }
        processingJob = job
        return job
    }

    fun handleStop() {
        onDestroy()
    }

    fun updateNotification(
        isPlaying: Boolean,
        title: String = currentTitle,
        text: String = currentText,
    ) {
        currentTitle = title
        currentText = text
        val notification =
            notificationManager.buildNotification(
                title = title,
                text = text,
                isPlaying = isPlaying,
                session = sessionHolder?.session,
            )
        host.updateNotification(MediaNotificationManager.NOTIFICATION_ID, notification)
    }

    fun handleProcessRedditUrl(url: String): Job {
        timing.urlReceived()
        // A thread shared by hand plays now and ends any queue.
        leaveQueue()
        manualPost = null
        cancelIdleTimer()
        pausedMarked = false
        threadFinished = false
        // This may have come through startForegroundService on a service that already left the foreground.
        enterForeground()
        // The engine pick applies per thread: read it now so the warm-up loads the right model.
        val engine = selectVoiceEngine()
        val narrator = requestedNarrator
        startWarmUp()
        val previousJob = processingJob
        previousJob?.cancel()
        activeThread = null

        prepareToLoadThread()

        val job =
            coroutineScope.launch {
                // Wait for the previous pipeline to stop. Fetching and parsing run without the engine
                // mutex so they overlap the warm-up; synthesis then holds the mutex (see runPipeline).
                previousJob?.cancelAndJoin()
                runPipeline(url, engine, narrator)
            }
        processingJob = job
        return job
    }

    /** Puts the state machine, notification and progress at "loading a thread". */
    private fun prepareToLoadThread() {
        generationHalted = false
        PlaybackAlertHolder.clear()
        val state = stateMachine.state.value
        if (state is PlaybackState.Error || state is PlaybackState.Released) {
            stateMachine.reset()
        } else if (state !is PlaybackState.Idle) {
            stateMachine.transition(PlaybackEvent.Release)
            stateMachine.reset()
        }
        updateNotification(isPlaying = false, text = notificationManager.strings.loadingThread)
        stateMachine.transition(PlaybackEvent.Load)
        SynthesisProgressHolder.update(SynthesisProgress(stage = SynthesisStage.FETCHING_THREAD))
    }

    private suspend fun loadThread(url: String): RedditThread {
        val canonicalUrl = urlResolver.resolveToCanonicalJsonUrl(url)
        val payload = apiClient.fetchThreadJson(canonicalUrl)
        val trimmedPayload = payload.trimStart()
        val thread =
            if (trimmedPayload.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedPayload.startsWith("<html", ignoreCase = true)
            ) {
                redlibParser.parseThread(payload)
            } else {
                parser.parseThread(payload)
            }
        return thread
    }

    /**
     * Loads, formats and synthesizes one thread. For a shared thread ([session] null) a failure is shown
     * to the listener; a queue's post just reports it, and the queue moves on.
     */
    private suspend fun runPipeline(
        url: String,
        engine: VoiceEngine,
        narrator: String,
        session: QueueSession? = null,
        fetchedThread: RedditThread? = null,
        loadServerPost: suspend () -> ServerPost? = { null },
    ): PipelineOutcome {
        var isLoading = true
        try {
            val serverPost = loadServerPost()
            val thread = serverPost?.thread ?: fetchedThread ?: loadThread(url)
            timing.mark(TimeToFirstAudioLog.THREAD_FETCHED)
            if (session == null) manualPost = manualPostFrom(url)
            isLoading = false
            val chunks = serverPost?.chunks ?: formatChunksForVoices(thread, pruner.prune(thread), engine, narrator)
            timing.mark(TimeToFirstAudioLog.THREAD_PARSED)

            if (chunks.isEmpty()) {
                if (session != null) return PipelineOutcome.NOTHING_TO_READ
                updateNotification(isPlaying = false, text = notificationManager.strings.noDialogue)
                SynthesisProgressHolder.update(SynthesisProgress(stage = SynthesisStage.READY))
                onNothingToPlay()
                return PipelineOutcome.NOTHING_TO_READ
            }

            val dialogueItems =
                chunks.mapIndexed { index, chunk ->
                    DialogueItem(
                        index = chunk.chunkIndex,
                        author = chunk.author,
                        text = chunk.spokenText,
                        isOp = chunk.isOp,
                        pauseMs = PlaybackOrchestrator.pauseAfterMs(chunk, chunks.getOrNull(index + 1)).toLong(),
                    )
                }
            val resumeAt = serverPost?.startIndex ?: session?.current?.let { post -> session.backend?.resumeChunk(post.id) }
            val startIndex = resumeAt?.takeIf { it in chunks.indices } ?: 0
            ActiveTranscriptHolder.setTranscript(dialogueItems, startIndex)
            activeThread = ActiveThread(thread, chunks, engine, narrator, serverPost)
            session?.current?.let { post -> session.backend?.started(post) }

            stateMachine.transition(PlaybackEvent.BufferingStarted)
            SynthesisProgressHolder.update(
                SynthesisProgress(
                    currentChunk = startIndex,
                    totalChunks = chunks.size,
                    stage = SynthesisStage.SYNTHESIZING,
                ),
            )

            // Real synthesis waits for the warm-up instead of racing it, then holds the mutex so the
            // shared engine is never used by two jobs at once. coroutineScope {} also waits for the
            // orchestrator's synthesis child job.
            startWarmUp().join()
            val played =
                processingMutex.withLock {
                    coroutineScope { synthesizeAndPlay(thread, chunks, this, engine, narrator, startIndex) }
                }
            return if (played) PipelineOutcome.PLAYED else PipelineOutcome.FAILED
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            try {
                Log.e("RedderAudioService", "Error processing Reddit URL: $url", t)
            } catch (_: Throwable) {
                // In unit test environments without mocked android.util.Log
            }
            if (session != null) return PipelineOutcome.FAILED
            if (stateMachine.state.value !is PlaybackState.Error &&
                stateMachine.state.value !is PlaybackState.Released
            ) {
                stateMachine.transition(PlaybackEvent.Fail(t))
            }
            SynthesisProgressHolder.update(SynthesisProgress(stage = SynthesisStage.ERROR))
            val errorMessage =
                if (isLoading) {
                    notificationManager.strings.threadLoadFailed
                } else {
                    t.localizedMessage ?: t.message ?: notificationManager.strings.processingFailed
                }
            updateNotification(isPlaying = false, text = errorMessage)
            ActiveTranscriptHolder.setTranscript(
                listOf(
                    DialogueItem(
                        index = 0,
                        author = notificationManager.strings.systemSpeaker,
                        text = errorMessage,
                        isOp = false,
                    ),
                ),
            )
            onNothingToPlay()
            return PipelineOutcome.FAILED
        }
    }

    private fun manualPostFrom(url: String): ManualPost? =
        THREAD_PATH.find(url)?.let { match -> ManualPost(id = match.groupValues[2], sub = match.groupValues[1]) }

    /** Returns false when not one chunk could be synthesized. */
    private suspend fun synthesizeAndPlay(
        thread: RedditThread,
        chunks: List<DialogueChunk>,
        pipelineScope: CoroutineScope,
        engine: VoiceEngine,
        narrator: String,
        startIndex: Int = 0,
        redirected: Boolean = false,
    ): Boolean {
        generationFinished = false
        generationHalted = false
        PlaybackAlertHolder.clear()
        val session = queueSession
        val pipelineThrottle = SynthesisThrottle(maxLookAheadMs, startIndex) { message -> runCatching { Log.i(TIMING_TAG, message) } }
        throttle = pipelineThrottle
        synthesisRunning = true
        val gate = runCatching { generationGateProvider() }.getOrNull()
        val serverPost = activeThread?.serverPost
        val activePipeline =
            // Every chunk uses the user's quality setting. Measured against a 32-step render, 4 steps is
            // far less converged (log-spectral distance 12.7 dB vs 7.7 dB at 8) and peaks about twice
            // as loud, clipping a third of chunks; quick start comes from short opening chunks instead.
            PlaybackOrchestrator(
                ttsEngine = obtainTtsEngine(engine, narrator),
                scope = pipelineScope,
                chunkTimeoutMs = engine.chunkTimeoutMs,
                cachedWav = keptClips::wav,
                awaitTurn = {
                    pipelineThrottle.awaitTurn()
                    gate?.awaitClear()
                },
                onSynthesized = { index, audioMs ->
                    pipelineThrottle.onSynthesized(index, audioMs)
                    gate?.onSynthesized(index, audioMs)
                },
                preparedWav = serverPost?.wav,
            )
        activeOrchestrator = activePipeline
        val pipelineStartedAt = System.nanoTime()
        try {
            activePipeline.startPipeline(chunks, startIndex)

            val firstChunk = activePipeline.nextChunk()
            if (firstChunk == null) {
                activePipeline.unavailable?.let { return haltGeneration(it) }
                activeThread = null
                if (session == null) {
                    updateNotification(isPlaying = false, text = notificationManager.strings.synthesisFailed)
                    SynthesisProgressHolder.update(SynthesisProgress(stage = SynthesisStage.ERROR))
                    onNothingToPlay()
                }
                return false
            }
            timing.mark(TimeToFirstAudioLog.FIRST_CHUNK_SYNTHESIZED)

            // Engines slower than Supertonic buffer audio before playback starts, sized from their
            // measured speed (see StartBufferPolicy) so a slow device does not stall right away and a
            // fast one starts after the minimum. Supertonic starts on the first chunk.
            val startBatch = mutableListOf(firstChunk)
            var bufferedMs = PlaybackOrchestrator.wavDurationMs(firstChunk.wavData)
            // After a caption tap the listener is waiting for that one clip, so it plays as soon as it is ready.
            if (engine != VoiceEngine.SUPERTONIC && !redirected) {
                val policy = StartBufferPolicy(minBufferMs = engine.minStartBufferMs)
                // remainingChars[i] = text still to synthesize after chunk i.
                val remainingChars = IntArray(chunks.size)
                for (i in chunks.size - 2 downTo 0) remainingChars[i] = remainingChars[i + 1] + chunks[i + 1].spokenText.length

                fun recordChunk(chunk: SynthesizedChunk) =
                    policy.record(chunk.dialogueChunk.spokenText.length, PlaybackOrchestrator.wavDurationMs(chunk.wavData), chunk.synthMs)

                fun remainingAfter(chunk: SynthesizedChunk) = remainingChars.getOrElse(chunk.index) { 0 }
                recordChunk(firstChunk)
                val speed = runCatching { playbackSpeedProvider() }.getOrDefault(1f)
                while (startBatch.last().index < chunks.lastIndex && pipelineScope.isActive &&
                    !policy.shouldStart(bufferedMs, remainingAfter(startBatch.last()), elapsedMs(pipelineStartedAt), speed)
                ) {
                    reportSynthesisProgress(startIndex + startBatch.size, chunks.size)
                    updateNotification(isPlaying = false, text = generatingText(startIndex + startBatch.size, chunks.size))
                    val next = activePipeline.nextChunk() ?: break
                    startBatch += next
                    bufferedMs += PlaybackOrchestrator.wavDurationMs(next.wavData)
                    recordChunk(next)
                }
                logStartBuffer(engine, policy, bufferedMs, remainingAfter(startBatch.last()), elapsedMs(pipelineStartedAt), speed)
            }
            var synthesizedCount = startIndex + startBatch.size
            reportSynthesisProgress(synthesizedCount, chunks.size)

            val startFiles = startBatch.map { writeChunkToTempFile(it) }
            val startUris = startFiles.map { uriConverter(it) }
            val postTitle = thread.post.title.ifBlank { notificationManager.strings.defaultTitle }
            currentTitle = postTitle

            var startQueued = false
            try {
                withContext(mainDispatcher) {
                    sessionHolder?.playerController?.clearMediaItems()
                    // The previous thread's playlist is gone now, so its files can go too.
                    if (redirected) {
                        chunkFiles.detachAll().forEach { (index, file) -> keptClips.put(index, file) }
                    } else {
                        chunkFiles.reset()
                        keptClips.clear()
                    }
                    startBatch.zip(startFiles).forEach { (chunk, file) -> chunkFiles.add(chunk.index, file) }
                    startQueued = true
                    sessionHolder?.playerController?.setMediaItem(
                        uri = startUris.first(),
                        title = postTitle,
                        artist = firstChunk.dialogueChunk.author,
                        mediaId = ChunkMediaId.of(firstChunk.index),
                    )
                    startBatch.drop(1).zip(startUris.drop(1)).forEach { (chunk, uri) ->
                        sessionHolder?.playerController?.addMediaItem(
                            uri = uri,
                            title = postTitle,
                            artist = chunk.dialogueChunk.author,
                            mediaId = ChunkMediaId.of(chunk.index),
                        )
                    }
                    sessionHolder?.playerController?.prepare()
                    currentText = notificationManager.strings.defaultText
                    handlePlay()
                    pipelineThrottle.playbackStarted()
                    if (redirected) logNav("REDIRECT_PLAYING transcriptIndex=$startIndex waited_ms=${elapsedMs(pipelineStartedAt)}")
                }
            } finally {
                if (!startQueued) startFiles.forEach { it.delete() }
            }

            while (pipelineScope.isActive) {
                val chunk = activePipeline.nextChunk() ?: break
                synthesizedCount++
                reportSynthesisProgress(synthesizedCount, chunks.size)
                val file = writeChunkToTempFile(chunk)
                val uri = uriConverter(file)
                var queued = false
                try {
                    withContext(mainDispatcher) {
                        chunkFiles.add(chunk.index, file)
                        queued = true
                        sessionHolder?.playerController?.addMediaItem(
                            uri = uri,
                            title = postTitle,
                            artist = chunk.dialogueChunk.author,
                            mediaId = ChunkMediaId.of(chunk.index),
                        )
                    }
                } finally {
                    if (!queued) file.delete()
                }
            }
            activePipeline.unavailable?.let { return haltGeneration(it) }
            SynthesisProgressHolder.update(
                SynthesisProgress(
                    currentChunk = chunks.size,
                    totalChunks = chunks.size,
                    stage = SynthesisStage.READY,
                ),
            )
            if (pipelineScope.isActive) {
                // No more inference until a rewind or caption tap asks for it, however much audio is still queued.
                generationFinished = true
                if (session != null) {
                    // The next post is close: keep the models and get its text, leaving its audio for later.
                    prefetchNextPost(session)
                } else {
                    logIdle("release reason=generation_finished")
                    unloadTtsEngine()
                }
                withContext(mainDispatcher) {
                    if (sessionHolder?.playerController?.isEnded == true) onThreadFinished()
                }
            }
        } finally {
            synthesisRunning = false
            throttle = null
            activePipeline.release()
        }
        return true
    }

    /**
     * The engine is gone. Says so and stops making audio, leaving the position alone: audio already made
     * plays out, the post stays unplayed, a queue does not move on, and a rewind or caption tap tries again.
     */
    private suspend fun haltGeneration(failure: TtsUnavailableException): Boolean {
        val message = failure.message ?: notificationManager.strings.synthesisFailed
        generationHalted = true
        logIdle("release reason=engine_unavailable")
        PlaybackAlertHolder.show(message)
        SynthesisProgressHolder.update(SynthesisProgress(stage = SynthesisStage.ERROR))
        updateNotification(isPlaying = isPlaybackActive(), text = message)
        unloadTtsEngine()
        if (!isPlaybackActive()) armIdleTimer { handleStop() }
        return true
    }

    private fun reportSynthesisProgress(
        synthesized: Int,
        total: Int,
    ) {
        SynthesisProgressHolder.update(
            SynthesisProgress(
                currentChunk = synthesized,
                totalChunks = total,
                stage = if (synthesized >= total) SynthesisStage.READY else SynthesisStage.SYNTHESIZING,
            ),
        )
    }

    private fun generatingText(
        synthesized: Int,
        total: Int,
    ): String = String.format(java.util.Locale.ROOT, notificationManager.strings.generatingProgress, synthesized, total)

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

    private fun logStartBuffer(
        engine: VoiceEngine,
        policy: StartBufferPolicy,
        bufferedMs: Long,
        remainingChars: Int,
        waitedMs: Long,
        speed: Float,
    ) {
        val message =
            "START_BUFFER engine=${engine.prefValue} buffered_ms=$bufferedMs " +
                "required_ms=${policy.requiredBufferMs(remainingChars, speed)} " +
                "rtf=${"%.2f".format(java.util.Locale.ROOT, policy.rtf)} remaining_chars=$remainingChars waited_ms=$waitedMs"
        try {
            Log.i("ThreadReaderTiming", message)
        } catch (_: Throwable) {
        }
    }

    private fun isPlaybackActive(): Boolean = stateMachine.state.value.let { it is PlaybackState.Playing || it is PlaybackState.Ducked }

    private fun writeChunkToTempFile(chunk: SynthesizedChunk): File {
        val cacheDir = cacheDirProvider()
        val tempFile =
            if (cacheDir != null) {
                File.createTempFile("${ChunkFileWindow.FILE_PREFIX}${chunk.index}_", ChunkFileWindow.FILE_SUFFIX, cacheDir)
            } else {
                File.createTempFile("${ChunkFileWindow.FILE_PREFIX}${chunk.index}_", ChunkFileWindow.FILE_SUFFIX)
            }
        val wavBytes =
            if (chunk.wavData.isNotEmpty()) {
                chunk.wavData
            } else {
                WavUtil.pcmToWav(chunk.pcmData)
            }
        try {
            tempFile.writeBytes(wavBytes)
        } catch (e: java.io.IOException) {
            tempFile.delete()
            throw e
        }
        return tempFile
    }

    fun onStartCommand(intent: Intent?): Int {
        if (!isForegroundStarted) {
            return Service.START_NOT_STICKY
        }
        if (intent == null && !hasWork()) {
            // A restart by the system: there is nothing to resume, so nothing may load.
            handleStop()
            return Service.START_NOT_STICKY
        }
        val url =
            try {
                intent?.getStringExtra(ShareReceiverActivity.EXTRA_REDDIT_URL)
                    ?: intent?.getStringExtra("EXTRA_REDDIT_URL")
            } catch (_: Throwable) {
                null
            }
        if (!url.isNullOrBlank()) {
            handleProcessRedditUrl(url)
        }
        when (intent?.action) {
            RedderAudioService.ACTION_PLAY -> handlePlay()
            RedderAudioService.ACTION_PAUSE -> handlePause()
            RedderAudioService.ACTION_SKIP_NEXT -> handleSkipNext()
            RedderAudioService.ACTION_NEXT_CLIP -> handleSkipNextClip()
            RedderAudioService.ACTION_PLAY_SUBREDDITS -> handlePlaySubreddits()
            RedderAudioService.ACTION_SKIP_PREVIOUS -> handleSkipPrevious()
            RedderAudioService.ACTION_STOP -> handleStop()
            RedderAudioService.ACTION_DONE -> handleDone()
            RedderAudioService.ACTION_UNDO_DONE -> handleUndoDone()
            RedderAudioService.ACTION_JUMP_TO_TRANSCRIPT_INDEX ->
                handleJumpToTranscriptIndex(
                    intent.getIntExtra(RedderAudioService.EXTRA_TRANSCRIPT_INDEX, -1),
                    intent.getLongExtra(RedderAudioService.EXTRA_POSITION_MS, 0L),
                )
        }
        // A restarted process has no thread to continue, so the system must not bring the service back.
        return Service.START_NOT_STICKY
    }

    fun onGetSession(controllerInfo: MediaSession.ControllerInfo? = null): MediaSession? = sessionHolder?.session

    fun onDestroy() {
        // Stopped partway through a post: it stays unplayed.
        leaveQueue()
        SynthesisProgressHolder.reset()
        try {
            registeredPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Throwable) {
        }
        registeredPrefs = null
        processingJob?.cancel()
        activeOrchestrator?.release()
        coroutineScope.cancel()
        activeTtsEngine?.release()
        activeTtsEngine = null

        host.unregisterNoisyReceiver()
        audioFocusManager?.abandonFocus()

        if (stateMachine.state.value !is PlaybackState.Released) {
            stateMachine.transition(PlaybackEvent.Release)
        }

        activeThread = null
        sessionHolder?.setPreviousHandler(null)
        sessionHolder?.setNextPostHandler(null)
        sessionHolder?.release()
        // The player is released, so no chunk file is read any more.
        chunkFiles.reset()
        keptClips.clear()
        host.stopForeground(ServiceCompat.STOP_FOREGROUND_REMOVE)
        host.stopSelf()
    }

    /**
     * The app's task was swiped away. While audio plays the service keeps going (played chunks are
     * still trimmed as it advances); otherwise it stops, which releases the player and deletes every
     * chunk file.
     */
    fun onTaskRemoved() {
        // A queue between posts is not playing for a moment, and is still going.
        val queueMoving = queueSession != null && !pausedMarked
        if (!queueMoving && (!isPlaybackActive() || threadFinished)) handleStop()
    }

    companion object {
        /** Logcat tag for playlist and transcript navigation (current item, rewind, caption jumps). */
        const val NAV_TAG: String = "ThreadReaderNav"

        /** Short form of a playlist's transcript indices for logs, e.g. "[3..14] (12)". */
        internal fun List<Int>.summary(): String = if (isEmpty()) "[]" else "[${first()}..${last()}] ($size)"

        internal fun logNav(message: String) {
            try {
                Log.i(NAV_TAG, message)
            } catch (_: Throwable) {
            }
        }

        private const val TIMING_TAG: String = "ThreadReaderTiming"

        /** See [idleReleaseMs]. */
        const val IDLE_RELEASE_MS: Long = 60_000L

        /** See [warmUpTimeoutMs]. */
        const val WARM_UP_TIMEOUT_MS: Long = 60_000L

        const val DUCKED_VOLUME: Float = 0.2f
        const val NORMAL_VOLUME: Float = 1.0f

        /** See [maxPostFailures]. */
        const val MAX_CONSECUTIVE_POST_FAILURES: Int = 5

        /** See [positionReportMs]. */
        const val POSITION_REPORT_MS: Long = 30_000L

        /** A queued post's permalink goes through the same URL path as a shared link; mirrors serve it. */
        private const val REDDIT_ORIGIN = "https://www.reddit.com"
        private val THREAD_PATH = Regex("""/r/([^/]+)/comments/([A-Za-z0-9]+)""")
    }
}

/**
 * Headless [MediaSessionService] managing audio playback and foreground notification (ADR-002).
 */
class RedderAudioService : MediaSessionService() {
    internal var delegate: RedderAudioServiceDelegate? = null

    override fun onCreate() {
        super.onCreate()
        val activeDelegate =
            delegate ?: run {
                val host =
                    DefaultServiceHost(
                        service = this,
                        noisyReceiverProvider = { delegate?.noisyReceiver },
                    )
                CurrentFlavor.onProcessStart(this)
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = MediaNotificationManager(this),
                    sessionHolder =
                        DefaultMediaSessionHolder(
                            this,
                            callback = ResumptionCallback(offersDone = CurrentFlavor.playlistBackend(this) != null),
                        ),
                    cacheDirProvider = { cacheDir },
                    contextProvider = { this },
                    voiceEngineProvider = {
                        CurrentFlavor.fixedVoiceEngine
                            ?: RedderPreferences.readVoiceEngine(getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE))
                    },
                    warmUpAllowedFor = { engine -> CurrentFlavor.isWarmUpAllowed(this, engine) },
                    voiceEngineFactory = { ctx, engine, narrator -> CurrentFlavor.createTtsEngine(ctx ?: this, engine, narrator) },
                    showMessage = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
                    subredditStoreProvider = { SubredditStore.forContext(this) },
                    playlistBackendProvider = { CurrentFlavor.playlistBackend(this) },
                    generationGateProvider = { CurrentFlavor.generationGate(this) },
                )
            }.also { delegate = it }
        activeDelegate.onCreate(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        return delegate?.onStartCommand(intent) ?: Service.START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = delegate?.onGetSession(controllerInfo)

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        delegate?.onTaskRemoved()
    }

    override fun onDestroy() {
        delegate?.onDestroy()
        delegate = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY: String = "com.redder.playback.action.PLAY"
        const val ACTION_PAUSE: String = "com.redder.playback.action.PAUSE"
        const val ACTION_SKIP_NEXT: String = "com.redder.playback.action.SKIP_NEXT"

        /** The next clip of the post being read, also while a queue runs; see [ACTION_SKIP_NEXT]. */
        const val ACTION_NEXT_CLIP: String = "com.redder.playback.action.NEXT_CLIP"

        /** Starts reading the subreddits selected on the dashboard. */
        const val ACTION_PLAY_SUBREDDITS: String = "com.redder.playback.action.PLAY_SUBREDDITS"
        const val ACTION_SKIP_PREVIOUS: String = "com.redder.playback.action.SKIP_PREVIOUS"
        const val ACTION_STOP: String = "com.redder.playback.action.STOP"

        /** Marks the post being read done, on a playlist. */
        const val ACTION_DONE: String = "com.redder.playback.action.DONE"
        const val ACTION_UNDO_DONE: String = "com.redder.playback.action.UNDO_DONE"

        /** Jumps to the transcript card in [EXTRA_TRANSCRIPT_INDEX] (a caption tap in the HUD). */
        const val ACTION_JUMP_TO_TRANSCRIPT_INDEX: String = "com.redder.playback.action.JUMP_TO_TRANSCRIPT_INDEX"
        const val EXTRA_TRANSCRIPT_INDEX: String = "com.redder.playback.extra.TRANSCRIPT_INDEX"

        /** Where in that card to start; absent means its beginning. */
        const val EXTRA_POSITION_MS: String = "com.redder.playback.extra.POSITION_MS"
        const val ERROR_TEXT_THREAD_LOAD_FAILED: String = "Couldn't load thread. Mirrors may be down, try again."
    }
}
