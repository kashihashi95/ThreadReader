package com.redder.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubredditNameTest {
    @Test
    fun `accepts the typed forms`() {
        listOf("BestofRedditorUpdates", "r/BestofRedditorUpdates", "/r/BestofRedditorUpdates", " R/BestofRedditorUpdates/ ").forEach {
            assertEquals("BestofRedditorUpdates", SubredditName.normalize(it), it)
        }
    }

    @Test
    fun `accepts reddit and mirror links`() {
        listOf(
            "https://www.reddit.com/r/AskReddit/",
            "https://old.reddit.com/r/AskReddit/hot/?sort=top",
            "https://reddit.com/r/AskReddit/comments/1abc/some_post/",
            "www.reddit.com/r/AskReddit",
            "https://red.artemislena.eu/r/AskReddit",
        ).forEach { assertEquals("AskReddit", SubredditName.normalize(it), it) }
    }

    @Test
    fun `accepts two letter and underscore names`() {
        assertEquals("tv", SubredditName.normalize("r/tv"))
        assertEquals("Ask_Me", SubredditName.normalize("Ask_Me"))
    }

    @Test
    fun `rejects invalid names`() {
        listOf(
            "",
            "  ",
            "r/",
            "a",
            "has space",
            "bad-dash",
            "_leading",
            "a+b",
            "../../etc",
            "r/name/extra",
            "x".repeat(22),
        ).forEach { assertNull(SubredditName.normalize(it), it) }
    }

    @Test
    fun `rejects links that are not reddit or a mirror`() {
        listOf(
            "https://evil.example.com/r/AskReddit",
            "http://www.reddit.com/r/AskReddit",
            "https://www.reddit.com/user/someone",
            "https://www.reddit.com/",
            "evil.example.com/r/AskReddit",
        ).forEach { assertNull(SubredditName.normalize(it), it) }
    }
}
