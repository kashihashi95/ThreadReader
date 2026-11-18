package com.redder.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.redder.R
import com.redder.flavor.CurrentFlavor

/**
 * Playback action classification for media notifications.
 */
enum class PlaybackActionType {
    PLAY,
    PAUSE,
    SKIP_NEXT,
    SKIP_PREVIOUS,
    STOP,
    DONE,
}

/**
 * User-visible playback text for the notification and the audio service. Defaults are the English
 * strings so pure JVM tests need no resources; [fromContext] reads the localized resources.
 */
data class PlaybackStrings(
    val channelName: String = MediaNotificationManager.CHANNEL_NAME,
    val channelDescription: String = MediaNotificationManager.DEFAULT_CHANNEL_DESCRIPTION,
    val defaultTitle: String = MediaNotificationManager.DEFAULT_TITLE,
    val defaultText: String = MediaNotificationManager.DEFAULT_TEXT,
    val actionPlay: String = "Play",
    val actionPause: String = "Pause",
    val actionPrevious: String = "Previous",
    val actionNext: String = "Next",
    val loadingThread: String = "Loading Reddit thread…",
    val noDialogue: String = "No dialogue found",
    val synthesisFailed: String = "Failed to synthesize audio",
    val processingFailed: String = "Failed to process Reddit thread",
    val threadLoadFailed: String = RedderAudioService.ERROR_TEXT_THREAD_LOAD_FAILED,
    val systemSpeaker: String = "System",
    /** Notification text while a slow engine synthesizes ahead; formatted with (done, total). */
    val generatingProgress: String = "Generating… %1\$d/%2\$d",
    /** The notification's next button while a subreddit queue runs. */
    val actionNextPost: String = "Next post",
    /** The notification's Done button while a playlist post is read (ADR-009). */
    val actionDone: String = "Done",
    val queueNoNewPosts: String = "No new posts in your selected subs",
    val queueAllFailed: String = "Couldn't load your selected subs. Mirrors may be down, try again.",
) {
    companion object {
        /** Builds [PlaybackStrings] from string resources. */
        fun fromContext(context: Context): PlaybackStrings =
            PlaybackStrings(
                channelName = context.getString(R.string.notification_channel_name),
                channelDescription = context.getString(R.string.notification_channel_description),
                defaultTitle = context.getString(R.string.app_name),
                defaultText = context.getString(R.string.notification_default_text),
                actionPlay = context.getString(R.string.action_play),
                actionPause = context.getString(R.string.action_pause),
                actionPrevious = context.getString(R.string.notification_action_previous),
                actionNext = context.getString(R.string.notification_action_next),
                loadingThread = context.getString(R.string.playback_loading_thread),
                noDialogue = context.getString(R.string.playback_no_dialogue),
                synthesisFailed = context.getString(R.string.playback_synthesis_failed),
                processingFailed = context.getString(R.string.playback_processing_failed),
                threadLoadFailed = context.getString(R.string.playback_thread_load_failed),
                systemSpeaker = context.getString(R.string.playback_system_speaker),
                generatingProgress = context.getString(R.string.playback_generating_progress),
                actionNextPost = context.getString(R.string.action_next_post),
                actionDone = context.getString(R.string.action_done),
                queueNoNewPosts = context.getString(R.string.queue_no_new_posts),
                queueAllFailed = context.getString(R.string.queue_all_failed),
            )
    }
}

/**
 * Pure data model representing a single notification action.
 */
data class NotificationActionSpec(
    val actionType: PlaybackActionType,
    val iconResId: Int,
    val title: String,
)

/**
 * Pure data model representing notification channel configuration parameters.
 */
data class NotificationChannelSpec(
    val id: String,
    val name: String,
    val importance: Int,
    val description: String? = null,
)

/**
 * Pure data model capturing all parameters required to build a media notification.
 */
data class NotificationSpec(
    val channelId: String,
    val title: String,
    val text: String,
    val smallIconResId: Int,
    val actions: List<NotificationActionSpec>,
    val compactActionIndices: List<Int>,
    val isOngoing: Boolean,
)

/**
 * Abstraction seam for notification channel creation on Android O+.
 */
interface NotificationChannelController {
    fun createNotificationChannel(spec: NotificationChannelSpec)
}

/**
 * Abstraction seam for producing Android [Notification] objects from [NotificationSpec].
 */
interface NotificationFactory {
    fun buildNotification(
        spec: NotificationSpec,
        session: MediaSession? = null,
    ): Notification
}

/**
 * Default Android platform implementation of [NotificationChannelController].
 */
class DefaultNotificationChannelController(
    private val context: Context,
) : NotificationChannelController {
    override fun createNotificationChannel(spec: NotificationChannelSpec) {
        val channel =
            NotificationChannel(spec.id, spec.name, spec.importance).apply {
                spec.description?.let { description = it }
            }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }
}

/**
 * Default Android platform implementation of [NotificationFactory] using AndroidX NotificationCompat.
 */

class DefaultNotificationFactory(
    private val context: Context,
) : NotificationFactory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun buildNotification(
        spec: NotificationSpec,
        session: MediaSession?,
    ): Notification {
        val builder =
            NotificationCompat.Builder(context, spec.channelId)
                .setContentTitle(spec.title)
                .setContentText(spec.text)
                .setSmallIcon(spec.smallIconResId)
                .setOngoing(spec.isOngoing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        CurrentFlavor.nowPlayingIntent(context)?.let { intent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONTENT_REQUEST,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        for (actionSpec in spec.actions) {
            val intent =
                Intent(context, RedderAudioService::class.java).apply {
                    action =
                        when (actionSpec.actionType) {
                            PlaybackActionType.PLAY -> RedderAudioService.ACTION_PLAY
                            PlaybackActionType.PAUSE -> RedderAudioService.ACTION_PAUSE
                            PlaybackActionType.SKIP_NEXT -> RedderAudioService.ACTION_SKIP_NEXT
                            PlaybackActionType.SKIP_PREVIOUS -> RedderAudioService.ACTION_SKIP_PREVIOUS
                            PlaybackActionType.STOP -> RedderAudioService.ACTION_STOP
                            PlaybackActionType.DONE -> RedderAudioService.ACTION_DONE
                        }
                }
            val pendingIntent =
                PendingIntent.getService(
                    context,
                    actionSpec.actionType.ordinal,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(actionSpec.iconResId, actionSpec.title, pendingIntent)
        }

        if (session != null) {
            val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)
            if (spec.compactActionIndices.isNotEmpty()) {
                mediaStyle.setShowActionsInCompactView(*spec.compactActionIndices.toIntArray())
            }
            builder.setStyle(mediaStyle)
        }

        return builder.build()
    }
}

/**
 * Notification manager for headless foreground playback in [RedderAudioService].
 *
 * Configures notification channel [CHANNEL_ID] ("redder_playback") and constructs
 * MediaStyle foreground notifications with play/pause and skip actions.
 */
class MediaNotificationManager(
    private val channelController: NotificationChannelController,
    private val notificationFactory: NotificationFactory,
    val strings: PlaybackStrings = PlaybackStrings(),
) {
    /** True while a subreddit queue runs, when the next button moves to the next post. */
    @Volatile
    var nextIsPost: Boolean = false

    /** True while a playlist queue runs, when the notification also offers Done. */
    @Volatile
    var offersDone: Boolean = false

    constructor(context: Context) : this(
        channelController = DefaultNotificationChannelController(context),
        notificationFactory = DefaultNotificationFactory(context),
        strings = PlaybackStrings.fromContext(context),
    )

    fun createNotificationChannel(
        channelId: String = CHANNEL_ID,
        channelName: String = strings.channelName,
        importance: Int = IMPORTANCE_LOW,
        description: String? = strings.channelDescription,
    ) {
        channelController.createNotificationChannel(
            NotificationChannelSpec(
                id = channelId,
                name = channelName,
                importance = importance,
                description = description,
            ),
        )
    }

    fun buildNotificationSpec(
        title: String = strings.defaultTitle,
        text: String = strings.defaultText,
        isPlaying: Boolean = false,
    ): NotificationSpec {
        val playPauseAction =
            if (isPlaying) {
                NotificationActionSpec(
                    actionType = PlaybackActionType.PAUSE,
                    iconResId = android.R.drawable.ic_media_pause,
                    title = strings.actionPause,
                )
            } else {
                NotificationActionSpec(
                    actionType = PlaybackActionType.PLAY,
                    iconResId = android.R.drawable.ic_media_play,
                    title = strings.actionPlay,
                )
            }

        val actions =
            listOf(
                NotificationActionSpec(
                    actionType = PlaybackActionType.SKIP_PREVIOUS,
                    iconResId = android.R.drawable.ic_media_previous,
                    title = strings.actionPrevious,
                ),
                playPauseAction,
                NotificationActionSpec(
                    actionType = PlaybackActionType.SKIP_NEXT,
                    iconResId = android.R.drawable.ic_media_next,
                    title = if (nextIsPost) strings.actionNextPost else strings.actionNext,
                ),
            ) +
                if (offersDone) {
                    listOf(
                        NotificationActionSpec(
                            actionType = PlaybackActionType.DONE,
                            iconResId = android.R.drawable.checkbox_on_background,
                            title = strings.actionDone,
                        ),
                    )
                } else {
                    emptyList()
                }

        return NotificationSpec(
            channelId = CHANNEL_ID,
            title = title,
            text = text,
            smallIconResId = android.R.drawable.ic_media_play,
            actions = actions,
            compactActionIndices = listOf(0, 1, 2),
            isOngoing = isPlaying,
        )
    }

    fun buildNotification(
        title: String = strings.defaultTitle,
        text: String = strings.defaultText,
        isPlaying: Boolean = false,
        session: MediaSession? = null,
    ): Notification {
        val spec = buildNotificationSpec(title, text, isPlaying)
        return notificationFactory.buildNotification(spec, session)
    }

    fun buildNotification(
        spec: NotificationSpec,
        session: MediaSession? = null,
    ): Notification = notificationFactory.buildNotification(spec, session)

    companion object {
        const val CHANNEL_ID: String = "redder_playback"
        const val CHANNEL_NAME: String = "ThreadReader Audio Playback"
        const val NOTIFICATION_ID: Int = 1001
        const val DEFAULT_TITLE: String = "ThreadReader"
        const val DEFAULT_TEXT: String = "Audio playback"
        const val DEFAULT_CHANNEL_DESCRIPTION: String = "ThreadReader audio playback controls and status"
        const val IMPORTANCE_LOW: Int = 2
    }
}

/** Past the action types' own request codes. */
private const val CONTENT_REQUEST = 100
