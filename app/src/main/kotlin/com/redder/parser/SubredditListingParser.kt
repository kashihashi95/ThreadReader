package com.redder.parser

/** One post of a subreddit listing; [permalink] is a path such as `/r/Sub/comments/id/slug/`. */
data class ListingPost(
    val id: String,
    val subreddit: String,
    val title: String,
    val permalink: String,
)

/** @property nextAfter The listing's next-page token (`t3_...`), or null on the last page. */
data class SubredditListingPage(
    val posts: List<ListingPost>,
    val nextAfter: String?,
)

/**
 * Parses a Redlib subreddit page. Stickied and pinned posts are left out, so the first post is the
 * first real hot one. NSFW posts and posts without body text are kept: their comments can be read.
 */
object SubredditListingParser {
    private val POST_OPEN = Regex("""<div class="(post(?: [^"]*)?)" id="([A-Za-z0-9]+)"\s*>""")
    private val TITLE_BLOCK = Regex("""<h2 class="post_title">(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<[^>]+>""")

    /** Redlib's NEXT link. The page's other links repeat the current token, so only this one counts. */
    private val NEXT_LINK = Regex("""<a href="\?[^"]*\bafter=(t3_[A-Za-z0-9]+)"[^>]*accesskey="N"""")

    fun parse(html: String): SubredditListingPage {
        val opens = POST_OPEN.findAll(html).toList()
        val posts =
            opens.mapIndexedNotNull { index, open ->
                val end = opens.getOrNull(index + 1)?.range?.first ?: html.length
                parsePost(open, html.substring(open.range.last + 1, end))
            }
        return SubredditListingPage(posts, NEXT_LINK.find(html)?.groupValues?.get(1))
    }

    private fun parsePost(
        open: MatchResult,
        block: String,
    ): ListingPost? {
        val classes = open.groupValues[1].split(' ')
        if ("stickied" in classes || "pinned" in classes) return null
        val id = open.groupValues[2]
        val titleBlock = TITLE_BLOCK.find(block)?.groupValues?.get(1) ?: return null
        // A flair link can come first in the heading; the title is the link to this post's comments.
        val link =
            Regex("""<a href="(/r/([^/"]+)/comments/$id/[^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(titleBlock) ?: return null
        val title = RedlibHtmlParser.unescapeHtml(link.groupValues[3].replace(TAG, "")).trim()
        return ListingPost(id, link.groupValues[2], title, RedlibHtmlParser.unescapeHtml(link.groupValues[1]))
    }
}
