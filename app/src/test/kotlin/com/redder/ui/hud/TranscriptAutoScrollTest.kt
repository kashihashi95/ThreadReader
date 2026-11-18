package com.redder.ui.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptAutoScrollTest {
    private val now = 100_000L

    @Test
    fun `scrolls to a new current card when the user has not scrolled`() {
        assertTrue(TranscriptAutoScroll.shouldScroll(5, 10, firstVisibleIndex = 2, lastUserScrollAtMs = null, nowMs = now))
    }

    @Test
    fun `does not scroll when the current card is already at the top`() {
        assertFalse(TranscriptAutoScroll.shouldScroll(5, 10, firstVisibleIndex = 5, lastUserScrollAtMs = null, nowMs = now))
    }

    @Test
    fun `does not scroll to an index that is not a card`() {
        assertFalse(TranscriptAutoScroll.shouldScroll(10, 10, firstVisibleIndex = 0, lastUserScrollAtMs = null, nowMs = now))
        assertFalse(TranscriptAutoScroll.shouldScroll(-1, 10, firstVisibleIndex = 0, lastUserScrollAtMs = null, nowMs = now))
        assertFalse(TranscriptAutoScroll.shouldScroll(0, 0, firstVisibleIndex = 0, lastUserScrollAtMs = null, nowMs = now))
    }

    @Test
    fun `pauses for five seconds after a manual scroll`() {
        val scrolledAt = now - 4_999L
        assertFalse(TranscriptAutoScroll.shouldScroll(5, 10, 2, scrolledAt, now))
        assertEquals(1L, TranscriptAutoScroll.pauseRemainingMs(scrolledAt, now))
    }

    @Test
    fun `resumes once five seconds have passed since the manual scroll`() {
        val scrolledAt = now - TranscriptAutoScroll.USER_SCROLL_PAUSE_MS
        assertTrue(TranscriptAutoScroll.shouldScroll(5, 10, 2, scrolledAt, now))
        assertEquals(0L, TranscriptAutoScroll.pauseRemainingMs(scrolledAt, now))
        assertEquals(0L, TranscriptAutoScroll.pauseRemainingMs(null, now))
    }
}
