package com.redder.model

/**
 * Domain model representing an extracted dialogue utterance item for full-page transcripts.
 *
 * @property index Monotonically increasing chunk index in the dialogue sequence.
 * @property author Reddit author/username or role attribution for this utterance.
 * @property text The linearized and spoken text content of the dialogue item.
 * @property isOp True if the author is the Original Poster (OP) of the thread.
 */
data class DialogueItem(
    val index: Int,
    val author: String,
    val text: String,
    val isOp: Boolean = false,
    /** Silence at the end of this item's audio before the next one, for timing captions. */
    val pauseMs: Long = 0L,
)
