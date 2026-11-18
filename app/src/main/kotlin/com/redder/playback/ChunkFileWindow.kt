package com.redder.playback

import java.io.File

/**
 * Mirrors the player's playlist: the transcript index and chunk WAV file of every item, in playlist
 * order. It is the playlist-to-transcript mapping used for rewind and caption jumps, and it deletes
 * files once playback has moved well past them.
 *
 * Only the [keepBehind] items before the current one are kept (for rewind and caption taps); older
 * ones are removed from the playlist and from this mapping together, and only then deleted, so the
 * player never holds an item whose file is gone. The current item and everything queued after it
 * are never touched. A jump further back re-synthesizes the card (see [PlaylistNavigator]).
 *
 * Thread-safe: files are added from the synthesis coroutine while the player callbacks trim.
 */
class ChunkFileWindow(
    private val keepBehind: Int = DEFAULT_KEEP_BEHIND,
    private val deleteFile: (File) -> Unit = { it.delete() },
) {
    private class Entry(val transcriptIndex: Int, val file: File)

    private val entries = ArrayDeque<Entry>()

    /** Appends [file] for transcript card [transcriptIndex]; it must back the playlist item appended at the same time. */
    @Synchronized
    fun add(
        transcriptIndex: Int,
        file: File,
    ) {
        entries.addLast(Entry(transcriptIndex, file))
    }

    /** Files still tracked, oldest first. */
    @Synchronized
    fun files(): List<File> = entries.map { it.file }

    /** Transcript index of every playlist item, in playlist order. */
    @Synchronized
    fun transcriptIndices(): List<Int> = entries.map { it.transcriptIndex }

    /**
     * Playback is now at [playlistIndex]: drops the items more than [keepBehind] before it by calling
     * [removeFromPlaylist] with how many leading items to remove, then deletes their files.
     */
    fun onCurrentIndex(
        playlistIndex: Int,
        removeFromPlaylist: (count: Int) -> Unit,
    ) {
        val expired =
            synchronized(this) {
                val count = (playlistIndex - keepBehind).coerceAtMost(entries.size)
                if (count <= 0) return
                removeFromPlaylist(count)
                List(count) { entries.removeFirst().file }
            }
        expired.forEach { runCatching { deleteFile(it) } }
    }

    /** Deletes every tracked file and starts over; call only once the player no longer uses them. */
    fun reset() {
        val all =
            synchronized(this) {
                entries.map { it.file }.also { entries.clear() }
            }
        all.forEach { runCatching { deleteFile(it) } }
    }

    /** Forgets every tracked file without deleting it and returns them; the caller now owns the files. */
    @Synchronized
    fun detachAll(): List<Pair<Int, File>> = entries.map { it.transcriptIndex to it.file }.also { entries.clear() }

    companion object {
        const val DEFAULT_KEEP_BEHIND: Int = 10
        const val FILE_PREFIX: String = "redder_chunk_"
        const val FILE_SUFFIX: String = ".wav"

        /** True for a chunk file name this app writes. */
        fun isChunkFileName(name: String): Boolean = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

        /** Deletes chunk files left in [dir] by an earlier session; returns how many were deleted. */
        fun deleteLeftovers(dir: File?): Int =
            dir?.listFiles { file -> file.isFile && isChunkFileName(file.name) }
                ?.count { it.delete() }
                ?: 0
    }
}

/**
 * Clips of the current thread kept when a caption tap replaces the playlist, so going back to one
 * plays it without synthesizing it again. Holds at most [maxClips] files, dropping the oldest.
 */
class KeptClips(
    private val maxClips: Int = DEFAULT_MAX_CLIPS,
    private val deleteFile: (File) -> Unit = { it.delete() },
) {
    private val clips = LinkedHashMap<Int, File>()

    @Synchronized
    fun put(
        transcriptIndex: Int,
        file: File,
    ) {
        clips.remove(transcriptIndex)?.let { runCatching { deleteFile(it) } }
        clips[transcriptIndex] = file
        while (clips.size > maxClips) {
            val oldest = clips.keys.first()
            clips.remove(oldest)?.let { runCatching { deleteFile(it) } }
        }
    }

    /** The WAV bytes of a kept clip, or null when there is none or it cannot be read. */
    @Synchronized
    fun wav(transcriptIndex: Int): ByteArray? = clips[transcriptIndex]?.let { file -> runCatching { file.readBytes() }.getOrNull() }

    @Synchronized
    fun clear() {
        clips.values.forEach { runCatching { deleteFile(it) } }
        clips.clear()
    }

    companion object {
        const val DEFAULT_MAX_CLIPS: Int = 40
    }
}
