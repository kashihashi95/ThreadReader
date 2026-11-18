package com.redder.queue

import com.redder.parser.ListingPost
import com.redder.parser.SubredditListingPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [SubredditQueue.next] found. */
sealed interface QueueNext {
    data class Post(val post: ListingPost) : QueueNext

    /** No unplayed post is left in the subs that could be read. */
    data object Exhausted : QueueNext

    /** Not one sub's listing could be loaded. */
    data object AllFailed : QueueNext
}

/** Hands out the posts to read, one at a time. */
interface PostQueue {
    suspend fun next(): QueueNext
}

/**
 * Hands out the hottest unplayed post of each sub in turn, fetching further listing pages as a sub
 * runs out. A sub whose listing fails is skipped for the rest of the queue. A post is handed out at
 * most once per queue, so one that fails to load is not tried again until the next queue.
 */
class SubredditQueue(
    subreddits: List<String>,
    private val isPlayed: (String) -> Boolean,
    private val fetchPage: suspend (subreddit: String, after: String?) -> SubredditListingPage,
    private val maxPagesWithoutNew: Int = MAX_PAGES_WITHOUT_NEW,
) : PostQueue {
    private class SubState(val name: String) {
        val buffer = ArrayDeque<ListingPost>()
        var after: String? = null
        var noMorePages = false
        var everLoaded = false
        var failed = false
    }

    private val subs = subreddits.map(::SubState)
    private val handedOut = HashSet<String>()
    private val mutex = Mutex()
    private var cursor = 0

    override suspend fun next(): QueueNext =
        mutex.withLock {
            for (offset in subs.indices) {
                val index = (cursor + offset) % subs.size
                val post = nextFrom(subs[index])
                if (post != null) {
                    cursor = (index + 1) % subs.size
                    handedOut += post.id
                    return@withLock QueueNext.Post(post)
                }
            }
            if (subs.isNotEmpty() && subs.all { it.failed && !it.everLoaded }) QueueNext.AllFailed else QueueNext.Exhausted
        }

    private suspend fun nextFrom(sub: SubState): ListingPost? {
        var pagesWithoutNew = 0
        while (true) {
            while (sub.buffer.isNotEmpty()) {
                val post = sub.buffer.removeFirst()
                if (post.id !in handedOut && !isPlayed(post.id)) return post
            }
            if (sub.noMorePages || pagesWithoutNew >= maxPagesWithoutNew) return null
            pagesWithoutNew++
            try {
                val page = fetchPage(sub.name, sub.after)
                sub.everLoaded = true
                sub.after = page.nextAfter
                sub.noMorePages = page.nextAfter == null
                sub.buffer.addAll(page.posts)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                sub.failed = true
                sub.noMorePages = true
                return null
            }
        }
    }

    companion object {
        /** Pages of already played posts to look through before a sub counts as having nothing new. */
        const val MAX_PAGES_WITHOUT_NEW: Int = 5
    }
}
