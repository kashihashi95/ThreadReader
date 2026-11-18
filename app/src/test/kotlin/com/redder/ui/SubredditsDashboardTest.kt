package com.redder.ui

import com.redder.R
import com.redder.playback.RedderAudioService
import com.redder.queue.SubredditStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SubredditsDashboardTest {
    @TempDir
    lateinit var dir: File

    private lateinit var store: SubredditStore
    private lateinit var controller: MainDashboardController
    private lateinit var handler: Handler

    private class Handler(private val store: SubredditStore) : MainDashboardActionHandler {
        override fun subredditStore(): SubredditStore = store

        override fun canDrawOverlays() = true

        override fun hasNotificationPermission() = true

        override fun openOverlaySettings() {}

        override fun openNotificationSettings() {}

        override fun startAudioService(redditUrl: String) {}

        override fun startOverlayService() {
            overlayStarts++
        }

        override fun openUrl(url: String) {}

        var queueStarts = 0
        var overlayStarts = 0
        val serviceActions = mutableListOf<String>()

        override fun startSubredditQueue() {
            queueStarts++
        }

        override fun sendAudioServiceAction(action: String) {
            serviceActions += action
        }
    }

    @BeforeEach
    fun setUp() {
        store = SubredditStore(File(dir, "subs.json"))
        handler = Handler(store)
        controller = MainDashboardController(handler)
    }

    private fun add(input: String) {
        controller.onSubredditInputChanged(input)
        controller.onAddSubreddit()
    }

    @Test
    fun `adding normalises the name and clears the field`() {
        add("https://www.reddit.com/r/AskReddit/")

        val state = controller.state.value
        assertEquals(listOf(SubredditRow("AskReddit", true, 0)), state.subreddits)
        assertEquals("", state.subredditInput)
        assertNull(state.subredditErrorRes)
    }

    @Test
    fun `invalid names and duplicates show an inline error and keep the text`() {
        add("not a sub")
        assertEquals(R.string.subs_error_invalid, controller.state.value.subredditErrorRes)
        assertEquals("not a sub", controller.state.value.subredditInput)

        add("AskReddit")
        add("r/askreddit")
        assertEquals(R.string.subs_error_duplicate, controller.state.value.subredditErrorRes)
        assertEquals(1, controller.state.value.subreddits.size)

        controller.onSubredditInputChanged("x")
        assertNull(controller.state.value.subredditErrorRes)
    }

    @Test
    fun `rows can be selected in any number, all or none, and removed`() {
        add("one_sub")
        add("two_sub")
        assertTrue(controller.state.value.subreddits.all { it.selected }, "new subs start selected")

        controller.onSubredditSelected("one_sub", false)
        assertEquals(listOf("two_sub"), store.selectedNames())

        controller.onSelectAllSubreddits(false)
        assertFalse(controller.state.value.subreddits.any { it.selected })

        controller.onSelectAllSubreddits(true)
        controller.onRemoveSubreddit("two_sub")
        assertEquals(listOf("one_sub"), controller.state.value.subreddits.map { it.name })
    }

    @Test
    fun `history resets per sub and for all subs`() {
        add("one_sub")
        add("two_sub")
        store.markPlayed("a1", "one_sub")
        store.markPlayed("b1", "two_sub")
        controller.refreshSubreddits()
        assertEquals(listOf(1, 1), controller.state.value.subreddits.map { it.playedCount })
        assertEquals(2, controller.state.value.playedTotal)

        controller.onForgetPlayed("one_sub")
        assertEquals(listOf(0, 1), controller.state.value.subreddits.map { it.playedCount })

        controller.onForgetPlayed()
        assertEquals(0, controller.state.value.playedTotal)
    }

    @Test
    fun `the list is there after a restart`() {
        add("one_sub")

        val restarted = MainDashboardController(Handler(SubredditStore(File(dir, "subs.json"))))

        assertEquals(listOf("one_sub"), restarted.state.value.subreddits.map { it.name })
    }

    @Test
    fun `play starts the queue and the floating player`() {
        add("one_sub")

        controller.onPlaySubredditsClicked()

        assertEquals(1, handler.queueStarts)
        assertEquals(1, handler.overlayStarts)
    }

    @Test
    fun `play with nothing selected shows an error and starts nothing`() {
        add("one_sub")
        controller.onSelectAllSubreddits(false)

        controller.onPlaySubredditsClicked()

        assertEquals(R.string.subs_error_none_selected, controller.state.value.subredditErrorRes)
        assertEquals(0, handler.queueStarts)
    }

    @Test
    fun `next post and stop go to the audio service`() {
        controller.onNextPostClicked()
        controller.onStopQueueClicked()

        assertEquals(listOf(RedderAudioService.ACTION_SKIP_NEXT, RedderAudioService.ACTION_STOP), handler.serviceActions)
    }
}
