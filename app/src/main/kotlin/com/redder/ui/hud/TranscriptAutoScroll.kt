package com.redder.ui.hud

/**
 * When the HUD transcript should scroll itself to the card being played. It follows playback,
 * except for a while after the user drags the list, so it never fights someone reading back.
 */
object TranscriptAutoScroll {
    /** How long after a manual scroll the list leaves the user alone. */
    const val USER_SCROLL_PAUSE_MS: Long = 5_000L

    /**
     * True when the list should scroll to [currentIndex] of [itemCount] cards: the index is a card,
     * it is not already the first card shown ([firstVisibleIndex]), and the user's last manual scroll
     * ([lastUserScrollAtMs], null for none) was at least [USER_SCROLL_PAUSE_MS] before [nowMs].
     */
    fun shouldScroll(
        currentIndex: Int,
        itemCount: Int,
        firstVisibleIndex: Int,
        lastUserScrollAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (currentIndex !in 0 until itemCount) return false
        if (currentIndex == firstVisibleIndex) return false
        return pauseRemainingMs(lastUserScrollAtMs, nowMs) == 0L
    }

    /** Milliseconds until auto-scroll resumes after a manual scroll at [lastUserScrollAtMs]; 0 when it is active. */
    fun pauseRemainingMs(
        lastUserScrollAtMs: Long?,
        nowMs: Long,
    ): Long {
        if (lastUserScrollAtMs == null) return 0L
        return (lastUserScrollAtMs + USER_SCROLL_PAUSE_MS - nowMs).coerceAtLeast(0L)
    }
}
