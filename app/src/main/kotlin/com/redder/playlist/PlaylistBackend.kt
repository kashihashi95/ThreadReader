package com.redder.playlist

import com.redder.model.RedditThread
import com.redder.parser.ListingPost
import com.redder.queue.PostQueue
import com.redder.sanitizer.DialogueChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A post that comes from a server-owned playlist (ADR-008): the server made its chunks, and [wav] gives
 * each chunk's finished audio, pauses included.
 *
 * @property startIndex Where listening stopped last time, on any device.
 * @property wav Throws `TtsUnavailableException` when the server cannot be used; any other exception skips the chunk.
 */
class ServerPost(
    val thread: RedditThread,
    val chunks: List<DialogueChunk>,
    val startIndex: Int,
    val wav: suspend (Int) -> ByteArray,
)

/**
 * The build's playlist with its own record of listening, kept on the phone (ADR-009). Reports return at once.
 */
interface PlaylistBackend {
    fun newQueue(): PostQueue

    /**
     * The post with its finished audio, or null when the phone reads and speaks it itself.
     * This path supports the optional self-hosted backend described in the README's Architecture note;
     * the phone-only build's [LocalPlaylist][com.redder.local.LocalPlaylist] always returns null.
     * Throws an `IOException` when the post cannot be loaded.
     */
    suspend fun load(post: ListingPost): ServerPost?

    /** Where to start a post [load] left to the phone: the chunk listening reached last time. */
    fun resumeChunk(postId: String): Int = 0

    fun started(post: ListingPost)

    fun position(
        post: ListingPost,
        chunk: Int,
        ms: Long,
    )

    fun finished(post: ListingPost)

    fun skipped(post: ListingPost)

    fun done(post: ListingPost)

    fun undone(postId: String)

    /** A thread shared by hand was read to the end, so the playlist should not offer it again. */
    fun manualFinished(
        postId: String,
        sub: String,
    )

    /** True when another device or the page took this post off the playlist. A failed check is false. */
    suspend fun isRemoved(postId: String): Boolean
}

/**
 * @property available True while a server playlist's post is being read, so Done can be offered.
 * @property undoPostId The post just marked done, while Undo can still be offered.
 */
data class DoneButtonState(
    val available: Boolean = false,
    val undoPostId: String? = null,
)

/** Process-wide state of the Done button, for the floating player. */
object DoneButtonHolder {
    private val mutableState = MutableStateFlow(DoneButtonState())
    val state: StateFlow<DoneButtonState> = mutableState.asStateFlow()

    fun update(state: DoneButtonState) {
        mutableState.value = state
    }
}
