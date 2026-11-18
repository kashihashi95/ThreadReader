package com.redder.queue

import com.redder.parser.ListingPost
import com.redder.parser.SubredditListingPage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class SubredditQueueTest {
    private fun post(
        id: String,
        sub: String = "s",
    ) = ListingPost(id, sub, "Title $id", "/r/$sub/comments/$id/x/")

    private fun page(
        sub: String,
        vararg ids: String,
        after: String? = null,
    ) = SubredditListingPage(ids.map { post(it, sub) }, after)

    private fun queue(
        subs: List<String>,
        played: Set<String> = emptySet(),
        fetch: (String, String?) -> SubredditListingPage,
    ) = SubredditQueue(subs, { it in played }, { sub, after -> fetch(sub, after) })

    private suspend fun SubredditQueue.ids(count: Int): List<String> =
        List(count) {
            when (val next = next()) {
                is QueueNext.Post -> next.post.id
                else -> next.toString()
            }
        }

    @Test
    fun `takes the hottest post of each sub in turn`() =
        runBlocking {
            val q =
                queue(listOf("a", "b")) { sub, _ ->
                    if (sub == "a") page("a", "a1", "a2", "a3") else page("b", "b1", "b2")
                }

            assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "Exhausted"), q.ids(6))
        }

    @Test
    fun `skips posts already played and never repeats a post`() =
        runBlocking {
            val q = queue(listOf("a"), played = setOf("a1", "a3")) { _, _ -> page("a", "a1", "a2", "a3", "a2", "a4") }

            assertEquals(listOf("a2", "a4", "Exhausted"), q.ids(3))
        }

    @Test
    fun `fetches the next page when a sub runs out`() =
        runBlocking {
            val asked = mutableListOf<String?>()
            val q =
                queue(listOf("a")) { _, after ->
                    asked += after
                    if (after == null) page("a", "a1", after = "t3_a1") else page("a", "a2")
                }

            assertEquals(listOf("a1", "a2", "Exhausted"), q.ids(3))
            assertEquals(listOf(null, "t3_a1"), asked)
        }

    @Test
    fun `gives up on a sub after pages with nothing new`() =
        runBlocking {
            var fetches = 0
            val q =
                SubredditQueue(listOf("a"), { true }, { _, _ ->
                    fetches++
                    page("a", "a1", after = "t3_more")
                })

            assertEquals(QueueNext.Exhausted, q.next())
            assertEquals(SubredditQueue.MAX_PAGES_WITHOUT_NEW, fetches)
        }

    @Test
    fun `a sub that fails is skipped and the others continue`() =
        runBlocking {
            val q =
                queue(listOf("bad", "b")) { sub, _ ->
                    if (sub == "bad") throw IOException("down")
                    page("b", "b1", "b2")
                }

            assertEquals(listOf("b1", "b2", "Exhausted"), q.ids(3))
        }

    @Test
    fun `reports all failed only when no sub could be loaded`() =
        runBlocking {
            val q = queue(listOf("a", "b")) { _, _ -> throw IOException("down") }

            assertEquals(QueueNext.AllFailed, q.next())
        }

    @Test
    fun `no subs and empty listings mean nothing new`() =
        runBlocking {
            assertEquals(QueueNext.Exhausted, queue(listOf("a")) { _, _ -> page("a") }.next())
        }

    @Test
    fun `a later page failing is not all failed`() =
        runBlocking {
            val q =
                queue(listOf("a")) { _, after ->
                    if (after == null) page("a", "a1", after = "t3_a1") else throw IOException("down")
                }

            assertEquals(listOf("a1", "Exhausted"), q.ids(2))
        }
}
