package com.redder.playback

import android.app.Notification
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.redder.audio.WavUtil
import com.redder.model.ActiveTranscriptHolder
import com.redder.model.RedditPost
import com.redder.model.RedditThread
import com.redder.model.SubredditQueueHolder
import com.redder.model.SubredditQueueState
import com.redder.parser.ListingPost
import com.redder.playlist.DoneButtonHolder
import com.redder.playlist.DoneButtonState
import com.redder.playlist.PlaylistBackend
import com.redder.playlist.ServerPost
import com.redder.queue.PostQueue
import com.redder.queue.QueueNext
import com.redder.queue.SubredditStore
import com.redder.sanitizer.DialogueChunk
import com.redder.tts.TtsEngine
import com.redder.tts.TtsUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** The audio service with a server-owned playlist (ADR-008): no engine, no mirrors, and reports for everything. */
class ServerPlaylistPlaybackTest {
    @TempDir
    lateinit var dir: File

    private lateinit var player: Player0
    private lateinit var holder: Holder
    private lateinit var backend: FakeBackend
    private lateinit var manager: MediaNotificationManager
    private lateinit var delegate: RedderAudioServiceDelegate
    private val engineCalls = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val wav = WavUtil.pcmToWav(ByteArray(4800), 24_000)

    private inner class FakeBackend(posts: List<String>) : PlaylistBackend {
        val queued = ConcurrentLinkedQueue(posts)
        val reports = CopyOnWriteArrayList<String>()
        val audioAsked = CopyOnWriteArrayList<Int>()
        val removed = HashSet<String>()

        @Volatile
        var startIndex = 0

        @Volatile
        var serverGone = false

        override fun newQueue(): PostQueue =
            object : PostQueue {
                override suspend fun next(): QueueNext =
                    queued.poll()?.let {
                        QueueNext.Post(ListingPost(it, "AITAH", "Title $it", "/r/AITAH/comments/$it/x/"))
                    } ?: QueueNext.Exhausted
            }

        override suspend fun load(post: ListingPost): ServerPost {
            val chunks = List(3) { DialogueChunk(it, "n", "op", "Text $it of ${post.id}", "af_heart", true) }
            return ServerPost(RedditThread(RedditPost(post.id, "op", post.title)), chunks, startIndex) { index ->
                audioAsked += index
                if (serverGone) throw TtsUnavailableException("Server unreachable")
                wav
            }
        }

        override fun started(post: ListingPost) {
            reports += "start ${post.id}"
        }

        override fun position(
            post: ListingPost,
            chunk: Int,
            ms: Long,
        ) {
            reports += "position ${post.id}"
        }

        override fun finished(post: ListingPost) {
            reports += "finish ${post.id}"
        }

        override fun skipped(post: ListingPost) {
            reports += "skip ${post.id}"
        }

        override fun done(post: ListingPost) {
            reports += "done ${post.id}"
        }

        override fun undone(postId: String) {
            reports += "undone $postId"
        }

        override fun manualFinished(
            postId: String,
            sub: String,
        ) {
            reports += "manual $postId"
        }

        override suspend fun isRemoved(postId: String): Boolean = postId in removed
    }

    private class Player0 : AudioPlayerController {
        val uris = CopyOnWriteArrayList<String>()
        override var volume = 1f
        override val currentMediaItemIndex = 0

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekToNext() = Unit

        override fun seekToPrevious() = Unit

        override fun release() = Unit

        override fun setMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            uris.clear()
            uris += mediaId
        }

        override fun addMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            uris += mediaId
        }

        override fun prepare() = Unit

        override fun clearMediaItems() {
            uris.clear()
        }

        override fun addListener(listener: Player.Listener) = Unit
    }

    private class Holder(override val playerController: AudioPlayerController) : MediaSessionHolder {
        @Volatile
        var doneHandler: (() -> Unit)? = null
        override val session: MediaSession? = null

        override fun setDoneHandler(
            label: String,
            handler: (() -> Unit)?,
        ) {
            doneHandler = handler
        }

        override fun release() = Unit
    }

    private class Host : ServiceHost {
        private var noisy = false

        override fun startForeground(
            notificationId: Int,
            notification: Notification,
            foregroundServiceType: Int,
        ) = true

        override fun stopForeground(flags: Int) = Unit

        override fun stopSelf() = Unit

        override fun registerNoisyReceiver() {
            noisy = true
        }

        override fun unregisterNoisyReceiver() {
            noisy = false
        }

        override fun isNoisyReceiverRegistered() = noisy

        override fun updateNotification(
            notificationId: Int,
            notification: Notification,
        ) = Unit
    }

    private class Engine(private val calls: MutableList<String>) : TtsEngine {
        override val sampleRate: Int = 24_000

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray {
            calls += text
            return ByteArray(4800)
        }

        override fun release() = Unit
    }

    @BeforeEach
    fun setUp() {
        SubredditQueueHolder.update(SubredditQueueState())
        DoneButtonHolder.update(DoneButtonState())
        ActiveTranscriptHolder.clear()
        player = Player0()
        holder = Holder(player)
        backend = FakeBackend(listOf("p1", "p2", "p3"))
        manager =
            MediaNotificationManager(
                object : NotificationChannelController {
                    override fun createNotificationChannel(spec: NotificationChannelSpec) = Unit
                },
                object : NotificationFactory {
                    override fun buildNotification(
                        spec: NotificationSpec,
                        session: MediaSession?,
                    ): Notification = org.mockito.Mockito.mock(Notification::class.java)
                },
            )
        delegate =
            RedderAudioServiceDelegate(
                host = Host(),
                notificationManager = manager,
                sessionHolder = holder,
                ttsEngineProvider = { Engine(engineCalls) },
                coroutineScope = scope,
                mainDispatcher = Dispatchers.Unconfined,
                uriConverter = { org.mockito.Mockito.mock(Uri::class.java) },
                cacheDirProvider = { dir },
                subredditStoreProvider = { SubredditStore(File(dir, "subs.json")) },
                playlistBackendProvider = { backend },
                positionReportMs = 20L,
            )
        delegate.onCreate()
    }

    /** Nothing may still be writing into the temporary folder when JUnit removes it. */
    @AfterEach
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]!!.children.forEach { it.cancelAndJoin() } }
        delegate.handleStop()
        runBlocking { scope.coroutineContext[Job]!!.children.forEach { it.cancelAndJoin() } }
    }

    private fun waitFor(what: () -> Boolean) =
        runBlocking {
            withTimeout(5_000L) {
                while (!what()) delay(5)
            }
        }

    @Test
    fun `a server post plays its prepared audio without touching the engine`() {
        delegate.handlePlaySubreddits()
        waitFor { player.uris.size >= 3 }
        assertEquals(listOf(0, 1, 2), backend.audioAsked.toList().take(3))
        assertEquals(emptyList<String>(), engineCalls.toList())
        assertTrue(backend.reports.contains("start p1"))
        assertEquals("Title p1", SubredditQueueHolder.state.value.title)
    }

    @Test
    fun `next reports a skip and moves on`() {
        delegate.handlePlaySubreddits()
        waitFor { "start p1" in backend.reports }
        delegate.handleSkipNext()
        waitFor { "start p2" in backend.reports }
        assertTrue("skip p1" in backend.reports)
        assertFalse("finish p1" in backend.reports)
    }

    @Test
    fun `done wins, moves on, and undo goes back to the server`() {
        delegate.handlePlaySubreddits()
        waitFor { "start p1" in backend.reports }
        delegate.handleDone()
        waitFor { "start p2" in backend.reports }
        assertTrue("done p1" in backend.reports)
        assertFalse("skip p1" in backend.reports)
        assertEquals("p1", DoneButtonHolder.state.value.undoPostId)
        delegate.handleUndoDone()
        assertTrue("undone p1" in backend.reports)
        assertNull(DoneButtonHolder.state.value.undoPostId)
    }

    @Test
    fun `done is on the notification and the session only while a server playlist runs`() {
        assertEquals(3, manager.buildNotificationSpec().actions.size)
        delegate.handlePlaySubreddits()
        assertEquals(PlaybackActionType.DONE, manager.buildNotificationSpec().actions.last().actionType)
        assertEquals(listOf(0, 1, 2), manager.buildNotificationSpec().compactActionIndices)
        assertTrue(holder.doneHandler != null)
        assertTrue(DoneButtonHolder.state.value.available)
        waitFor { player.uris.size >= 3 }
        delegate.handleStop()
        assertEquals(3, manager.buildNotificationSpec().actions.size)
        assertNull(holder.doneHandler)
        assertFalse(DoneButtonHolder.state.value.available)
    }

    @Test
    fun `a post resumes where listening stopped`() {
        backend.startIndex = 2
        delegate.handlePlaySubreddits()
        waitFor { player.uris.isNotEmpty() }
        assertEquals(listOf(2), backend.audioAsked.toList().distinct().take(1))
    }

    @Test
    fun `a post taken off elsewhere is left without a skip or finish`() {
        delegate.handlePlaySubreddits()
        waitFor { "start p1" in backend.reports }
        backend.removed += "p1"
        waitFor { "start p2" in backend.reports }
        assertFalse("skip p1" in backend.reports)
        assertFalse("finish p1" in backend.reports)
    }

    @Test
    fun `when the server goes the post stays open and the queue does not run on`() {
        backend.serverGone = true
        delegate.handlePlaySubreddits()
        waitFor { backend.audioAsked.isNotEmpty() }
        runBlocking { delay(100) }
        assertFalse(backend.reports.any { it.startsWith("skip") || it.startsWith("finish") })
        assertEquals(setOf("start p1"), backend.reports.filter { it.startsWith("start") }.toSet())
    }
}
