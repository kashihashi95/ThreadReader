package com.redder.queue

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class SavedSubreddit(
    val name: String,
    val selected: Boolean = true,
)

/** A post already read or skipped; [at] is in epoch milliseconds. */
@Serializable
data class PlayedPost(
    val id: String,
    val sub: String,
    val at: Long,
)

enum class AddSubredditResult { ADDED, INVALID, DUPLICATE }

/**
 * The subreddit list, its selection and the played history, in one small JSON file. The dashboard and
 * the audio service share the [forContext] instance; every change is written straight away, so a crash
 * loses nothing that was already recorded.
 */
class SubredditStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPlayed: Int = MAX_PLAYED,
) {
    @Serializable
    private data class Contents(
        val subs: List<SavedSubreddit> = emptyList(),
        val played: List<PlayedPost> = emptyList(),
    )

    private val lock = Any()
    private val subs: MutableList<SavedSubreddit>

    /** Called after the listener changes the sub list; null unless a playlist owns the list (ADR-009). */
    @Volatile
    var onSubsChanged: (() -> Unit)? = null

    /** Called with the subs whose history is being forgotten; null unless a playlist owns the history. */
    @Volatile
    var onForget: ((List<String>) -> Unit)? = null

    /** By post ID, oldest first. */
    private val played = LinkedHashMap<String, PlayedPost>()

    init {
        val contents = read()
        subs = contents.subs.toMutableList()
        contents.played.forEach { played[it.id] = it }
    }

    fun subreddits(): List<SavedSubreddit> = synchronized(lock) { subs.toList() }

    fun selectedNames(): List<String> = synchronized(lock) { subs.filter { it.selected }.map { it.name } }

    /** Adds what [input] names, selected; see [SubredditName.normalize]. */
    fun add(input: String): AddSubredditResult {
        val name = SubredditName.normalize(input) ?: return AddSubredditResult.INVALID
        return synchronized(lock) {
            if (subs.any { it.name.equals(name, ignoreCase = true) }) {
                AddSubredditResult.DUPLICATE
            } else {
                subs += SavedSubreddit(name)
                write()
                AddSubredditResult.ADDED
            }
        }.also { if (it == AddSubredditResult.ADDED) onSubsChanged?.invoke() }
    }

    /** Takes the sub off the list; its played history stays, so adding it back does not replay posts. */
    fun remove(name: String) {
        synchronized(lock) {
            if (subs.removeAll { it.name.equals(name, ignoreCase = true) }) write()
        }
        onSubsChanged?.invoke()
    }

    fun setSelected(
        name: String,
        selected: Boolean,
    ) {
        synchronized(lock) {
            subs.replaceAll { if (it.name.equals(name, ignoreCase = true)) it.copy(selected = selected) else it }
            write()
        }
        onSubsChanged?.invoke()
    }

    fun setAllSelected(selected: Boolean) {
        synchronized(lock) {
            subs.replaceAll { it.copy(selected = selected) }
            write()
        }
        onSubsChanged?.invoke()
    }

    fun isPlayed(postId: String): Boolean = synchronized(lock) { played.containsKey(postId) }

    /** Records a finished or skipped post, dropping the oldest entries beyond the cap. */
    fun markPlayed(
        postId: String,
        sub: String,
    ) {
        synchronized(lock) {
            played.remove(postId)
            played[postId] = PlayedPost(postId, sub, clock())
            played.keys.take((played.size - maxPlayed).coerceAtLeast(0)).forEach(played::remove)
            write()
        }
    }

    /** Forgets one post that was marked played, when it is put back on a playlist. */
    fun unmarkPlayed(postId: String) {
        synchronized(lock) {
            if (played.remove(postId) != null) write()
        }
    }

    fun playedCount(sub: String): Int = synchronized(lock) { played.values.count { it.sub.equals(sub, ignoreCase = true) } }

    fun playedTotal(): Int = synchronized(lock) { played.size }

    /** Forgets the played posts of [sub], or of every sub when it is null. */
    fun forgetPlayed(sub: String? = null) {
        onForget?.invoke(
            if (sub != null) listOf(sub) else synchronized(lock) { (subs.map { it.name } + played.values.map { it.sub }).distinct() },
        )
        synchronized(lock) {
            if (sub == null) played.clear() else played.values.removeAll { it.sub.equals(sub, ignoreCase = true) }
            write()
        }
    }

    /** Every played post, oldest first. */
    fun playedPosts(): List<PlayedPost> = synchronized(lock) { played.values.toList() }

    private fun read(): Contents =
        try {
            if (file.exists()) JSON.decodeFromString<Contents>(file.readText()) else Contents()
        } catch (e: IOException) {
            warn("read failed", e)
            Contents()
        } catch (e: IllegalArgumentException) {
            warn("unreadable contents", e)
            Contents()
        }

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(JSON.encodeToString(Contents(subs.toList(), played.values.toList())))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            warn("write failed", e)
        }
    }

    private fun warn(
        message: String,
        e: Exception,
    ) {
        runCatching { Log.w(TAG, message, e) }
    }

    companion object {
        const val FILE_NAME: String = "subreddits.json"
        const val MAX_PLAYED: Int = 5_000
        private const val TAG = "SubredditStore"
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        @Volatile
        private var shared: SubredditStore? = null

        fun forContext(context: Context): SubredditStore =
            shared ?: synchronized(this) {
                shared ?: SubredditStore(File(context.applicationContext.filesDir, FILE_NAME)).also { shared = it }
            }
    }
}
