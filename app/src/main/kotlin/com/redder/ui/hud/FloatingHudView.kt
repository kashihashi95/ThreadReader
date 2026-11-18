package com.redder.ui.hud

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redder.R
import com.redder.model.DialogueItem
import com.redder.model.SynthesisProgress
import com.redder.model.SynthesisStage
import com.redder.settings.RedderPreferences
import kotlinx.coroutines.delay

/**
 * Pure state model representing the interactive floating HUD overlay.
 *
 * @property speaker Speaker name or role attribution of the current dialogue utterance.
 * @property text The dialogue text snippet currently spoken or synthesized.
 * @property isPlaying True if audio playback is actively playing, false if paused or idle.
 * @property hasNext True if navigation to a succeeding utterance is available.
 * @property hasPrevious True if navigation to an earlier utterance is available.
 * @property synthesisProgress Current neural synthesis progress or download state.
 * @property dialogueItems Full linearized list of thread dialogue transcript items.
 * @property isExpanded Whether the overlay transcript list view is expanded.
 * @property currentUtteranceIndex Current active utterance index.
 * @property playbackSpeed Current audio playback speed multiplier.
 * @property readUsernames Whether author usernames and attribution prefixes are read aloud.
 * @property isMinimized Whether the HUD is collapsed to its bubble; playback is not affected.
 * @property queueActive True while a subreddit queue runs; shows the post headline and Next post.
 * @property queueSubreddit Sub of the queued post being read.
 * @property queueTitle Title of the queued post being read, or null between posts.
 */
data class HudState(
    val speaker: String,
    val text: String,
    val isPlaying: Boolean,
    val hasNext: Boolean = true,
    val hasPrevious: Boolean = true,
    val synthesisProgress: SynthesisProgress = SynthesisProgress(),
    val dialogueItems: List<DialogueItem> = emptyList(),
    val isExpanded: Boolean = false,
    val currentUtteranceIndex: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val readUsernames: Boolean = true,
    val isMinimized: Boolean = false,
    val queueActive: Boolean = false,
    val queueSubreddit: String? = null,
    val queueTitle: String? = null,
    val alertMessage: String? = null,
    val doneAvailable: Boolean = false,
    val undoAvailable: Boolean = false,
    val nowPlayingAvailable: Boolean = false,
)

/**
 * Sealed interface for all user interaction events originating from the floating HUD overlay.
 */
sealed interface HudEvent {
    data object PlayPauseClicked : HudEvent

    data object SkipNextClicked : HudEvent

    data object SkipPreviousClicked : HudEvent

    data object NextPostClicked : HudEvent

    data object DoneClicked : HudEvent

    data object UndoDoneClicked : HudEvent

    data object NowPlayingClicked : HudEvent

    data object DismissClicked : HudEvent

    data object ToggleExpandedClicked : HudEvent

    data class DialogueItemClicked(val index: Int) : HudEvent

    data object SpeedClicked : HudEvent

    data object ToggleReadUsernamesClicked : HudEvent

    data class Drag(val deltaX: Float, val deltaY: Float) : HudEvent

    data object MinimizeClicked : HudEvent

    data object RestoreClicked : HudEvent

    /** A drag of the bubble ended. */
    data object DragEnded : HudEvent
}

/** The bubble's on-screen size. */
val HudBubbleSize: Dp = 52.dp

/** Keeps the bubble's top-left corner on a [screenWidth] by [screenHeight] screen. */
object HudBubbleBounds {
    fun clamp(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        size: Int,
    ): Pair<Int, Int> = x.coerceIn(0, (screenWidth - size).coerceAtLeast(0)) to y.coerceIn(0, (screenHeight - size).coerceAtLeast(0))
}

/**
 * Built-in vector icons for the floating HUD overlay, avoiding external icon pack dependencies.
 */
internal object HudIcons {
    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 5f)
                lineTo(19f, 12f)
                lineTo(8f, 19f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 19f)
                horizontalLineTo(10f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                close()
                moveTo(14f, 5f)
                verticalLineTo(19f)
                horizontalLineTo(18f)
                verticalLineTo(5f)
                close()
            }
        }.build()
    }

    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "SkipNext",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 18f)
                lineTo(14.5f, 12f)
                lineTo(6f, 6f)
                close()
                moveTo(16f, 6f)
                verticalLineTo(18f)
                horizontalLineTo(18f)
                verticalLineTo(6f)
                close()
            }
        }.build()
    }

    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "SkipPrevious",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                horizontalLineTo(8f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                close()
                moveTo(9.5f, 12f)
                lineTo(18f, 18f)
                verticalLineTo(6f)
                close()
            }
        }.build()
    }

    val Minimize: ImageVector by lazy {
        ImageVector.Builder(
            name = "Minimize",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 17f)
                horizontalLineTo(18f)
                verticalLineTo(19f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
    }

    val AudioBars: ImageVector by lazy {
        ImageVector.Builder(
            name = "AudioBars",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(5f, 10f)
                horizontalLineTo(7f)
                verticalLineTo(14f)
                horizontalLineTo(5f)
                close()
                moveTo(9f, 6f)
                horizontalLineTo(11f)
                verticalLineTo(18f)
                horizontalLineTo(9f)
                close()
                moveTo(13f, 3f)
                horizontalLineTo(15f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                close()
                moveTo(17f, 8f)
                horizontalLineTo(19f)
                verticalLineTo(16f)
                horizontalLineTo(17f)
                close()
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19f, 6.41f)
                lineTo(17.59f, 5f)
                lineTo(12f, 10.59f)
                lineTo(6.41f, 5f)
                lineTo(5f, 6.41f)
                lineTo(10.59f, 12f)
                lineTo(5f, 17.59f)
                lineTo(6.41f, 19f)
                lineTo(12f, 13.41f)
                lineTo(17.59f, 19f)
                lineTo(19f, 17.59f)
                lineTo(13.41f, 12f)
                close()
            }
        }.build()
    }

    val Expand: ImageVector by lazy {
        ImageVector.Builder(
            name = "Expand",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(16.59f, 8.59f)
                lineTo(12f, 13.17f)
                lineTo(7.41f, 8.59f)
                lineTo(6f, 10f)
                lineTo(12f, 16f)
                lineTo(18f, 10f)
                close()
            }
        }.build()
    }

    val Collapse: ImageVector by lazy {
        ImageVector.Builder(
            name = "Collapse",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 8f)
                lineTo(6f, 14f)
                lineTo(7.41f, 15.41f)
                lineTo(12f, 10.83f)
                lineTo(16.59f, 15.41f)
                lineTo(18f, 14f)
                close()
            }
        }.build()
    }

    val Person: ImageVector by lazy {
        ImageVector.Builder(
            name = "Person",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 12f)
                curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
                curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
                curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
                curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
                close()
                moveTo(12f, 14f)
                curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f)
                verticalLineTo(20f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f)
                close()
            }
        }.build()
    }

    val PersonOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "PersonOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 12f)
                curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
                curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
                curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
                curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
                close()
                moveTo(12f, 14f)
                curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f)
                verticalLineTo(20f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f)
                close()
                moveTo(3.27f, 3.27f)
                lineTo(20.73f, 20.73f)
                lineTo(22f, 19.46f)
                lineTo(4.54f, 2f)
                close()
            }
        }.build()
    }

    val DragHandle: ImageVector by lazy {
        ImageVector.Builder(
            name = "DragHandle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 9f)
                horizontalLineTo(20f)
                verticalLineTo(11f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 13f)
                horizontalLineTo(20f)
                verticalLineTo(15f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
    }
}

/**
 * The minimized floating player: a static glyph that restores on a tap and moves on a drag.
 */
@Composable
private fun HudBubble(
    onEvent: (HudEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .size(HudBubbleSize)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onEvent(HudEvent.DragEnded) }) { change, dragAmount ->
                        change.consume()
                        onEvent(HudEvent.Drag(dragAmount.x, dragAmount.y))
                    }
                }.clickable { onEvent(HudEvent.RestoreClicked) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = HudIcons.AudioBars,
                contentDescription = stringResource(R.string.hud_restore),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Interactive floating HUD composable overlay displaying current speaker metadata,
 * utterance dialogue snippet, playback controls, and a dismissal button.
 *
 * @param state Immutable HUD presentation state.
 * @param onEvent Callback receiving user interactions.
 * @param modifier Composable layout modifier.
 */
@Composable
fun FloatingHud(
    state: HudState,
    onEvent: (HudEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isMinimized) {
        HudBubble(onEvent = onEvent, modifier = modifier)
        return
    }
    Card(
        modifier =
            modifier
                .widthIn(min = 280.dp, max = if (state.isExpanded) 380.dp else 360.dp)
                .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // Top Bar: Drag Handle, Speaker Badge, Expand Button, and Dismiss Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onEvent(HudEvent.Drag(dragAmount.x, dragAmount.y))
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HudIcons.DragHandle,
                            contentDescription = stringResource(R.string.hud_drag_handle),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = state.speaker,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = { onEvent(HudEvent.ToggleReadUsernamesClicked) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (state.readUsernames) HudIcons.Person else HudIcons.PersonOff,
                                contentDescription =
                                    stringResource(
                                        if (state.readUsernames) R.string.hud_mute_usernames else R.string.hud_read_usernames,
                                    ),
                                modifier = Modifier.size(18.dp),
                                tint =
                                    if (state.readUsernames) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                            )
                        }

                        IconButton(
                            onClick = { onEvent(HudEvent.ToggleExpandedClicked) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (state.isExpanded) HudIcons.Collapse else HudIcons.Expand,
                                contentDescription =
                                    stringResource(
                                        if (state.isExpanded) R.string.hud_collapse_transcript else R.string.hud_expand_transcript,
                                    ),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = { onEvent(HudEvent.MinimizeClicked) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = HudIcons.Minimize,
                                contentDescription = stringResource(R.string.hud_minimize),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = { onEvent(HudEvent.DismissClicked) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = HudIcons.Close,
                                contentDescription = stringResource(R.string.hud_dismiss),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.alertMessage?.let { alert ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = alert,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            // Real-time synthesis progress bar directly under the header
            val progressStage = state.synthesisProgress.stage
            if (progressStage == SynthesisStage.FETCHING_THREAD || progressStage == SynthesisStage.SYNTHESIZING) {
                Spacer(modifier = Modifier.height(6.dp))
                val statusText =
                    when (progressStage) {
                        SynthesisStage.FETCHING_THREAD -> stringResource(R.string.hud_fetching_thread)
                        SynthesisStage.SYNTHESIZING -> {
                            val cur = state.synthesisProgress.currentChunk
                            val tot = state.synthesisProgress.totalChunks
                            if (tot > 0) {
                                stringResource(R.string.hud_synthesizing_progress, cur, tot)
                            } else {
                                stringResource(R.string.hud_synthesizing)
                            }
                        }
                    }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                val total = state.synthesisProgress.totalChunks
                if (progressStage == SynthesisStage.SYNTHESIZING && total > 0) {
                    val progressFraction =
                        (state.synthesisProgress.currentChunk.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (state.queueActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            if (state.queueTitle != null) {
                                stringResource(R.string.queue_now_playing, state.queueSubreddit.orEmpty(), state.queueTitle)
                            } else {
                                stringResource(R.string.queue_loading_next)
                            },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onEvent(HudEvent.NextPostClicked) }) {
                        Text(stringResource(R.string.action_next_post))
                    }
                }
                if (state.doneAvailable || state.undoAvailable || state.nowPlayingAvailable) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (state.nowPlayingAvailable) {
                            TextButton(onClick = { onEvent(HudEvent.NowPlayingClicked) }) {
                                Text(stringResource(R.string.action_now_playing))
                            }
                        }
                        if (state.undoAvailable) {
                            TextButton(onClick = { onEvent(HudEvent.UndoDoneClicked) }) {
                                Text(stringResource(R.string.action_undo))
                            }
                        }
                        if (state.doneAvailable) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(onClick = { onEvent(HudEvent.DoneClicked) }) {
                                Text(stringResource(R.string.action_done))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Utterance Dialogue Snippet
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clickable { onEvent(HudEvent.DialogueItemClicked(state.currentUtteranceIndex)) }
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onEvent(HudEvent.SkipPreviousClicked) },
                    enabled = state.hasPrevious,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = HudIcons.SkipPrevious,
                        contentDescription = stringResource(R.string.hud_previous),
                        modifier = Modifier.size(24.dp),
                    )
                }

                FilledIconButton(
                    onClick = { onEvent(HudEvent.PlayPauseClicked) },
                    modifier = Modifier.size(44.dp),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) HudIcons.Pause else HudIcons.Play,
                        contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(26.dp),
                    )
                }

                IconButton(
                    onClick = { onEvent(HudEvent.SkipNextClicked) },
                    enabled = state.hasNext,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = HudIcons.SkipNext,
                        contentDescription = stringResource(R.string.hud_next),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val formattedSpeed = RedderPreferences.formatPlaybackSpeed(state.playbackSpeed)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clickable { onEvent(HudEvent.SpeedClicked) },
                ) {
                    Text(
                        text = formattedSpeed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Expanded Full Transcript View
            if (state.isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.hud_transcript_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                if (state.dialogueItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hud_transcript_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    val listState = rememberLazyListState()
                    // Uptime of the user's last drag of the list; null when they have not dragged it.
                    // Only drags count: the list's own animated scrolls emit no drag interactions.
                    var lastUserScrollAt by remember { mutableStateOf<Long?>(null) }

                    LaunchedEffect(listState) {
                        listState.interactionSource.interactions.collect { interaction ->
                            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop) {
                                lastUserScrollAt = SystemClock.uptimeMillis()
                            }
                        }
                    }

                    // Follows the card being played; re-runs when it changes, when cards arrive, and
                    // once the pause after a manual scroll is over.
                    LaunchedEffect(state.currentUtteranceIndex, state.dialogueItems.size, lastUserScrollAt) {
                        delay(TranscriptAutoScroll.pauseRemainingMs(lastUserScrollAt, SystemClock.uptimeMillis()))
                        if (TranscriptAutoScroll.shouldScroll(
                                currentIndex = state.currentUtteranceIndex,
                                itemCount = state.dialogueItems.size,
                                firstVisibleIndex = listState.firstVisibleItemIndex,
                                lastUserScrollAtMs = lastUserScrollAt,
                                nowMs = SystemClock.uptimeMillis(),
                            )
                        ) {
                            listState.animateScrollToItem(state.currentUtteranceIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.dialogueItems, key = { it.index }) { item ->
                            val isSelected = item.index == state.currentUtteranceIndex
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // A tap is not reading back: follow playback to the tapped card.
                                            lastUserScrollAt = null
                                            onEvent(HudEvent.DialogueItemClicked(item.index))
                                        },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.hud_author, item.author),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color =
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                        if (item.isOp) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.hud_op_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
