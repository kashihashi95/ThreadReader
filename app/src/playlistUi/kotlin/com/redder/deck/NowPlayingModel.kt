package com.redder.deck

import com.redder.model.DialogueItem
import com.redder.model.SynthesisStage

/** One sentence on the card. [speaker] is set on the first line of a run by one author. */
data class CaptionLine(
    val chunk: Int,
    val sentence: Int,
    val text: String,
    val speaker: String?,
)

/** A post's text as the card's lines, and the sums between a place in the audio and a line. */
class CaptionScript(private val items: List<DialogueItem>) {
    private val sentences: List<List<String>> =
        items.map { item -> CaptionTiming.split(item.text).ifEmpty { listOf(item.text) } }
    private val firstOfChunk: List<Int> = sentences.runningFold(0) { sum, chunk -> sum + chunk.size }

    val lines: List<CaptionLine> =
        items.flatMapIndexed { c, item ->
            val newSpeaker = item.author.takeIf { it.isNotBlank() && it != items.getOrNull(c - 1)?.author }
            sentences[c].mapIndexed { s, text -> CaptionLine(c, s, text, if (s == 0) newSpeaker else null) }
        }

    private fun startsOf(
        chunk: Int,
        durationMs: Long,
    ): List<Long> = CaptionTiming.starts(sentences[chunk], durationMs, items[chunk].pauseMs)

    /** The line at [positionMs] of [chunk]. With the clip's length not known yet, its first line. */
    fun lineAt(
        chunk: Int,
        positionMs: Long,
        durationMs: Long?,
    ): Int {
        val c = chunk.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isEmpty()) return 0
        val inChunk = if (durationMs == null) 0 else CaptionTiming.lineAt(startsOf(c, durationMs), positionMs)
        return firstOfChunk[c] + inChunk
    }

    /** Where to seek for [line]; for a clip of unknown length, from an estimated speaking rate. */
    fun startMs(
        line: CaptionLine,
        durationMs: Long?,
    ): Long {
        if (line.sentence == 0) return 0L
        val duration = durationMs ?: (items[line.chunk].text.length * MS_PER_SECOND / ESTIMATED_CHARS_PER_SECOND)
        return startsOf(line.chunk, duration)[line.sentence] + JUMP_NUDGE_MS
    }

    /**
     * A line tapped in a clip that was not loaded was placed from its estimate. Once [chunk]'s real length is known,
     * this is where it should be, or null when it is already close enough or [pending] is for another chunk.
     */
    fun correctionFor(
        pending: CaptionLine?,
        chunk: Int,
        positionMs: Long,
        durationMs: Long?,
    ): Long? {
        if (pending == null || durationMs == null || pending.chunk != chunk) return null
        val target = startMs(pending, durationMs)
        return target.takeIf { kotlin.math.abs(positionMs - it) > CORRECT_OVER_MS }
    }

    private companion object {
        const val ESTIMATED_CHARS_PER_SECOND = 14L
        const val MS_PER_SECOND = 1000L
        const val JUMP_NUDGE_MS = 20L // lands inside the line, whatever rounding the player does
        const val CORRECT_OVER_MS = 50L
    }
}

/** Hand scrolling holds the view still for a while; the clock is a seam so this needs no real time. */
class FollowGate(
    private val clock: () -> Long,
    private val holdMs: Long = HOLD_MS,
) {
    private var heldUntil = 0L

    fun userScrolled() {
        heldUntil = clock() + holdMs
    }

    fun resume() {
        heldUntil = 0L
    }

    fun following(): Boolean = clock() >= heldUntil

    companion object {
        const val HOLD_MS = 5_000L
    }
}

/** The header's place in the playlist and the two peeks, from the list and the post being read. */
data class DeckView(
    val current: PlaylistRow?,
    val place: Int?,
    val total: Int,
    val next: PlaylistRow?,
    val then: PlaylistRow?,
)

fun deckView(
    rows: List<PlaylistRow>,
    currentPostId: String?,
): DeckView {
    val at = rows.indexOfFirst { it.postId == currentPostId }
    val rest = if (at >= 0) rows.drop(at + 1) else rows.filter { it.postId != currentPostId }
    return DeckView(rows.getOrNull(at), if (at >= 0) at + 1 else null, rows.size, rest.getOrNull(0), rest.getOrNull(1))
}

/** What the small line under the title says. */
enum class CardNotice { NONE, GENERATING, LOADING }

fun cardNotice(
    queueActive: Boolean,
    hasText: Boolean,
    isPlaying: Boolean,
    stage: SynthesisStage,
): CardNotice =
    when {
        isPlaying -> CardNotice.NONE
        stage == SynthesisStage.SYNTHESIZING -> CardNotice.GENERATING
        stage == SynthesisStage.FETCHING_THREAD -> CardNotice.LOADING
        queueActive && !hasText -> CardNotice.LOADING
        else -> CardNotice.NONE
    }
