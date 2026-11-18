package com.redder.deck

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.redder.R
import com.redder.flavor.CurrentFlavor
import com.redder.model.ActiveTranscriptHolder
import com.redder.model.SubredditQueueHolder
import com.redder.model.SynthesisProgressHolder
import com.redder.playback.ChunkMediaId
import com.redder.playback.RedderAudioService
import com.redder.playlist.DoneButtonHolder
import com.redder.ui.MainActivity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Ground = Color(0xFF17151C)
private val OnGround = Color(0xFFF4F1EA)
private val Paper = Color(0xFFF4EBDD)
private val Ink = Color(0xFF2A2118)
private val InkSoft = Color(0xFF5B4C3B)
private val InkQuiet = Color(0xFF6E5E4C) // 5.3:1 on the paper; the mockup's #786754 is under 4.5:1
private val NoticeInk = Color(0xFF7A3E00)
private val PeekNext = Color(0xFF3B5B4F)
private val PeekThen = Color(0xFF6A4A5E)
private val Banner = Color(0xFFF2C879)

private val MAX_DECK_WIDTH = 640.dp
private val WIDE_AT = 600.dp
private val SHORT_AT = 680.dp
private val TINY_AT = 480.dp
private val ANCHOR = 70.dp
private val ANCHOR_SHORT = 30.dp
private const val PLAYER_TICK_MS = 100L
private const val COLOUR_FADE_MS = 400
private const val FAR_LINES = 3
private const val MS_PER_MINUTE = 60_000L

/** What the screen asks of playback; the service and its session do the rest. */
interface NowPlayingCommands {
    fun startPlaylist()

    fun play()

    fun pause()

    fun nextPost()

    fun done()

    fun undoDone()

    fun jump(
        chunk: Int,
        ms: Long,
    )
}

private class ServiceCommands(private val context: Context) : NowPlayingCommands {
    private fun intent(action: String) = Intent(context, RedderAudioService::class.java).setAction(action)

    private fun send(action: String) {
        context.startService(intent(action))
    }

    override fun startPlaylist() {
        ContextCompat.startForegroundService(context, intent(RedderAudioService.ACTION_PLAY_SUBREDDITS))
    }

    override fun play() = send(RedderAudioService.ACTION_PLAY)

    override fun pause() = send(RedderAudioService.ACTION_PAUSE)

    override fun nextPost() = send(RedderAudioService.ACTION_SKIP_NEXT)

    override fun done() = send(RedderAudioService.ACTION_DONE)

    override fun undoDone() = send(RedderAudioService.ACTION_UNDO_DONE)

    override fun jump(
        chunk: Int,
        ms: Long,
    ) {
        context.startService(
            intent(RedderAudioService.ACTION_JUMP_TO_TRANSCRIPT_INDEX)
                .putExtra(RedderAudioService.EXTRA_TRANSCRIPT_INDEX, chunk)
                .putExtra(RedderAudioService.EXTRA_POSITION_MS, ms),
        )
    }
}

/** The card deck (the owner's mockup B), played by the same service as everything else. Personal and mobile builds. */
class NowPlayingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val commands = ServiceCommands(applicationContext)
        setContent {
            NowPlayingScreen(lifecycle = lifecycle, commands = commands, onOpenList = ::openList)
        }
    }

    private fun openList() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, NowPlayingActivity::class.java)
    }
}

/** Follows [this] only while the screen can be seen, so nothing runs for a stopped screen. */
@Composable
private fun <T> StateFlow<T>.whileStarted(lifecycle: Lifecycle): State<T> {
    val flow = this
    val state = remember(flow) { mutableStateOf(flow.value) }
    LaunchedEffect(flow, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { state.value = it }
        }
    }
    return state
}

/** The player's position as last read. */
private data class PlayerReading(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val chunk: Int? = null,
    val title: String? = null,
)

private suspend fun connect(context: Context): MediaController =
    suspendCancellableCoroutine { continuation ->
        val future =
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, RedderAudioService::class.java)),
            ).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
    }

/** Reads the player a few times a second, while there is a session to read and the screen is visible. */
@Composable
private fun playerReadings(
    lifecycle: Lifecycle,
    hasSession: Boolean,
): State<PlayerReading> {
    val context = LocalContext.current.applicationContext
    val reading = remember { mutableStateOf(PlayerReading()) }
    LaunchedEffect(lifecycle, hasSession) {
        if (!hasSession) {
            reading.value = PlayerReading()
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val controller = runCatching { connect(context) }.getOrNull() ?: return@repeatOnLifecycle
            try {
                while (currentCoroutineContext().isActive) {
                    reading.value =
                        PlayerReading(
                            isPlaying = controller.isPlaying,
                            positionMs = controller.currentPosition,
                            durationMs = controller.duration.takeIf { it != C.TIME_UNSET && it > 0L },
                            chunk = ChunkMediaId.transcriptIndex(controller.currentMediaItem?.mediaId),
                            title = controller.mediaMetadata.title?.toString(),
                        )
                    delay(PLAYER_TICK_MS)
                }
            } finally {
                controller.release()
            }
        }
    }
    return reading
}

@Composable
private fun NowPlayingScreen(
    lifecycle: Lifecycle,
    commands: NowPlayingCommands,
    onOpenList: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember { CurrentFlavor.playlistSource(context) }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { repository.whileScreenVisible() }
    }
    val playlist by repository.state.whileStarted(lifecycle)
    val transcript by ActiveTranscriptHolder.transcript.whileStarted(lifecycle)
    val currentIndex by ActiveTranscriptHolder.currentIndex.whileStarted(lifecycle)
    val queue by SubredditQueueHolder.state.whileStarted(lifecycle)
    val doneState by DoneButtonHolder.state.whileStarted(lifecycle)
    val progress by SynthesisProgressHolder.progress.whileStarted(lifecycle)
    val hasSession = transcript.isNotEmpty() || queue.active
    val player by playerReadings(lifecycle, hasSession)

    val rows = remember(playlist) { repository.visibleRows(playlist) }
    val deck = remember(rows, queue.postId) { deckView(rows, queue.postId) }
    val script = remember(transcript) { CaptionScript(transcript) }
    // The player's position only means something for the clip the holder says is current.
    val inClip = player.chunk == currentIndex
    val activeLine = script.lineAt(currentIndex, if (inClip) player.positionMs else 0L, if (inClip) player.durationMs else null)

    var pending by remember { mutableStateOf<CaptionLine?>(null) }
    LaunchedEffect(player, currentIndex) {
        val correction = script.correctionFor(pending, currentIndex, player.positionMs, if (inClip) player.durationMs else null)
        if (pending?.chunk == currentIndex && inClip && player.durationMs != null) {
            pending = null
            if (correction != null) commands.jump(currentIndex, correction)
        }
    }

    val notice = cardNotice(queue.active, transcript.isNotEmpty(), player.isPlaying, progress.stage)
    val title = queue.title ?: deck.current?.title ?: player.title ?: rows.firstOrNull()?.title.orEmpty()
    val sub = queue.subreddit ?: deck.current?.sub ?: rows.firstOrNull()?.sub
    val lengthMs = (deck.current ?: rows.firstOrNull())?.lengthMs

    Box(modifier = Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .widthIn(max = MAX_DECK_WIDTH)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp),
        ) {
            val short = maxHeight < SHORT_AT
            val tiny = maxHeight < TINY_AT
            val wideDeck = maxWidth >= WIDE_AT
            val viewport = maxHeight
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp)) {
                Header(deck, onOpenList)
                if (playlist.offline) {
                    Text(
                        text = stringResource(R.string.now_playing_unreachable),
                        modifier =
                            Modifier.fillMaxWidth().clip(
                                RoundedCornerShape(12.dp),
                            ).background(Banner).padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Card(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    sub = sub,
                    lengthMs = lengthMs,
                    title = title,
                    notice = notice,
                    message = queue.message,
                    script = script,
                    activeLine = activeLine,
                    lifecycle = lifecycle,
                    wide = wideDeck,
                    short = short,
                    viewportHeight = viewport,
                    isPlaying = player.isPlaying,
                    hasSession = hasSession,
                    canPlay = rows.isNotEmpty(),
                    queueActive = queue.active,
                    doneAvailable = doneState.available,
                    undoAvailable = doneState.undoPostId != null,
                    commands = commands,
                    onTapLine = { line ->
                        val known = if (inClip && line.chunk == currentIndex) player.durationMs else null
                        if (known == null) pending = line
                        commands.jump(line.chunk, script.startMs(line, known))
                    },
                )
                if (!tiny) deck.next?.let { Peek(R.string.now_playing_next, it, PeekNext, short) }
                if (!short) deck.then?.let { Peek(R.string.now_playing_then, it, PeekThen, short = false) }
                Box(modifier = Modifier.height(if (short) 4.dp else 12.dp))
            }
        }
    }
}

@Composable
private fun Header(
    deck: DeckView,
    onOpenList: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpenList),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.now_playing_your_playlist),
                color = OnGround,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val place =
            when {
                deck.place != null -> stringResource(R.string.now_playing_place, deck.place, deck.total)
                deck.total > 0 -> stringResource(R.string.now_playing_in_playlist, deck.total)
                else -> ""
            }
        Text(text = place, color = OnGround, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun metaLine(
    sub: String?,
    lengthMs: Long?,
): String = listOfNotNull(sub?.let { "r/$it" }, lengthWords(lengthMs)).joinToString(stringResource(R.string.now_playing_separator))

@Composable
private fun lengthWords(ms: Long?): String? =
    when {
        ms == null -> null
        ms < MS_PER_MINUTE -> stringResource(R.string.playlist_length_short)
        else -> stringResource(R.string.playlist_length_minutes, (ms / MS_PER_MINUTE).toInt())
    }

@Composable
private fun Card(
    modifier: Modifier,
    sub: String?,
    lengthMs: Long?,
    title: String,
    notice: CardNotice,
    message: String?,
    script: CaptionScript,
    activeLine: Int,
    lifecycle: Lifecycle,
    wide: Boolean,
    short: Boolean,
    viewportHeight: Dp,
    isPlaying: Boolean,
    hasSession: Boolean,
    canPlay: Boolean,
    queueActive: Boolean,
    doneAvailable: Boolean,
    undoAvailable: Boolean,
    commands: NowPlayingCommands,
    onTapLine: (CaptionLine) -> Unit,
) {
    Column(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Paper)) {
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (short) 12.dp else 20.dp,
                    bottom = if (short) 6.dp else 10.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = metaLine(sub, lengthMs), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = InkSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (short) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val noticeText =
                when (notice) {
                    CardNotice.GENERATING -> stringResource(R.string.now_playing_generating)
                    CardNotice.LOADING -> stringResource(R.string.now_playing_loading)
                    CardNotice.NONE -> null
                } ?: message
            noticeText?.let { Text(text = it, color = NoticeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Lines(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            script = script,
            activeLine = activeLine,
            lifecycle = lifecycle,
            wide = wide,
            short = short,
            viewportHeight = viewportHeight,
            canPlay = canPlay,
            onTapLine = onTapLine,
        )
        if (undoAvailable) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().padding(
                        horizontal = 12.dp,
                    ).clip(RoundedCornerShape(14.dp)).background(Ink).padding(start = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.now_playing_marked_done), color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                PillButton(stringResource(R.string.action_undo), filled = false, ink = Paper, onClick = commands::undoDone)
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (short) 8.dp else 12.dp,
                    bottom = if (short) 12.dp else 20.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                text = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                filled = true,
                modifier = Modifier.weight(1f),
                enabled = hasSession || canPlay,
                onClick = {
                    when {
                        isPlaying -> commands.pause()
                        hasSession -> commands.play()
                        else -> commands.startPlaylist()
                    }
                },
            )
            if (queueActive) PillButton(stringResource(R.string.now_playing_next_button), filled = false, onClick = commands::nextPost)
            if (doneAvailable) PillButton(stringResource(R.string.action_done), filled = false, onClick = commands::done)
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    ink: Color = Ink,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val look =
        if (filled) {
            Modifier.background(if (enabled) Ink else InkQuiet, shape)
        } else {
            Modifier.border(2.dp, ink, shape)
        }
    Box(
        modifier =
            modifier
                .height(48.dp)
                .clip(shape)
                .then(look)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = if (filled) Paper else ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Lines(
    modifier: Modifier,
    script: CaptionScript,
    activeLine: Int,
    lifecycle: Lifecycle,
    wide: Boolean,
    short: Boolean,
    viewportHeight: Dp,
    canPlay: Boolean,
    onTapLine: (CaptionLine) -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion =
        remember {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    val listState = rememberLazyListState()
    val gate = remember { FollowGate(clock = System::currentTimeMillis) }
    var holds by remember { mutableIntStateOf(0) }
    var returns by remember { mutableIntStateOf(0) }

    // A finger on the list holds the view; only real drags count, so the screen's own scrolling never trips this.
    LaunchedEffect(listState, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction) {
                    gate.userScrolled()
                    holds++
                }
            }
        }
    }
    LaunchedEffect(holds) {
        if (holds > 0) {
            delay(FollowGate.HOLD_MS)
            returns++
        }
    }
    LaunchedEffect(script, activeLine, returns) {
        if (script.lines.isEmpty() || !gate.following() || listState.isScrollInProgress) return@LaunchedEffect
        val far = kotlin.math.abs(listState.firstVisibleItemIndex - activeLine) > FAR_LINES
        if (reduceMotion || far) listState.scrollToItem(activeLine) else listState.animateScrollToItem(activeLine)
    }

    if (script.lines.isEmpty()) {
        Box(modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(if (canPlay) R.string.now_playing_idle else R.string.now_playing_empty),
                color = Ink,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 27.sp,
            )
        }
        return
    }
    val size =
        when {
            short -> 22.sp
            wide -> 28.sp
            else -> 25.sp
        }
    // Padding lets the first and the last line both reach the fixed position; heights are measured by the list.
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(top = if (short) ANCHOR_SHORT else ANCHOR, bottom = viewportHeight),
    ) {
        itemsIndexed(script.lines) { index, line ->
            val colour by animateColorAsState(
                targetValue = if (index == activeLine) Ink else InkQuiet,
                animationSpec = if (reduceMotion) snap() else tween(COLOUR_FADE_MS),
                label = "line",
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { onTapLine(line) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                line.speaker?.let { Text(text = it, color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Text(
                    text = line.text,
                    color = colour,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = size,
                    lineHeight = size * LINE_HEIGHT,
                )
            }
        }
    }
}

private const val LINE_HEIGHT = 1.2f

@Composable
private fun Peek(
    word: Int,
    row: PlaylistRow,
    colour: Color,
    short: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (short) 14.dp else 18.dp))
                .background(colour)
                .padding(horizontal = if (short) 14.dp else 18.dp, vertical = if (short) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text =
                stringResource(word) + stringResource(R.string.now_playing_separator) +
                    metaLine(row.sub, row.lengthMs),
            color = OnGround,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.title,
            color = OnGround,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
