package com.redder.playback

import android.app.Notification
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.redder.audio.AudioFocusLostException
import com.redder.audio.AudioFocusManager
import com.redder.audio.AudioFocusRequestController
import com.redder.audio.WavUtil
import com.redder.model.ActiveTranscriptHolder
import com.redder.network.RedditApiClient
import com.redder.network.RedditUrlResolver
import com.redder.settings.RedderPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.ToneFallbackTtsEngine
import com.redder.tts.TtsEngine
import com.redder.tts.VoiceEngine
import com.redder.ui.ShareReceiverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class RedderAudioServiceTest {
    private lateinit var channelController: FakeNotificationChannelController
    private lateinit var notificationFactory: FakeNotificationFactory
    private lateinit var notificationManager: MediaNotificationManager
    private lateinit var host: FakeServiceHost
    private lateinit var playerController: FakeAudioPlayerController
    private lateinit var sessionHolder: FakeMediaSessionHolder
    private lateinit var focusController: FakeAudioFocusRequestController
    private lateinit var stateMachine: PlaybackStateMachine
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testClient: OkHttpClient
    private lateinit var urlResolver: RedditUrlResolver
    private lateinit var apiClient: RedditApiClient
    private lateinit var delegate: RedderAudioServiceDelegate
    private var skipNextCount = 0
    private var skipPreviousCount = 0

    @BeforeEach
    fun setUp() {
        channelController = FakeNotificationChannelController()
        notificationFactory = FakeNotificationFactory()
        notificationManager = MediaNotificationManager(channelController, notificationFactory)
        host = FakeServiceHost()
        playerController = FakeAudioPlayerController()
        sessionHolder = FakeMediaSessionHolder(playerController)
        focusController = FakeAudioFocusRequestController()
        stateMachine = PlaybackStateMachine()
        skipNextCount = 0
        skipPreviousCount = 0

        mockWebServer = MockWebServer()
        mockWebServer.start()

        testClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val originalHost = original.url.host
                    val rewrittenUrl =
                        original.url.newBuilder()
                            .scheme("http")
                            .host(mockWebServer.hostName)
                            .port(mockWebServer.port)
                            .build()
                    val rewrittenRequest =
                        original.newBuilder()
                            .url(rewrittenUrl)
                            .header("Host", originalHost)
                            .build()
                    chain.proceed(rewrittenRequest)
                }
                .build()

        urlResolver = RedditUrlResolver(client = testClient, ioDispatcher = Dispatchers.IO)
        apiClient = RedditApiClient(client = testClient, ioDispatcher = Dispatchers.IO)

        audioFocusManager =
            AudioFocusManager(
                stateMachine = stateMachine,
                onDuck = { delegate.handleDuck() },
                onUnduck = { delegate.handleUnduck() },
                controller = focusController,
            )

        delegate =
            RedderAudioServiceDelegate(
                host = host,
                notificationManager = notificationManager,
                sessionHolder = sessionHolder,
                stateMachine = stateMachine,
                audioFocusManager = audioFocusManager,
                onSkipNext = { skipNextCount++ },
                onSkipPrevious = { skipPreviousCount++ },
                urlResolver = urlResolver,
                apiClient = apiClient,
                coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                mainDispatcher = Dispatchers.Unconfined,
                uriConverter = { mockUri(it) },
            )
    }

    private fun mockUri(file: File): Uri = org.mockito.Mockito.mock(Uri::class.java)

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // -------------------------------------------------------------------------
    // Notification & Channel Configuration Tests
    // -------------------------------------------------------------------------

    @Test
    fun `notification channel is configured with correct id name and importance`() {
        notificationManager.createNotificationChannel()

        assertEquals(1, channelController.createdChannels.size)
        val channel = channelController.createdChannels[0]
        assertEquals(MediaNotificationManager.CHANNEL_ID, channel.id)
        assertEquals(MediaNotificationManager.CHANNEL_NAME, channel.name)
        assertEquals(MediaNotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `notification channel description is configured correctly`() {
        notificationManager.createNotificationChannel(description = "Custom description")

        assertEquals(1, channelController.createdChannels.size)
        assertEquals("Custom description", channelController.createdChannels[0].description)
    }

    @Test
    fun `notification spec has expected default title and text`() {
        val spec = notificationManager.buildNotificationSpec()

        assertEquals(MediaNotificationManager.DEFAULT_TITLE, spec.title)
        assertEquals(MediaNotificationManager.DEFAULT_TEXT, spec.text)
        assertEquals(MediaNotificationManager.CHANNEL_ID, spec.channelId)
    }

    @Test
    fun `notification spec accepts custom title and text`() {
        val spec =
            notificationManager.buildNotificationSpec(
                title = "AskReddit Post",
                text = "Top answer dialogue",
                isPlaying = false,
            )

        assertEquals("AskReddit Post", spec.title)
        assertEquals("Top answer dialogue", spec.text)
    }

    @Test
    fun `notification spec uses ic_media_play as small icon`() {
        val spec = notificationManager.buildNotificationSpec()

        assertEquals(android.R.drawable.ic_media_play, spec.smallIconResId)
    }

    @Test
    fun `notification spec creates play action when paused or idle`() {
        val spec = notificationManager.buildNotificationSpec(isPlaying = false)

        assertEquals(3, spec.actions.size)
        val playAction = spec.actions[1]
        assertEquals(PlaybackActionType.PLAY, playAction.actionType)
        assertEquals(android.R.drawable.ic_media_play, playAction.iconResId)
        assertEquals("Play", playAction.title)
    }

    @Test
    fun `notification spec creates pause action when playing`() {
        val spec = notificationManager.buildNotificationSpec(isPlaying = true)

        assertEquals(3, spec.actions.size)
        val pauseAction = spec.actions[1]
        assertEquals(PlaybackActionType.PAUSE, pauseAction.actionType)
        assertEquals(android.R.drawable.ic_media_pause, pauseAction.iconResId)
        assertEquals("Pause", pauseAction.title)
    }

    @Test
    fun `notification spec includes previous and next actions with standard drawables`() {
        val spec = notificationManager.buildNotificationSpec()

        assertEquals(3, spec.actions.size)
        val prevAction = spec.actions[0]
        assertEquals(PlaybackActionType.SKIP_PREVIOUS, prevAction.actionType)
        assertEquals(android.R.drawable.ic_media_previous, prevAction.iconResId)
        assertEquals("Previous", prevAction.title)

        val nextAction = spec.actions[2]
        assertEquals(PlaybackActionType.SKIP_NEXT, nextAction.actionType)
        assertEquals(android.R.drawable.ic_media_next, nextAction.iconResId)
        assertEquals("Next", nextAction.title)
    }

    @Test
    fun `notification spec defines compact action indices for media controls`() {
        val spec = notificationManager.buildNotificationSpec()

        assertEquals(listOf(0, 1, 2), spec.compactActionIndices)
    }

    @Test
    fun `notification spec marks ongoing true when playing and false when paused`() {
        val playingSpec = notificationManager.buildNotificationSpec(isPlaying = true)
        assertTrue(playingSpec.isOngoing)

        val pausedSpec = notificationManager.buildNotificationSpec(isPlaying = false)
        assertFalse(pausedSpec.isOngoing)
    }

    @Test
    fun `buildNotification delegates to NotificationFactory with spec`() {
        val notification = notificationManager.buildNotification(title = "T", text = "M", isPlaying = true)

        assertNotNull(notification)
        assertEquals(1, notificationFactory.buildCount)
        assertEquals("T", notificationFactory.lastSpec?.title)
        assertEquals("M", notificationFactory.lastSpec?.text)
        assertTrue(notificationFactory.lastSpec?.isOngoing == true)
    }

    // -------------------------------------------------------------------------
    // Service Lifecycle & Foreground Notification Tests
    // -------------------------------------------------------------------------

    @Test
    fun `onCreate creates notification channel`() {
        delegate.onCreate()

        assertEquals(1, channelController.createdChannels.size)
        assertEquals(MediaNotificationManager.CHANNEL_ID, channelController.createdChannels[0].id)
    }

    @Test
    fun `onCreate starts foreground service with NOTIFICATION_ID and FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`() {
        delegate.onCreate(foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        assertTrue(host.startForegroundCalled)
        assertEquals(MediaNotificationManager.NOTIFICATION_ID, host.lastNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, host.lastForegroundServiceType)
        assertNotNull(host.lastNotification)
    }

    @Test
    fun `onGetSession returns active session from session holder`() {
        delegate.onCreate()

        val session = delegate.onGetSession()

        assertNotNull(session)
        assertEquals(sessionHolder.session, session)
    }

    @Test
    fun `onGetSession returns null when session holder is null`() {
        val emptyDelegate =
            RedderAudioServiceDelegate(
                host = host,
                notificationManager = notificationManager,
                sessionHolder = null,
                stateMachine = stateMachine,
            )

        val session = emptyDelegate.onGetSession()

        assertNull(session)
    }

    // -------------------------------------------------------------------------
    // Playback Actions & State Machine Integration Tests
    // -------------------------------------------------------------------------

    @Test
    fun `handlePlay requests audio focus`() {
        delegate.onCreate()
        delegate.handlePlay()

        assertTrue(focusController.focusRequested)
    }

    @Test
    fun `handlePlay transitions state machine from Idle to Playing when focus granted`() {
        delegate.onCreate()
        assertEquals(PlaybackState.Idle, stateMachine.state.value)

        delegate.handlePlay()

        assertEquals(PlaybackState.Playing, stateMachine.state.value)
    }

    @Test
    fun `handlePlay transitions state machine from Paused to Playing when focus granted`() {
        delegate.onCreate()
        delegate.handlePlay()
        delegate.handlePause()
        assertEquals(PlaybackState.Paused, stateMachine.state.value)

        delegate.handlePlay()

        assertEquals(PlaybackState.Playing, stateMachine.state.value)
    }

    @Test
    fun `handlePlay starts player playback`() {
        delegate.onCreate()
        delegate.handlePlay()

        assertEquals(1, playerController.playCount)
    }

    @Test
    fun `handlePlay registers noisy receiver`() {
        delegate.onCreate()
        assertFalse(host.noisyReceiverRegistered)

        delegate.handlePlay()

        assertTrue(host.noisyReceiverRegistered)
    }

    @Test
    fun `handlePlay updates notification to playing state`() {
        delegate.onCreate()
        delegate.handlePlay()

        assertTrue(host.updateNotificationCalled)
        assertEquals(MediaNotificationManager.NOTIFICATION_ID, host.lastUpdatedNotificationId)
        assertTrue(notificationFactory.lastSpec?.isOngoing == true)
    }

    @Test
    fun `handlePlay is rejected and does not play when audio focus is denied`() {
        focusController.shouldGrantFocus = false
        delegate.onCreate()

        delegate.handlePlay()

        assertEquals(PlaybackState.Idle, stateMachine.state.value)
        assertEquals(0, playerController.playCount)
        assertFalse(host.noisyReceiverRegistered)
    }

    @Test
    fun `handlePause transitions state machine from Playing to Paused`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        delegate.handlePause()

        assertEquals(PlaybackState.Paused, stateMachine.state.value)
    }

    @Test
    fun `handlePause pauses player playback`() {
        delegate.onCreate()
        delegate.handlePlay()
        delegate.handlePause()

        assertEquals(1, playerController.pauseCount)
    }

    @Test
    fun `handlePause unregisters noisy receiver`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertTrue(host.noisyReceiverRegistered)

        delegate.handlePause()

        assertFalse(host.noisyReceiverRegistered)
        assertTrue(host.unregisterNoisyReceiverCalled)
    }

    @Test
    fun `handlePause updates notification to paused state`() {
        delegate.onCreate()
        delegate.handlePlay()
        delegate.handlePause()

        assertTrue(host.updateNotificationCalled)
        assertFalse(notificationFactory.lastSpec?.isOngoing == true)
    }

    // -------------------------------------------------------------------------
    // Noisy & Audio Focus Event Reactions
    // -------------------------------------------------------------------------

    @Test
    fun `handleNoisyDetected pauses playback when playing`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        delegate.handleNoisyDetected()

        assertEquals(PlaybackState.Paused, stateMachine.state.value)
        assertEquals(1, playerController.pauseCount)
        assertFalse(host.noisyReceiverRegistered)
    }

    @Test
    fun `handleNoisyDetected does nothing when already paused or idle`() {
        delegate.onCreate()
        assertEquals(PlaybackState.Idle, stateMachine.state.value)

        delegate.handleNoisyDetected()

        assertEquals(PlaybackState.Idle, stateMachine.state.value)
        assertEquals(0, playerController.pauseCount)
    }

    @Test
    fun `handleDuck lowers player volume to ducked level`() {
        playerController.volume = 1.0f

        delegate.handleDuck()

        assertEquals(RedderAudioServiceDelegate.DUCKED_VOLUME, playerController.volume)
    }

    @Test
    fun `handleUnduck restores player volume to normal level`() {
        playerController.volume = 0.2f

        delegate.handleUnduck()

        assertEquals(RedderAudioServiceDelegate.NORMAL_VOLUME, playerController.volume)
    }

    @Test
    fun `pausing while ducked restores full volume so resume is not stuck quiet`() {
        delegate.onCreate()
        delegate.handlePlay()
        focusController.listener?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(PlaybackState.Ducked, stateMachine.state.value)
        assertEquals(RedderAudioServiceDelegate.DUCKED_VOLUME, playerController.volume)

        delegate.handlePause()
        delegate.handlePlay()
        // The focus gain arrives after the state left Ducked, so it cannot unduck any more.
        focusController.listener?.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(PlaybackState.Playing, stateMachine.state.value)
        assertEquals(RedderAudioServiceDelegate.NORMAL_VOLUME, playerController.volume)
    }

    @Test
    fun `play while ducked restores full volume`() {
        delegate.onCreate()
        delegate.handlePlay()
        focusController.listener?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(RedderAudioServiceDelegate.DUCKED_VOLUME, playerController.volume)

        delegate.handlePlay()

        assertEquals(PlaybackState.Playing, stateMachine.state.value)
        assertEquals(RedderAudioServiceDelegate.NORMAL_VOLUME, playerController.volume)
    }

    @Test
    fun `permanent audio focus loss transitions state machine to Error and abandons focus`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        focusController.listener?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
        val errorState = stateMachine.state.value as PlaybackState.Error
        assertInstanceOf(AudioFocusLostException::class.java, errorState.cause)
        assertTrue(focusController.focusAbandoned)
    }

    // -------------------------------------------------------------------------
    // Navigation & Command Routing Tests
    // -------------------------------------------------------------------------

    @Test
    fun `handleSkipNext invokes callback and player seekToNext`() {
        delegate.onCreate()

        delegate.handleSkipNext()

        assertEquals(1, skipNextCount)
        assertEquals(1, playerController.seekNextCount)
    }

    @Test
    fun `handleSkipPrevious invokes callback and rewinds the player`() {
        delegate.onCreate()

        delegate.handleSkipPrevious()

        assertEquals(1, skipPreviousCount)
        // Nothing queued yet, so rewind restarts the current item.
        assertEquals(listOf(0 to 0L), playerController.seeks)
    }

    @Test
    fun `onStartCommand routes ACTION_PLAY to handlePlay`() {
        delegate.onCreate()
        val intent = FakeIntent(RedderAudioService.ACTION_PLAY)

        delegate.onStartCommand(intent)

        assertEquals(PlaybackState.Playing, stateMachine.state.value)
        assertEquals(1, playerController.playCount)
    }

    @Test
    fun `onStartCommand routes ACTION_PAUSE to handlePause`() {
        delegate.onCreate()
        delegate.handlePlay()

        val intent = FakeIntent(RedderAudioService.ACTION_PAUSE)
        delegate.onStartCommand(intent)

        assertEquals(PlaybackState.Paused, stateMachine.state.value)
        assertEquals(1, playerController.pauseCount)
    }

    @Test
    fun `onStartCommand routes ACTION_SKIP_NEXT to handleSkipNext`() {
        delegate.onCreate()
        val intent = FakeIntent(RedderAudioService.ACTION_SKIP_NEXT)

        delegate.onStartCommand(intent)

        assertEquals(1, skipNextCount)
        assertEquals(1, playerController.seekNextCount)
    }

    @Test
    fun `onStartCommand routes ACTION_SKIP_PREVIOUS to handleSkipPrevious`() {
        delegate.onCreate()
        val intent = FakeIntent(RedderAudioService.ACTION_SKIP_PREVIOUS)

        delegate.onStartCommand(intent)

        assertEquals(1, skipPreviousCount)
        assertEquals(listOf(0 to 0L), playerController.seeks)
    }

    @Test
    fun `onStartCommand routes ACTION_STOP to handleStop`() {
        delegate.onCreate()
        delegate.handlePlay()

        val intent = FakeIntent(RedderAudioService.ACTION_STOP)
        delegate.onStartCommand(intent)

        assertEquals(PlaybackState.Released, stateMachine.state.value)
        assertTrue(host.stopSelfCalled)
    }

    // -------------------------------------------------------------------------
    // End-to-End Reddit URL Pipeline Tests
    // -------------------------------------------------------------------------

    @Test
    fun `inbound intent with EXTRA_REDDIT_URL triggers handleProcessRedditUrl`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            delegate.onCreate()
            val intent =
                FakeIntent(
                    stringExtras =
                        mapOf(
                            ShareReceiverActivity.EXTRA_REDDIT_URL to
                                "https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/",
                        ),
                )

            delegate.onStartCommand(intent)
            delegate.processingJob?.join()

            assertTrue(playerController.prepareCount >= 1)
            assertTrue(playerController.playCount >= 1)
            assertTrue(playerController.mediaItems.isNotEmpty())
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    @Test
    fun `end-to-end processing pipeline flow queues first chunk and starts playback`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertEquals(1, playerController.prepareCount)
            assertEquals(1, playerController.playCount)
            assertTrue(playerController.mediaItems.isNotEmpty())
            val firstItem = playerController.mediaItems[0]
            assertEquals("Kotlin Media3 Integration", firstItem.title)
            assertEquals("kotlin_dev", firstItem.artist)
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    @Test
    fun `subsequent synthesized chunks are added to player playlist`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            // Title and post body are separate items, both attributed to the OP.
            assertEquals(4, playerController.mediaItems.size)
            assertEquals("kotlin_dev", playerController.mediaItems[0].artist)
            assertEquals("kotlin_dev", playerController.mediaItems[1].artist)
            assertEquals("android_user", playerController.mediaItems[2].artist)
            assertEquals("piper_expert", playerController.mediaItems[3].artist)
        }

    @Test
    fun `handleProcessRedditUrl succeeds with mainDispatcher and sets media items`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            var mainDispatcherDispatched = false
            val trackingDispatcher =
                object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        mainDispatcherDispatched = true
                        block.run()
                    }
                }

            val customDelegate =
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = notificationManager,
                    sessionHolder = sessionHolder,
                    stateMachine = stateMachine,
                    audioFocusManager = audioFocusManager,
                    urlResolver = urlResolver,
                    apiClient = apiClient,
                    coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    mainDispatcher = trackingDispatcher,
                    uriConverter = { mockUri(it) },
                )
            customDelegate.onCreate()

            val job = customDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertTrue(mainDispatcherDispatched)
            assertTrue(playerController.prepareCount >= 1)
            assertTrue(playerController.playCount >= 1)
            assertTrue(playerController.mediaItems.isNotEmpty())
            assertEquals("Kotlin Media3 Integration", playerController.mediaItems[0].title)
            assertEquals("kotlin_dev", playerController.mediaItems[0].artist)
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    @Test
    fun `empty dialogue chunks update notification to No dialogue found and do not play`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            val emptyChunksDelegate =
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = notificationManager,
                    sessionHolder = sessionHolder,
                    stateMachine = stateMachine,
                    audioFocusManager = audioFocusManager,
                    urlResolver = urlResolver,
                    apiClient = apiClient,
                    formatChunks = { _, _ -> emptyList() },
                    coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    mainDispatcher = Dispatchers.Unconfined,
                    uriConverter = { mockUri(it) },
                )
            emptyChunksDelegate.onCreate()

            val job = emptyChunksDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertEquals(0, playerController.playCount)
            assertEquals("No dialogue found", notificationFactory.lastSpec?.text)
        }

    @Test
    fun `network failure transitions state machine to Error and updates notification`() =
        runBlocking {
            repeat(1 + RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setResponseCode(500))
            }

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
            assertEquals(0, playerController.playCount)
            assertTrue(host.updateNotificationCalled)
            assertFalse(notificationFactory.lastSpec?.isOngoing == true)
            assertEquals(RedderAudioService.ERROR_TEXT_THREAD_LOAD_FAILED, notificationFactory.lastSpec?.text)
        }

    @Test
    fun `json parse failure transitions state machine to Error and updates notification`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            repeat(RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>Invalid HTML payload</html>"))
            }

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
            assertEquals(0, playerController.playCount)
            assertFalse(notificationFactory.lastSpec?.isOngoing == true)
        }

    @Test
    fun `end-to-end processing pipeline successfully parses HTML payload from community mirror and plays audio`() =
        runBlocking {
            // First: URL resolution succeeds
            mockWebServer.enqueue(MockResponse().setResponseCode(200))

            // Second: Mirror returns Redlib HTML payload
            val redlibHtml =
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="post" id="post_m1">
                    <h1 class="post_title">Community Mirror Audio Thread</h1>
                    <a class="post_author" href="/u/mirror_author">mirror_author</a>
                    <div class="post_body">
                      Listening to Reddit discussions via community mirror proxy.
                    </div>
                  </div>
                  <div class="comments">
                    <div class="comment" id="c_m1">
                      <a class="comment_author" href="/u/mirror_commenter">mirror_commenter</a>
                      <span class="comment_score">25 points</span>
                      <div class="comment_body">
                        Audio synthesized seamlessly from Redlib HTML!
                      </div>
                    </div>
                  </div>
                </body>
                </html>
                """.trimIndent()
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(redlibHtml))

            val mirrorDelegate =
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = notificationManager,
                    sessionHolder = sessionHolder,
                    stateMachine = stateMachine,
                    audioFocusManager = audioFocusManager,
                    urlResolver = urlResolver,
                    apiClient = apiClient,
                    coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    mainDispatcher = Dispatchers.Unconfined,
                    uriConverter = { mockUri(it) },
                )
            mirrorDelegate.onCreate()

            val job = mirrorDelegate.handleProcessRedditUrl("https://safereddit.com/r/androiddev/comments/xyz123/mirror_thread/")
            job.join()

            assertTrue(playerController.prepareCount >= 1)
            assertTrue(playerController.playCount >= 1)
            assertTrue(playerController.mediaItems.isNotEmpty())
            assertEquals("Community Mirror Audio Thread", playerController.mediaItems[0].title)
            assertEquals("mirror_author", playerController.mediaItems[0].artist)
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    @Test
    fun `onPlaybackResumption does not throw UnsupportedOperationException and returns empty items`() {
        val callback = ResumptionCallback()
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val dummySession = allocateMethod.invoke(unsafe, MediaSession::class.java) as MediaSession
        val dummyController = allocateMethod.invoke(unsafe, MediaSession.ControllerInfo::class.java) as MediaSession.ControllerInfo

        val future = callback.onPlaybackResumption(dummySession, dummyController)
        val result = future.get()

        assertNotNull(result)
        assertTrue(result.mediaItems.isEmpty())
        assertEquals(0, result.startIndex)
        assertEquals(0L, result.startPositionMs)
    }

    @Test
    fun `service destruction cancels pipeline jobs and cleans up temp files`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertTrue(delegate.chunkFiles.files().isNotEmpty())
            val createdFiles = delegate.chunkFiles.files()
            assertTrue(createdFiles.all { it.exists() })

            delegate.onDestroy()

            assertEquals(PlaybackState.Released, stateMachine.state.value)
            assertTrue(delegate.chunkFiles.files().isEmpty())
            assertTrue(createdFiles.none { it.exists() })
        }

    @Test
    fun `delegate invokes ttsEngineProvider with context provided by contextProvider`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON))

            val mockContext = org.mockito.Mockito.mock(android.content.Context::class.java)
            var providedContext: android.content.Context? = null

            val testDelegate =
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = notificationManager,
                    sessionHolder = sessionHolder,
                    stateMachine = stateMachine,
                    audioFocusManager = audioFocusManager,
                    urlResolver = urlResolver,
                    apiClient = apiClient,
                    contextProvider = { mockContext },
                    ttsEngineProvider = { ctx ->
                        providedContext = ctx
                        ToneFallbackTtsEngine()
                    },
                    coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    mainDispatcher = Dispatchers.Unconfined,
                    uriConverter = { mockUri(it) },
                )
            testDelegate.onCreate()

            val job = testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/")
            job.join()

            assertEquals(mockContext, providedContext)
            assertTrue(playerController.mediaItems.isNotEmpty())
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    @Test
    fun `bot challenge on every mirror sets transcript load error and notifies user without playing`() =
        runBlocking {
            val botChallengeHtml =
                "<!DOCTYPE html><html><head><title>Making sure you&#39;re not a bot!</title></head></html>"
            mockWebServer.enqueue(MockResponse().setResponseCode(200))
            repeat(RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(botChallengeHtml))
            }

            delegate.onCreate()
            val job = delegate.handleProcessRedditUrl("https://www.reddit.com/r/technology/comments/xyz123/kotlin_media3/")
            job.join()

            val transcript = ActiveTranscriptHolder.transcript.value
            assertEquals(1, transcript.size)
            assertEquals("System", transcript[0].author)
            assertEquals(RedderAudioService.ERROR_TEXT_THREAD_LOAD_FAILED, transcript[0].text)

            assertEquals(0, playerController.mediaItems.size)
            assertEquals(0, playerController.playCount)
            assertInstanceOf(PlaybackState.Error::class.java, stateMachine.state.value)
            assertEquals(
                RedderAudioService.ERROR_TEXT_THREAD_LOAD_FAILED,
                notificationFactory.lastSpec?.text,
            )
        }

    // -------------------------------------------------------------------------
    // Engine reuse, pipeline serialization and playback preferences
    // -------------------------------------------------------------------------

    private fun serveThreadForEveryRequest() {
        mockWebServer.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path?.endsWith(".json") == true) {
                        MockResponse().setResponseCode(200).setBody(VALID_THREAD_JSON)
                    } else {
                        MockResponse().setResponseCode(200)
                    }
            }
    }

    /** Engine that blocks (like native ONNX inference) and records concurrent use and releases. */
    private class TrackingTtsEngine(
        private val blockMs: Long = 0L,
    ) : TtsEngine {
        override val sampleRate: Int = 44100
        private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val synthesisCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstSynthesisStarted = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var releaseCount = 0

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray {
            check(releaseCount == 0) { "engine used after release" }
            val now = inFlight.incrementAndGet()
            maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            firstSynthesisStarted.countDown()
            try {
                if (blockMs > 0) Thread.sleep(blockMs)
                synthesisCount.incrementAndGet()
                return ByteArray(200) { 1 }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String,
        ): ByteArray = WavUtil.pcmToWav(synthesizeToPcm(text, voiceId))

        override fun release() {
            releaseCount++
        }
    }

    private fun delegateWithEngine(
        engine: TtsEngine,
        onProvide: () -> Unit = {},
    ): RedderAudioServiceDelegate =
        RedderAudioServiceDelegate(
            host = host,
            notificationManager = notificationManager,
            sessionHolder = sessionHolder,
            stateMachine = stateMachine,
            audioFocusManager = audioFocusManager,
            urlResolver = urlResolver,
            apiClient = apiClient,
            ttsEngineProvider = {
                onProvide()
                engine
            },
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { mockUri(it) },
        )

    @Test
    fun `tts models are freed when a thread is synthesized and loaded again for the next one`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engines = mutableListOf<TrackingTtsEngine>()
            val testDelegate = idleDelegate({ TrackingTtsEngine().also { engines += it } })
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            assertEquals(1, engines.size)
            assertEquals(1, engines[0].releaseCount)

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/second/").join()
            assertEquals(2, engines.size)
            assertEquals(1, engines[1].releaseCount)
            assertTrue(engines.sumOf { it.synthesisCount.get() } >= 6)

            testDelegate.onDestroy()
            assertEquals(listOf(1, 1), engines.map { it.releaseCount })
        }

    @Test
    fun `new thread waits for in-flight inference so the engine is never used concurrently`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = TrackingTtsEngine(blockMs = 150L)
            val testDelegate = delegateWithEngine(engine)
            testDelegate.onCreate()

            val firstJob = testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/")
            assertTrue(engine.firstSynthesisStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val secondJob = testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/second/")
            val thirdJob = testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/third/")
            thirdJob.join()

            assertTrue(firstJob.isCancelled)
            assertTrue(secondJob.isCompleted)
            assertEquals(1, engine.maxConcurrent.get())
            assertEquals(1, engine.releaseCount, "freed once, when the last thread was synthesized")
            assertEquals(4, playerController.mediaItems.size)
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
        }

    // -------------------------------------------------------------------------
    // Adaptive start buffer and chunk file cleanup
    // -------------------------------------------------------------------------

    @Test
    fun `chunk files left by an earlier session are deleted on create`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) {
        val stale = File(cacheDir, "redder_chunk_3_123.wav").apply { writeText("old") }
        val unrelated = File(cacheDir, "voice_pack.bin").apply { writeText("keep") }
        val testDelegate =
            RedderAudioServiceDelegate(
                host = host,
                notificationManager = notificationManager,
                sessionHolder = sessionHolder,
                stateMachine = stateMachine,
                cacheDirProvider = { cacheDir },
            )

        testDelegate.onCreate()

        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    private fun cachingDelegate(
        cacheDir: File,
        keepBehind: Int = 2,
        showMessage: (String) -> Unit = {},
    ): RedderAudioServiceDelegate =
        RedderAudioServiceDelegate(
            host = host,
            notificationManager = notificationManager,
            sessionHolder = sessionHolder,
            stateMachine = stateMachine,
            audioFocusManager = audioFocusManager,
            urlResolver = urlResolver,
            apiClient = apiClient,
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { mockUri(it) },
            cacheDirProvider = { cacheDir },
            chunkFiles = ChunkFileWindow(keepBehind = keepBehind),
            showMessage = showMessage,
        )

    private fun chunkFilesIn(dir: File): List<File> = dir.listFiles { f -> ChunkFileWindow.isChunkFileName(f.name) }.orEmpty().toList()

    /** Moves the fake player to playlist position [index], announcing that item (with its media ID) like ExoPlayer does. */
    private fun moveToItem(index: Int) {
        playerController.currentMediaItemIndex = index
        val item = MediaItem.Builder().setMediaId(playerController.mediaItems[index].mediaId).build()
        playerController.listeners.toList().forEach { it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    private fun playlistCards(): List<Int?> = playerController.mediaItems.map { ChunkMediaId.transcriptIndex(it.mediaId) }

    private suspend fun playingThread(
        cacheDir: File,
        keepBehind: Int = 2,
        showMessage: (String) -> Unit = {},
    ): RedderAudioServiceDelegate {
        serveThreadForEveryRequest()
        val testDelegate = cachingDelegate(cacheDir, keepBehind, showMessage)
        testDelegate.onCreate()
        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        return testDelegate
    }

    @Test
    fun `every playlist item carries the media id of its transcript card`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir)
        assertEquals(listOf(0, 1, 2, 3), playlistCards())
        assertEquals(listOf(0, 1, 2, 3), testDelegate.chunkFiles.transcriptIndices())
        testDelegate.onDestroy()
    }

    @Test
    fun `the current card follows the media id after played items are trimmed off the playlist`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 1)
        moveToItem(1)
        moveToItem(2) // trims card 0: card 2 is now at playlist position 1
        assertEquals(2, ActiveTranscriptHolder.currentIndex.value)
        moveToItem(2) // playlist position 2 is card 3, not card 2
        assertEquals(3, ActiveTranscriptHolder.currentIndex.value)
        assertEquals(listOf(2, 3), testDelegate.chunkFiles.transcriptIndices())
        assertEquals(listOf<Int?>(2, 3), playlistCards())
        testDelegate.onDestroy()
    }

    @Test
    fun `rewind more than three seconds into a clip restarts it`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir)
        moveToItem(2)
        playerController.currentPositionMs = 4_000L
        testDelegate.handleSkipPrevious()
        assertEquals(listOf(2 to 0L), playerController.seeks)
        testDelegate.onDestroy()
    }

    @Test
    fun `rewind early in a clip plays the previous clip`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 1)
        moveToItem(1)
        moveToItem(2) // card 2 at playlist position 1, card 1 at position 0
        playerController.currentPositionMs = 1_000L
        testDelegate.handleSkipPrevious()
        assertEquals(listOf(0 to 0L), playerController.seeks)
        testDelegate.onDestroy()
    }

    @Test
    fun `rewind past the kept window re-synthesizes the previous card`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 0)
        moveToItem(1)
        moveToItem(1) // card 2, alone at the front of the playlist
        assertEquals(listOf(2, 3), testDelegate.chunkFiles.transcriptIndices())
        val trimmedFiles = testDelegate.chunkFiles.files()

        testDelegate.handleSkipPrevious()
        testDelegate.processingJob?.join()

        assertEquals(listOf<Int?>(1, 2, 3), playlistCards())
        assertEquals(1, ActiveTranscriptHolder.currentIndex.value)
        // The replaced playlist's clips are kept for later jumps, until the service stops.
        assertTrue(trimmedFiles.all { it.exists() })
        testDelegate.onDestroy()
        assertTrue(chunkFilesIn(cacheDir).isEmpty())
    }

    @Test
    fun `tapping a queued caption seeks to its playlist item and plays`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 1)
        moveToItem(1)
        moveToItem(2) // playlist now holds cards 1, 2, 3
        testDelegate.handlePause()
        val plays = playerController.playCount

        testDelegate.handleJumpToTranscriptIndex(3)

        assertEquals(listOf(2 to 0L), playerController.seeks)
        assertEquals(plays + 1, playerController.playCount)
        testDelegate.onDestroy()
    }

    @Test
    fun `a line tap starts inside its clip`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 1)
        moveToItem(1)
        moveToItem(2) // playlist now holds cards 1, 2, 3

        testDelegate.onStartCommand(
            FakeIntent(
                RedderAudioService.ACTION_JUMP_TO_TRANSCRIPT_INDEX,
                intExtras = mapOf(RedderAudioService.EXTRA_TRANSCRIPT_INDEX to 3),
                longExtras = mapOf(RedderAudioService.EXTRA_POSITION_MS to 1234L),
            ),
        )

        assertEquals(listOf(2 to 1234L), playerController.seeks)
        testDelegate.onDestroy()
    }

    @Test
    fun `tapping a caption behind the kept window re-synthesizes from that card`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 0)
        moveToItem(3) // only card 3 is left
        assertEquals(listOf(3), testDelegate.chunkFiles.transcriptIndices())

        testDelegate.onStartCommand(
            FakeIntent(
                RedderAudioService.ACTION_JUMP_TO_TRANSCRIPT_INDEX,
                intExtras = mapOf(RedderAudioService.EXTRA_TRANSCRIPT_INDEX to 0),
            ),
        )
        testDelegate.processingJob?.join()

        assertEquals(listOf<Int?>(0, 1, 2, 3), playlistCards())
        assertEquals(0, ActiveTranscriptHolder.currentIndex.value)
        assertTrue(playerController.seeks.isEmpty())
        testDelegate.onDestroy()
    }

    @Test
    fun `tapping a caption not synthesized yet plays it from the generator and shows no message`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val messages = mutableListOf<String>()
        val testDelegate = playingThread(cacheDir, showMessage = { messages += it })
        // Pretend card 3 is still being synthesized.
        testDelegate.chunkFiles.detachAll().filter { it.first == 3 }.forEach { it.second.delete() }
        listOf(0, 1, 2).forEach {
            testDelegate.chunkFiles.add(it, File(cacheDir, "redder_chunk_${it}_t.wav").apply { writeBytes(ByteArray(44)) })
        }

        testDelegate.handleJumpToTranscriptIndex(3)
        assertEquals(3, ActiveTranscriptHolder.currentIndex.value)
        testDelegate.processingJob?.join()

        assertTrue(messages.isEmpty())
        assertEquals(listOf<Int?>(3), playlistCards())
        assertEquals(listOf(3), testDelegate.chunkFiles.transcriptIndices())
        assertEquals(PlaybackState.Playing::class, testDelegate.stateMachine.state.value::class)
        testDelegate.onDestroy()
    }

    @Test
    fun `clips of the old playlist are kept after a caption tap and not synthesized again`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 0)
        moveToItem(2) // only cards 2 and 3 are left
        val kept = testDelegate.chunkFiles.files().toList()

        testDelegate.handleJumpToTranscriptIndex(0)
        testDelegate.processingJob?.join()

        assertEquals(listOf<Int?>(0, 1, 2, 3), playlistCards())
        assertTrue(kept.all { it.exists() }, "the old clips stay on disk")
        testDelegate.onDestroy()
        assertTrue(kept.none { it.exists() }, "and go when the service stops")
    }

    @Test
    fun `tapping a caption while paused plays once it is ready`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        val testDelegate = playingThread(cacheDir, keepBehind = 0)
        moveToItem(3)
        testDelegate.handlePause()

        testDelegate.handleJumpToTranscriptIndex(1)
        testDelegate.processingJob?.join()

        assertEquals(PlaybackState.Playing::class, testDelegate.stateMachine.state.value::class)
        assertEquals(listOf<Int?>(1, 2, 3), playlistCards())
        testDelegate.onDestroy()
    }

    @Test
    fun `previous from any media controller runs the same rewind as the notification`() {
        delegate.onCreate()
        val handler = sessionHolder.registeredPreviousHandler
        assertTrue(handler != null)
        playerController.currentPositionMs = 5_000L

        handler!!.invoke()

        assertEquals(1, skipPreviousCount)
        assertEquals(listOf(0 to 0L), playerController.seeks)
        delegate.onDestroy()
        assertEquals(null, sessionHolder.registeredPreviousHandler)
    }

    @Test
    fun `played chunks are removed from the playlist and deleted keeping two for skip-back`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        serveThreadForEveryRequest()
        val testDelegate = cachingDelegate(cacheDir)
        testDelegate.onCreate()
        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        val files = testDelegate.chunkFiles.files()
        assertEquals(4, files.size)
        val uris = playerController.mediaItems.map { it.uri }

        moveToItem(2)
        assertTrue(files.all { it.exists() }, "the previous two chunks are kept")

        moveToItem(3)
        assertFalse(files[0].exists())
        assertTrue(files.drop(1).all { it.exists() })
        // The deleted chunk left the playlist first, so the player never sees a missing file.
        assertEquals(uris.drop(1), playerController.mediaItems.map { it.uri })
        assertEquals(listOf(0 to 1), playerController.removedRanges)
        assertEquals(3, ActiveTranscriptHolder.currentIndex.value)
        assertEquals(listOf(1, 2, 3), testDelegate.chunkFiles.transcriptIndices())
        assertEquals(files.drop(1).toSet(), chunkFilesIn(cacheDir).toSet())

        testDelegate.onDestroy()
        assertTrue(chunkFilesIn(cacheDir).isEmpty())
    }

    @Test
    fun `starting a new thread deletes the previous thread's chunks`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        serveThreadForEveryRequest()
        val testDelegate = cachingDelegate(cacheDir)
        testDelegate.onCreate()
        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        val first = testDelegate.chunkFiles.files()

        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        val second = testDelegate.chunkFiles.files()

        assertTrue(first.none { it.exists() })
        assertEquals(4, second.size)
        assertTrue(second.all { it.exists() })
        assertEquals(second.toSet(), chunkFilesIn(cacheDir).toSet())
        testDelegate.onDestroy()
    }

    @Test
    fun `removing the task while paused stops the service and deletes the chunks`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        serveThreadForEveryRequest()
        val testDelegate = cachingDelegate(cacheDir)
        testDelegate.onCreate()
        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        testDelegate.handlePause()

        testDelegate.onTaskRemoved()

        assertTrue(chunkFilesIn(cacheDir).isEmpty())
        assertTrue(sessionHolder.isReleased, "player released before its files are deleted")
        assertTrue(host.stopSelfCalled)
    }

    @Test
    fun `removing the task while playing keeps the chunks being played`(
        @org.junit.jupiter.api.io.TempDir cacheDir: File,
    ) = runBlocking {
        serveThreadForEveryRequest()
        val testDelegate = cachingDelegate(cacheDir)
        testDelegate.onCreate()
        testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        testDelegate.onTaskRemoved()

        assertEquals(4, chunkFilesIn(cacheDir).size)
        assertFalse(sessionHolder.isReleased)
        testDelegate.onDestroy()
        assertTrue(chunkFilesIn(cacheDir).isEmpty())
    }

    /** Engine that records the voice ids it was asked for and whether it was warmed or released. */
    private class VoiceRecordingEngine(
        val name: VoiceEngine,
        private val pcmBytes: Int = 200,
    ) : TtsEngine {
        val voiceIds = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var warmUps = 0

        @Volatile
        var releaseCount = 0

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray {
            check(releaseCount == 0) { "$name used after release" }
            voiceIds += voiceId
            return ByteArray(pcmBytes) { 1 }
        }

        override suspend fun warmUp() {
            warmUps++
        }

        override fun release() {
            releaseCount++
        }
    }

    private fun delegateWithVoiceEngines(
        engineProvider: () -> VoiceEngine,
        created: MutableList<VoiceRecordingEngine>,
        narrators: Map<VoiceEngine, String> = emptyMap(),
        factoryNarrators: MutableList<String> = mutableListOf(),
    ): RedderAudioServiceDelegate =
        RedderAudioServiceDelegate(
            host = host,
            notificationManager = notificationManager,
            sessionHolder = sessionHolder,
            stateMachine = stateMachine,
            audioFocusManager = audioFocusManager,
            urlResolver = urlResolver,
            apiClient = apiClient,
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { mockUri(it) },
            voiceEngineProvider = engineProvider,
            narratorVoiceProvider = { narrators[it] ?: EngineVoices.defaultNarrator(it) },
            voiceEngineFactory = { _, engine, narrator ->
                factoryNarrators += narrator
                VoiceRecordingEngine(engine).also { created += it }
            },
        )

    @Test
    fun `voice engine pref is read per thread and a finished thread's models are not kept for the next`() =
        runBlocking {
            serveThreadForEveryRequest()
            val created = mutableListOf<VoiceRecordingEngine>()
            val testDelegate = delegateWithVoiceEngines({ VoiceEngine.SUPERTONIC }, created)
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            assertEquals(listOf(VoiceEngine.SUPERTONIC), created.map { it.name })

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/second/").join()
            assertEquals(listOf(VoiceEngine.SUPERTONIC, VoiceEngine.SUPERTONIC), created.map { it.name })
            assertEquals(1, created[0].releaseCount, "a finished thread's models are not kept for the next")
            assertEquals(1, created[1].warmUps, "the engine is warmed again before synthesis")

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/third/").join()
            assertEquals(3, created.size)
            assertEquals(1, created[1].releaseCount, "a finished thread's models are not kept for the next")
            assertEquals(1, created[2].warmUps)

            testDelegate.onDestroy()
            assertEquals(listOf(1, 1, 1), created.map { it.releaseCount })
        }

    @Test
    fun `speakers map to distinct voices of the picked engine with the narrator for the OP`() =
        runBlocking {
            serveThreadForEveryRequest()
            val created = mutableListOf<VoiceRecordingEngine>()
            val narrator = "F2"
            val factoryNarrators = mutableListOf<String>()
            val testDelegate =
                delegateWithVoiceEngines(
                    { VoiceEngine.SUPERTONIC },
                    created,
                    narrators = mapOf(VoiceEngine.SUPERTONIC to narrator),
                    factoryNarrators = factoryNarrators,
                )
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()

            val voices = created.single().voiceIds
            assertEquals(4, voices.size)
            assertEquals(narrator, voices[0], "title is read by the narrator")
            assertEquals(narrator, voices[1], "OP post body is read by the narrator")
            val supertonicIds = EngineVoices.SUPERTONIC.map { it.id }
            assertTrue(voices.drop(2).all { it in supertonicIds && it != narrator }, "commenters get other Supertonic voices: $voices")
            assertEquals(listOf(narrator), factoryNarrators.distinct())
        }

    @Test
    fun `Supertonic starts playback on the first chunk`() =
        runBlocking {
            serveThreadForEveryRequest()
            val supertonicPlayer = FakeAudioPlayerController()
            val supertonicDelegate =
                RedderAudioServiceDelegate(
                    host = host,
                    notificationManager = notificationManager,
                    sessionHolder = FakeMediaSessionHolder(supertonicPlayer),
                    stateMachine = PlaybackStateMachine(),
                    urlResolver = urlResolver,
                    apiClient = apiClient,
                    coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    mainDispatcher = Dispatchers.Unconfined,
                    uriConverter = { mockUri(it) },
                    voiceEngineProvider = { VoiceEngine.SUPERTONIC },
                    voiceEngineFactory = { _, engine, _ -> VoiceRecordingEngine(engine) },
                )
            supertonicDelegate.onCreate()
            supertonicDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/").join()
            assertEquals(1, supertonicPlayer.itemsAtFirstPlay)
            assertEquals(4, supertonicPlayer.mediaItems.size)
        }

    @Test
    fun `selectVoiceEngine falls back to the default narrator for a voice of another engine`() {
        val created = mutableListOf<VoiceRecordingEngine>()
        val testDelegate =
            delegateWithVoiceEngines({ VoiceEngine.SUPERTONIC }, created, narrators = mapOf(VoiceEngine.SUPERTONIC to "not-a-supertonic-voice"))

        assertEquals(VoiceEngine.SUPERTONIC, testDelegate.selectVoiceEngine())
        assertEquals(EngineVoices.defaultNarrator(VoiceEngine.SUPERTONIC), testDelegate.requestedNarrator)
    }

    @Test
    fun `handlePlay applies saved playback speed`() {
        val testDelegate =
            RedderAudioServiceDelegate(
                host = host,
                notificationManager = notificationManager,
                sessionHolder = sessionHolder,
                stateMachine = stateMachine,
                audioFocusManager = audioFocusManager,
                playbackSpeedProvider = { 1.5f },
            )
        testDelegate.onCreate()

        testDelegate.handlePlay()

        assertEquals(1.5f, playerController.lastPlaybackSpeed)
    }

    @Test
    fun `playback speed preference change is applied live and listener is unregistered on destroy`() {
        val prefs = org.mockito.Mockito.mock(SharedPreferences::class.java)
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        org.mockito.Mockito.doAnswer { invocation ->
            listener = invocation.getArgument(0)
            null
        }.`when`(prefs).registerOnSharedPreferenceChangeListener(org.mockito.Mockito.any())
        var savedSpeed = 1.0f
        val testDelegate =
            RedderAudioServiceDelegate(
                host = host,
                notificationManager = notificationManager,
                sessionHolder = sessionHolder,
                stateMachine = stateMachine,
                audioFocusManager = audioFocusManager,
                prefsProvider = { prefs },
                playbackSpeedProvider = { savedSpeed },
            )
        testDelegate.onCreate()
        assertNotNull(listener)

        savedSpeed = 2.0f
        listener!!.onSharedPreferenceChanged(prefs, RedderPreferences.KEY_PLAYBACK_SPEED)
        assertEquals(2.0f, playerController.lastPlaybackSpeed)

        savedSpeed = 0.5f
        listener!!.onSharedPreferenceChanged(prefs, RedderPreferences.KEY_READ_USERNAMES)
        assertEquals(2.0f, playerController.lastPlaybackSpeed)

        testDelegate.onDestroy()
        org.mockito.Mockito.verify(prefs).unregisterOnSharedPreferenceChangeListener(listener)
    }

    // -------------------------------------------------------------------------
    // ToneFallbackTtsEngine Tests
    // -------------------------------------------------------------------------

    @Test
    fun `ToneFallbackTtsEngine generates valid PCM bytes with correct size non-empty and throws after release`() =
        runBlocking {
            val engine = ToneFallbackTtsEngine()
            val text = "Hello world this is a test of fallback tone engine"
            val pcm = engine.synthesizeToPcm(text, "test_voice")

            assertTrue(pcm.isNotEmpty())
            val wordCount = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            val expectedDuration = maxOf(0.5, wordCount * 0.35)
            val expectedSampleCount = (expectedDuration * 22050).toInt()
            val expectedByteCount = expectedSampleCount * 2
            assertEquals(expectedByteCount, pcm.size)

            engine.release()
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    engine.synthesizeToPcm(text, "test_voice")
                }
            }
        }

    @Test
    fun `ToneFallbackTtsEngine generates distinct audio per voiceId`() =
        runBlocking {
            val engine = ToneFallbackTtsEngine()
            val pcm1 = engine.synthesizeToPcm("Identical text phrase", "voice_alpha")
            val pcm2 = engine.synthesizeToPcm("Identical text phrase", "voice_beta")

            assertEquals(pcm1.size, pcm2.size)
            assertFalse(pcm1.contentEquals(pcm2))
        }

    @Test
    fun `ToneFallbackTtsEngine applies attack and decay envelope`() =
        runBlocking {
            val engine = ToneFallbackTtsEngine()
            val pcm = engine.synthesizeToPcm("Envelope test phrase", "voice_test")

            val firstSample = ((pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xFF)).toShort()
            assertEquals(0, firstSample)
        }

    // -------------------------------------------------------------------------
    // Destruction & Cleanup Tests
    // -------------------------------------------------------------------------

    @Test
    fun `onDestroy unregisters noisy receiver`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertTrue(host.noisyReceiverRegistered)

        delegate.onDestroy()

        assertFalse(host.noisyReceiverRegistered)
        assertTrue(host.unregisterNoisyReceiverCalled)
    }

    @Test
    fun `onDestroy abandons audio focus`() {
        delegate.onCreate()
        delegate.handlePlay()
        focusController.focusAbandoned = false

        delegate.onDestroy()

        assertTrue(focusController.focusAbandoned)
    }

    @Test
    fun `onDestroy transitions state machine to Released`() {
        delegate.onCreate()
        delegate.handlePlay()
        assertEquals(PlaybackState.Playing, stateMachine.state.value)

        delegate.onDestroy()

        assertEquals(PlaybackState.Released, stateMachine.state.value)
    }

    @Test
    fun `onDestroy releases session holder and player`() {
        delegate.onCreate()

        delegate.onDestroy()

        assertTrue(sessionHolder.isReleased)
        assertEquals(1, playerController.releaseCount)
    }

    @Test
    fun `onDestroy stops foreground service with STOP_FOREGROUND_REMOVE and stops service`() {
        delegate.onCreate()

        delegate.onDestroy()

        assertTrue(host.stopForegroundCalled)
        assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, host.lastStopForegroundFlags)
        assertTrue(host.stopSelfCalled)
    }

    @Test
    fun `onCreate performs clean teardown and releases session when startForeground fails`() {
        host.startForegroundResult = false

        delegate.onCreate()

        assertFalse(delegate.isForegroundStarted)
        assertTrue(sessionHolder.isReleased)
        assertEquals(1, playerController.releaseCount)
        assertTrue(host.stopForegroundCalled)
        assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, host.lastStopForegroundFlags)
        assertTrue(host.stopSelfCalled)
        assertEquals(PlaybackState.Released, delegate.stateMachine.state.value)
    }

    @Test
    fun `onStartCommand ignores intent and returns START_NOT_STICKY if foreground failed to start`() {
        host.startForegroundResult = false
        delegate.onCreate()

        val intent =
            FakeIntent(
                stringExtras =
                    mapOf(
                        ShareReceiverActivity.EXTRA_REDDIT_URL to
                            "https://reddit.com/r/technology/comments/test123",
                    ),
            )
        val result = delegate.onStartCommand(intent)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertNull(delegate.processingJob)
    }

    // -------------------------------------------------------------------------
    // DefaultServiceHost Tests
    // -------------------------------------------------------------------------

    @Test
    fun `DefaultServiceHost startForeground succeeds and returns true`() {
        var starterCalled = false
        val defaultHost =
            DefaultServiceHost(
                service = null,
                noisyReceiverProvider = { null },
                foregroundStarter = { _, _, _ -> starterCalled = true },
                foregroundStopper = {},
                notificationCanceler = {},
                serviceStopper = {},
            )

        val result = defaultHost.startForeground(1, Notification(), 0)

        assertTrue(result)
        assertTrue(starterCalled)
    }

    @Test
    fun `DefaultServiceHost startForeground handles SecurityException and cleans up`() {
        var cancelerId: Int? = null
        var stopperFlags: Int? = null
        var stopSelfCalled = false

        val defaultHost =
            DefaultServiceHost(
                service = null,
                noisyReceiverProvider = { null },
                foregroundStarter = { _, _, _ -> throw SecurityException("Permission denied") },
                foregroundStopper = { flags -> stopperFlags = flags },
                notificationCanceler = { id -> cancelerId = id },
                serviceStopper = { stopSelfCalled = true },
            )

        val result = defaultHost.startForeground(42, Notification(), 0)

        assertFalse(result)
        assertEquals(42, cancelerId)
        assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, stopperFlags)
        assertTrue(stopSelfCalled)
    }

    @Test
    fun `DefaultServiceHost startForeground handles IllegalStateException and cleans up`() {
        var cancelerId: Int? = null
        var stopperFlags: Int? = null
        var stopSelfCalled = false

        val defaultHost =
            DefaultServiceHost(
                service = null,
                noisyReceiverProvider = { null },
                foregroundStarter = { _, _, _ -> throw IllegalStateException("Foreground not allowed") },
                foregroundStopper = { flags -> stopperFlags = flags },
                notificationCanceler = { id -> cancelerId = id },
                serviceStopper = { stopSelfCalled = true },
            )

        val result = defaultHost.startForeground(99, Notification(), 0)

        assertFalse(result)
        assertEquals(99, cancelerId)
        assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, stopperFlags)
        assertTrue(stopSelfCalled)
    }

    @Test
    fun `DefaultServiceHost startForeground handles IllegalArgumentException and cleans up`() {
        var cancelerId: Int? = null
        var stopperFlags: Int? = null
        var stopSelfCalled = false

        val defaultHost =
            DefaultServiceHost(
                service = null,
                noisyReceiverProvider = { null },
                foregroundStarter = { _, _, _ -> throw IllegalArgumentException("Bad type") },
                foregroundStopper = { flags -> stopperFlags = flags },
                notificationCanceler = { id -> cancelerId = id },
                serviceStopper = { stopSelfCalled = true },
            )

        val result = defaultHost.startForeground(101, Notification(), 0)

        assertFalse(result)
        assertEquals(101, cancelerId)
        assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, stopperFlags)
        assertTrue(stopSelfCalled)
    }

    // -------------------------------------------------------------------------
    // Test Doubles
    // -------------------------------------------------------------------------

    private class FakeNotificationChannelController : NotificationChannelController {
        val createdChannels = mutableListOf<NotificationChannelSpec>()

        override fun createNotificationChannel(spec: NotificationChannelSpec) {
            createdChannels.add(spec)
        }
    }

    private class FakeNotificationFactory : NotificationFactory {
        var lastSpec: NotificationSpec? = null
        var lastSession: MediaSession? = null
        var buildCount: Int = 0

        override fun buildNotification(
            spec: NotificationSpec,
            session: MediaSession?,
        ): Notification {
            buildCount++
            lastSpec = spec
            lastSession = session
            return Notification()
        }
    }

    private class FakeAudioPlayerController : AudioPlayerController {
        var playCount = 0
        var pauseCount = 0
        var seekNextCount = 0
        var seekPreviousCount = 0
        var releaseCount = 0
        override var volume = 1.0f
        var lastPlaybackSpeed: Float? = null

        override fun setPlaybackSpeed(speed: Float) {
            lastPlaybackSpeed = speed
        }

        var prepareCount = 0
        var clearMediaItemsCount = 0
        override var currentMediaItemIndex: Int = 0
        val mediaItems = mutableListOf<MediaItemEntry>()
        val listeners = mutableListOf<Player.Listener>()

        data class MediaItemEntry(val uri: Uri, val title: String, val artist: String, val mediaId: String = "")

        override var currentPositionMs: Long = 0L
        val seeks = mutableListOf<Pair<Int, Long>>()

        override fun seekTo(
            mediaItemIndex: Int,
            positionMs: Long,
        ) {
            seeks += mediaItemIndex to positionMs
        }

        /** Playlist size the first time play() was called (shows how much audio was buffered). */
        var itemsAtFirstPlay: Int? = null

        override fun play() {
            playCount++
            if (itemsAtFirstPlay == null) itemsAtFirstPlay = mediaItems.size
        }

        override fun pause() {
            pauseCount++
        }

        override fun seekToNext() {
            seekNextCount++
        }

        override fun seekToPrevious() {
            seekPreviousCount++
        }

        override fun release() {
            releaseCount++
        }

        override fun setMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            mediaItems.clear()
            mediaItems.add(MediaItemEntry(uri, title, artist, mediaId))
        }

        override fun addMediaItem(
            uri: Uri,
            title: String,
            artist: String,
            mediaId: String,
        ) {
            mediaItems.add(MediaItemEntry(uri, title, artist, mediaId))
        }

        override fun prepare() {
            prepareCount++
        }

        override fun clearMediaItems() {
            clearMediaItemsCount++
            mediaItems.clear()
        }

        val removedRanges = mutableListOf<Pair<Int, Int>>()

        override fun removeMediaItems(
            fromIndex: Int,
            toIndex: Int,
        ) {
            removedRanges += fromIndex to toIndex
            repeat(toIndex - fromIndex) { mediaItems.removeAt(fromIndex) }
            currentMediaItemIndex -= toIndex - fromIndex
        }

        override fun addListener(listener: Player.Listener) {
            listeners.add(listener)
        }
    }

    private class FakeMediaSessionHolder(
        override val playerController: AudioPlayerController,
    ) : MediaSessionHolder {
        var isReleased = false
        val dummySession: MediaSession = createDummySession()
        var registeredPreviousHandler: (() -> Unit)? = null

        override fun setPreviousHandler(handler: (() -> Unit)?) {
            registeredPreviousHandler = handler
        }

        override val session: MediaSession?
            get() = if (isReleased) null else dummySession

        override fun release() {
            isReleased = true
            playerController.release()
        }

        companion object {
            private fun createDummySession(): MediaSession {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = field.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                return allocateMethod.invoke(unsafe, MediaSession::class.java) as MediaSession
            }
        }
    }

    private class FakeServiceHost : ServiceHost {
        var startForegroundCalled = false
        var lastNotificationId: Int? = null
        var lastNotification: Notification? = null
        var lastForegroundServiceType: Int? = null

        var stopForegroundCalled = false
        var lastStopForegroundFlags: Int? = null

        var stopSelfCalled = false
        var noisyReceiverRegistered = false
        var unregisterNoisyReceiverCalled = false

        var updateNotificationCalled = false
        var lastUpdatedNotificationId: Int? = null
        var lastUpdatedNotification: Notification? = null
        var startForegroundResult = true

        override fun startForeground(
            notificationId: Int,
            notification: Notification,
            foregroundServiceType: Int,
        ): Boolean {
            startForegroundCalled = true
            lastNotificationId = notificationId
            lastNotification = notification
            lastForegroundServiceType = foregroundServiceType
            return startForegroundResult
        }

        override fun stopForeground(flags: Int) {
            stopForegroundCalled = true
            lastStopForegroundFlags = flags
        }

        override fun stopSelf() {
            stopSelfCalled = true
        }

        override fun registerNoisyReceiver() {
            noisyReceiverRegistered = true
        }

        override fun unregisterNoisyReceiver() {
            noisyReceiverRegistered = false
            unregisterNoisyReceiverCalled = true
        }

        override fun isNoisyReceiverRegistered(): Boolean = noisyReceiverRegistered

        override fun updateNotification(
            notificationId: Int,
            notification: Notification,
        ) {
            updateNotificationCalled = true
            lastUpdatedNotificationId = notificationId
            lastUpdatedNotification = notification
        }
    }

    private class FakeAudioFocusRequestController : AudioFocusRequestController {
        var shouldGrantFocus: Boolean = true
        var focusRequested: Boolean = false
        var focusAbandoned: Boolean = false
        var listener: AudioManager.OnAudioFocusChangeListener? = null

        override fun requestFocus(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            this.focusRequested = true
            this.listener = listener
            return shouldGrantFocus
        }

        override fun abandonFocus() {
            this.focusAbandoned = true
        }
    }

    private class FakeIntent(
        private val customAction: String? = null,
        private val stringExtras: Map<String, String> = emptyMap(),
        private val intExtras: Map<String, Int> = emptyMap(),
        private val longExtras: Map<String, Long> = emptyMap(),
    ) : Intent() {
        override fun getLongExtra(
            name: String?,
            defaultValue: Long,
        ): Long = longExtras[name] ?: defaultValue

        override fun getAction(): String? = customAction

        override fun getStringExtra(name: String?): String? = if (name != null) stringExtras[name] else null

        override fun getIntExtra(
            name: String?,
            defaultValue: Int,
        ): Int = intExtras[name] ?: defaultValue
    }

    companion object {
        private val VALID_THREAD_JSON =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "post_xyz123",
                        "name": "t3_post_xyz123",
                        "title": "Kotlin Media3 Integration",
                        "selftext": "Discussion on audio playback pipeline.",
                        "author": "kotlin_dev",
                        "score": 120,
                        "num_comments": 2,
                        "created_utc": 1726830000.0,
                        "subreddit": "androiddev",
                        "permalink": "/r/androiddev/comments/xyz123/kotlin_media3/"
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "id": "c_1",
                        "name": "t1_c_1",
                        "parent_id": "t3_post_xyz123",
                        "author": "android_user",
                        "body": "This architecture is very clean and reactive.",
                        "score": 45,
                        "depth": 0,
                        "created_utc": 1726830100.0,
                        "replies": ""
                      }
                    },
                    {
                      "kind": "t1",
                      "data": {
                        "id": "c_2",
                        "name": "t1_c_2",
                        "parent_id": "t3_post_xyz123",
                        "author": "piper_expert",
                        "body": "Tone fallback ensures resilience on any device.",
                        "score": 30,
                        "depth": 0,
                        "created_utc": 1726830200.0,
                        "replies": ""
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Warm-up, fast start and time-to-first-audio
    // -------------------------------------------------------------------------

    /** Engine recording the order of warm-up and synthesis calls and the steps cap of each call. */
    private class WarmUpRecordingEngine(
        private val warmUpBlockMs: Long = 0L,
    ) : TtsEngine {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)

        private fun <T> exclusive(block: () -> T): T {
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun warmUp() {
            exclusive {
                if (warmUpBlockMs > 0) Thread.sleep(warmUpBlockMs)
                events.add("warmup")
            }
        }

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray = ByteArray(200) { 1 }

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String,
        ): ByteArray = synthesizeToWav(text, voiceId, null)

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String,
            maxQualitySteps: Int?,
        ): ByteArray =
            exclusive {
                events.add("synth:${maxQualitySteps ?: "pref"}")
                WavUtil.pcmToWav(ByteArray(200) { 1 })
            }

        override fun release() {}
    }

    private fun warmUpDelegate(
        engine: TtsEngine,
        warmUpAllowed: () -> Boolean = { true },
        timing: TimeToFirstAudioLog = TimeToFirstAudioLog(),
    ): RedderAudioServiceDelegate =
        RedderAudioServiceDelegate(
            host = host,
            notificationManager = notificationManager,
            sessionHolder = sessionHolder,
            stateMachine = stateMachine,
            audioFocusManager = audioFocusManager,
            urlResolver = urlResolver,
            apiClient = apiClient,
            ttsEngineProvider = { engine },
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { mockUri(it) },
            warmUpAllowed = warmUpAllowed,
            timing = timing,
        )

    @Test
    fun `each thread warms up first and warm-up never overlaps real synthesis`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = WarmUpRecordingEngine(warmUpBlockMs = 150L)
            val testDelegate = warmUpDelegate(engine)
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/second/").join()

            assertEquals(2, engine.events.count { it == "warmup" })
            assertEquals("warmup", engine.events.first())
            assertEquals(1, engine.maxConcurrent.get())
            assertSame(testDelegate.startWarmUp(), testDelegate.startWarmUp())
            testDelegate.onDestroy()
        }

    @Test
    fun `every chunk including the opening ones is synthesized at the quality preference`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = WarmUpRecordingEngine()
            val testDelegate = warmUpDelegate(engine)
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()

            val synthCalls = engine.events.filter { it.startsWith("synth:") }
            assertEquals(listOf("synth:pref", "synth:pref", "synth:pref", "synth:pref"), synthCalls)
            testDelegate.onDestroy()
        }

    @Test
    fun `warm-up is skipped while model assets are unavailable and retried later`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = WarmUpRecordingEngine()
            var assetsReady = false
            val testDelegate = warmUpDelegate(engine, warmUpAllowed = { assetsReady })
            testDelegate.onCreate()
            testDelegate.startWarmUp().join()

            assertTrue(engine.events.isEmpty())
            assertFalse(testDelegate.isEngineWarmedUp)

            assetsReady = true
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()

            assertEquals("warmup", engine.events.first())
            assertFalse(testDelegate.isEngineWarmedUp, "the warm-up ends with the models, once the thread is synthesized")
            testDelegate.onDestroy()
        }

    @Test
    fun `time-to-first-audio milestones are logged relative to the shared url`() =
        runBlocking {
            serveThreadForEveryRequest()
            val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
            val timing = TimeToFirstAudioLog(logger = { messages.add(it) })
            val testDelegate = warmUpDelegate(WarmUpRecordingEngine(), timing = timing)
            testDelegate.onCreate()

            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            timing.onPlayerState(isReady = true, isPlaying = true)
            timing.onPlayerState(isReady = true, isPlaying = true)

            val ttfa = Regex("""TTFA (\w+)=(\d+)ms""")
            val events = messages.mapNotNull { ttfa.matchEntire(it)?.groupValues?.get(1) }
            assertEquals("url_received", events.first())
            for (event in listOf("thread_fetched", "thread_parsed", "warmup_duration", "first_chunk_synthesized")) {
                assertTrue(event in events, "missing $event in $messages")
            }
            assertTrue(messages.any { it == "TTFA warmup_done=before_url" || it.startsWith("TTFA warmup_done=") })
            assertEquals(1, events.count { it == "first_audio_playing" })
            assertTrue(events.indexOf("thread_fetched") < events.indexOf("thread_parsed"))
            assertTrue(events.indexOf("thread_parsed") < events.indexOf("first_chunk_synthesized"))
            testDelegate.onDestroy()
        }

    @Test
    fun `time-to-first-audio log ignores player events that are not ready and playing`() {
        val messages = mutableListOf<String>()
        var now = 1_000L
        val timing = TimeToFirstAudioLog(clockMs = { now }, logger = { messages.add(it) })

        timing.mark("thread_fetched")
        assertTrue(messages.isEmpty())

        timing.urlReceived()
        now = 1_250L
        timing.onPlayerState(isReady = false, isPlaying = true)
        timing.onPlayerState(isReady = true, isPlaying = false)
        now = 4_000L
        timing.onPlayerState(isReady = true, isPlaying = true)

        assertEquals(listOf("TTFA url_received=0ms", "TTFA first_audio_playing=3000ms"), messages)
    }

    // -------------------------------------------------------------------------
    // Nothing stays loaded or running when idle
    // -------------------------------------------------------------------------

    private fun idleDelegate(
        provider: () -> TtsEngine,
        idleMs: Long = 60_000L,
        warmUpMs: Long = 60_000L,
        lookAheadMs: Long = SynthesisThrottle.MAX_LOOK_AHEAD_MS,
    ): RedderAudioServiceDelegate =
        RedderAudioServiceDelegate(
            host = host,
            notificationManager = notificationManager,
            sessionHolder = sessionHolder,
            stateMachine = stateMachine,
            audioFocusManager = audioFocusManager,
            urlResolver = urlResolver,
            apiClient = apiClient,
            ttsEngineProvider = { provider() },
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            mainDispatcher = Dispatchers.Unconfined,
            uriConverter = { mockUri(it) },
            idleReleaseMs = idleMs,
            warmUpTimeoutMs = warmUpMs,
            maxLookAheadMs = lookAheadMs,
        )

    private suspend fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for: $what")
            kotlinx.coroutines.delay(5L)
        }
    }

    /** Starts a thread on a slow engine and returns once its first chunk is playing. */
    private suspend fun startSlowThread(testDelegate: RedderAudioServiceDelegate): kotlinx.coroutines.Job {
        testDelegate.onCreate()
        val job = testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/")
        awaitTrue("playback") { playerController.playCount > 0 }
        return job
    }

    private fun slowEngine() = TrackingTtsEngine(blockMs = 80L)

    @Test
    fun `service creation and a restart with nothing to play load no model and stop the service`() =
        runBlocking {
            var built = 0
            val testDelegate =
                idleDelegate({
                    built++
                    TrackingTtsEngine()
                })
            testDelegate.onCreate()
            val result = testDelegate.onStartCommand(null)

            assertEquals(0, built)
            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertTrue(host.stopSelfCalled)
            assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, host.lastStopForegroundFlags)
        }

    @Test
    fun `a service created only by a controller binding stops itself when idle without loading`() =
        runBlocking {
            var built = 0
            val testDelegate =
                idleDelegate({
                    built++
                    TrackingTtsEngine()
                }, idleMs = 30L)
            testDelegate.onCreate()
            awaitTrue("stop") { host.stopSelfCalled }
            assertEquals(0, built)
        }

    @Test
    fun `a share always asks for the foreground again because the player may have dropped it`() =
        runBlocking {
            serveThreadForEveryRequest()
            val testDelegate = idleDelegate({ TrackingTtsEngine() })
            testDelegate.onCreate()
            host.startForegroundCalled = false
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            assertTrue(host.startForegroundCalled)
            testDelegate.onDestroy()
        }

    @Test
    fun `an unused warm-up times out frees the model and stops the service`() =
        runBlocking {
            val engine = TrackingTtsEngine()
            val testDelegate = idleDelegate({ engine }, warmUpMs = 30L)
            testDelegate.onCreate()
            testDelegate.startWarmUp().join()

            awaitTrue("release") { engine.releaseCount == 1 }
            awaitTrue("stop") { host.stopSelfCalled }
            assertFalse(testDelegate.isEngineWarmedUp)
        }

    @Test
    fun `a warm-up is not timed out while its thread is synthesizing`() =
        runBlocking {
            serveThreadForEveryRequest()
            val testDelegate = idleDelegate({ slowEngine() }, warmUpMs = 1L)
            val job = startSlowThread(testDelegate)
            job.join()
            assertEquals(4, playerController.mediaItems.size, "no chunk may be lost to the timeout")
            testDelegate.onDestroy()
        }

    @Test
    fun `a thread that fails to load frees the warmed model and winds the service down`() =
        runBlocking {
            mockWebServer.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                        MockResponse().setResponseCode(404)
                }
            val engine = TrackingTtsEngine()
            val testDelegate = idleDelegate({ engine }, idleMs = 30L)
            testDelegate.onCreate()
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()

            awaitTrue("release") { engine.releaseCount == 1 }
            awaitTrue("stop") { host.stopSelfCalled }
        }

    @Test
    fun `synthesis stays within the look-ahead until the playhead moves`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = slowEngine()
            val testDelegate = idleDelegate({ engine }, lookAheadMs = 1L)
            val job = startSlowThread(testDelegate)

            kotlinx.coroutines.delay(600L)
            val held = engine.synthesisCount.get()
            assertTrue(held < 4, "synthesized $held of 4 with a full look-ahead")
            assertTrue(job.isActive)
            assertEquals(0, engine.releaseCount)

            testDelegate.throttle!!.onPlayhead(3)
            job.join()
            assertEquals(4, engine.synthesisCount.get())
            testDelegate.onDestroy()
        }

    @Test
    fun `pausing stops synthesis after the current chunk and resuming carries on`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = slowEngine()
            val testDelegate = idleDelegate({ engine })
            val job = startSlowThread(testDelegate)

            testDelegate.handlePause()
            kotlinx.coroutines.delay(150L)
            val atPause = engine.synthesisCount.get()
            kotlinx.coroutines.delay(400L)
            assertEquals(atPause, engine.synthesisCount.get(), "no generation while paused")
            assertTrue(atPause < 4)

            testDelegate.handlePlay()
            job.join()
            assertEquals(4, engine.synthesisCount.get())
            testDelegate.onDestroy()
        }

    @Test
    fun `a pause from the hud through the media session counts like any other`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = slowEngine()
            val testDelegate = idleDelegate({ engine })
            val job = startSlowThread(testDelegate)
            val listener = playerController.listeners.single()

            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            assertEquals(PlaybackState.Paused, stateMachine.state.value)
            kotlinx.coroutines.delay(150L)
            val atPause = engine.synthesisCount.get()
            kotlinx.coroutines.delay(300L)
            assertEquals(atPause, engine.synthesisCount.get())

            listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            assertEquals(PlaybackState.Playing, stateMachine.state.value)
            job.join()
            testDelegate.onDestroy()
        }

    @Test
    fun `a long pause frees the model and the foreground and resuming reloads it`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engines = java.util.Collections.synchronizedList(mutableListOf<TrackingTtsEngine>())
            val testDelegate = idleDelegate({ slowEngine().also { engines += it } }, idleMs = 200L)
            val job = startSlowThread(testDelegate)

            testDelegate.handlePause()
            awaitTrue("model freed") { engines.first().releaseCount == 1 }
            awaitTrue("foreground dropped") { host.lastStopForegroundFlags == ServiceCompat.STOP_FOREGROUND_DETACH }
            assertFalse(host.stopSelfCalled, "a paused thread stays resumable")
            assertEquals(1, engines.size)

            host.startForegroundCalled = false
            testDelegate.handlePlay()
            assertTrue(host.startForegroundCalled)
            job.join()
            assertEquals(2, engines.size, "the model loads again for the rest of the thread")
            assertEquals(4, engines.sumOf { it.synthesisCount.get() })
            testDelegate.onDestroy()
        }

    @Test
    fun `a short pause keeps the model`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = slowEngine()
            val testDelegate = idleDelegate({ engine }, idleMs = 400L)
            val job = startSlowThread(testDelegate)

            testDelegate.handlePause()
            kotlinx.coroutines.delay(100L)
            testDelegate.handlePlay()
            kotlinx.coroutines.delay(500L)
            assertFalse(host.stopForegroundCalled)
            job.join()
            testDelegate.onDestroy()
        }

    @Test
    fun `when the last chunk is played the foreground goes at once and the service soon after`() =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = TrackingTtsEngine()
            val testDelegate = idleDelegate({ engine }, idleMs = 100L)
            testDelegate.onCreate()
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            assertEquals(1, engine.releaseCount, "the model goes as soon as everything is synthesized")
            assertFalse(host.stopForegroundCalled, "queued audio is still playing")

            playerController.listeners.single().onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, host.lastStopForegroundFlags)
            awaitTrue("stop") { host.stopSelfCalled }
        }

    @Test
    fun `playing again right after the end cancels the stop`() =
        runBlocking {
            serveThreadForEveryRequest()
            val testDelegate = idleDelegate({ TrackingTtsEngine() }, idleMs = 150L)
            testDelegate.onCreate()
            testDelegate.handleProcessRedditUrl("https://www.reddit.com/r/androiddev/comments/xyz123/first/").join()
            val listener = playerController.listeners.single()
            listener.onPlaybackStateChanged(Player.STATE_ENDED)
            listener.onPlaybackStateChanged(Player.STATE_READY)
            kotlinx.coroutines.delay(300L)
            assertFalse(host.stopSelfCalled)
            testDelegate.onDestroy()
        }

    private fun assertStopFreesEverything(stop: (RedderAudioServiceDelegate) -> Unit) =
        runBlocking {
            serveThreadForEveryRequest()
            val engine = slowEngine()
            val testDelegate = idleDelegate({ engine })
            val job = startSlowThread(testDelegate)

            stop(testDelegate)

            job.join()
            awaitTrue("release") { engine.releaseCount == 1 }
            assertTrue(host.stopSelfCalled)
            assertEquals(ServiceCompat.STOP_FOREGROUND_REMOVE, host.lastStopForegroundFlags)
            val after = engine.synthesisCount.get()
            kotlinx.coroutines.delay(300L)
            assertEquals(after, engine.synthesisCount.get(), "no generation after stop")
        }

    @Test
    fun `action stop from the notification or the hud close button frees everything`() =
        assertStopFreesEverything { it.onStartCommand(FakeIntent(RedderAudioService.ACTION_STOP)) }

    @Test
    fun `a stop through the media session frees everything`() =
        assertStopFreesEverything { playerController.listeners.single().onPlaybackStateChanged(Player.STATE_IDLE) }

    @Test
    fun `onDestroy frees everything`() = assertStopFreesEverything { it.onDestroy() }

    @Test
    fun `removing the task while paused frees everything`() =
        assertStopFreesEverything {
            it.handlePause()
            it.onTaskRemoved()
        }
}
