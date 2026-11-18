package com.redder.playback

import android.app.Notification
import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.redder.audio.WavUtil
import com.redder.model.ActiveTranscriptHolder
import com.redder.model.PlaybackAlertHolder
import com.redder.model.SubredditQueueHolder
import com.redder.model.SynthesisProgressHolder
import com.redder.model.SynthesisStage
import com.redder.network.RedditApiClient
import com.redder.network.RedditUrlResolver
import com.redder.queue.SubredditStore
import com.redder.sanitizer.DialogueFormatter
import com.redder.tts.TtsEngine
import com.redder.tts.TtsUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SubredditQueuePlaybackTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private lateinit var store: SubredditStore
    private lateinit var player: FakePlayer
    private lateinit var holder: FakeSessionHolder
    private lateinit var host: FakeHost
    private lateinit var engine: CountingEngine
    private lateinit var delegate: RedderAudioServiceDelegate
    private val messages = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()

    /** Listing post IDs by sub; every post's thread is served unless its ID is in [failingPosts]. */
    private val listings = HashMap<String, List<String>>()
    private val failingSubs = HashSet<String>()
    private val failingPosts = HashSet<String>()

    @Volatile
    private var threadDelayMs = 0L

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request.path.orEmpty())
            }
        server.start()
        store = SubredditStore(File(dir, "subs.json"))
        player = FakePlayer()
        holder = FakeSessionHolder(player)
        host = FakeHost()
        engine = CountingEngine()
        messages.clear()
        requests.clear()
        SubredditQueueHolder.update(com.redder.model.SubredditQueueState())
        ActiveTranscriptHolder.clear()
        delegate = newDelegate()
        delegate.onCreate()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(path: String): MockResponse {
        requests += path
        val thread = Regex("/r/([^/]+)/comments/([^/]+)/").find(path)
        if (thread != null) {
            val id = thread.groupValues[2]
            return when {
                id in failingPosts -> MockResponse().setResponseCode(500)
                else -> MockResponse().setBody(threadJson(id)).setBodyDelay(threadDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        val sub = Regex("/r/([^/?]+)").find(path)?.groupValues?.get(1) ?: return MockResponse().setResponseCode(404)
        if (sub in failingSubs) return MockResponse().setResponseCode(503)
        val posts =
            listings[sub].orEmpty().joinToString("") { id ->
                """<div class="post" id="$id"><h2 class="post_title"><a href="/r/$sub/comments/$id/slug/">Title $id</a></h2></div>"""
            }
        return MockResponse().setBody("""<div id="posts">$posts</div>""")
    }

    private fun threadJson(id: String): String {
        val text = "Body of $id."
        val title = "Title $id"
        val comments =
            """{"kind":"t1","data":{"id":"c_$id","author":"someone","body":"A comment on $id.","score":5,""" +
                """"created_utc":1726830000.0,"depth":0,"replies":""}}"""
        return """[{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"id":"$id","name":"t3_$id","title":"$title",""" +
            """"selftext":"$text","author":"op","score":10,"num_comments":1,"created_utc":1726830000.0,""" +
            """"subreddit":"s","permalink":"/r/s/comments/$id/x/"}}]}},""" +
            """{"kind":"Listing","data":{"children":[$comments]}}]"""
    }

    private fun newDelegate(
        idleMs: Long = 60_000L,
        maxFailures: Int = RedderAudioServiceDelegate.MAX_CONSECUTIVE_POST_FAILURES,
        silentTitle: String? = null,
    ): RedderAudioServiceDelegate {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val rewritten = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }.build()
        return RedderAudioServiceDelegate(
            host = host,
            notificationManager = MediaNotificationManager(FakeChannels(), FakeFactory()),
            sessionHolder = holder,
            urlResolver = RedditUrlResolver(client = client, ioDispatcher = Dispatchers.IO),
            apiClient = RedditApiClient(client = client, ioDispatcher = Dispatchers.IO),
            ttsEngineProvider = { engine },
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { org.mockito.Mockito.mock(Uri::class.java) },
            cacheDirProvider = { dir },
            showMessage = { messages += it },
            subredditStoreProvider = { store },
            idleReleaseMs = idleMs,
            maxPostFailures = maxFailures,
            formatChunks = { thread, nodes ->
                if (thread.post.title == silentTitle) emptyList() else DialogueFormatter().format(thread, nodes, true)
            },
        )
    }

    private fun selectSubs(vararg subs: Pair<String, List<String>>) {
        subs.forEach { (sub, posts) ->
            store.add(sub)
            listings[sub] = posts
        }
    }

    private suspend fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(10_000L) { while (!condition()) delay(10L) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** Waits for [title] to be queued and fully synthesized. */
    private suspend fun awaitPost(title: String) {
        await("$title playing") { player.items.isNotEmpty() && player.items.all { it == title } && delegate.queueSession?.prefetch != null }
    }

    private fun playedPosts(vararg ids: String) = ids.map(store::isPlayed)

    @Test
    fun `an unavailable engine says so and leaves the queue where it is`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            engine.unavailableAfter = 0

            delegate.handlePlaySubreddits()
            await("the alert") { PlaybackAlertHolder.message.value != null }

            assertEquals("Server unreachable", PlaybackAlertHolder.message.value)
            assertEquals(SynthesisStage.ERROR, SynthesisProgressHolder.progress.value.stage)
            assertTrue(player.items.isEmpty())
            assertFalse(store.isPlayed("a1"), "the post stays unplayed")
            assertTrue(requests.none { "a2" in it }, "the queue does not move on: $requests")
            assertTrue(ActiveTranscriptHolder.transcript.value.any { "a1" in it.text }, "the post stays on screen")
            delegate.onDestroy()
            assertEquals("Server unreachable", PlaybackAlertHolder.message.value, "the reason outlives the service")
            PlaybackAlertHolder.clear()
        }

    @Test
    fun `audio already made plays out when the engine goes, without finishing the post`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            engine.unavailableAfter = 1

            delegate.handlePlaySubreddits()
            await("the alert") { PlaybackAlertHolder.message.value != null }
            await("the first clip") { player.items.size == 1 }
            val synthesized = engine.syntheses.get()

            delegate.onPlayerState(Player.STATE_ENDED)
            delay(100L)

            assertEquals(listOf("Title a1"), player.items.toList(), "the queue stays on this post")
            assertFalse(store.isPlayed("a1"))
            assertTrue(requests.none { "a2" in it })
            assertEquals(synthesized, engine.syntheses.get(), "nothing more is made")
            delegate.onDestroy()
            PlaybackAlertHolder.clear()
        }

    @Test
    fun `the next thread clears the alert and tries again`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1"))
            engine.unavailableAfter = 0
            delegate.handlePlaySubreddits()
            await("the alert") { PlaybackAlertHolder.message.value != null }

            engine.unavailableAfter = Int.MAX_VALUE
            delegate.handlePlaySubreddits()
            await("a1 again") { player.items.isNotEmpty() }

            assertNull(PlaybackAlertHolder.message.value)
            delegate.onDestroy()
        }

    @Test
    fun `plays the hottest unplayed posts round robin, moving on as each one ends`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"), "sub_b" to listOf("b1"))
            store.markPlayed("a1", "sub_a")

            delegate.handlePlaySubreddits()
            awaitPost("Title a2")
            assertEquals("sub_a", SubredditQueueHolder.state.value.subreddit)
            assertEquals("Title a2", SubredditQueueHolder.state.value.title)
            assertTrue(SubredditQueueHolder.state.value.active)
            assertFalse(store.isPlayed("a2"), "not played until it finishes")

            delegate.onPlayerState(Player.STATE_ENDED)
            awaitPost("Title b1")

            assertTrue(store.isPlayed("a2"))
            assertFalse(store.isPlayed("b1"))
            assertEquals(listOf(true), ActiveTranscriptHolder.transcript.value.map { it.text.isNotEmpty() }.distinct())
            assertTrue(ActiveTranscriptHolder.transcript.value.any { it.text.contains("b1") })
            assertTrue(ActiveTranscriptHolder.transcript.value.none { it.text.contains("a2") })
            assertEquals(0, ActiveTranscriptHolder.currentIndex.value)
            delegate.onDestroy()
        }

    @Test
    fun `next post skips at once, marks the skipped post and leaves single clips alone`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            // Keeps the next post's text on its way while the skip is checked.
            threadDelayMs = 500L
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            delegate.handleSkipNext()

            assertTrue(store.isPlayed("a1"), "skipped after it started")
            assertTrue(player.items.isEmpty(), "the old post stops immediately")
            assertEquals(0, player.seekNextCount, "no clip seek")
            assertNull(SubredditQueueHolder.state.value.title)
            awaitPost("Title a2")
            delegate.onDestroy()
        }

    @Test
    fun `media session next means next post during a queue and is restored afterwards`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            assertNull(holder.nextHandler)
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            assertNotNull(holder.nextHandler)
            holder.nextHandler?.invoke()
            awaitPost("Title a2")
            assertTrue(store.isPlayed("a1"))

            delegate.onDestroy()
            assertNull(holder.nextHandler)
        }

    @Test
    fun `the clip button still moves between clips during a queue`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1"))
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            delegate.handleSkipNextClip()

            assertEquals(1, player.seekNextCount)
            assertFalse(store.isPlayed("a1"))
            delegate.onDestroy()
        }

    @Test
    fun `without a queue next keeps its clip behaviour`() {
        delegate.handleSkipNext()

        assertEquals(1, player.seekNextCount)
    }

    @Test
    fun `stopping partway leaves the post unplayed`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1"))
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            delegate.handleStop()

            assertFalse(store.isPlayed("a1"))
            assertFalse(SubredditQueueHolder.state.value.active)
            assertNull(SubredditQueueHolder.state.value.message)
        }

    @Test
    fun `a post that fails to load is skipped and stays unplayed`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            failingPosts += "a1"

            delegate.handlePlaySubreddits()
            awaitPost("Title a2")

            assertFalse(store.isPlayed("a1"))
            delegate.onDestroy()
        }

    @Test
    fun `a post with nothing to read is skipped`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            delegate = newDelegate(silentTitle = "Title a1")
            delegate.onCreate()

            delegate.handlePlaySubreddits()
            awaitPost("Title a2")

            delegate.onDestroy()
        }

    @Test
    fun `a sub that fails is skipped`() =
        runBlocking {
            selectSubs("sub_bad" to emptyList(), "sub_b" to listOf("b1"))
            failingSubs += "sub_bad"

            delegate.handlePlaySubreddits()
            awaitPost("Title b1")

            delegate.onDestroy()
        }

    @Test
    fun `stops with the friendly error when every sub fails`() =
        runBlocking {
            selectSubs("sub_bad" to emptyList())
            failingSubs += "sub_bad"

            delegate.handlePlaySubreddits()
            await("stopped") { host.stopSelfCalled }

            assertEquals(listOf(PlaybackStrings().queueAllFailed), messages)
            assertEquals(PlaybackStrings().queueAllFailed, SubredditQueueHolder.state.value.message)
        }

    @Test
    fun `stops with the friendly error when every post fails`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2", "a3", "a4"))
            failingPosts += listOf("a1", "a2", "a3", "a4")
            delegate = newDelegate(maxFailures = 2)
            delegate.onCreate()

            delegate.handlePlaySubreddits()
            await("stopped") { host.stopSelfCalled }

            assertEquals(listOf(PlaybackStrings().queueAllFailed), messages)
            assertFalse(requests.any { "a3" in it }, "gave up after the limit")
        }

    @Test
    fun `stops cleanly when nothing new is left`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1"))
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            delegate.onPlayerState(Player.STATE_ENDED)
            await("stopped") { host.stopSelfCalled }

            assertTrue(store.isPlayed("a1"))
            assertEquals(listOf(PlaybackStrings().queueNoNewPosts), messages)
            assertFalse(SubredditQueueHolder.state.value.active)
            assertEquals(PlaybackStrings().queueNoNewPosts, SubredditQueueHolder.state.value.message)
            assertTrue(engine.releases.get() > 0, "models go when the queue ends")
        }

    @Test
    fun `nothing selected stops with the message`() =
        runBlocking {
            delegate.handlePlaySubreddits()
            await("stopped") { host.stopSelfCalled }

            assertEquals(listOf(PlaybackStrings().queueNoNewPosts), messages)
        }

    @Test
    fun `no idle release, foreground drop or model unload between posts`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            delegate = newDelegate(idleMs = 30L)
            delegate.onCreate()

            delegate.handlePlaySubreddits()
            awaitPost("Title a1")
            threadDelayMs = 300L
            delegate.queueSession?.prefetch?.await()
            delay(100L)
            assertEquals(0, engine.releases.get(), "models stay loaded while the last audio of a post plays")

            delegate.onPlayerState(Player.STATE_ENDED)
            await("next post") { player.items.isNotEmpty() && player.items.all { it == "Title a2" } }
            delay(100L)

            assertFalse(host.stopSelfCalled)
            assertFalse(host.stopForegroundCalled)
            assertEquals(0, engine.releases.get())
            delegate.onDestroy()
        }

    @Test
    fun `the next post's text is fetched ahead, its audio is not`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")
            val synthesizedForFirst = engine.syntheses.get()

            delegate.queueSession?.prefetch?.await()

            assertTrue(requests.any { "/comments/a2/" in it })
            assertEquals(synthesizedForFirst, engine.syntheses.get())
            assertFalse(store.isPlayed("a2"))
            val requestsBefore = requests.count { "/comments/a2/" in it }

            delegate.onPlayerState(Player.STATE_ENDED)
            awaitPost("Title a2")

            assertEquals(requestsBefore, requests.count { "/comments/a2/" in it }, "uses the fetched text")
            delegate.onDestroy()
        }

    @Test
    fun `sharing a thread ends the queue and plays even if it was played, and counts when it finishes`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            delegate.handlePlaySubreddits()
            awaitPost("Title a1")

            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/sub_a/comments/a1/slug/")
            job.join()

            assertNull(delegate.queueSession)
            assertFalse(SubredditQueueHolder.state.value.active)
            assertFalse(store.isPlayed("a1"), "not finished yet")
            assertTrue(player.items.isNotEmpty())

            delegate.onPlayerState(Player.STATE_ENDED)
            assertTrue(store.isPlayed("a1"))

            val again = delegate.handleProcessRedditUrl("https://www.reddit.com/r/sub_a/comments/a1/slug/")
            again.join()
            assertTrue(player.items.isNotEmpty(), "already played, and it plays anyway")
            delegate.onDestroy()
        }

    @Test
    fun `the play subreddits and clip actions reach the service`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1"))
            val play = org.mockito.Mockito.mock(Intent::class.java)
            org.mockito.Mockito.`when`(play.action).thenReturn(RedderAudioService.ACTION_PLAY_SUBREDDITS)

            delegate.onStartCommand(play)

            assertNotNull(delegate.queueSession)
            awaitPost("Title a1")
            delegate.onDestroy()
        }

    @Test
    fun `swiping the task away does not stop a queue that is moving`() =
        runBlocking {
            selectSubs("sub_a" to listOf("a1", "a2"))
            threadDelayMs = 300L

            delegate.handlePlaySubreddits()
            delegate.onTaskRemoved()

            assertFalse(host.stopSelfCalled)
            awaitPost("Title a1")
            delegate.onDestroy()
        }

    private class CountingEngine : TtsEngine {
        override val sampleRate: Int = 44100
        val syntheses = AtomicInteger(0)
        val releases = AtomicInteger(0)

        /** From this many successful syntheses on, the engine reports it cannot work. */
        @Volatile
        var unavailableAfter = Int.MAX_VALUE

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray {
            if (syntheses.get() >= unavailableAfter) throw TtsUnavailableException("Server unreachable")
            syntheses.incrementAndGet()
            return ByteArray(200) { 1 }
        }

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String,
        ): ByteArray = WavUtil.pcmToWav(synthesizeToPcm(text, voiceId))

        override fun release() {
            releases.incrementAndGet()
        }
    }

    private class FakePlayer : AudioPlayerController {
        val items = CopyOnWriteArrayList<String>()
        var seekNextCount = 0
        override var volume = 1f
        override val currentMediaItemIndex = 0

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekToNext() {
            seekNextCount++
        }

        override fun seekToPrevious() = Unit

        override fun release() = Unit

        override fun setMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            items.clear()
            items += title
        }

        override fun addMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            items += title
        }

        override fun prepare() = Unit

        override fun clearMediaItems() {
            items.clear()
        }

        override fun addListener(listener: Player.Listener) = Unit
    }

    private class FakeSessionHolder(override val playerController: AudioPlayerController) : MediaSessionHolder {
        override val session: MediaSession? = null

        @Volatile
        var nextHandler: (() -> Unit)? = null

        override fun setNextPostHandler(handler: (() -> Unit)?) {
            nextHandler = handler
        }

        override fun release() = Unit
    }

    private class FakeHost : ServiceHost {
        @Volatile
        var stopSelfCalled = false

        @Volatile
        var stopForegroundCalled = false
        private var noisy = false

        override fun startForeground(
            notificationId: Int,
            notification: Notification,
            foregroundServiceType: Int,
        ) = true

        override fun stopForeground(flags: Int) {
            stopForegroundCalled = true
        }

        override fun stopSelf() {
            stopSelfCalled = true
        }

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

    private class FakeChannels : NotificationChannelController {
        override fun createNotificationChannel(spec: NotificationChannelSpec) = Unit
    }

    private class FakeFactory : NotificationFactory {
        override fun buildNotification(
            spec: NotificationSpec,
            session: MediaSession?,
        ): Notification = org.mockito.Mockito.mock(Notification::class.java)
    }
}
