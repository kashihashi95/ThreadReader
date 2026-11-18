package com.redder.local

import android.content.Context
import android.util.Log
import com.redder.deck.PlaylistRow
import com.redder.deck.PlaylistSource
import com.redder.deck.PlaylistUiState
import com.redder.deck.StaleSub
import com.redder.network.RedditApiClient
import com.redder.parser.ListingPost
import com.redder.parser.SubredditListingParser
import com.redder.playlist.PlaylistBackend
import com.redder.playlist.ServerPost
import com.redder.queue.PostQueue
import com.redder.queue.QueueNext
import com.redder.queue.SubredditStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** What the file keeps between runs, besides the event log. */
@Serializable
internal data class SavedPlaylist(
    val order: List<String> = emptyList(),
    val lists: Map<String, List<String>> = emptyMap(),
    val posts: Map<String, SavedPost> = emptyMap(),
    val status: Map<String, SubStatus> = emptyMap(),
    val backoffLevel: Int = 0,
    val backoffUntil: Long = 0L,
    val orderComplete: Boolean = false,
)

@Serializable
internal data class SavedPost(
    val sub: String,
    val title: String,
    val permalink: String,
    val lastSeen: Long,
)

@Serializable
internal data class SubStatus(
    val lastOk: Long? = null,
    val nextTry: Long = 0L,
)

/**
 * The phone's playlist (ADR-009): ADR-008's order, states and session rule. Listings are
 * refreshed only when something asks, which is the dashboard or the deck on screen, or listening going on. Nothing
 * here starts by itself, and nothing runs while the app is closed and silent.
 */
class LocalPlaylist(
    private val subs: SubredditStore,
    private val log: EventLog,
    private val stateFile: File,
    private val fetchListing: suspend (String) -> List<ListingPost>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : PlaylistBackend, PlaylistSource {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val lock = Any()
    private var saved: SavedPlaylist = readSaved()
    private val events = log.load().toMutableList()
    private val states = HashMap(PostStates.fold(events))
    private val mutableState = MutableStateFlow(PlaylistUiState())
    override val state: StateFlow<PlaylistUiState> = mutableState.asStateFlow()

    private val passLock = Mutex()

    @Volatile
    private var lastFetchAt = 0L
    private var throttleStreak = 0

    @Volatile
    private var lastPassFailed = false

    /** Posts the running queue has already offered; an undone post goes back in. */
    private val handedOut = ConcurrentHashMap.newKeySet<String>()

    init {
        subs.onSubsChanged = {
            synchronized(lock) { rebuild() }
            publish()
            scope.launch { refreshDue() }
        }
        subs.onForget = { names ->
            names.forEach { name -> record(event(PostStates.FORGET, null, name)) }
            synchronized(lock) { rebuild() }
            publish()
        }
        publish()
    }

    private fun readSaved(): SavedPlaylist =
        runCatching { if (stateFile.exists()) json.decodeFromString<SavedPlaylist>(stateFile.readText()) else SavedPlaylist() }
            .getOrElse {
                warn("unreadable playlist file", it)
                SavedPlaylist()
            }

    private fun writeSaved() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val temp = File(stateFile.path + ".tmp")
            temp.writeText(json.encodeToString(saved))
            Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { warn("could not save the playlist", it) }
    }

    // ---- order

    private fun crosspostKey(postId: String): String? = saved.posts[postId]?.title?.let(PlaylistOrder::crosspostKeyOf)

    private fun selected(): List<String> = subs.selectedNames()

    private fun sessionActive(): Boolean {
        val last = events.filter { it.type in PostStates.LISTENING }.maxOfOrNull { it.at } ?: return false
        // Until every sub has been read once the order is still being put together, so it may still change.
        return saved.orderComplete && clock() - last < SESSION_IDLE_MS
    }

    /** Call while holding [lock]. */
    private fun rebuild() {
        val names = selected()
        val lists = names.associateWith { saved.lists[it].orEmpty() }
        val hot = lists.values.flatten().toSet()
        val wanted = names.mapTo(HashSet()) { it.lowercase() }
        val live =
            states.entries
                .filter { (_, s) -> !s.removed && s.sub?.lowercase() in wanted }
                .filter { (_, s) -> s.startedAt != null || s.undoneAt != null }
        val started = live.filter { it.value.startedAt != null }.sortedByDescending { it.value.startedAt }.map { it.key }
        val undone = live.filter { it.value.startedAt == null && it.key !in hot }.sortedByDescending { it.value.undoneAt }.map { it.key }
        val removed = states.filterValues { it.removed }.keys
        saved =
            saved.copy(
                order = PlaylistOrder.nextOrder(saved.order, names, lists, removed, started + undone, sessionActive(), ::crosspostKey),
            )
        writeSaved()
    }

    /** Who is in the playlist now: not removed, known, and the sub still selected. */
    private fun rowsNow(): List<PlaylistRow> {
        val wanted = selected().mapTo(HashSet()) { it.lowercase() }
        return saved.order.mapNotNull { id ->
            val post = saved.posts[id] ?: return@mapNotNull null
            val state = states[id]
            if (state?.removed == true || post.sub.lowercase() !in wanted) null else row(id, post, state)
        }
    }

    private fun row(
        id: String,
        post: SavedPost,
        state: PostState?,
    ) = PlaylistRow(id, post.sub, post.title, post.permalink, state?.word ?: NEW, null, state?.positionChunk)

    private fun publish() {
        mutableState.value =
            synchronized(lock) {
                val now = clock()
                PlaylistUiState(
                    rows = rowsNow(),
                    recent =
                        states.entries
                            .filter { it.value.removed && saved.posts.containsKey(it.key) }
                            .sortedByDescending { it.value.lastChange }
                            .take(RECENT_KEPT)
                            .map { (id, state) -> row(id, saved.posts.getValue(id), state) },
                    stale =
                        selected().mapNotNull { sub ->
                            val lastOk = saved.status[sub]?.lastOk
                            if (lastOk == null || now - lastOk > STALE_AFTER_MS) StaleSub(sub, lastOk) else null
                        },
                    offline = lastPassFailed,
                    loaded = true,
                )
            }
    }

    // ---- listings

    private fun isDue(sub: String): Boolean =
        synchronized(lock) {
            (saved.status[sub]?.nextTry ?: 0L) <= clock() && saved.backoffUntil <= clock()
        }

    /**
     * One pass: whatever is due, one polite request at a time. Does nothing if another pass is running, or when no
     * sub is due. What was fetched last stays in use whatever happens here.
     */
    suspend fun refreshDue() {
        if (selected().none(::isDue)) return
        if (!passLock.tryLock()) return
        try {
            var tried = 0
            var failed = 0
            for (sub in selected()) {
                if (!isDue(sub)) continue
                tried++
                if (!refresh(sub)) failed++
                if (saved.backoffUntil > clock()) break
            }
            lastPassFailed = tried > 0 && failed == tried
            synchronized(lock) {
                val names = selected()
                if (!saved.orderComplete && names.isNotEmpty() && names.all { saved.lists.containsKey(it) }) {
                    rebuild()
                    saved = saved.copy(orderComplete = true)
                } else {
                    rebuild()
                }
                prunePosts()
                writeSaved()
            }
            publish()
        } finally {
            passLock.unlock()
        }
    }

    private suspend fun refresh(sub: String): Boolean {
        val wait = lastFetchAt + MIN_REQUEST_GAP_MS - clock()
        if (lastFetchAt > 0L && wait > 0L) pauseMs(wait)
        lastFetchAt = clock()
        return try {
            val page = fetchListing(sub).filter(::isReadable)
            synchronized(lock) {
                val now = clock()
                saved =
                    saved.copy(
                        lists = saved.lists + (sub to page.map { it.id }),
                        posts = saved.posts + page.associate { it.id to SavedPost(it.subreddit, it.title, it.permalink, now) },
                        status = saved.status + (sub to SubStatus(now, now + refreshWaitMs())),
                        backoffLevel = 0,
                    )
                throttleStreak = 0
                // In a session the list only grows at the end of the pass, so the new posts go in by the rule.
                if (!sessionActive()) rebuild()
            }
            publish()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onFetchFailed(sub, e)
            false
        } catch (e: IllegalArgumentException) {
            onFetchFailed(sub, e)
            false
        } catch (e: SecurityException) {
            onFetchFailed(sub, e)
            false
        }
    }

    private fun refreshWaitMs(): Long = REFRESH_MS + random.nextLong(-REFRESH_JITTER_MS, REFRESH_JITTER_MS)

    /** No network is ordinary and soon retried. A mirror saying slow down, three times running, gets a long wait. */
    private fun onFetchFailed(
        sub: String,
        e: Exception,
    ) {
        warn("listing failed for $sub", e)
        val throttled = THROTTLED.containsMatchIn(e.message.orEmpty())
        synchronized(lock) {
            val now = clock()
            val status = saved.status[sub] ?: SubStatus()
            saved = saved.copy(status = saved.status + (sub to status.copy(nextTry = now + if (throttled) refreshWaitMs() else RETRY_MS)))
            throttleStreak = if (throttled) throttleStreak + 1 else 0
            if (throttleStreak >= THROTTLE_STREAK_LIMIT) {
                throttleStreak = 0
                val level = saved.backoffLevel + 1
                val waitMs = (FIRST_BACKOFF_MS shl (level - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)
                saved = saved.copy(backoffLevel = level, backoffUntil = now + waitMs)
                runCatching { Log.i(TAG, "mirrors throttled, waiting ${waitMs / MS_PER_MINUTE} minutes") }
            }
            writeSaved()
        }
    }

    /** Old posts nobody can reach again. Posts with a state stay: they hold the titles crossposts are matched on. */
    private fun prunePosts() {
        if (saved.posts.size <= MAX_POSTS) return
        val needed = saved.order.toSet() + saved.lists.values.flatten() + states.keys
        val spare = saved.posts.filterKeys { it !in needed }.entries.sortedBy { it.value.lastSeen }
        saved = saved.copy(posts = saved.posts - spare.take(saved.posts.size - MAX_POSTS).map { it.key }.toSet())
    }

    // ---- what happened

    private fun event(
        type: String,
        post: String?,
        sub: String?,
        chunk: Int? = null,
        ms: Long? = null,
    ) = LocalEvent("$type-${UUID.randomUUID()}", type, clock(), post, sub ?: post?.let { saved.posts[it]?.sub }, chunk, ms)

    private fun record(event: LocalEvent) {
        synchronized(lock) {
            val outOfOrder = events.lastOrNull()?.let { it.at > event.at } == true
            events += event
            if (outOfOrder) {
                states.clear()
                states.putAll(PostStates.fold(events))
            } else {
                PostStates.apply(states, event)
            }
        }
        log.append(event)
    }

    private fun report(event: LocalEvent) {
        record(event)
        publish()
    }

    // ---- PlaylistSource

    override fun visibleRows(state: PlaylistUiState): List<PlaylistRow> = state.rows

    override suspend fun syncOnce(): Boolean {
        refreshDue()
        return !lastPassFailed
    }

    override fun done(row: PlaylistRow) = report(event(PostStates.DONE, row.postId, row.sub))

    override suspend fun whileScreenVisible() {
        while (true) {
            refreshDue()
            delay(SCREEN_CHECK_MS)
        }
    }

    // ---- PlaylistBackend

    override fun newQueue(): PostQueue {
        handedOut.clear()
        return object : PostQueue {
            override suspend fun next(): QueueNext {
                val pass: Job = scope.launch { refreshDue() }
                while (true) {
                    val row = synchronized(lock) { rowsNow() }.firstOrNull { it.postId !in handedOut }
                    if (row != null) {
                        handedOut += row.postId
                        return QueueNext.Post(ListingPost(row.postId, row.sub, row.title, row.permalink))
                    }
                    // Nothing to read yet, and the first listing may be on its way: waiting is all there is to do.
                    if (!pass.isActive && !passLock.isLocked) {
                        val nothingLoaded = selected().none { saved.lists.containsKey(it) }
                        return if (nothingLoaded && selected().isNotEmpty()) QueueNext.AllFailed else QueueNext.Exhausted
                    }
                    delay(QUEUE_WAIT_MS)
                }
            }
        }
    }

    /** Null: this build reads and speaks every post itself. */
    override suspend fun load(post: ListingPost): ServerPost? = null

    override fun resumeChunk(postId: String): Int = synchronized(lock) { states[postId]?.positionChunk ?: 0 }

    override fun started(post: ListingPost) = report(event(PostStates.START, post.id, post.subreddit))

    override fun position(
        post: ListingPost,
        chunk: Int,
        ms: Long,
    ) {
        report(event(PostStates.POSITION, post.id, post.subreddit, chunk, ms))
        // Only sent while audio plays, so this is the one way a list refreshes with the screen off.
        if (selected().any(::isDue)) scope.launch { refreshDue() }
    }

    override fun finished(post: ListingPost) = report(event(PostStates.FINISH, post.id, post.subreddit))

    override fun skipped(post: ListingPost) = report(event(PostStates.SKIP, post.id, post.subreddit))

    override fun done(post: ListingPost) = report(event(PostStates.DONE, post.id, post.subreddit))

    override fun undone(postId: String) {
        handedOut.remove(postId)
        record(event(PostStates.UNDONE, postId, null))
        subs.unmarkPlayed(postId)
        synchronized(lock) { rebuild() }
        publish()
    }

    override fun manualFinished(
        postId: String,
        sub: String,
    ) = report(event(PostStates.FINISH, postId, sub.takeIf { it.isNotBlank() }))

    override suspend fun isRemoved(postId: String): Boolean = synchronized(lock) { states[postId]?.removed == true }

    private fun warn(
        message: String,
        e: Throwable,
    ) {
        runCatching { Log.w(TAG, message, e) }
    }

    companion object {
        private const val TAG = "LocalPlaylist"
        private const val NEW = "new"
        private const val STATE_FILE = "playlist_state.json"
        private const val EVENTS_FILE = "playlist_events.jsonl"
        private const val PREFS = "local_playlist"
        private const val KEY_SEEDED = "default_subs_added"

        internal const val MS_PER_MINUTE = 60_000L
        internal const val SESSION_IDLE_MS = 30 * MS_PER_MINUTE
        internal const val STALE_AFTER_MS = 120 * MS_PER_MINUTE
        internal const val REFRESH_MS = 60 * MS_PER_MINUTE
        internal const val REFRESH_JITTER_MS = 5 * MS_PER_MINUTE
        internal const val RETRY_MS = 5 * MS_PER_MINUTE
        internal const val MIN_REQUEST_GAP_MS = 5_000L
        internal const val THROTTLE_STREAK_LIMIT = 3
        internal const val FIRST_BACKOFF_MS = 120 * MS_PER_MINUTE
        internal const val MAX_BACKOFF_MS = 480 * MS_PER_MINUTE
        private const val MAX_BACKOFF_DOUBLINGS = 2
        private const val SCREEN_CHECK_MS = MS_PER_MINUTE
        private const val QUEUE_WAIT_MS = 100L
        private const val RECENT_KEPT = 20
        private const val MAX_POSTS = 5_000
        private val THROTTLED = Regex("HTTP (429|503)|Bot challenge")

        /** Which listing entries are posts (`redlib.PERMALINK`). */
        private val PERMALINK = Regex("^/r/[A-Za-z0-9_]+/comments/[A-Za-z0-9]+/[A-Za-z0-9_%./-]*$")

        internal fun isReadable(post: ListingPost): Boolean = PERMALINK.matches(post.permalink)

        /** The owner's list, on a first run with an empty list. */
        val DEFAULT_SUBS: List<String> =
            listOf(
                "BestofRedditorUpdates", "AmItheAsshole", "AITAH", "TrueOffMyChest", "relationship_advice", "tifu",
                "MaliciousCompliance", "pettyrevenge", "ProRevenge", "entitledparents", "JUSTNOMIL", "offmychest",
            )

        @Volatile
        private var shared: LocalPlaylist? = null

        /** The process's one playlist. Creating it reads two small files and starts nothing. */
        fun forContext(context: Context): LocalPlaylist =
            shared ?: synchronized(this) {
                shared ?: create(context.applicationContext).also { shared = it }
            }

        private fun create(app: Context): LocalPlaylist {
            val subs = SubredditStore.forContext(app)
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_SEEDED, false)) {
                if (subs.subreddits().isEmpty()) DEFAULT_SUBS.forEach(subs::add)
                prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            }
            val api = RedditApiClient()
            return LocalPlaylist(
                subs = subs,
                log = EventLog(File(app.filesDir, EVENTS_FILE)),
                stateFile = File(app.filesDir, STATE_FILE),
                fetchListing = { sub -> SubredditListingParser.parse(api.fetchSubredditListing(sub)).posts },
            )
        }
    }
}
