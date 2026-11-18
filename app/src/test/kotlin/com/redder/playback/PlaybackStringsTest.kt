package com.redder.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the English [PlaybackStrings] defaults (used by pure JVM tests) in sync with strings.xml
 * (used on device via [PlaybackStrings.fromContext]).
 */
class PlaybackStringsTest {
    private val strings: Map<String, String> by lazy {
        val file =
            listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
                .first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            el.getAttribute("name") to el.textContent.replace("\\'", "'")
        }
    }

    @Test
    fun `default playback strings match string resources`() {
        val defaults = PlaybackStrings()
        val expected =
            mapOf(
                "notification_channel_name" to defaults.channelName,
                "notification_channel_description" to defaults.channelDescription,
                "app_name" to defaults.defaultTitle,
                "notification_default_text" to defaults.defaultText,
                "action_play" to defaults.actionPlay,
                "action_pause" to defaults.actionPause,
                "notification_action_previous" to defaults.actionPrevious,
                "notification_action_next" to defaults.actionNext,
                "playback_loading_thread" to defaults.loadingThread,
                "playback_no_dialogue" to defaults.noDialogue,
                "playback_synthesis_failed" to defaults.synthesisFailed,
                "playback_processing_failed" to defaults.processingFailed,
                "playback_thread_load_failed" to defaults.threadLoadFailed,
                "playback_system_speaker" to defaults.systemSpeaker,
                "playback_generating_progress" to defaults.generatingProgress,
                "action_next_post" to defaults.actionNextPost,
                "queue_no_new_posts" to defaults.queueNoNewPosts,
                "queue_all_failed" to defaults.queueAllFailed,
            )
        expected.forEach { (name, value) ->
            assertTrue(strings.containsKey(name), "strings.xml is missing $name")
            assertEquals(value, strings[name], "strings.xml $name differs from PlaybackStrings default")
        }
    }
}
