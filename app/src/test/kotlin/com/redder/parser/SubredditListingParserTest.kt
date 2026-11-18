package com.redder.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SubredditListingParserTest {
    private fun fixture(name: String): String =
        listOf(File("test-fixtures", name), File("../test-fixtures", name)).first { it.exists() }.readText()

    @Test
    fun `parses a real hot listing`() {
        val page = SubredditListingParser.parse(fixture("redlib_listing_askreddit_hot.html"))

        assertEquals(25, page.posts.size)
        assertEquals("t3_1xjw3rq", page.nextAfter)
        val first = page.posts.first()
        assertEquals("1xkbciw", first.id)
        assertEquals("AskReddit", first.subreddit)
        assertTrue(first.title.isNotBlank())
        assertTrue(first.permalink.startsWith("/r/AskReddit/comments/1xkbciw/"), first.permalink)
        assertTrue(page.posts.none { it.title.contains("&#") || it.title.contains("&amp;") })
    }

    @Test
    fun `keeps nsfw posts`() {
        val page = SubredditListingParser.parse(fixture("redlib_listing_askreddit_hot.html"))

        assertTrue(page.posts.any { it.id == "1xk3ait" }, "the blurred post is kept")
    }

    @Test
    fun `skips the stickied posts of a real listing`() {
        val page = SubredditListingParser.parse(fixture("redlib_listing_stickies_trimmed.html"))

        assertFalse(page.posts.any { it.id == "1xipl67" || it.id == "1x7bp6n" })
        assertEquals("1xk0dsf", page.posts.first().id)
        assertEquals(4, page.posts.size)
    }

    private fun post(
        id: String,
        classes: String = "post",
        heading: String = """<a href="/r/Sub/comments/$id/slug/">Title $id</a>""",
    ) = """<div class="$classes" id="$id"><h2 class="post_title">$heading</h2></div>"""

    @Test
    fun `a flair link before the title is not the title`() {
        val html =
            post(
                "abc",
                heading =
                    """<a href="/r/Sub/search?q=x" class="post_flair">Flair</a> """ +
                        """<a href="/r/Sub/comments/abc/s/">Real &amp; title</a>""",
            )

        assertEquals("Real & title", SubredditListingParser.parse(html).posts.single().title)
    }

    @Test
    fun `pinned posts are skipped and a page without posts is empty`() {
        assertTrue(SubredditListingParser.parse(post("abc", "post pinned")).posts.isEmpty())

        val empty = SubredditListingParser.parse("""<div id="posts"><div id="no_posts">No posts</div></div>""")
        assertTrue(empty.posts.isEmpty())
        assertNull(empty.nextAfter)
    }

    @Test
    fun `only the NEXT link gives the next page`() {
        val html =
            post("abc") +
                """<a href="https://www.reddit.com/r/Sub?sort=hot&#38;after=t3_current">Reddit</a>""" +
                """<a href="/settings?return_to=/r/Sub?sort=hot&#38;after=t3_current">Settings</a>"""
        assertNull(SubredditListingParser.parse(html).nextAfter)

        val withNext = html + """<a href="?sort=hot&t=&after=t3_next1" accesskey="N">NEXT</a>"""
        assertEquals("t3_next1", SubredditListingParser.parse(withNext).nextAfter)
    }
}
