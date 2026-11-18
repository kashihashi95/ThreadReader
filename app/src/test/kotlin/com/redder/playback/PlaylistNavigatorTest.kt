package com.redder.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaylistNavigatorTest {
    @Test
    fun `media ids round-trip the transcript index and part`() {
        val id = ChunkMediaId.of(42, part = 3)
        assertEquals(42, ChunkMediaId.transcriptIndex(id))
        assertEquals(3, ChunkMediaId.part(id))
        assertEquals(0, ChunkMediaId.part(ChunkMediaId.of(7)))
        assertEquals(7, ChunkMediaId.transcriptIndex(ChunkMediaId.of(7)))
    }

    @Test
    fun `anything that is not a chunk media id maps to no transcript index`() {
        listOf(null, "", "chunk:", "chunk:5", "chunk:x:0", "chunk:-1:0", "chunk:1:2:3", "other:1:0").forEach {
            assertNull(ChunkMediaId.transcriptIndex(it), "for $it")
        }
    }

    // --- jumpTo -----------------------------------------------------------------------------

    @Test
    fun `a queued card is found by its transcript index, not its position`() {
        // Cards 0-4 were trimmed: card 7 sits at playlist position 2.
        assertEquals(NavTarget.Seek(2, 7), PlaylistNavigator.jumpTo(7, listOf(5, 6, 7, 8), transcriptSize = 20))
    }

    @Test
    fun `a card voiced by several parts jumps to its first part`() {
        assertEquals(NavTarget.Seek(1, 4), PlaylistNavigator.jumpTo(4, listOf(3, 4, 4, 4, 5), transcriptSize = 10))
    }

    @Test
    fun `a card behind the kept window is re-synthesized`() {
        assertEquals(NavTarget.Resynthesize(2), PlaylistNavigator.jumpTo(2, listOf(5, 6, 7), transcriptSize = 20))
    }

    @Test
    fun `a card past the synthesized ones sends the generator there`() {
        assertEquals(NavTarget.Resynthesize(9), PlaylistNavigator.jumpTo(9, listOf(5, 6, 7), transcriptSize = 20))
        assertEquals(NavTarget.Resynthesize(0), PlaylistNavigator.jumpTo(0, emptyList(), transcriptSize = 20))
    }

    @Test
    fun `a skipped card falls through to the next queued one`() {
        // Card 6 failed to synthesize and was never queued.
        assertEquals(NavTarget.Seek(1, 7), PlaylistNavigator.jumpTo(6, listOf(5, 7, 8), transcriptSize = 20))
    }

    @Test
    fun `an index outside the transcript is ignored`() {
        assertEquals(NavTarget.Ignore, PlaylistNavigator.jumpTo(-1, listOf(0, 1), transcriptSize = 2))
        assertEquals(NavTarget.Ignore, PlaylistNavigator.jumpTo(2, listOf(0, 1), transcriptSize = 2))
    }

    // --- previous (rewind) ------------------------------------------------------------------

    @Test
    fun `rewind more than three seconds in restarts the clip`() {
        assertEquals(NavTarget.RestartCurrent, PlaylistNavigator.previous(3_001L, 2, listOf(5, 6, 7)))
    }

    @Test
    fun `rewind within three seconds goes to the previous clip`() {
        assertEquals(NavTarget.Seek(1, 6), PlaylistNavigator.previous(3_000L, 2, listOf(5, 6, 7)))
        assertEquals(NavTarget.Seek(1, 6), PlaylistNavigator.previous(0L, 2, listOf(5, 6, 7)))
    }

    @Test
    fun `rewind at the front of a trimmed playlist re-synthesizes the previous card`() {
        assertEquals(NavTarget.Resynthesize(4), PlaylistNavigator.previous(500L, 0, listOf(5, 6, 7)))
    }

    @Test
    fun `rewind on the first card restarts it`() {
        assertEquals(NavTarget.RestartCurrent, PlaylistNavigator.previous(500L, 0, listOf(0, 1, 2)))
    }

    @Test
    fun `rewind with no known current item restarts`() {
        assertEquals(NavTarget.RestartCurrent, PlaylistNavigator.previous(500L, 3, listOf(0, 1)))
        assertEquals(NavTarget.RestartCurrent, PlaylistNavigator.previous(500L, -1, emptyList()))
    }
}
