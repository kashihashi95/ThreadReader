package com.redder.playback

/**
 * Media IDs of the chunk items in the player's playlist. Each one names the transcript (dialogue) card
 * it voices and which part of that card it is, so the current card is always read from the playing
 * item and never from its position in the playlist (played items are trimmed off the front).
 */
object ChunkMediaId {
    private const val PREFIX = "chunk:"

    /** Media ID of part [part] of transcript card [transcriptIndex]. */
    fun of(
        transcriptIndex: Int,
        part: Int = 0,
    ): String = "$PREFIX$transcriptIndex:$part"

    /** Transcript index named by [mediaId], or null when it is not a chunk media ID. */
    fun transcriptIndex(mediaId: String?): Int? = fields(mediaId)?.first

    /** Part named by [mediaId], or null when it is not a chunk media ID. */
    fun part(mediaId: String?): Int? = fields(mediaId)?.second

    private fun fields(mediaId: String?): Pair<Int, Int>? {
        if (mediaId == null || !mediaId.startsWith(PREFIX)) return null
        val parts = mediaId.substring(PREFIX.length).split(':')
        if (parts.size != 2) return null
        val index = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val part = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return index to part
    }
}

/** What to do for a rewind or a jump to a transcript card. */
sealed interface NavTarget {
    /** Play the playlist item at [playlistIndex] (voicing [transcriptIndex]) from its start. */
    data class Seek(val playlistIndex: Int, val transcriptIndex: Int) : NavTarget

    /** Play the current item again from its start. */
    data object RestartCurrent : NavTarget

    /** The card's audio was already cleaned up: synthesize again from [transcriptIndex] and play it. */
    data class Resynthesize(val transcriptIndex: Int) : NavTarget

    /** Nothing to do (no such card). */
    data object Ignore : NavTarget
}

/**
 * Pure rewind and tap-to-jump decisions over the playlist, given as the transcript index of every
 * playlist item in order (oldest first; a card voiced by several parts appears once per part).
 */
object PlaylistNavigator {
    /** Past this far into a clip, rewind restarts it instead of going to the previous one. */
    const val RESTART_THRESHOLD_MS: Long = 3_000L

    /**
     * Jump to transcript card [target] of a thread with [transcriptSize] cards: seeks to its first
     * part when queued, and otherwise, whether it was trimmed off the front or is not synthesized yet,
     * sends the generator there. A card that was skipped (synthesis failed) falls through to the next
     * one queued.
     */
    fun jumpTo(
        target: Int,
        playlist: List<Int>,
        transcriptSize: Int,
    ): NavTarget {
        if (target < 0 || target >= transcriptSize) return NavTarget.Ignore
        if (playlist.isEmpty() || target < playlist.first()) return NavTarget.Resynthesize(target)
        val position = playlist.indexOfFirst { it >= target }
        return if (position >= 0) NavTarget.Seek(position, playlist[position]) else NavTarget.Resynthesize(target)
    }

    /**
     * Rewind ("previous") from [positionMs] into the item at [currentPlaylistIndex]: restarts the clip
     * when more than [RESTART_THRESHOLD_MS] in (or at the very first card), otherwise goes to the
     * previous clip, re-synthesizing it when it was already trimmed off the front.
     */
    fun previous(
        positionMs: Long,
        currentPlaylistIndex: Int,
        playlist: List<Int>,
    ): NavTarget {
        val current = playlist.getOrNull(currentPlaylistIndex) ?: return NavTarget.RestartCurrent
        if (positionMs > RESTART_THRESHOLD_MS || current <= 0) return NavTarget.RestartCurrent
        if (currentPlaylistIndex > 0) {
            return NavTarget.Seek(currentPlaylistIndex - 1, playlist[currentPlaylistIndex - 1])
        }
        return NavTarget.Resynthesize(current - 1)
    }
}
