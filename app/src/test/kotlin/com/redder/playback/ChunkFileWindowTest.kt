package com.redder.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChunkFileWindowTest {
    @TempDir
    lateinit var dir: File

    /** Stands in for the player's playlist: one entry per queued file. */
    private val playlist = mutableListOf<File>()

    private fun chunk(i: Int): File = File(dir, "redder_chunk_${i}_x.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    /** Small window (two kept behind) so the tests stay short; production keeps [ChunkFileWindow.DEFAULT_KEEP_BEHIND]. */
    private fun windowWith(
        count: Int,
        keepBehind: Int = 2,
    ): Pair<ChunkFileWindow, List<File>> {
        val window = ChunkFileWindow(keepBehind = keepBehind)
        val files = (0 until count).map { chunk(it) }
        files.forEachIndexed { index, file ->
            window.add(index, file)
            playlist += file
        }
        return window to files
    }

    private fun ChunkFileWindow.playAt(index: Int) =
        onCurrentIndex(index) { count ->
            // Every file must still exist while the player holds its item.
            assertTrue(playlist.take(count).all { it.exists() })
            repeat(count) { playlist.removeAt(0) }
        }

    @Test
    fun `nothing is deleted within the first two items`() {
        val (window, files) = windowWith(5)
        window.playAt(0)
        window.playAt(1)
        window.playAt(2)
        assertTrue(files.all { it.exists() })
        assertEquals(5, playlist.size)
    }

    @Test
    fun `played chunks beyond the two kept for skip-back are removed from the playlist and deleted`() {
        val (window, files) = windowWith(6)
        window.playAt(4)

        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        // The previous two, the current one and everything queued after it stay.
        assertTrue(files.drop(2).all { it.exists() })
        assertEquals(files.drop(2), playlist)
        assertEquals(files.drop(2), window.files())
        // The mapping drops the same items: the current item is now at playlist index 2, still card 4.
        assertEquals(listOf(2, 3, 4, 5), window.transcriptIndices())
        assertEquals(4, window.transcriptIndices()[2])
    }

    @Test
    fun `playback advancing one item at a time keeps a window of three files plus the queue`() {
        val (window, files) = windowWith(10)
        var playlistIndex = 0
        for (absolute in 0 until 8) {
            window.playAt(playlistIndex)
            assertEquals(absolute, window.transcriptIndices()[minOf(playlistIndex, 2)])
            playlistIndex = minOf(playlistIndex, 2) + 1
        }
        // At chunk 7: chunks 5 and 6 kept behind, 7 current, 8 and 9 queued.
        assertEquals(files.drop(5), window.files())
        assertTrue(files.take(5).none { it.exists() })
        assertTrue(files.drop(5).all { it.exists() })
    }

    @Test
    fun `skipping back within the window deletes nothing`() {
        val (window, files) = windowWith(6)
        window.playAt(4)
        window.playAt(1)
        window.playAt(0)
        assertEquals(files.drop(2), window.files())
        assertTrue(files.drop(2).all { it.exists() })
    }

    @Test
    fun `an index past the tracked files never removes more than is tracked`() {
        val (window, files) = windowWith(2)
        window.playAt(10)
        assertTrue(files.none { it.exists() })
        assertTrue(window.files().isEmpty())
    }

    @Test
    fun `reset deletes every tracked file and restarts the index`() {
        val (window, files) = windowWith(5)
        window.playAt(4)
        window.reset()
        assertTrue(files.none { it.exists() })
        assertTrue(window.files().isEmpty())
        assertTrue(window.transcriptIndices().isEmpty())
    }

    @Test
    fun `the default window keeps ten played items behind the current one`() {
        val (window, files) = windowWith(15, keepBehind = ChunkFileWindow.DEFAULT_KEEP_BEHIND)
        window.playAt(10)
        assertTrue(files.all { it.exists() })
        window.playAt(12)
        assertTrue(files.take(2).none { it.exists() })
        assertEquals((2 until 15).toList(), window.transcriptIndices())
        assertEquals(files.drop(2), playlist)
    }

    @Test
    fun `leftovers from an earlier session are deleted but other cache files stay`() {
        val stale = (0 until 3).map { chunk(it) }
        val other = File(dir, "voice_model.onnx").apply { writeText("x") }
        val otherWav = File(dir, "notes.wav").apply { writeText("x") }

        assertEquals(3, ChunkFileWindow.deleteLeftovers(dir))
        assertTrue(stale.none { it.exists() })
        assertTrue(other.exists())
        assertTrue(otherWav.exists())
        assertEquals(0, ChunkFileWindow.deleteLeftovers(null))
    }
}
