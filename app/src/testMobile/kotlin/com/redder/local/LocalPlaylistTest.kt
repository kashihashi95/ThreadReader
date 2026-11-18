package com.redder.local

import com.redder.parser.ListingPost
import com.redder.parser.SubredditListingParser
import com.redder.queue.QueueNext
import com.redder.queue.SubredditStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.random.Random

class LocalPlaylistTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetched = CopyOnWriteArrayList<String>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private var now = 1_000_000_000L
    private val pages = HashMap<String, List<ListingPost>>()

    @Volatile
    private var failure: Exception? = null

    @AfterEach
    fun stop() = scope.cancel()

    private fun post(
        id: String,
        sub: String,
        title: String = "A long and distinctive title for post $id",
    ) = ListingPost(id, sub, title, "/r/$sub/comments/$id/slug/")

    private val store by lazy { SubredditStore(File(dir, "subs.json"), clock = { now }) }

    private fun playlist(subs: List<String> = emptyList()): LocalPlaylist {
        subs.forEach(store::add)
        return LocalPlaylist(
            subs = store,
            log = EventLog(File(dir, "events.jsonl")),
            stateFile = File(dir, "state.json"),
            fetchListing = { sub ->
                fetched += sub
                failure?.let { throw it }
                pages[sub].orEmpty()
            },
            scope = scope,
            clock = { now },
            pauseMs = { pauses += it },
            random = Random(1),
        )
    }

    private fun LocalPlaylist.ids() = visibleRows().map { it.postId }

    private fun twoSubs(): LocalPlaylist {
        pages["aa"] = listOf(post("a1", "aa"), post("a2", "aa"))
        pages["bb"] = listOf(post("b1", "bb"), post("b2", "bb"))
        return playlist(listOf("aa", "bb"))
    }

    // ---- nothing by itself

    @Test
    fun `being created fetches nothing and starts nothing`() =
        runBlocking {
            twoSubs()
            delay(150)
            assertTrue(fetched.isEmpty())
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none())
        }

    @Test
    fun `reports never touch the network until a refresh is due`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            fetched.clear()
            val a1 = post("a1", "aa")
            list.started(a1)
            list.position(a1, 2, 100)
            list.finished(a1)
            delay(100)
            assertTrue(fetched.isEmpty())
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none { it.isActive })
        }

    @Test
    fun `a pass is paced and a sub is not fetched again within the hour`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            assertEquals(listOf("aa", "bb"), fetched.toList())
            assertEquals(listOf(LocalPlaylist.MIN_REQUEST_GAP_MS), pauses.toList())
            now += 10 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertEquals(2, fetched.size)
            now += 70 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertEquals(4, fetched.size)
        }

    // ---- states

    @Test
    fun `order is round robin, and every way off the list stays off`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            assertEquals(listOf("a1", "b1", "a2", "b2"), list.ids())
            list.finished(post("a1", "aa"))
            list.skipped(post("b1", "bb"))
            list.done(post("a2", "aa"))
            assertEquals(listOf("b2"), list.ids())
            assertEquals(setOf("finish", "skip", "done"), list.state.value.recent.map { it.state }.toSet())
        }

    @Test
    fun `done never returns, whatever else happens`() =
        runBlocking {
            val a1 = post("a1", "aa")
            var list = twoSubs()
            list.refreshDue()
            list.done(a1)
            // Later listening reports for the same post.
            now += 1000
            list.started(a1)
            list.position(a1, 3, 500)
            list.finished(a1)
            assertFalse("a1" in list.ids())
            // A history reset, per sub and for all.
            store.forgetPlayed("aa")
            assertFalse("a1" in list.ids())
            store.forgetPlayed(null)
            assertFalse("a1" in list.ids())
            // The post is still hot, across refreshes, once the session has ended.
            now += 3 * 60 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertFalse("a1" in list.ids())
            assertTrue("a2" in list.ids())
            // A restart reads it back from the log.
            list = playlist()
            assertFalse("a1" in list.ids())
            assertEquals(true, list.isRemoved("a1"))
            // A queue does not offer it either.
            val queue = list.newQueue()
            val offered = mutableListOf<String>()
            while (true) {
                val next = withTimeout(5_000) { queue.next() }
                if (next !is QueueNext.Post) break
                offered += next.post.id
            }
            assertFalse("a1" in offered)
            // Only undo brings it back.
            list.undone("a1")
            assertTrue("a1" in list.ids())
            assertEquals(false, list.isRemoved("a1"))
        }

    @Test
    fun `undo returns a post to its old place during a session`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            list.started(post("b2", "bb"))
            list.done(post("b1", "bb"))
            assertEquals(listOf("a1", "a2", "b2"), list.ids())
            list.undone("b1")
            assertEquals(listOf("a1", "b1", "a2", "b2"), list.ids())
        }

    @Test
    fun `reset forgets finished and skipped per sub, and keeps done`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            list.finished(post("a1", "aa"))
            list.finished(post("b1", "bb"))
            list.done(post("a2", "aa"))
            store.forgetPlayed("aa")
            assertEquals(listOf("a1", "b2"), list.ids())
            store.forgetPlayed(null)
            assertEquals(listOf("a1", "b1", "b2"), list.ids())
        }

    @Test
    fun `position resumes after a restart, and a started post comes first after the session`() =
        runBlocking {
            var list = twoSubs()
            list.refreshDue()
            val b2 = post("b2", "bb")
            list.started(b2)
            list.position(b2, 7, 1234)
            list = playlist()
            assertEquals(7, list.resumeChunk("b2"))
            assertEquals(0, list.resumeChunk("a1"))
            now += 2 * 60 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertEquals("b2", list.ids().first())
            assertEquals("started", list.visibleRows().first().state)
        }

    @Test
    fun `the order is frozen in a session, new posts go at the end, and it rebuilds after`() =
        runBlocking {
            val list = twoSubs()
            list.refreshDue()
            list.started(post("a1", "aa"))
            now += 70 * LocalPlaylist.MS_PER_MINUTE
            list.position(post("a1", "aa"), 1, 0)
            pages["aa"] = listOf(post("a9", "aa"), post("a1", "aa"), post("a2", "aa"))
            pages["bb"] = listOf(post("b2", "bb"), post("b8", "bb"), post("b1", "bb"))
            list.refreshDue()
            assertEquals(listOf("a1", "b1", "a2", "b2", "a9", "b8"), list.ids())
            now += 90 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertEquals(listOf("a1", "a9", "b2", "a2", "b8", "b1"), list.ids())
        }

    // ---- crossposts

    @Test
    fun `a crosspost is played once, the first by the rule`() =
        runBlocking {
            val title = "AITA for telling my sister the truth about her wedding?"
            pages["aa"] = listOf(post("a1", "aa", "Update: $title"), post("a2", "aa", title))
            pages["bb"] = listOf(post("b1", "bb", title.uppercase()), post("b2", "bb"))
            val list = playlist(listOf("aa", "bb"))
            list.refreshDue()
            assertEquals(listOf("a1", "b1", "b2"), list.ids())
            list.done(post("b1", "bb", title))
            now += 3 * 60 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertFalse("a2" in list.ids())
        }

    @Test
    fun `short titles are never matched`() {
        assertEquals(null, PlaylistOrder.crosspostKeyOf("Update"))
        assertEquals(
            PlaylistOrder.crosspostKeyOf("My long-running title: part ONE!"),
            PlaylistOrder.crosspostKeyOf("my long running title part one"),
        )
    }

    // ---- offline

    @Test
    fun `offline with nothing cached says so, and the last list survives a later failure`() =
        runBlocking {
            failure = IOException("no network")
            var list = twoSubs()
            assertInstanceOf(QueueNext.AllFailed::class.java, withTimeout(5_000) { list.newQueue().next() })
            assertTrue(list.state.value.offline)
            failure = null
            now += LocalPlaylist.RETRY_MS + 1
            list.refreshDue()
            assertEquals(4, list.ids().size)
            assertFalse(list.state.value.offline)
            failure = IOException("no network")
            now += 90 * LocalPlaylist.MS_PER_MINUTE
            list.refreshDue()
            assertEquals(4, list.ids().size)
            assertTrue(list.state.value.offline)
            list = playlist()
            assertEquals(4, list.ids().size)
            now += 2 * 60 * LocalPlaylist.MS_PER_MINUTE
            assertEquals(listOf("aa", "bb"), playlist().state.value.stale.map { it.sub })
            assertInstanceOf(QueueNext.Post::class.java, withTimeout(5_000) { list.newQueue().next() })
        }

    @Test
    fun `three throttled replies end the pass and back off`() =
        runBlocking {
            listOf("cc", "dd").forEach { pages[it] = emptyList() }
            failure = IOException("HTTP 429 error from mirror x for URL: y")
            val list = playlist(listOf("aa", "bb", "cc", "dd"))
            list.refreshDue()
            assertEquals(3, fetched.size)
            failure = null
            now += LocalPlaylist.FIRST_BACKOFF_MS - 1000
            list.refreshDue()
            assertEquals(3, fetched.size)
            now += 2000
            list.refreshDue()
            assertEquals(7, fetched.size)
        }

    @Test
    fun `no network does not cost a long back-off`() =
        runBlocking {
            failure = IOException("Unable to resolve host")
            val list = twoSubs()
            repeat(3) {
                list.refreshDue()
                now += LocalPlaylist.RETRY_MS + 1
            }
            assertEquals(6, fetched.size)
        }

    // ---- replay against what the server's code does with the same pages

    private fun golden() = Json.parseToJsonElement(File("../server/testdata/order_golden.json").readText()).jsonObject

    private fun parseCapture(
        prefix: String,
        sub: String,
    ): List<ListingPost> {
        val file =
            File("../server/testdata/listings").listFiles().orEmpty()
                .first { it.name.startsWith(prefix) && it.name.contains(".$sub.html.gz") }
        return SubredditListingParser.parse(GZIPInputStream(file.inputStream()).readBytes().decodeToString()).posts
    }

    @Test
    fun `order matches the server's on every captured scenario`() {
        val root = golden()
        val subs = root.getValue("subs").jsonArray.map { it.jsonPrimitive.content }

        fun listsOf(name: String) = root.getValue(name).jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
        val scenarios = root.getValue("scenarios").jsonArray
        assertEquals(4, scenarios.size)
        scenarios.forEach { element ->
            val s = element.jsonObject

            fun ids(key: String) = s.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            val made =
                PlaylistOrder.nextOrder(
                    old = ids("old"),
                    subs = subs,
                    lists = listsOf(s.getValue("lists").jsonPrimitive.content),
                    removed = ids("removed").toSet(),
                    pinned = ids("pinned"),
                    sessionActive = s.getValue("session").jsonPrimitive.boolean,
                )
            assertEquals(ids("order"), made, s.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `the Kotlin parser reads captured pages as the server does, stickies dropped`() {
        val root = golden()
        root.getValue("subs").jsonArray.map { it.jsonPrimitive.content }.forEach { sub ->
            val expected = root.getValue("second").jsonObject.getValue(sub).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, parseCapture("173", sub).filter(LocalPlaylist::isReadable).map { it.id }, sub)
        }
    }

    @Test
    fun `the whole playlist replays the captured pages to the server's order`() =
        runBlocking {
            val root = golden()
            val subs = root.getValue("subs").jsonArray.map { it.jsonPrimitive.content }
            subs.forEach { sub -> pages[sub] = parseCapture("173", sub) }
            val list = playlist(subs)
            list.refreshDue()
            val scenario =
                root.getValue(
                    "scenarios",
                ).jsonArray.first { it.jsonObject.getValue("name").jsonPrimitive.content == "fresh_second_pass" }
            val expected = scenario.jsonObject.getValue("order").jsonArray.map { it.jsonPrimitive.content }
            // The captures hold no crossposts by title, so dedupe leaves the server's order as it is.
            assertEquals(expected, list.ids())
            // Replaying is not fetching: twelve requests, each five seconds after the last.
            assertEquals(subs, fetched.toList())
            assertEquals(subs.size - 1, pauses.size)
            // ADR-008's written example.
            assertEquals(
                listOf(
                    "1xl0w8k", "1xldmmf", "1xl385p", "1xlb14w", "1xlb1ab", "1xlgfck",
                    "1xixcg2", "1xkcc3o", "1qlqzxo", "1xkaonj", "1xlgooe", "1xlhkfy",
                ),
                list.ids().take(12),
            )
            assertFalse("1x7bp6n" in list.ids(), "the sticky stays out")
        }

    // ---- event log

    @Test
    fun `the log survives a cut line and counts each id once`() {
        val file = File(dir, "log.jsonl")
        val log = EventLog(file)
        log.append(LocalEvent("done-1", PostStates.DONE, 10, "p1", "aa"))
        log.append(LocalEvent("done-1", PostStates.DONE, 10, "p1", "aa"))
        file.appendText("{\"id\":\"cut")
        file.appendText("\n")
        log.append(LocalEvent("start-2", PostStates.START, 20, "p2", "aa"))
        val events = EventLog(file).load()
        assertEquals(listOf("done-1", "start-2"), events.map { it.id })
    }

    @Test
    fun `state is a fold in time order, whatever order the lines are in`() {
        val undone = LocalEvent("undone-b", PostStates.UNDONE, 30, "p", "aa")
        val done = LocalEvent("done-a", PostStates.DONE, 20, "p", "aa")
        val state = PostStates.fold(listOf(undone, done)).getValue("p")
        assertFalse(state.done)
        val again = PostStates.fold(listOf(done, LocalEvent("done-c", PostStates.DONE, 40, "p", "aa"), undone)).getValue("p")
        assertTrue(again.done)
        assertEquals("done", again.word)
    }

    @Test
    fun `a long log keeps only each post's last position, and its state`() {
        val file = File(dir, "long.jsonl")
        val log = EventLog(file, compactOverLines = 5)
        log.append(LocalEvent("done-1", PostStates.DONE, 1, "d", "aa"))
        (2..9).forEach { log.append(LocalEvent("position-$it", PostStates.POSITION, it.toLong(), "p", "aa", chunk = it)) }
        val before = PostStates.fold(EventLog(file, compactOverLines = 100).load())
        val kept = log.load()
        assertEquals(listOf("done-1", "position-9"), kept.map { it.id })
        assertEquals(2, file.readLines().size)
        val after = PostStates.fold(kept)
        assertEquals(before.getValue("p").positionChunk, after.getValue("p").positionChunk)
        assertTrue(after.getValue("d").done)
    }

    // ---- thermal

    @Test
    fun `generation holds at severe and carries on when it cools, reporting each chunk`() =
        runBlocking {
            val readings = ArrayDeque(listOf(3, 3, 4, 2, 2, 2))
            val lines = mutableListOf<String>()
            val waits = mutableListOf<Long>()
            var clock = 0L
            val gate =
                ThermalGate(
                    status = { readings.removeFirstOrNull() ?: 2 },
                    batteryTenths = { 341 },
                    clock = { clock },
                    pauseMs = { waits += it },
                    log = { lines += it },
                )
            gate.awaitClear()
            assertTrue(waits.isNotEmpty())
            assertTrue(lines.first().startsWith("HOLD"))
            assertTrue(lines.last().startsWith("RESUME"))
            clock += 500
            gate.onSynthesized(4, 2000)
            assertTrue(lines.last().contains("rtf=0.250"), lines.last())
            assertTrue(lines.last().contains("battery_tenths=341"))
        }

    @Test
    fun `below severe nothing waits`() =
        runBlocking {
            val waits = mutableListOf<Long>()
            val gate = ThermalGate(status = { 2 }, pauseMs = { waits += it }, log = {})
            gate.awaitClear()
            assertTrue(waits.isEmpty())
        }
}
