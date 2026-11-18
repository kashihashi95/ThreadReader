package com.redder.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.redder.model.DialogueItem
import com.redder.model.SubredditQueueState
import com.redder.playback.RedderAudioService
import com.redder.ui.hud.HudEvent
import com.redder.ui.hud.HudState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FloatingOverlayServiceTest {
    private lateinit var windowManager: FakeOverlayWindowManager
    private lateinit var host: FakeOverlayServiceHost
    private lateinit var playbackController: FakeHudPlaybackController
    private lateinit var delegate: FloatingOverlayServiceDelegate
    private val sentServiceActions = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        windowManager = FakeOverlayWindowManager()
        host = FakeOverlayServiceHost()
        playbackController = FakeHudPlaybackController()
        sentServiceActions.clear()
        delegate =
            FloatingOverlayServiceDelegate(
                windowManager = windowManager,
                host = host,
                playbackController = playbackController,
                sendPlaybackServiceAction = { action ->
                    // The view is still attached when the stop is sent, so the service stops first.
                    sentServiceActions += if (windowManager.isViewAttached) action else "late:$action"
                },
            )
    }

    // =========================================================================
    // 1. Window Layout Parameters & Specification Tests
    // =========================================================================

    @Test
    fun `window layout params default type is TYPE_APPLICATION_OVERLAY`() {
        val params = OverlayWindowParams()
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
    }

    @Test
    fun `window layout params default flags include FLAG_NOT_TOUCH_MODAL`() {
        val params = OverlayWindowParams()
        val hasFlag = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0
        assertTrue(hasFlag)
    }

    @Test
    fun `window layout params default flags include FLAG_NOT_FOCUSABLE`() {
        val params = OverlayWindowParams()
        val hasFlag = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        assertTrue(hasFlag)
    }

    @Test
    fun `window layout params format is PixelFormat TRANSLUCENT`() {
        val params = OverlayWindowParams()
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
    }

    @Test
    fun `window layout params width and height are WRAP_CONTENT`() {
        val params = OverlayWindowParams()
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test
    fun `window layout params gravity contains TOP and CENTER_HORIZONTAL`() {
        val params = OverlayWindowParams()
        val hasTop = (params.gravity and Gravity.TOP) != 0
        val hasCenterH = (params.gravity and Gravity.CENTER_HORIZONTAL) != 0
        assertTrue(hasTop)
        assertTrue(hasCenterH)
    }

    @Test
    fun `window layout params retains custom coordinate offsets`() {
        val params = OverlayWindowParams(x = 50, y = 120)
        assertEquals(50, params.x)
        assertEquals(120, params.y)
    }

    @Test
    fun `onCreate attaches view to window manager with expected parameters`() {
        delegate.onCreate()

        assertEquals(1, windowManager.addViewCount)
        assertTrue(windowManager.isViewAttached)
        assertEquals(delegate.windowParams, windowManager.lastAddedParams)
        assertNotNull(windowManager.lastAddedView)
    }

    // =========================================================================
    // 2. Initial HUD State & Creation Tests
    // =========================================================================

    @Test
    fun `initial state sets default speaker and empty text`() {
        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_TEXT, delegate.state.value.text)
    }

    @Test
    fun `initial state sets isPlaying to false`() {
        assertFalse(delegate.state.value.isPlaying)
    }

    @Test
    fun `initial state sets hasNext and hasPrevious to true by default`() {
        assertTrue(delegate.state.value.hasNext)
        assertTrue(delegate.state.value.hasPrevious)
    }

    @Test
    fun `initial state respects custom initial parameters`() {
        val customState =
            HudState(
                speaker = "u/CustomAuthor",
                text = "Custom dialogue utterance",
                isPlaying = true,
                hasNext = false,
                hasPrevious = false,
            )
        val customDelegate =
            FloatingOverlayServiceDelegate(
                windowManager = windowManager,
                host = host,
                playbackController = playbackController,
                initialState = customState,
            )

        assertEquals("u/CustomAuthor", customDelegate.state.value.speaker)
        assertEquals("Custom dialogue utterance", customDelegate.state.value.text)
        assertTrue(customDelegate.state.value.isPlaying)
        assertFalse(customDelegate.state.value.hasNext)
        assertFalse(customDelegate.state.value.hasPrevious)
    }

    @Test
    fun `onCreate marks delegate as created`() {
        assertFalse(delegate.isCreated)
        delegate.onCreate()
        assertTrue(delegate.isCreated)
    }

    @Test
    fun `onCreate does not re-add view if already attached`() {
        windowManager.isViewAttached = true
        delegate.onCreate()

        assertEquals(0, windowManager.addViewCount)
    }

    // =========================================================================
    // 3. Playback Controls & Event Routing Tests
    // =========================================================================

    @Test
    fun `PlayPauseClicked dispatches play when currently paused`() {
        assertFalse(delegate.state.value.isPlaying)

        delegate.onEvent(HudEvent.PlayPauseClicked)

        assertEquals(1, playbackController.playCount)
        assertEquals(0, playbackController.pauseCount)
    }

    @Test
    fun `PlayPauseClicked dispatches pause when currently playing`() {
        delegate.updatePlaying(true)
        assertTrue(delegate.state.value.isPlaying)

        delegate.onEvent(HudEvent.PlayPauseClicked)

        assertEquals(1, playbackController.pauseCount)
        assertEquals(0, playbackController.playCount)
    }

    @Test
    fun `PlayPauseClicked toggles play to pause across state transitions`() {
        delegate.onEvent(HudEvent.PlayPauseClicked)
        assertEquals(1, playbackController.playCount)

        delegate.updatePlaying(true)
        delegate.onEvent(HudEvent.PlayPauseClicked)
        assertEquals(1, playbackController.pauseCount)
    }

    @Test
    fun `PlayPauseClicked with null playback controller handles safely without exception`() {
        delegate.playbackController = null

        delegate.onEvent(HudEvent.PlayPauseClicked)

        assertEquals(0, playbackController.playCount)
    }

    @Test
    fun `SkipNextClicked dispatches skipToNext on playback controller`() {
        delegate.onEvent(HudEvent.SkipNextClicked)

        assertEquals(1, playbackController.skipToNextCount)
    }

    @Test
    fun `SkipNextClicked with null playback controller handles safely without exception`() {
        delegate.playbackController = null

        delegate.onEvent(HudEvent.SkipNextClicked)

        assertEquals(0, playbackController.skipToNextCount)
    }

    @Test
    fun `SkipPreviousClicked dispatches skipToPrevious on playback controller`() {
        delegate.onEvent(HudEvent.SkipPreviousClicked)

        assertEquals(1, playbackController.skipToPreviousCount)
    }

    @Test
    fun `SkipPreviousClicked with null playback controller handles safely without exception`() {
        delegate.playbackController = null

        delegate.onEvent(HudEvent.SkipPreviousClicked)

        assertEquals(0, playbackController.skipToPreviousCount)
    }

    @Test
    fun `SpeedClicked cycles speed through 1_5, 2_0, 0_5, 1_0`() {
        assertEquals(1.0f, delegate.state.value.playbackSpeed)

        delegate.onEvent(HudEvent.SpeedClicked)
        assertEquals(1.5f, delegate.state.value.playbackSpeed)
        assertEquals(1.5f, playbackController.lastSpeed)

        delegate.onEvent(HudEvent.SpeedClicked)
        assertEquals(2.0f, delegate.state.value.playbackSpeed)
        assertEquals(2.0f, playbackController.lastSpeed)

        delegate.onEvent(HudEvent.SpeedClicked)
        assertEquals(0.5f, delegate.state.value.playbackSpeed)
        assertEquals(0.5f, playbackController.lastSpeed)

        delegate.onEvent(HudEvent.SpeedClicked)
        assertEquals(1.0f, delegate.state.value.playbackSpeed)
        assertEquals(1.0f, playbackController.lastSpeed)
    }

    @Test
    fun `ToggleReadUsernamesClicked toggles readUsernames state and notifies callback`() {
        var notifiedValue: Boolean? = null
        val customDelegate =
            FloatingOverlayServiceDelegate(
                windowManager = windowManager,
                host = host,
                playbackController = playbackController,
                onReadUsernamesChanged = { notifiedValue = it },
            )
        assertTrue(customDelegate.state.value.readUsernames)

        customDelegate.onEvent(HudEvent.ToggleReadUsernamesClicked)
        assertFalse(customDelegate.state.value.readUsernames)
        assertEquals(false, notifiedValue)

        customDelegate.onEvent(HudEvent.ToggleReadUsernamesClicked)
        assertTrue(customDelegate.state.value.readUsernames)
        assertEquals(true, notifiedValue)
    }

    // =========================================================================
    // 4. Decoupled Lifecycle & Dismissal Tests (ADR-002)
    // =========================================================================

    @Test
    fun `the hud closes when the playback session goes away without restarting the audio service`() {
        delegate.onCreate()
        delegate.onPlaybackSessionGone()
        assertTrue(host.stopSelfCount == 1)
        assertFalse(windowManager.isViewAttached)
        assertTrue(delegate.isDismissed)
    }

    @Test
    fun `DismissClicked removes view from window manager`() {
        delegate.onCreate()
        assertTrue(windowManager.isViewAttached)

        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(1, windowManager.removeViewCount)
        assertFalse(windowManager.isViewAttached)
    }

    @Test
    fun `DismissClicked stops overlay service host via stopSelf`() {
        delegate.onCreate()

        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(1, host.stopSelfCount)
    }

    @Test
    fun `DismissClicked marks delegate as dismissed`() {
        assertFalse(delegate.isDismissed)

        delegate.onEvent(HudEvent.DismissClicked)

        assertTrue(delegate.isDismissed)
    }

    @Test
    fun `DismissClicked stops and releases playback on playback controller`() {
        delegate.onCreate()

        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(1, playbackController.stopCount)
        assertEquals(1, playbackController.releaseCount)
    }

    @Test
    fun `DismissClicked sends the stop action to the audio service and removes the HUD`() {
        delegate.onCreate()
        assertTrue(windowManager.isViewAttached)

        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(listOf(com.redder.playback.RedderAudioService.ACTION_STOP), sentServiceActions)
        assertFalse(windowManager.isViewAttached)
        assertEquals(1, host.stopSelfCount)
    }

    @Test
    fun `repeated DismissClicked sends the stop action only once`() {
        delegate.onCreate()

        delegate.onEvent(HudEvent.DismissClicked)
        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(listOf(com.redder.playback.RedderAudioService.ACTION_STOP), sentServiceActions)
    }

    @Test
    fun `multiple DismissClicked events are idempotent and only remove view and call stopSelf once`() {
        delegate.onCreate()

        delegate.onEvent(HudEvent.DismissClicked)
        delegate.onEvent(HudEvent.DismissClicked)
        delegate.onEvent(HudEvent.DismissClicked)

        assertEquals(1, windowManager.removeViewCount)
        assertEquals(1, host.stopSelfCount)
    }

    @Test
    fun `dismiss when view was not attached still calls stopSelf cleanly`() {
        assertFalse(windowManager.isViewAttached)

        delegate.handleDismiss()

        assertEquals(0, windowManager.removeViewCount)
        assertEquals(1, host.stopSelfCount)
        assertTrue(delegate.isDismissed)
    }

    @Test
    fun `onDestroy removes attached view from window manager`() {
        delegate.onCreate()
        assertTrue(windowManager.isViewAttached)

        delegate.onDestroy()

        assertEquals(1, windowManager.removeViewCount)
        assertFalse(windowManager.isViewAttached)
    }

    @Test
    fun `onDestroy when view already removed does not attempt duplicate removeView`() {
        delegate.onCreate()
        delegate.handleDismiss()
        assertEquals(1, windowManager.removeViewCount)

        delegate.onDestroy()

        assertEquals(1, windowManager.removeViewCount)
    }

    @Test
    fun `onDestroy does NOT stop or release background audio playback`() {
        delegate.onCreate()

        delegate.onDestroy()

        assertEquals(0, playbackController.stopCount)
        assertEquals(0, playbackController.releaseCount)
    }

    // =========================================================================
    // 5. State Synchronization & Updates Tests
    // =========================================================================

    @Test
    fun `updatePlaying updates playing state in HudState`() {
        delegate.updatePlaying(true)
        assertTrue(delegate.state.value.isPlaying)

        delegate.updatePlaying(false)
        assertFalse(delegate.state.value.isPlaying)
    }

    @Test
    fun `updateSpeaker updates speaker name in HudState`() {
        delegate.updateSpeaker("u/deep_diver")
        assertEquals("u/deep_diver", delegate.state.value.speaker)
    }

    @Test
    fun `updateSpeaker falls back to default speaker when argument is null`() {
        delegate.updateSpeaker("u/test")
        delegate.updateSpeaker(null)

        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
    }

    @Test
    fun `updateSpeaker falls back to default speaker when argument is empty`() {
        delegate.updateSpeaker("")

        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
    }

    @Test
    fun `updateSpeaker falls back to default speaker when argument is blank whitespace`() {
        delegate.updateSpeaker("   \t\n")

        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
    }

    @Test
    fun `updateText updates dialogue snippet text in HudState`() {
        val snippet = "This is an insightful comment on the topic."
        delegate.updateText(snippet)

        assertEquals(snippet, delegate.state.value.text)
    }

    @Test
    fun `updateText with null sets empty text string safely`() {
        delegate.updateText("Prior text")
        delegate.updateText(null)

        assertEquals("", delegate.state.value.text)
    }

    @Test
    fun `updateNavigation updates hasNext and hasPrevious flags`() {
        delegate.updateNavigation(hasNext = false, hasPrevious = true)

        assertFalse(delegate.state.value.hasNext)
        assertTrue(delegate.state.value.hasPrevious)
    }

    @Test
    fun `updateState updates multiple properties simultaneously while preserving unspecified fields`() {
        delegate.updateText("Original text")
        delegate.updateState(speaker = "u/new_speaker", isPlaying = true)

        assertEquals("u/new_speaker", delegate.state.value.speaker)
        assertTrue(delegate.state.value.isPlaying)
        assertEquals("Original text", delegate.state.value.text)
        assertTrue(delegate.state.value.hasNext)
        assertTrue(delegate.state.value.hasPrevious)
    }

    @Test
    fun `updateState with blank speaker falls back to default speaker`() {
        delegate.updateState(speaker = "   ")

        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
    }

    // =========================================================================
    // 6. Inbound Intent Commands & Extras Tests
    // =========================================================================

    @Test
    fun `onStartCommand routes ACTION_DISMISS to handleDismiss`() {
        delegate.onCreate()
        val intent = FakeIntent(FloatingOverlayService.ACTION_DISMISS)

        val result = delegate.onStartCommand(intent)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(delegate.isDismissed)
        assertEquals(1, host.stopSelfCount)
    }

    @Test
    fun `onStartCommand routes ACTION_UPDATE_HUD with speaker text and isPlaying extras`() {
        val extras =
            mapOf(
                FloatingOverlayService.EXTRA_SPEAKER to "u/host_moderator",
                FloatingOverlayService.EXTRA_TEXT to "Playing chapter dialogue chunk",
                FloatingOverlayService.EXTRA_IS_PLAYING to true,
            )
        val intent = FakeIntent(FloatingOverlayService.ACTION_UPDATE_HUD, extras)

        delegate.onStartCommand(intent)

        assertEquals("u/host_moderator", delegate.state.value.speaker)
        assertEquals("Playing chapter dialogue chunk", delegate.state.value.text)
        assertTrue(delegate.state.value.isPlaying)
    }

    @Test
    fun `onStartCommand routes ACTION_START_OVERLAY and updates metadata extras`() {
        val extras =
            mapOf(
                FloatingOverlayService.EXTRA_SPEAKER to "u/starter",
                FloatingOverlayService.EXTRA_TEXT to "Welcome to the thread",
            )
        val intent = FakeIntent(FloatingOverlayService.ACTION_START_OVERLAY, extras)

        delegate.onStartCommand(intent)

        assertEquals("u/starter", delegate.state.value.speaker)
        assertEquals("Welcome to the thread", delegate.state.value.text)
    }

    @Test
    fun `onStartCommand with null intent does not throw and returns START_NOT_STICKY`() {
        val result = delegate.onStartCommand(null)

        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `onStartCommand with unknown action returns START_NOT_STICKY without state change`() {
        val intent = FakeIntent("com.unknown.ACTION")

        val result = delegate.onStartCommand(intent)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(delegate.isDismissed)
    }

    @Test
    fun `onEvent Drag updates coordinates and updates view layout`() {
        delegate.onCreate()
        assertEquals(0, delegate.currentWindowParams.x)
        assertEquals(180, delegate.currentWindowParams.y)

        delegate.onEvent(HudEvent.Drag(15f, 25f))

        assertEquals(15, delegate.currentWindowParams.x)
        assertEquals(205, delegate.currentWindowParams.y)
        assertEquals(1, windowManager.updateViewLayoutCount)
        assertEquals(delegate.currentWindowParams, windowManager.lastUpdatedParams)
    }

    @Test
    fun `onEvent Drag clamps y coordinate to not go above screen edge`() {
        delegate.onCreate()

        delegate.onEvent(HudEvent.Drag(0f, -300f))

        assertEquals(0, delegate.currentWindowParams.y)
        assertEquals(1, windowManager.updateViewLayoutCount)
    }

    @Test
    fun `updateCurrentUtteranceIndex synchronizes speaker and text from transcript items`() {
        val items =
            listOf(
                com.redder.model.DialogueItem(index = 0, author = "user0", text = "First text", isOp = true),
                com.redder.model.DialogueItem(index = 1, author = "user1", text = "Second text", isOp = false),
            )
        delegate.updateDialogueItems(items)

        assertEquals(0, delegate.state.value.currentUtteranceIndex)
        assertEquals("user0", delegate.state.value.speaker)
        assertEquals("First text", delegate.state.value.text)

        delegate.updateCurrentUtteranceIndex(1)

        assertEquals(1, delegate.state.value.currentUtteranceIndex)
        assertEquals("user1", delegate.state.value.speaker)
        assertEquals("Second text", delegate.state.value.text)
    }

    // =========================================================================
    // 7. HUD State & Event Data Model Equality Tests
    // =========================================================================

    @Test
    fun `HudState equals and copy contracts behave correctly`() {
        val state1 = HudState(speaker = "A", text = "B", isPlaying = false)
        val state2 = HudState(speaker = "A", text = "B", isPlaying = false)
        val state3 = state1.copy(isPlaying = true)

        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
        assertTrue(state3.isPlaying)
    }

    @Test
    fun `HudEvent data objects are distinct types`() {
        val events: List<HudEvent> =
            listOf(
                HudEvent.PlayPauseClicked,
                HudEvent.SkipNextClicked,
                HudEvent.SkipPreviousClicked,
                HudEvent.DismissClicked,
                HudEvent.ToggleExpandedClicked,
                HudEvent.DialogueItemClicked(0),
            )

        assertEquals(6, events.distinct().size)
    }

    @Test
    fun `onEvent ToggleExpandedClicked toggles isExpanded`() {
        val wm = FakeOverlayWindowManager()
        val host = FakeOverlayServiceHost()
        val delegate = FloatingOverlayServiceDelegate(windowManager = wm, host = host)

        assertFalse(delegate.state.value.isExpanded)
        delegate.onEvent(HudEvent.ToggleExpandedClicked)
        assertTrue(delegate.state.value.isExpanded)
        delegate.onEvent(HudEvent.ToggleExpandedClicked)
        assertFalse(delegate.state.value.isExpanded)
    }

    @Test
    fun `onEvent DialogueItemClicked asks for a jump to that transcript card`() {
        val wm = FakeOverlayWindowManager()
        val host = FakeOverlayServiceHost()
        val playback = FakeHudPlaybackController()
        val delegate =
            FloatingOverlayServiceDelegate(
                windowManager = wm,
                host = host,
                playbackController = playback,
            )

        delegate.onEvent(HudEvent.DialogueItemClicked(4))
        assertEquals(4, playback.jumpToTranscriptIndex)
        // The highlight moves only once the audio service reports the new current card.
        assertEquals(0, delegate.state.value.currentUtteranceIndex)
        delegate.updateCurrentUtteranceIndex(4)
        assertEquals(4, delegate.state.value.currentUtteranceIndex)
    }

    @Test
    fun `next post button goes to the audio service and shows with a queue only`() {
        delegate.onCreate()
        assertFalse(delegate.state.value.queueActive)

        delegate.updateQueue(SubredditQueueState(active = true, subreddit = "AskReddit", title = "A title"))
        assertTrue(delegate.state.value.queueActive)
        assertEquals("A title", delegate.state.value.queueTitle)

        delegate.onEvent(HudEvent.NextPostClicked)
        assertEquals(listOf(RedderAudioService.ACTION_SKIP_NEXT), sentServiceActions)

        delegate.updateQueue(SubredditQueueState())
        assertFalse(delegate.state.value.queueActive)
    }

    @Test
    fun `between queued posts the last post's text leaves the HUD`() {
        delegate.updateQueue(SubredditQueueState(active = true))
        delegate.updateDialogueItems(listOf(DialogueItem(0, "someone", "old post", false)))
        assertEquals("old post", delegate.state.value.text)

        delegate.updateDialogueItems(emptyList())

        assertEquals("", delegate.state.value.text)
        assertEquals(FloatingOverlayServiceDelegate.DEFAULT_SPEAKER, delegate.state.value.speaker)
    }

    private fun bubbleDelegate(
        screen: () -> Pair<Int, Int> = { 1000 to 2000 },
        saved: Pair<Int, Int>? = null,
        onMoved: (Int, Int) -> Unit = { _, _ -> },
    ) = FloatingOverlayServiceDelegate(
        windowManager = windowManager,
        host = host,
        playbackController = playbackController,
        sendPlaybackServiceAction = { sentServiceActions += it },
        screenSizeProvider = screen,
        bubbleSizePx = 100,
        savedBubblePosition = saved,
        onBubblePositionChanged = onMoved,
    ).also { it.onCreate() }

    @Test
    fun `minimizing changes only the window, never playback`() {
        val hud = bubbleDelegate()
        val expandedBefore = hud.state.value.isExpanded

        hud.onEvent(HudEvent.MinimizeClicked)

        assertTrue(hud.state.value.isMinimized)
        assertEquals(expandedBefore, hud.state.value.isExpanded)
        assertEquals(hud.bubbleWindowParams, windowManager.lastUpdatedParams)
        assertEquals(0, playbackController.pauseCount)
        assertEquals(0, playbackController.stopCount)
        assertTrue(sentServiceActions.isEmpty())
        assertEquals(0, windowManager.removeViewCount)
    }

    @Test
    fun `a tap on the bubble restores the HUD where it was`() {
        val hud = bubbleDelegate()
        hud.onEvent(HudEvent.Drag(30f, 40f))
        val hudPosition = hud.currentWindowParams

        hud.onEvent(HudEvent.MinimizeClicked)
        hud.onEvent(HudEvent.RestoreClicked)

        assertFalse(hud.state.value.isMinimized)
        assertEquals(hudPosition, windowManager.lastUpdatedParams)
    }

    @Test
    fun `minimize and the transcript toggle are separate states`() {
        val hud = bubbleDelegate()
        hud.onEvent(HudEvent.ToggleExpandedClicked)

        hud.onEvent(HudEvent.MinimizeClicked)
        assertTrue(hud.state.value.isExpanded)
        hud.onEvent(HudEvent.RestoreClicked)
        assertTrue(hud.state.value.isExpanded)
        assertFalse(hud.state.value.isMinimized)

        hud.onEvent(HudEvent.ToggleExpandedClicked)
        assertFalse(hud.state.value.isExpanded)
        assertFalse(hud.state.value.isMinimized)
    }

    @Test
    fun `a new bubble sits at the right edge and stays on screen when dragged`() {
        val hud = bubbleDelegate()
        hud.onEvent(HudEvent.MinimizeClicked)
        assertEquals(900, hud.bubbleWindowParams.x)

        hud.onEvent(HudEvent.Drag(500f, -5000f))
        assertEquals(900, hud.bubbleWindowParams.x)
        assertEquals(0, hud.bubbleWindowParams.y)

        hud.onEvent(HudEvent.Drag(-5000f, 5000f))
        assertEquals(0, hud.bubbleWindowParams.x)
        assertEquals(1900, hud.bubbleWindowParams.y)
    }

    @Test
    fun `dragging the bubble does not move the HUD and its position is saved on release`() {
        val saved = mutableListOf<Pair<Int, Int>>()
        val hud = bubbleDelegate(onMoved = { x, y -> saved += x to y })
        val hudPosition = hud.currentWindowParams
        hud.onEvent(HudEvent.MinimizeClicked)

        hud.onEvent(HudEvent.Drag(-100f, 50f))
        assertEquals(hudPosition, hud.currentWindowParams)
        assertTrue(saved.isEmpty())

        hud.onEvent(HudEvent.DragEnded)
        assertEquals(listOf(800 to hud.bubbleWindowParams.y), saved)
    }

    @Test
    fun `a saved position is used and pulled back on a smaller screen`() {
        var screen = 1000 to 2000
        val hud = bubbleDelegate(screen = { screen }, saved = 850 to 1800)
        hud.onEvent(HudEvent.MinimizeClicked)
        assertEquals(850, hud.bubbleWindowParams.x)
        assertEquals(1800, hud.bubbleWindowParams.y)

        screen = 600 to 1500
        hud.onScreenChanged()

        assertEquals(500, hud.bubbleWindowParams.x)
        assertEquals(1400, hud.bubbleWindowParams.y)
        assertEquals(hud.bubbleWindowParams, windowManager.lastUpdatedParams)
    }

    // =========================================================================
    // Test Doubles (Fakes)
    // =========================================================================

    private class FakeOverlayWindowManager : OverlayWindowManager {
        override var isViewAttached: Boolean = false
        var addViewCount = 0
        var removeViewCount = 0
        var updateViewLayoutCount = 0
        var lastAddedView: Any? = null
        var lastAddedParams: OverlayWindowParams? = null
        var lastRemovedView: Any? = null
        var lastUpdatedParams: OverlayWindowParams? = null

        override fun addView(
            view: Any,
            params: OverlayWindowParams,
        ) {
            addViewCount++
            lastAddedView = view
            lastAddedParams = params
            isViewAttached = true
        }

        override fun removeView(view: Any) {
            removeViewCount++
            lastRemovedView = view
            isViewAttached = false
        }

        override fun updateViewLayout(
            view: Any,
            params: OverlayWindowParams,
        ) {
            updateViewLayoutCount++
            lastUpdatedParams = params
        }
    }

    private class FakeOverlayServiceHost : OverlayServiceHost {
        var stopSelfCount = 0

        override fun stopSelf() {
            stopSelfCount++
        }
    }

    private class FakeHudPlaybackController : HudPlaybackController {
        var playCount = 0
        var pauseCount = 0
        var skipToNextCount = 0
        var skipToPreviousCount = 0
        var jumpToTranscriptIndex: Int? = null
        var stopCount = 0
        var releaseCount = 0

        override fun play() {
            playCount++
        }

        override fun pause() {
            pauseCount++
        }

        override fun skipToNext() {
            skipToNextCount++
        }

        override fun skipToPrevious() {
            skipToPreviousCount++
        }

        override fun jumpToTranscriptIndex(transcriptIndex: Int) {
            jumpToTranscriptIndex = transcriptIndex
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            releaseCount++
        }

        var lastSpeed: Float? = null

        override fun setPlaybackSpeed(speed: Float) {
            lastSpeed = speed
        }
    }

    private class FakeIntent(
        private val customAction: String? = null,
        private val extrasMap: Map<String, Any?> = emptyMap(),
    ) : Intent() {
        override fun getAction(): String? = customAction

        override fun getStringExtra(name: String): String? = extrasMap[name] as? String

        override fun hasExtra(name: String): Boolean = extrasMap.containsKey(name)

        override fun getBooleanExtra(
            name: String,
            defaultValue: Boolean,
        ): Boolean = (extrasMap[name] as? Boolean) ?: defaultValue
    }

    @Test
    fun `the now playing button opens the build's screen and leaves playback alone`() {
        var opened = 0
        val opener =
            FloatingOverlayServiceDelegate(
                windowManager = windowManager,
                host = host,
                playbackController = playbackController,
                onOpenNowPlaying = { opened++ },
            )

        opener.onEvent(HudEvent.NowPlayingClicked)

        assertEquals(1, opened)
    }
}
