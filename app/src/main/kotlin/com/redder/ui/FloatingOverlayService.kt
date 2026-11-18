package com.redder.ui

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.common.util.concurrent.ListenableFuture
import com.redder.flavor.CurrentFlavor
import com.redder.model.ActiveTranscriptHolder
import com.redder.model.DialogueItem
import com.redder.model.PlaybackAlertHolder
import com.redder.model.SubredditQueueHolder
import com.redder.model.SubredditQueueState
import com.redder.model.SynthesisProgress
import com.redder.model.SynthesisProgressHolder
import com.redder.playback.RedderAudioService
import com.redder.playback.RedderAudioServiceDelegate
import com.redder.settings.RedderPreferences
import com.redder.ui.hud.FloatingHud
import com.redder.ui.hud.HudBubbleBounds
import com.redder.ui.hud.HudBubbleSize
import com.redder.ui.hud.HudEvent
import com.redder.ui.hud.HudState
import com.redder.ui.theme.ThreadReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Encapsulates layout parameters for the floating overlay window.
 *
 * @property type Window type (defaults to [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]).
 * @property flags Window behavior flags (defaults to NOT_TOUCH_MODAL and NOT_FOCUSABLE).
 * @property format Window pixel format (defaults to [PixelFormat.TRANSLUCENT]).
 * @property width Layout width (defaults to [WindowManager.LayoutParams.WRAP_CONTENT]).
 * @property height Layout height (defaults to [WindowManager.LayoutParams.WRAP_CONTENT]).
 * @property gravity Window placement gravity (defaults to [Gravity.TOP] or [Gravity.CENTER_HORIZONTAL]).
 * @property x Horizontal pixel offset from gravity anchor.
 * @property y Vertical pixel offset from gravity anchor.
 */
data class OverlayWindowParams(
    val type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    val flags: Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    val format: Int = PixelFormat.TRANSLUCENT,
    val width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    val height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    val gravity: Int = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
    val x: Int = 0,
    val y: Int = 180,
)

/**
 * Abstraction seam for adding, removing, and updating overlay views in [WindowManager].
 */
interface OverlayWindowManager {
    val isViewAttached: Boolean

    fun addView(
        view: Any,
        params: OverlayWindowParams = OverlayWindowParams(),
    )

    fun removeView(view: Any)

    fun updateViewLayout(
        view: Any,
        params: OverlayWindowParams,
    )
}

/**
 * Default Android platform implementation of [OverlayWindowManager].
 */
class DefaultOverlayWindowManager(
    private val windowManager: WindowManager,
) : OverlayWindowManager {
    private var attached: Boolean = false

    override val isViewAttached: Boolean
        get() = attached

    override fun addView(
        view: Any,
        params: OverlayWindowParams,
    ) {
        val androidView = view as? View ?: return
        val layoutParams =
            WindowManager.LayoutParams(
                params.width,
                params.height,
                params.type,
                params.flags,
                params.format,
            ).apply {
                gravity = params.gravity
                x = params.x
                y = params.y
            }
        windowManager.addView(androidView, layoutParams)
        attached = true
    }

    override fun removeView(view: Any) {
        val androidView = view as? View ?: return
        if (attached) {
            try {
                windowManager.removeView(androidView)
            } finally {
                attached = false
            }
        }
    }

    override fun updateViewLayout(
        view: Any,
        params: OverlayWindowParams,
    ) {
        val androidView = view as? View ?: return
        if (attached) {
            val layoutParams =
                WindowManager.LayoutParams(
                    params.width,
                    params.height,
                    params.type,
                    params.flags,
                    params.format,
                ).apply {
                    gravity = params.gravity
                    x = params.x
                    y = params.y
                }
            windowManager.updateViewLayout(androidView, layoutParams)
        }
    }
}

/**
 * Platform seam for host service interactions (stopping service upon dismissal).
 */
interface OverlayServiceHost {
    fun stopSelf()
}

/**
 * Default Android platform implementation of [OverlayServiceHost].
 */
class DefaultOverlayServiceHost(
    private val service: Service,
) : OverlayServiceHost {
    override fun stopSelf() {
        service.stopSelf()
    }
}

/**
 * Seam for audio playback controls requested by the HUD overlay.
 */
interface HudPlaybackController {
    fun play()

    fun pause()

    fun skipToNext()

    /** Rewind; the audio service decides between restarting the clip and the previous one. */
    fun skipToPrevious()

    /** Plays transcript card [transcriptIndex] (a caption tap); the audio service resolves it. */
    fun jumpToTranscriptIndex(transcriptIndex: Int)

    fun stop()

    fun release()

    fun setPlaybackSpeed(speed: Float) {}
}

/**
 * Default Android platform implementation of [HudPlaybackController] wrapping a Media3 [MediaController].
 */
class Media3HudPlaybackController(
    private val mediaController: MediaController,
    /** Sends a caption jump to the audio service, which maps the card to its playlist item. */
    private val sendJumpToTranscriptIndex: (Int) -> Unit,
    /** Sends the next-clip command; the session's own "next" is the next post while a queue runs. */
    private val sendNextClip: () -> Unit,
) : HudPlaybackController {
    override fun play() {
        mediaController.play()
    }

    override fun pause() {
        mediaController.pause()
    }

    override fun skipToNext() {
        sendNextClip()
    }

    override fun skipToPrevious() {
        mediaController.seekToPrevious()
    }

    override fun jumpToTranscriptIndex(transcriptIndex: Int) {
        sendJumpToTranscriptIndex(transcriptIndex)
    }

    override fun stop() {
        mediaController.stop()
    }

    override fun release() {
        mediaController.release()
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaController.setPlaybackSpeed(speed)
    }
}

/**
 * Host lifecycle and saved state owner allowing Jetpack Compose hierarchies to render safely
 * inside an Android [Service] window.
 */
class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/**
 * Business and presentation delegate for [FloatingOverlayService] (ADR-002).
 *
 * Manages the floating HUD presentation state, user events (Play/Pause, Skip, Dismiss),
 * and overlay window lifecycle completely decoupled from background audio playback.
 */
class FloatingOverlayServiceDelegate(
    val windowManager: OverlayWindowManager,
    val host: OverlayServiceHost,
    var playbackController: HudPlaybackController? = null,
    val viewProvider: () -> Any = { Any() },
    val windowParams: OverlayWindowParams = OverlayWindowParams(),
    initialState: HudState =
        HudState(
            speaker = DEFAULT_SPEAKER,
            text = DEFAULT_TEXT,
            isPlaying = false,
            hasNext = true,
            hasPrevious = true,
        ),
    val onSpeedChanged: ((Float) -> Unit)? = null,
    val onReadUsernamesChanged: ((Boolean) -> Unit)? = null,
    /**
     * Sends a command (such as [RedderAudioService.ACTION_STOP]) to the audio service. On dismiss the
     * HUD sends the stop action so the service releases the player, removes its notification,
     * deletes its chunk files and stops itself; the app process stays alive.
     */
    val sendPlaybackServiceAction: (String) -> Unit = {},
    /** The usable screen in pixels, read each time because folding the phone changes it. */
    val screenSizeProvider: () -> Pair<Int, Int> = { 0 to 0 },
    val bubbleSizePx: Int = 0,
    /** Where the bubble was last left; null puts it at the right edge. */
    savedBubblePosition: Pair<Int, Int>? = null,
    val onBubblePositionChanged: (Int, Int) -> Unit = { _, _ -> },
    /** Opens this build's now-playing screen, when it has one. */
    val onOpenNowPlaying: () -> Unit = {},
) {
    var currentWindowParams: OverlayWindowParams = windowParams
        private set

    /** The bubble's window, in screen pixels from the top-left corner. */
    var bubbleWindowParams: OverlayWindowParams =
        windowParams.copy(
            gravity = Gravity.TOP or Gravity.START,
            x = savedBubblePosition?.first ?: Int.MAX_VALUE,
            y = savedBubblePosition?.second ?: windowParams.y,
        )
        private set

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<HudState> = _state.asStateFlow()

    private var _view: Any? = null
    val view: Any get() = _view ?: viewProvider().also { _view = it }

    var isCreated: Boolean = false
        private set

    var isDismissed: Boolean = false
        private set

    /**
     * Initializes overlay view and attaches it to [OverlayWindowManager].
     */
    fun onCreate() {
        isCreated = true
        if (!windowManager.isViewAttached) {
            windowManager.addView(view, currentWindowParams)
        }
    }

    /**
     * Dispatches user interaction events originating from the floating HUD overlay.
     */
    fun onEvent(event: HudEvent) {
        try {
            android.util.Log.i("FloatingOverlayService", "onEvent received: $event")
        } catch (_: Throwable) {
        }
        when (event) {
            is HudEvent.PlayPauseClicked -> {
                if (_state.value.isPlaying) {
                    playbackController?.pause()
                } else {
                    playbackController?.play()
                }
            }
            is HudEvent.SkipNextClicked -> {
                playbackController?.skipToNext()
            }
            is HudEvent.NextPostClicked -> {
                sendPlaybackServiceAction(RedderAudioService.ACTION_SKIP_NEXT)
            }
            is HudEvent.DoneClicked -> {
                sendPlaybackServiceAction(RedderAudioService.ACTION_DONE)
            }
            is HudEvent.UndoDoneClicked -> {
                sendPlaybackServiceAction(RedderAudioService.ACTION_UNDO_DONE)
            }
            is HudEvent.NowPlayingClicked -> {
                onOpenNowPlaying()
            }
            is HudEvent.SkipPreviousClicked -> {
                playbackController?.skipToPrevious()
            }
            is HudEvent.DismissClicked -> {
                try {
                    android.util.Log.i("FloatingOverlayService", "DismissClicked -> handleDismiss()")
                } catch (_: Throwable) {
                }
                handleDismiss()
            }
            is HudEvent.ToggleExpandedClicked -> {
                try {
                    android.util.Log.i(
                        "FloatingOverlayService",
                        "ToggleExpandedClicked: toggling isExpanded from ${_state.value.isExpanded}",
                    )
                } catch (_: Throwable) {
                }
                _state.value = _state.value.copy(isExpanded = !_state.value.isExpanded)
            }
            is HudEvent.DialogueItemClicked -> {
                try {
                    android.util.Log.i("FloatingOverlayService", "DialogueItemClicked: index ${event.index}")
                    RedderAudioServiceDelegate.logNav("CAPTION_TAP transcriptIndex=${event.index}")
                } catch (_: Throwable) {
                }
                // The highlight follows once the service has actually moved playback to that card.
                playbackController?.jumpToTranscriptIndex(event.index)
            }
            is HudEvent.SpeedClicked -> {
                val currentSpeed = _state.value.playbackSpeed
                val newSpeed =
                    when {
                        currentSpeed < 0.75f -> 1.0f
                        currentSpeed < 1.25f -> 1.5f
                        currentSpeed < 1.75f -> 2.0f
                        else -> 0.5f
                    }
                _state.update { it.copy(playbackSpeed = newSpeed) }
                playbackController?.setPlaybackSpeed(newSpeed)
                onSpeedChanged?.invoke(newSpeed)
            }
            is HudEvent.ToggleReadUsernamesClicked -> {
                val newReadUsernames = !_state.value.readUsernames
                _state.update { it.copy(readUsernames = newReadUsernames) }
                onReadUsernamesChanged?.invoke(newReadUsernames)
            }
            is HudEvent.Drag -> {
                if (_state.value.isMinimized) moveBubble(event.deltaX, event.deltaY) else handleDrag(event.deltaX, event.deltaY)
            }
            is HudEvent.DragEnded -> {
                onBubblePositionChanged(bubbleWindowParams.x, bubbleWindowParams.y)
            }
            is HudEvent.MinimizeClicked -> {
                // Only the window changes: playback and the service's synthesis are not told anything.
                _state.update { it.copy(isMinimized = true) }
                clampBubble()
                applyWindow(bubbleWindowParams)
            }
            is HudEvent.RestoreClicked -> {
                _state.update { it.copy(isMinimized = false) }
                applyWindow(currentWindowParams)
            }
        }
    }

    private fun applyWindow(params: OverlayWindowParams) {
        if (windowManager.isViewAttached) windowManager.updateViewLayout(view, params)
    }

    private fun clampBubble() {
        val (width, height) = screenSizeProvider()
        val (x, y) = HudBubbleBounds.clamp(bubbleWindowParams.x, bubbleWindowParams.y, width, height, bubbleSizePx)
        bubbleWindowParams = bubbleWindowParams.copy(x = x, y = y)
    }

    private fun moveBubble(
        deltaX: Float,
        deltaY: Float,
    ) {
        bubbleWindowParams =
            bubbleWindowParams.copy(x = bubbleWindowParams.x + deltaX.toInt(), y = bubbleWindowParams.y + deltaY.toInt())
        clampBubble()
        applyWindow(bubbleWindowParams)
    }

    /**
     * The screen changed size (the phone was folded or unfolded, or rotated): keeps the bubble on it.
     */
    fun onScreenChanged() {
        clampBubble()
        if (_state.value.isMinimized) applyWindow(bubbleWindowParams)
    }

    /**
     * Updates overlay position coordinates when dragged.
     */
    fun handleDrag(
        deltaX: Float,
        deltaY: Float,
    ) {
        currentWindowParams =
            currentWindowParams.copy(
                x = currentWindowParams.x + deltaX.toInt(),
                y = (currentWindowParams.y + deltaY.toInt()).coerceAtLeast(0),
            )
        if (windowManager.isViewAttached) {
            windowManager.updateViewLayout(view, currentWindowParams)
        }
    }

    /**
     * Dismisses the overlay HUD: stops audio playback, asks the audio service to stop cleanly
     * ([RedderAudioService.ACTION_STOP]), removes the view and stops the host service. The app
     * process and its activities stay alive.
     */
    fun handleDismiss() {
        try {
            android.util.Log.i("FloatingOverlayService", "handleDismiss executed, isDismissed=$isDismissed")
        } catch (_: Throwable) {
        }
        if (isDismissed) return
        isDismissed = true

        try {
            playbackController?.stop()
            playbackController?.release()
        } catch (_: Throwable) {
        }

        try {
            sendPlaybackServiceAction(RedderAudioService.ACTION_STOP)
        } catch (_: Throwable) {
        }

        if (windowManager.isViewAttached) {
            windowManager.removeView(view)
        }
        host.stopSelf()
    }

    /**
     * The audio service went away (stopped, or finished its thread), so the controls have nothing to
     * drive: the HUD closes too. Nothing is sent to the audio service, which would only start it again.
     */
    fun onPlaybackSessionGone() {
        if (isDismissed) return
        isDismissed = true
        if (windowManager.isViewAttached) {
            windowManager.removeView(view)
        }
        host.stopSelf()
    }

    /**
     * Updates the message shown when the voice engine became unavailable; null removes it.
     */
    fun updateAlert(message: String?) {
        _state.update { it.copy(alertMessage = message) }
    }

    /**
     * Updates the neural audio synthesis progress displayed in the floating HUD.
     */
    fun updateSynthesisProgress(progress: SynthesisProgress) {
        _state.update { it.copy(synthesisProgress = progress) }
    }

    /**
     * Updates the playback speed multiplier displayed in the floating HUD.
     */
    fun updatePlaybackSpeed(speed: Float) {
        _state.update { it.copy(playbackSpeed = speed) }
    }

    /**
     * Updates the read author usernames preference displayed in the floating HUD.
     */
    fun updateReadUsernames(enabled: Boolean) {
        _state.update { it.copy(readUsernames = enabled) }
    }

    /**
     * Shows the subreddit queue's current post and its Next post button.
     */
    fun updateQueue(queue: SubredditQueueState) {
        _state.update { it.copy(queueActive = queue.active, queueSubreddit = queue.subreddit, queueTitle = queue.title) }
    }

    fun updateDoneButton(done: com.redder.playlist.DoneButtonState) {
        _state.update { it.copy(doneAvailable = done.available, undoAvailable = done.undoPostId != null) }
    }

    /**
     * Updates the full thread transcript items displayed in the expanded HUD.
     */
    fun updateDialogueItems(items: List<DialogueItem>) {
        _state.update { current ->
            val activeItem = items.getOrNull(current.currentUtteranceIndex)
            if (items.isEmpty() && current.queueActive) {
                // Between queued posts: nothing of the last post stays on screen.
                current.copy(dialogueItems = items, speaker = DEFAULT_SPEAKER, text = DEFAULT_TEXT)
            } else if (activeItem != null) {
                current.copy(
                    dialogueItems = items,
                    speaker = activeItem.author,
                    text = activeItem.text,
                )
            } else {
                current.copy(dialogueItems = items)
            }
        }
    }

    /**
     * Updates the current active utterance index and synchronizes the active dialogue utterance.
     */
    fun updateCurrentUtteranceIndex(index: Int) {
        _state.update { current ->
            val activeItem = current.dialogueItems.getOrNull(index)
            if (activeItem != null) {
                current.copy(
                    currentUtteranceIndex = index,
                    speaker = activeItem.author,
                    text = activeItem.text,
                )
            } else {
                current.copy(currentUtteranceIndex = index)
            }
        }
    }

    /**
     * Updates current speaker name with fallback for blank or null attributions.
     */
    fun updateSpeaker(speaker: String?) {
        val sanitized = if (speaker.isNullOrBlank()) DEFAULT_SPEAKER else speaker
        _state.update { it.copy(speaker = sanitized) }
    }

    /**
     * Updates current dialogue utterance text snippet.
     */
    fun updateText(text: String?) {
        val sanitized = text ?: ""
        _state.update { it.copy(text = sanitized) }
    }

    /**
     * Updates current audio playback state in the HUD.
     */
    fun updatePlaying(isPlaying: Boolean) {
        _state.update { it.copy(isPlaying = isPlaying) }
    }

    /**
     * Updates navigation availability (Skip Next / Previous) in the HUD.
     */
    fun updateNavigation(
        hasNext: Boolean,
        hasPrevious: Boolean,
    ) {
        _state.update { it.copy(hasNext = hasNext, hasPrevious = hasPrevious) }
    }

    /**
     * Convenience method to update multiple HUD state fields simultaneously.
     */
    fun updateState(
        speaker: String? = null,
        text: String? = null,
        isPlaying: Boolean? = null,
        hasNext: Boolean? = null,
        hasPrevious: Boolean? = null,
    ) {
        _state.update { current ->
            val newSpeaker =
                if (speaker != null) {
                    if (speaker.isBlank()) DEFAULT_SPEAKER else speaker
                } else {
                    current.speaker
                }
            current.copy(
                speaker = newSpeaker,
                text = text ?: current.text,
                isPlaying = isPlaying ?: current.isPlaying,
                hasNext = hasNext ?: current.hasNext,
                hasPrevious = hasPrevious ?: current.hasPrevious,
            )
        }
    }

    /**
     * Handles inbound start commands and intent extras.
     */
    fun onStartCommand(intent: Intent?): Int {
        if (intent == null) {
            return Service.START_NOT_STICKY
        }

        when (intent.action) {
            FloatingOverlayService.ACTION_DISMISS -> {
                handleDismiss()
            }
            FloatingOverlayService.ACTION_START_OVERLAY,
            FloatingOverlayService.ACTION_UPDATE_HUD,
            -> {
                val speaker = intent.getStringExtra(FloatingOverlayService.EXTRA_SPEAKER)
                if (speaker != null) {
                    updateSpeaker(speaker)
                }
                val text = intent.getStringExtra(FloatingOverlayService.EXTRA_TEXT)
                if (text != null) {
                    updateText(text)
                }
                if (intent.hasExtra(FloatingOverlayService.EXTRA_IS_PLAYING)) {
                    updatePlaying(intent.getBooleanExtra(FloatingOverlayService.EXTRA_IS_PLAYING, false))
                }
            }
        }

        return Service.START_NOT_STICKY
    }

    /**
     * Teardown logic removing any remaining attached view without interrupting audio.
     */
    fun onDestroy() {
        if (windowManager.isViewAttached) {
            windowManager.removeView(view)
        }
        _view = null
    }

    companion object {
        const val DEFAULT_SPEAKER: String = "Speaker"
        const val DEFAULT_TEXT: String = ""
    }
}

/**
 * On-screen floating overlay service displaying speaker metadata, dialogue snippets,
 * and playback controls via Jetpack Compose (ADR-002).
 */
class FloatingOverlayService : Service() {
    internal var delegate: FloatingOverlayServiceDelegate? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val activeDelegate =
            delegate ?: run {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val windowManagerSeam = DefaultOverlayWindowManager(wm)
                val host = DefaultOverlayServiceHost(this)

                val owner =
                    OverlayLifecycleOwner().also {
                        it.onCreate()
                        lifecycleOwner = it
                    }

                val composeView =
                    ComposeView(this).apply {
                        setViewTreeLifecycleOwner(owner)
                        setViewTreeSavedStateRegistryOwner(owner)
                    }

                val prefs = getSharedPreferences("redder_prefs", Context.MODE_PRIVATE)
                val initialSpeed = prefs.getFloat("pref_playback_speed", 1.0f)
                val initialReadUsernames = prefs.getBoolean("pref_read_usernames", true)
                val nowPlayingIntent = CurrentFlavor.nowPlayingIntent(this)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                FloatingOverlayServiceDelegate(
                    windowManager = windowManagerSeam,
                    host = host,
                    viewProvider = { composeView },
                    initialState =
                        HudState(
                            speaker = FloatingOverlayServiceDelegate.DEFAULT_SPEAKER,
                            text = FloatingOverlayServiceDelegate.DEFAULT_TEXT,
                            isPlaying = false,
                            hasNext = true,
                            hasPrevious = true,
                            playbackSpeed = initialSpeed,
                            readUsernames = initialReadUsernames,
                            nowPlayingAvailable = nowPlayingIntent != null,
                        ),
                    onSpeedChanged = { newSpeed ->
                        // The delegate already applied the speed via the media controller; persisting
                        // it lets RedderAudioService (the single applier) and the dashboard stay in sync.
                        prefs.edit().putFloat("pref_playback_speed", newSpeed).apply()
                    },
                    onReadUsernamesChanged = { enabled ->
                        prefs.edit().putBoolean("pref_read_usernames", enabled).apply()
                    },
                    sendPlaybackServiceAction = { serviceAction ->
                        startService(Intent(this, RedderAudioService::class.java).setAction(serviceAction))
                    },
                    screenSizeProvider = { usableScreenSize(wm) },
                    bubbleSizePx = (HudBubbleSize.value * resources.displayMetrics.density).toInt(),
                    savedBubblePosition =
                        if (prefs.contains(RedderPreferences.KEY_BUBBLE_X)) {
                            prefs.getInt(RedderPreferences.KEY_BUBBLE_X, 0) to prefs.getInt(RedderPreferences.KEY_BUBBLE_Y, 0)
                        } else {
                            null
                        },
                    onBubblePositionChanged = { x, y ->
                        prefs.edit().putInt(RedderPreferences.KEY_BUBBLE_X, x).putInt(RedderPreferences.KEY_BUBBLE_Y, y).apply()
                    },
                    onOpenNowPlaying = { nowPlayingIntent?.let { startActivity(it) } },
                ).also { d ->
                    composeView.setContent {
                        val hudState by d.state.collectAsState()
                        ThreadReaderTheme {
                            FloatingHud(
                                state = hudState,
                                onEvent = { d.onEvent(it) },
                            )
                        }
                    }
                }
            }.also { delegate = it }

        activeDelegate.onCreate()

        serviceScope.launch {
            ActiveTranscriptHolder.transcript.collect { items ->
                activeDelegate.updateDialogueItems(items)
            }
        }
        serviceScope.launch {
            ActiveTranscriptHolder.currentIndex.collect { index ->
                activeDelegate.updateCurrentUtteranceIndex(index)
            }
        }
        serviceScope.launch {
            SubredditQueueHolder.state.collect { queue ->
                activeDelegate.updateQueue(queue)
            }
        }
        serviceScope.launch {
            com.redder.playlist.DoneButtonHolder.state.collect { done ->
                activeDelegate.updateDoneButton(done)
            }
        }
        serviceScope.launch {
            PlaybackAlertHolder.message.collect { message ->
                activeDelegate.updateAlert(message)
            }
        }
        serviceScope.launch {
            SynthesisProgressHolder.progress.collect { progress ->
                activeDelegate.updateSynthesisProgress(progress)
            }
        }

        connectToMediaSession()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val superResult = super.onStartCommand(intent, flags, startId)
        delegate?.onStartCommand(intent)
        return superResult
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        delegate?.onScreenChanged()
    }

    private fun usableScreenSize(windowManager: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            metrics.bounds.width() - insets.left - insets.right to metrics.bounds.height() - insets.top - insets.bottom
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun connectToMediaSession() {
        val sessionToken = SessionToken(this, ComponentName(this, RedderAudioService::class.java))
        val controllerFuture =
            MediaController.Builder(this, sessionToken)
                .setListener(
                    object : MediaController.Listener {
                        override fun onDisconnected(controller: MediaController) {
                            delegate?.onPlaybackSessionGone()
                        }
                    },
                ).buildAsync()
        mediaControllerFuture = controllerFuture
        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    setupMediaController(controller)
                } catch (_: Exception) {
                    // Audio service might not be bound or initialized yet; HUD remains functional
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun setupMediaController(controller: MediaController) {
        val playbackController =
            Media3HudPlaybackController(
                mediaController = controller,
                sendJumpToTranscriptIndex = { transcriptIndex ->
                    startService(
                        Intent(this, RedderAudioService::class.java)
                            .setAction(RedderAudioService.ACTION_JUMP_TO_TRANSCRIPT_INDEX)
                            .putExtra(RedderAudioService.EXTRA_TRANSCRIPT_INDEX, transcriptIndex),
                    )
                },
                sendNextClip = {
                    startService(Intent(this, RedderAudioService::class.java).setAction(RedderAudioService.ACTION_NEXT_CLIP))
                },
            )
        delegate?.playbackController = playbackController
        delegate?.updatePlaying(controller.isPlaying)
        // RedderAudioService applies the saved speed; the HUD just mirrors the player's actual speed.
        delegate?.updatePlaybackSpeed(controller.playbackParameters.speed)

        controller.addListener(
            object : Player.Listener {
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    delegate?.updatePlaybackSpeed(playbackParameters.speed)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    delegate?.updatePlaying(isPlaying)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    // Every chunk's title is the post title, so with a transcript the current card's
                    // own text (set from ActiveTranscriptHolder.currentIndex) is shown instead.
                    if (delegate?.state?.value?.dialogueItems.isNullOrEmpty()) {
                        delegate?.updateSpeaker(mediaMetadata.artist?.toString())
                        delegate?.updateText(mediaMetadata.title?.toString())
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    // RedderAudioService sets ActiveTranscriptHolder.currentIndex from the item's media ID;
                    // the player's position is not a transcript index (played items are trimmed off).
                    RedderAudioServiceDelegate.logNav(
                        "HUD_TRANSITION mediaId=${mediaItem?.mediaId} playlistIndex=${controller.currentMediaItemIndex} " +
                            "playlistSize=${controller.mediaItemCount} transcriptIndex=${ActiveTranscriptHolder.currentIndex.value}",
                    )
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    val hasNext = availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)
                    val hasPrevious = availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)
                    delegate?.updateNavigation(hasNext, hasPrevious)
                }
            },
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaController?.let { controller ->
            // Disconnect controller client binding from session without killing playback engine
            controller.release()
        }
        mediaController = null
        mediaControllerFuture = null

        lifecycleOwner?.onDestroy()
        lifecycleOwner = null

        delegate?.onDestroy()
        delegate = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_OVERLAY: String = "com.redder.action.START_OVERLAY"
        const val ACTION_DISMISS: String = "com.redder.action.DISMISS"
        const val ACTION_UPDATE_HUD: String = "com.redder.action.UPDATE_HUD"
        const val EXTRA_SPEAKER: String = "com.redder.extra.SPEAKER"
        const val EXTRA_TEXT: String = "com.redder.extra.TEXT"
        const val EXTRA_IS_PLAYING: String = "com.redder.extra.IS_PLAYING"
    }
}
