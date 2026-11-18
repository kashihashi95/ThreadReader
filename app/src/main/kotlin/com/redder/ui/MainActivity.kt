package com.redder.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.redder.R
import com.redder.flavor.CurrentFlavor
import com.redder.flavor.FlavorPlaylistCard
import com.redder.model.PlaybackAlertHolder
import com.redder.model.SubredditQueueHolder
import com.redder.model.SubredditQueueState
import com.redder.network.RedditApiClient
import com.redder.playback.RedderAudioService
import com.redder.queue.AddSubredditResult
import com.redder.queue.SubredditStore
import com.redder.security.UrlSecurityValidator
import com.redder.settings.RedderPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.VoiceEngine
import com.redder.ui.theme.ThreadReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the dashboard.
 *
 * @property inputUrl The current text in the URL input field.
 * @property hasOverlayPermission True if the SYSTEM_ALERT_WINDOW permission is granted.
 * @property hasNotificationPermission True if POST_NOTIFICATIONS permission is granted (or not needed pre-API 33).
 * @property errorMessageRes String resource of the error to display, or null if no active error.
 * @property playbackStarted True if audio playback was successfully initiated from the dashboard.
 * @property supertonicQualitySteps Preferred diffusion quality steps (4 = Fast, 8 = Best).
 * @property readUsernames True if author usernames and attributions should be spoken.
 * @property playbackSpeed Audio playback speed multiplier.
 * @property isPreparingVoices True while the bundled neural voice models are being unpacked in the background.
 * @property voiceEngine Voice engine picked for the next thread.
 * @property narratorVoiceId Narrator (OP) voice of [voiceEngine].
 * @property voicePackStatus Whether [voiceEngine]'s voice pack is ready to use.
 * @property subredditInput The current text in the add-a-subreddit field.
 * @property subredditErrorRes String resource of the subreddit list's error, or null.
 * @property subreddits The saved subreddits, in the order they were added.
 * @property playedTotal How many posts the played history holds, across all subs.
 */
data class MainDashboardState(
    val inputUrl: String = "",
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    @StringRes val errorMessageRes: Int? = null,
    val playbackStarted: Boolean = false,
    val supertonicQualitySteps: Int = RedderPreferences.DEFAULT_QUALITY_STEPS,
    val readUsernames: Boolean = true,
    val playbackSpeed: Float = RedderPreferences.DEFAULT_PLAYBACK_SPEED,
    val isPreparingVoices: Boolean = false,
    val voiceEngine: VoiceEngine = VoiceEngine.DEFAULT,
    val narratorVoiceId: String = EngineVoices.defaultNarrator(VoiceEngine.DEFAULT),
    val voicePackStatus: VoicePackStatus = VoicePackStatus.READY,
    val subredditInput: String = "",
    @StringRes val subredditErrorRes: Int? = null,
    val subreddits: List<SubredditRow> = emptyList(),
    val playedTotal: Int = 0,
)

/** One row of the dashboard's subreddit list. */
data class SubredditRow(
    val name: String,
    val selected: Boolean,
    val playedCount: Int,
)

/** Availability of a voice engine's voices on this install. */
enum class VoicePackStatus {
    /** Unpacked (or not needed, as for Supertonic). */
    READY,

    /** Bundled but still being unpacked on first launch. */
    PREPARING,

    /** Not in this install; Supertonic reads threads instead. */
    NOT_INSTALLED,
}

/**
 * Platform abstraction seam for actions executed by the companion dashboard.
 */
interface MainDashboardActionHandler {
    /**
     * Checks if author usernames should be read aloud during dialogue playback.
     */
    fun isReadUsernamesEnabled(): Boolean = true

    /**
     * Updates author username reading preference.
     */
    fun saveReadUsernames(enabled: Boolean) {}

    /**
     * Returns the preferred audio playback speed multiplier.
     */
    fun getPlaybackSpeed(): Float = 1.0f

    /**
     * Updates preferred audio playback speed multiplier.
     */
    fun savePlaybackSpeed(speed: Float) {}

    /**
     * Returns the preferred Supertonic diffusion quality steps (4 or 8).
     */
    fun getQualitySteps(): Int = RedderPreferences.DEFAULT_QUALITY_STEPS

    /**
     * Updates preferred Supertonic diffusion quality steps.
     */
    fun saveQualitySteps(steps: Int) {}

    /**
     * Returns the voice engine picked for the next thread.
     */
    fun getVoiceEngine(): VoiceEngine = VoiceEngine.DEFAULT

    /**
     * Returns the narrator (OP) voice id saved for [engine].
     */
    fun getNarratorVoice(engine: VoiceEngine): String = EngineVoices.defaultNarrator(engine)

    /**
     * Persists the narrator (OP) voice id of [engine].
     */
    fun saveNarratorVoice(
        engine: VoiceEngine,
        voiceId: String,
    ) {}

    /**
     * Reports whether [engine]'s voice pack is unpacked, still being prepared, or missing.
     */
    fun voicePackStatus(engine: VoiceEngine): VoicePackStatus = VoicePackStatus.READY

    /**
     * Checks if the app currently has permission to draw floating overlays.
     */
    fun canDrawOverlays(): Boolean

    /**
     * Checks if the app currently has POST_NOTIFICATIONS permission.
     * Returns true on pre-API 33 devices where the permission is auto-granted.
     */
    fun hasNotificationPermission(): Boolean

    /**
     * Opens the system settings screen to allow granting overlay permission.
     */
    fun openOverlaySettings()

    /**
     * Opens the system app notification settings screen.
     */
    fun openNotificationSettings()

    /**
     * Starts the audio service to play the specified Reddit URL.
     */
    fun startAudioService(redditUrl: String)

    /**
     * Starts the floating overlay HUD service.
     */
    fun startOverlayService()

    /**
     * Opens an external URL in the default browser.
     */
    fun openUrl(url: String)

    /**
     * Unpacks the bundled Supertonic voice models if not on disk yet.
     */
    fun ensureModelAvailable() {}

    /**
     * The saved subreddits and played history; null where there is no storage.
     */
    fun subredditStore(): SubredditStore? = null

    /**
     * Starts the audio service reading the selected subreddits.
     */
    fun startSubredditQueue() {}

    /**
     * Sends a command such as [RedderAudioService.ACTION_SKIP_NEXT] to the running audio service.
     */
    fun sendAudioServiceAction(action: String) {}
}

/**
 * Default Android platform implementation of [MainDashboardActionHandler].
 */
class DefaultMainDashboardActionHandler(
    private val activity: Activity? = null,
    private val modelEnsurer: () -> Unit = {
        activity?.let { CurrentFlavor.prepareVoices(it) }
    },
    private val overlayPermissionChecker: () -> Boolean = {
        val act = activity ?: error("Activity required")
        Settings.canDrawOverlays(act)
    },
    private val notificationPermissionChecker: () -> Boolean = {
        val act = activity ?: error("Activity required")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                act,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    },
    private val activityStarter: (Intent) -> Unit = { intent ->
        val act = activity ?: error("Activity required")
        act.startActivity(intent)
    },
    private val foregroundServiceStarter: (Intent) -> Unit = { intent ->
        val act = activity ?: error("Activity required")
        ContextCompat.startForegroundService(act, intent)
    },
    private val serviceStarter: (Intent) -> Unit = { intent ->
        val act = activity ?: error("Activity required")
        act.startService(intent)
    },
    private val packageNameProvider: () -> String = { activity?.packageName ?: "com.redder" },
    private val voicePackStatusProvider: (VoiceEngine) -> VoicePackStatus = { CurrentFlavor.voicePackStatus(activity, it) },
    private val isAtLeastOreo: Boolean = true,
    private val uriParser: (String) -> Uri? = {
        try {
            Uri.parse(it)
        } catch (_: Throwable) {
            null
        }
    },
) : MainDashboardActionHandler {
    override fun canDrawOverlays(): Boolean = overlayPermissionChecker()

    override fun hasNotificationPermission(): Boolean = notificationPermissionChecker()

    override fun ensureModelAvailable() {
        modelEnsurer()
    }

    override fun openOverlaySettings() {
        try {
            val pkg = packageNameProvider()
            val uri =
                try {
                    Uri.parse("package:$pkg")
                } catch (_: Throwable) {
                    null
                }
            val intent =
                if (uri != null) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                } else {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                }
            activityStarter(intent)
        } catch (_: Exception) {
            try {
                activityStarter(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                // Ignore failure if settings cannot be opened on unusual environments
            }
        }
    }

    override fun openNotificationSettings() {
        try {
            val pkg = packageNameProvider()
            if (isAtLeastOreo) {
                val intent =
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        try {
                            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        } catch (_: Throwable) {
                        }
                    }
                activityStarter(intent)
            } else {
                val uri =
                    try {
                        Uri.parse("package:$pkg")
                    } catch (_: Throwable) {
                        null
                    }
                val fallbackIntent =
                    if (uri != null) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = uri
                        }
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    }
                activityStarter(fallbackIntent)
            }
        } catch (_: Exception) {
            try {
                activityStarter(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            } catch (_: Exception) {
                // Ignore failure if settings cannot be opened on unusual environments
            }
        }
    }

    override fun startAudioService(redditUrl: String) {
        val act = activity
        val audioIntent =
            try {
                if (act != null) {
                    Intent(act, RedderAudioService::class.java).apply {
                        putExtra(ShareReceiverActivity.EXTRA_REDDIT_URL, redditUrl)
                    }
                } else {
                    Intent()
                }
            } catch (_: Throwable) {
                Intent()
            }
        foregroundServiceStarter(audioIntent)
    }

    override fun startOverlayService() {
        try {
            val pkg = packageNameProvider()
            val overlayIntent =
                Intent(ShareReceiverActivity.ACTION_START_OVERLAY).apply {
                    try {
                        if (activity != null) {
                            component =
                                ComponentName(
                                    activity,
                                    ShareReceiverActivity.FLOATING_OVERLAY_SERVICE_CLASS,
                                )
                        }
                        setPackage(pkg)
                    } catch (_: Throwable) {
                    }
                }
            serviceStarter(overlayIntent)
        } catch (_: Exception) {
            // Overlay service failure must not crash activity
        }
    }

    override fun isReadUsernamesEnabled(): Boolean {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getBoolean(RedderPreferences.KEY_READ_USERNAMES, true) ?: true
    }

    override fun saveReadUsernames(enabled: Boolean) {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putBoolean(RedderPreferences.KEY_READ_USERNAMES, enabled)?.apply()
    }

    override fun getPlaybackSpeed(): Float {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getFloat(RedderPreferences.KEY_PLAYBACK_SPEED, RedderPreferences.DEFAULT_PLAYBACK_SPEED)
            ?: RedderPreferences.DEFAULT_PLAYBACK_SPEED
    }

    override fun savePlaybackSpeed(speed: Float) {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putFloat(RedderPreferences.KEY_PLAYBACK_SPEED, speed)?.apply()
    }

    override fun getQualitySteps(): Int {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getInt(RedderPreferences.KEY_QUALITY_STEPS, RedderPreferences.DEFAULT_QUALITY_STEPS)
            ?: RedderPreferences.DEFAULT_QUALITY_STEPS
    }

    override fun saveQualitySteps(steps: Int) {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putInt(RedderPreferences.KEY_QUALITY_STEPS, steps)?.apply()
    }

    override fun getVoiceEngine(): VoiceEngine =
        CurrentFlavor.fixedVoiceEngine
            ?: RedderPreferences.readVoiceEngine(activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE))

    override fun getNarratorVoice(engine: VoiceEngine): String =
        RedderPreferences.readNarrator(activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE), engine)

    override fun saveNarratorVoice(
        engine: VoiceEngine,
        voiceId: String,
    ) {
        val prefs = activity?.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putString(RedderPreferences.narratorKey(engine), voiceId)?.apply()
    }

    override fun voicePackStatus(engine: VoiceEngine): VoicePackStatus = voicePackStatusProvider(engine)

    override fun subredditStore(): SubredditStore? = activity?.let { SubredditStore.forContext(it) }

    override fun startSubredditQueue() {
        val act = activity ?: return
        foregroundServiceStarter(Intent(act, RedderAudioService::class.java).setAction(RedderAudioService.ACTION_PLAY_SUBREDDITS))
    }

    override fun sendAudioServiceAction(action: String) {
        val act = activity ?: return
        serviceStarter(Intent(act, RedderAudioService::class.java).setAction(action))
    }

    override fun openUrl(url: String) {
        try {
            val uri =
                try {
                    uriParser(url)
                } catch (_: Throwable) {
                    null
                }
            val intent =
                if (uri != null) {
                    try {
                        Intent(Intent.ACTION_VIEW, uri)
                    } catch (_: Throwable) {
                        Intent(Intent.ACTION_VIEW)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW)
                }
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (_: Throwable) {
                // In test environments without mocked Intent
            }
            activityStarter(intent)
        } catch (_: Exception) {
            try {
                activityStarter(Intent(Intent.ACTION_VIEW))
            } catch (_: Exception) {
                // Ignore failure if browser cannot be opened
            }
        }
    }
}

/**
 * Controller and state manager for the dashboard.
 *
 * @property actionHandler Platform action seam for overlay checks and service triggers.
 * @property urlValidator Predicate validating candidate Reddit URLs against security constraints.
 */
class MainDashboardController(
    private val actionHandler: MainDashboardActionHandler,
    private val urlValidator: (String?) -> Boolean = UrlSecurityValidator::isPermittedUrl,
) {
    private val subredditStore: SubredditStore? = actionHandler.subredditStore()

    private val _state: MutableStateFlow<MainDashboardState> =
        MutableStateFlow(
            MainDashboardState(
                hasOverlayPermission = actionHandler.canDrawOverlays(),
                hasNotificationPermission = actionHandler.hasNotificationPermission(),
                supertonicQualitySteps = actionHandler.getQualitySteps(),
                readUsernames = actionHandler.isReadUsernamesEnabled(),
                playbackSpeed = actionHandler.getPlaybackSpeed(),
            ).withVoiceEngine(actionHandler.getVoiceEngine()).withSubreddits(),
        )

    val state: StateFlow<MainDashboardState> = _state.asStateFlow()

    /**
     * Toggles whether author usernames are read aloud during playback.
     */
    fun onToggleReadUsernames(enabled: Boolean) {
        actionHandler.saveReadUsernames(enabled)
        _state.update { it.copy(readUsernames = enabled) }
    }

    /**
     * Updates preferred playback speed multiplier.
     */
    fun onPlaybackSpeedChanged(speed: Float) {
        actionHandler.savePlaybackSpeed(speed)
        _state.update { it.copy(playbackSpeed = speed) }
    }

    /**
     * Updates Supertonic diffusion quality steps preference (4 = Fast, 8 = Best).
     */
    fun onQualityStepsChanged(steps: Int) {
        actionHandler.saveQualitySteps(steps)
        _state.update { it.copy(supertonicQualitySteps = steps) }
    }

    /**
     * Picks the narrator (OP) voice of the current voice engine; ignores voices of other engines.
     */
    fun onNarratorVoiceChanged(voiceId: String) {
        val engine = _state.value.voiceEngine
        if (EngineVoices.voices(engine).none { it.id == voiceId }) return
        actionHandler.saveNarratorVoice(engine, voiceId)
        _state.update { it.copy(narratorVoiceId = voiceId) }
    }

    private fun MainDashboardState.withSubreddits(): MainDashboardState {
        val store = subredditStore ?: return this
        return copy(
            subreddits = store.subreddits().map { SubredditRow(it.name, it.selected, store.playedCount(it.name)) },
            playedTotal = store.playedTotal(),
        )
    }

    /**
     * Re-reads the subreddit list and played counts, which the audio service changes while it plays.
     */
    fun refreshSubreddits() {
        _state.update { it.withSubreddits() }
    }

    fun onSubredditInputChanged(input: String) {
        _state.update { it.copy(subredditInput = input, subredditErrorRes = null) }
    }

    /**
     * Adds what the field holds, then clears it; an invalid name or a duplicate shows an inline error.
     */
    fun onAddSubreddit() {
        val store = subredditStore ?: return
        when (store.add(_state.value.subredditInput)) {
            AddSubredditResult.ADDED -> _state.update { it.copy(subredditInput = "", subredditErrorRes = null).withSubreddits() }
            AddSubredditResult.INVALID -> _state.update { it.copy(subredditErrorRes = R.string.subs_error_invalid) }
            AddSubredditResult.DUPLICATE -> _state.update { it.copy(subredditErrorRes = R.string.subs_error_duplicate) }
        }
    }

    fun onRemoveSubreddit(name: String) {
        subredditStore?.remove(name)
        refreshSubreddits()
    }

    fun onSubredditSelected(
        name: String,
        selected: Boolean,
    ) {
        subredditStore?.setSelected(name, selected)
        _state.update { it.copy(subredditErrorRes = null).withSubreddits() }
    }

    fun onSelectAllSubreddits(selected: Boolean) {
        subredditStore?.setAllSelected(selected)
        _state.update { it.copy(subredditErrorRes = null).withSubreddits() }
    }

    /**
     * Starts reading the selected subreddits, with the floating player when it is allowed.
     */
    fun onPlaySubredditsClicked() {
        if (_state.value.subreddits.none { it.selected }) {
            _state.update { it.copy(subredditErrorRes = R.string.subs_error_none_selected) }
            return
        }
        actionHandler.startSubredditQueue()
        val canOverlay = actionHandler.canDrawOverlays()
        if (canOverlay) actionHandler.startOverlayService()
        _state.update { it.copy(hasOverlayPermission = canOverlay, subredditErrorRes = null) }
    }

    fun onNextPostClicked() = actionHandler.sendAudioServiceAction(RedderAudioService.ACTION_SKIP_NEXT)

    fun onStopQueueClicked() = actionHandler.sendAudioServiceAction(RedderAudioService.ACTION_STOP)

    /**
     * Forgets the played posts of [sub], or of every sub when it is null.
     */
    fun onForgetPlayed(sub: String? = null) {
        subredditStore?.forgetPlayed(sub)
        refreshSubreddits()
    }

    private fun MainDashboardState.withVoiceEngine(engine: VoiceEngine): MainDashboardState =
        copy(
            voiceEngine = engine,
            narratorVoiceId = EngineVoices.narratorOrDefault(engine, actionHandler.getNarratorVoice(engine)),
            voicePackStatus = actionHandler.voicePackStatus(engine),
        )

    /**
     * Handles URL text field changes. Clears prior errors or playback-started indicators.
     */
    fun onUrlChanged(newUrl: String) {
        _state.update {
            it.copy(
                inputUrl = newUrl,
                errorMessageRes = null,
                playbackStarted = false,
            )
        }
    }

    /**
     * Fills the URL field from clipboard text. Prefers the first permitted Reddit link in the
     * text, then any link, then the trimmed text itself. Blank clipboard content is ignored.
     */
    fun onPasteFromClipboard(clipboardText: String?) {
        val text = clipboardText?.trim().orEmpty()
        if (text.isEmpty()) return
        val candidates = ShareIntentProcessor.findUrlCandidates(text)
        val chosen = candidates.firstOrNull { urlValidator(it) } ?: candidates.firstOrNull() ?: text
        onUrlChanged(chosen)
    }

    /**
     * Updates whether the bundled voice models are still being prepared.
     */
    fun onVoicesPreparing(preparing: Boolean) {
        _state.update { it.copy(isPreparingVoices = preparing, voicePackStatus = actionHandler.voicePackStatus(it.voiceEngine)) }
    }

    /**
     * Dismisses the active error message.
     */
    fun onDismissError() {
        _state.update { it.copy(errorMessageRes = null) }
    }

    /**
     * Refreshes permission status and saved preferences from the system.
     */
    fun onRefreshPermission() {
        _state.update {
            it.copy(
                hasOverlayPermission = actionHandler.canDrawOverlays(),
                hasNotificationPermission = actionHandler.hasNotificationPermission(),
                readUsernames = actionHandler.isReadUsernamesEnabled(),
                playbackSpeed = actionHandler.getPlaybackSpeed(),
                supertonicQualitySteps = actionHandler.getQualitySteps(),
            ).withVoiceEngine(actionHandler.getVoiceEngine()).withSubreddits()
        }
    }

    /**
     * Opens the overlay permission management screen in system settings.
     */
    fun onOpenSettingsClicked() {
        actionHandler.openOverlaySettings()
    }

    /**
     * Opens the app notification settings in system settings.
     */
    fun onOpenNotificationSettingsClicked() {
        actionHandler.openNotificationSettings()
    }

    /**
     * Validates the candidate URL, launches background services if valid, or displays error.
     */
    fun onPlayClicked() {
        val trimmed = _state.value.inputUrl.trim()

        if (trimmed.isEmpty()) {
            _state.update { it.copy(errorMessageRes = ERROR_EMPTY_URL, playbackStarted = false) }
            return
        }

        val cleanedUrl = ShareIntentProcessor.trimTrailingPunctuation(trimmed)
        if (!urlValidator(cleanedUrl)) {
            _state.update { it.copy(errorMessageRes = ERROR_INVALID_URL, playbackStarted = false) }
            return
        }

        actionHandler.startAudioService(cleanedUrl)
        val canOverlay = actionHandler.canDrawOverlays()
        if (canOverlay) {
            actionHandler.startOverlayService()
        }

        _state.update {
            it.copy(
                hasOverlayPermission = canOverlay,
                errorMessageRes = null,
                playbackStarted = true,
            )
        }
    }

    companion object {
        @StringRes
        val ERROR_EMPTY_URL: Int = R.string.error_empty_url

        @StringRes
        val ERROR_INVALID_URL: Int = R.string.error_invalid_url
    }
}

/** Material "content paste" glyph; material-icons-core does not ship it. */
private val PasteIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentPaste",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData =
            addPathNodes(
                "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16" +
                    "c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1" +
                    "s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z",
            ),
        fill = SolidColor(Color.Black),
    ).build()
}

private val ContentMaxWidth = 600.dp

/**
 * Root Composable for the dashboard: one scrolling column, capped at [ContentMaxWidth] and
 * centered on wide screens.
 */
@Composable
fun MainDashboardScreen(
    controller: MainDashboardController,
    modifier: Modifier = Modifier,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val queue by SubredditQueueHolder.state.collectAsState()
    val alert by PlaybackAlertHolder.message.collectAsState()
    // The service records played posts as it moves on.
    LaunchedEffect(queue) { controller.refreshSubreddits() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DashboardHeader()

                if (state.isPreparingVoices) {
                    PreparingVoicesCard()
                }

                alert?.let { PlaybackAlertCard(it) }

                if (!state.hasNotificationPermission) {
                    NotificationPermissionCard(
                        onGrantPermission = onRequestNotificationPermission,
                        onOpenSettings = { controller.onOpenNotificationSettingsClicked() },
                    )
                }

                SubredditsCard(
                    state = state,
                    queue = queue,
                    controller = controller,
                )

                FlavorPlaylistCard()

                PlayThreadCard(
                    inputUrl = state.inputUrl,
                    errorMessageRes = state.errorMessageRes,
                    playbackStarted = state.playbackStarted,
                    onUrlChanged = { controller.onUrlChanged(it) },
                    onPaste = { controller.onPasteFromClipboard(it) },
                    onPlayClicked = { controller.onPlayClicked() },
                    onDismissError = { controller.onDismissError() },
                )

                SettingsCard(
                    readUsernames = state.readUsernames,
                    onReadUsernamesChanged = { controller.onToggleReadUsernames(it) },
                    playbackSpeed = state.playbackSpeed,
                    onPlaybackSpeedChanged = { controller.onPlaybackSpeedChanged(it) },
                    qualitySteps = state.supertonicQualitySteps,
                    onQualityStepsChanged = { controller.onQualityStepsChanged(it) },
                    voiceEngine = state.voiceEngine,
                    narratorVoiceId = state.narratorVoiceId,
                    onNarratorVoiceChanged = { controller.onNarratorVoiceChanged(it) },
                    voicePackStatus = state.voicePackStatus,
                )

                FloatingPlayerRow(
                    hasPermission = state.hasOverlayPermission,
                    onOpenSettings = { controller.onOpenSettingsClicked() },
                )

                HowToUseSection()

                DashboardFooter()
            }
        }
    }
}

/**
 * App name and one-line tagline.
 */
@Composable
fun DashboardHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dashboard_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Why playback stopped, when the voice engine became unavailable.
 */
@Composable
fun PlaybackAlertCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * Status card shown on first launch while the bundled neural voice models are unpacked.
 */
@Composable
fun PreparingVoicesCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.preparing_voices_title),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.preparing_voices_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Compact card asking for POST_NOTIFICATIONS, which drives the playback notification on Android 13+.
 */
@Composable
fun NotificationPermissionCard(
    onGrantPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.notification_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
                TextButton(onClick = onGrantPermission) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

/**
 * The subreddits to read: add field, one row per sub with its checkbox, history reset and remove button.
 */
@Composable
fun SubredditsCard(
    state: MainDashboardState,
    queue: SubredditQueueState,
    controller: MainDashboardController,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val add = {
        controller.onAddSubreddit()
        focusManager.clearFocus()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.subs_card_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.subredditInput,
                    onValueChange = { controller.onSubredditInputChanged(it) },
                    label = { Text(stringResource(R.string.subs_field_label)) },
                    placeholder = { Text(stringResource(R.string.subs_field_placeholder)) },
                    isError = state.subredditErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = add) {
                    Text(stringResource(R.string.subs_add))
                }
            }

            state.subredditErrorRes?.let { errorRes ->
                Text(
                    text = stringResource(errorRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.subreddits.forEach { sub ->
                SubredditListRow(
                    row = sub,
                    onSelected = { controller.onSubredditSelected(sub.name, it) },
                    onResetPlayed = { controller.onForgetPlayed(sub.name) },
                    onRemove = { controller.onRemoveSubreddit(sub.name) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.subreddits.isNotEmpty()) {
                    val allSelected = state.subreddits.all { it.selected }
                    TextButton(onClick = { controller.onSelectAllSubreddits(!allSelected) }) {
                        Text(stringResource(if (allSelected) R.string.subs_select_none else R.string.subs_select_all))
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                TextButton(onClick = { controller.onForgetPlayed() }, enabled = state.playedTotal > 0) {
                    Text(stringResource(R.string.subs_forget_all))
                }
            }

            if (queue.active) {
                Text(
                    text =
                        if (queue.title != null) {
                            stringResource(R.string.queue_now_playing, queue.subreddit.orEmpty(), queue.title)
                        } else {
                            stringResource(R.string.queue_loading_next)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { controller.onNextPostClicked() }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.action_next_post))
                    }
                    FilledTonalButton(onClick = { controller.onStopQueueClicked() }, modifier = Modifier.height(52.dp)) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            } else {
                Button(onClick = { controller.onPlaySubredditsClicked() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.subs_play),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                queue.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubredditListRow(
    row: SubredditRow,
    onSelected: (Boolean) -> Unit,
    onResetPlayed: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.selected, onCheckedChange = onSelected)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.subs_name, row.name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.playedCount > 0) {
                Text(
                    text = stringResource(R.string.subs_played_count, row.playedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.playedCount > 0) {
            TextButton(onClick = onResetPlayed) {
                Text(stringResource(R.string.subs_reset))
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.subs_remove, row.name),
            )
        }
    }
}

/**
 * Primary card: link field with paste button, Play button, inline error and playback confirmation.
 */
@Composable
fun PlayThreadCard(
    inputUrl: String,
    @StringRes errorMessageRes: Int?,
    playbackStarted: Boolean,
    onUrlChanged: (String) -> Unit,
    onPaste: (String?) -> Unit,
    onPlayClicked: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val play = {
        focusManager.clearFocus()
        onPlayClicked()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.play_card_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = inputUrl,
                onValueChange = onUrlChanged,
                label = { Text(stringResource(R.string.url_field_label)) },
                placeholder = { Text(stringResource(R.string.url_field_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = { onPaste(clipboard.getText()?.text) }) {
                        Icon(
                            imageVector = PasteIcon,
                            contentDescription = stringResource(R.string.action_paste_link),
                        )
                    }
                },
                isError = errorMessageRes != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { play() }, onDone = { play() }),
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorMessageRes != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(errorMessageRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }

            Button(
                onClick = play,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_play),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (playbackStarted) {
                Text(
                    text = stringResource(R.string.playback_started),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Playback preferences: read usernames, speed, narrator voice and (Supertonic only) voice quality.
 */
@Composable
fun SettingsCard(
    readUsernames: Boolean,
    onReadUsernamesChanged: (Boolean) -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChanged: (Float) -> Unit,
    qualitySteps: Int,
    onQualityStepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    voiceEngine: VoiceEngine = VoiceEngine.DEFAULT,
    narratorVoiceId: String = EngineVoices.defaultNarrator(voiceEngine),
    onNarratorVoiceChanged: (String) -> Unit = {},
    voicePackStatus: VoicePackStatus = VoicePackStatus.READY,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.read_usernames_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.read_usernames_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = readUsernames, onCheckedChange = onReadUsernamesChanged)
            }

            ChoiceChips(
                title = stringResource(R.string.playback_speed_title),
                options = RedderPreferences.PLAYBACK_SPEEDS.map { it to RedderPreferences.formatPlaybackSpeed(it) },
                selected = playbackSpeed,
                onSelected = onPlaybackSpeedChanged,
            )

            when (voicePackStatus) {
                VoicePackStatus.PREPARING -> SettingsNote(stringResource(R.string.voice_pack_preparing))
                VoicePackStatus.NOT_INSTALLED -> SettingsNote(stringResource(R.string.voice_pack_not_installed), isWarning = true)
                VoicePackStatus.READY -> Unit
            }

            ChoiceChips(
                title = stringResource(R.string.narrator_voice_title),
                options = EngineVoices.voices(voiceEngine).map { it.id to it.label },
                selected = narratorVoiceId,
                onSelected = onNarratorVoiceChanged,
            )

            if (SettingsCardRules.showsVoiceQuality(voiceEngine)) {
                ChoiceChips(
                    title = stringResource(R.string.voice_quality_title),
                    options =
                        listOf(
                            RedderPreferences.QUALITY_STEPS_FAST to stringResource(R.string.voice_quality_fast),
                            RedderPreferences.QUALITY_STEPS_BEST to stringResource(R.string.voice_quality_best),
                        ),
                    selected = qualitySteps,
                    onSelected = onQualityStepsChanged,
                )
            }
        }
    }
}

/** Which optional rows the Settings card shows for a voice engine. */
internal object SettingsCardRules {
    /** Diffusion steps ("Voice quality") only exist in Supertonic. */
    fun showsVoiceQuality(engine: VoiceEngine): Boolean = engine == VoiceEngine.SUPERTONIC
}

/**
 * One-line explanatory note under a setting.
 */
@Composable
private fun SettingsNote(
    text: String,
    isWarning: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Labelled single-choice row of filter chips; scrolls horizontally if it does not fit.
 */
@Composable
private fun <T> ChoiceChips(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(text = label, maxLines = 1) },
                )
            }
        }
    }
}

/**
 * One-line floating player status; the button opens the overlay permission screen.
 */
@Composable
fun FloatingPlayerRow(
    hasPermission: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.floating_player_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (hasPermission) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.floating_player_on))
                }
            } else {
                FilledTonalButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.floating_player_turn_on))
                }
            }
        }
    }
}

/**
 * Short usage hint.
 */
@Composable
fun HowToUseSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.how_to_use_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.how_to_use_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Disclaimer and a one-line about note listing the Redlib mirrors.
 */
@Composable
fun DashboardFooter(
    modifier: Modifier = Modifier,
    mirrors: List<String> = RedditApiClient.COMMUNITY_MIRRORS,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.about_line, mirrors.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Main activity hosting the dashboard.
 */
class MainActivity : ComponentActivity() {
    internal var actionHandler: MainDashboardActionHandler? = null
    internal var controller: MainDashboardController? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller?.onRefreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CurrentFlavor.onProcessStart(this)
        val handler =
            actionHandler
                ?: DefaultMainDashboardActionHandler(activity = this)
        val ctrl =
            controller
                ?: MainDashboardController(actionHandler = handler)
        controller = ctrl

        setContent {
            ThreadReaderTheme {
                MainDashboardScreen(
                    controller = ctrl,
                    onRequestNotificationPermission = { requestNotificationPermission() },
                )
            }
        }

        // Unpack the Supertonic models off the main thread (hundreds of MB on first launch)
        lifecycleScope.launch {
            val alreadyReady = withContext(Dispatchers.IO) { CurrentFlavor.voicesReady(this@MainActivity) }
            if (!alreadyReady) {
                ctrl.onVoicesPreparing(true)
                try {
                    withContext(Dispatchers.IO) { handler.ensureModelAvailable() }
                } finally {
                    ctrl.onVoicesPreparing(false)
                }
            }
        }

        // Proactively request notification permission on first launch (API 33+)
        if (savedInstanceState == null && !handler.hasNotificationPermission()) {
            requestNotificationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        controller?.onRefreshPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
