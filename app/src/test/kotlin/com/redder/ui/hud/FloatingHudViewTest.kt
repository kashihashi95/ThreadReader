package com.redder.ui.hud

import androidx.compose.ui.unit.dp
import com.redder.model.DialogueItem
import com.redder.model.SynthesisProgress
import com.redder.model.SynthesisStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FloatingHudViewTest {
    @Test
    fun `HudState default parameters initialize correctly`() {
        val state =
            HudState(
                speaker = "u/default_user",
                text = "Hello world utterance",
                isPlaying = false,
            )

        assertEquals("u/default_user", state.speaker)
        assertEquals("Hello world utterance", state.text)
        assertFalse(state.isPlaying)
        assertTrue(state.hasNext)
        assertTrue(state.hasPrevious)
        assertEquals(SynthesisStage.IDLE, state.synthesisProgress.stage)
        assertTrue(state.dialogueItems.isEmpty())
        assertFalse(state.isExpanded)
        assertEquals(0, state.currentUtteranceIndex)
        assertEquals(1.0f, state.playbackSpeed)
        assertTrue(state.readUsernames)
    }

    @Test
    fun `HudState copy supports expanding and collapsing overlay`() {
        val initial =
            HudState(
                speaker = "u/author",
                text = "Snippet",
                isPlaying = true,
                isExpanded = false,
            )
        assertFalse(initial.isExpanded)

        val expanded = initial.copy(isExpanded = true)
        assertTrue(expanded.isExpanded)
        assertEquals("u/author", expanded.speaker)
        assertTrue(expanded.isPlaying)

        val collapsed = expanded.copy(isExpanded = false)
        assertFalse(collapsed.isExpanded)
    }

    @Test
    fun `HudState copy updates currentUtteranceIndex`() {
        val state =
            HudState(
                speaker = "u/author",
                text = "Snippet",
                isPlaying = false,
                currentUtteranceIndex = 0,
            )

        val updated = state.copy(currentUtteranceIndex = 4)
        assertEquals(4, updated.currentUtteranceIndex)
    }

    @Test
    fun `HudState copy updates synthesisProgress`() {
        val state =
            HudState(
                speaker = "u/author",
                text = "Snippet",
                isPlaying = false,
            )
        val progress =
            SynthesisProgress(
                currentChunk = 2,
                totalChunks = 5,
                stage = SynthesisStage.SYNTHESIZING,
                progressPercent = null,
            )
        val updated = state.copy(synthesisProgress = progress)
        assertEquals(progress, updated.synthesisProgress)
        assertEquals(SynthesisStage.SYNTHESIZING, updated.synthesisProgress.stage)
    }

    @Test
    fun `HudState copy updates transcript dialogue items`() {
        val items =
            listOf(
                DialogueItem(index = 0, author = "user1", text = "Text 1", isOp = true),
                DialogueItem(index = 1, author = "user2", text = "Text 2", isOp = false),
            )
        val state =
            HudState(
                speaker = "user1",
                text = "Text 1",
                isPlaying = true,
                dialogueItems = items,
            )

        assertEquals(2, state.dialogueItems.size)
        assertEquals("user1", state.dialogueItems[0].author)
        assertTrue(state.dialogueItems[0].isOp)
        assertEquals("user2", state.dialogueItems[1].author)
        assertFalse(state.dialogueItems[1].isOp)
    }

    @Test
    fun `HudState equality and hashCode contracts hold`() {
        val state1 =
            HudState(
                speaker = "u/same",
                text = "Content",
                isPlaying = true,
                isExpanded = true,
                currentUtteranceIndex = 2,
            )
        val state2 =
            HudState(
                speaker = "u/same",
                text = "Content",
                isPlaying = true,
                isExpanded = true,
                currentUtteranceIndex = 2,
            )
        val state3 = state1.copy(currentUtteranceIndex = 3)

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
        assertNotEquals(state1, state3)
    }

    @Test
    fun `HudEvent data objects and classes are distinct`() {
        val events: List<HudEvent> =
            listOf(
                HudEvent.PlayPauseClicked,
                HudEvent.SkipNextClicked,
                HudEvent.SkipPreviousClicked,
                HudEvent.DismissClicked,
                HudEvent.ToggleExpandedClicked,
                HudEvent.SpeedClicked,
                HudEvent.ToggleReadUsernamesClicked,
                HudEvent.DialogueItemClicked(index = 0),
                HudEvent.DialogueItemClicked(index = 1),
                HudEvent.Drag(deltaX = 10f, deltaY = 20f),
            )

        assertEquals(10, events.distinct().size)
    }

    @Test
    fun `Drag retains delta offsets and satisfies value equality`() {
        val event1 = HudEvent.Drag(12f, 24f)
        val event2 = HudEvent.Drag(12f, 24f)
        val event3 = HudEvent.Drag(5f, -10f)

        assertEquals(12f, event1.deltaX)
        assertEquals(24f, event1.deltaY)
        assertEquals(event1, event2)
        assertNotEquals(event1, event3)
    }

    @Test
    fun `DialogueItemClicked retains clicked item index and satisfies value equality`() {
        val event1 = HudEvent.DialogueItemClicked(index = 5)
        val event2 = HudEvent.DialogueItemClicked(index = 5)
        val event3 = HudEvent.DialogueItemClicked(index = 8)

        assertEquals(5, event1.index)
        assertEquals(event1, event2)
        assertNotEquals(event1, event3)
    }

    @Test
    fun `HudIcons vector instances are defined with standard 24dp viewport`() {
        assertNotNull(HudIcons.Play)
        assertNotNull(HudIcons.Pause)
        assertNotNull(HudIcons.SkipNext)
        assertNotNull(HudIcons.SkipPrevious)
        assertNotNull(HudIcons.Close)
        assertNotNull(HudIcons.Expand)
        assertNotNull(HudIcons.Collapse)
        assertNotNull(HudIcons.Person)
        assertNotNull(HudIcons.PersonOff)
        assertNotNull(HudIcons.DragHandle)

        assertEquals(24.dp, HudIcons.Expand.defaultWidth)
        assertEquals(24.dp, HudIcons.Expand.defaultHeight)
        assertEquals(24.dp, HudIcons.Collapse.defaultWidth)
        assertEquals(24.dp, HudIcons.Collapse.defaultHeight)
        assertEquals(24.dp, HudIcons.Person.defaultWidth)
        assertEquals(24.dp, HudIcons.PersonOff.defaultWidth)
        assertEquals(24.dp, HudIcons.PersonOff.defaultHeight)
        assertEquals(24.dp, HudIcons.DragHandle.defaultWidth)
        assertEquals(24.dp, HudIcons.DragHandle.defaultHeight)
    }
}
