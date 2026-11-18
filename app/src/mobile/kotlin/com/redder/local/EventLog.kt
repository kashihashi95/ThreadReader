package com.redder.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One thing that happened. [id] is unique, so an event replayed from the file counts once. */
@Serializable
data class LocalEvent(
    val id: String,
    val type: String,
    val at: Long,
    val post: String? = null,
    val sub: String? = null,
    val chunk: Int? = null,
    val ms: Long? = null,
)

/** A post's state, worked out from its events alone. */
data class PostState(
    val sub: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val undoneAt: Long? = null,
    val finishedAt: Long? = null,
    val skippedAt: Long? = null,
    val startedAt: Long? = null,
    val positionChunk: Int? = null,
    val positionMs: Long? = null,
) {
    val removed: Boolean get() = done || finishedAt != null || skippedAt != null

    /** Done wins over everything; null for a post nobody has touched. */
    val word: String?
        get() =
            when {
                done -> DONE
                finishedAt != null -> FINISHED
                skippedAt != null -> SKIPPED
                startedAt != null -> STARTED
                else -> null
            }

    val lastChange: Long get() = listOfNotNull(doneAt, finishedAt, skippedAt).maxOrNull() ?: 0L

    companion object {
        const val DONE = "done"
        const val FINISHED = "finish"
        const val SKIPPED = "skip"
        const val STARTED = "started"
    }
}

/** The fold of the event log: the same events give the same state, in (at, id) order. */
object PostStates {
    const val START = "start"
    const val POSITION = "position"
    const val FINISH = "finish"
    const val SKIP = "skip"
    const val DONE = "done"
    const val UNDONE = "undone"
    const val FORGET = "forget"

    val LISTENING = setOf(START, POSITION, FINISH, SKIP)

    fun fold(events: List<LocalEvent>): Map<String, PostState> {
        val states = HashMap<String, PostState>()
        events.sortedWith(compareBy({ it.at }, { it.id })).forEach { apply(states, it) }
        return states
    }

    fun apply(
        states: MutableMap<String, PostState>,
        event: LocalEvent,
    ) {
        if (event.type == FORGET) {
            states.replaceAll { _, state ->
                if (state.sub.equals(event.sub, ignoreCase = true)) {
                    state.copy(finishedAt = null, skippedAt = null, startedAt = null, positionChunk = null, positionMs = null)
                } else {
                    state
                }
            }
            return
        }
        val post = event.post ?: return
        val state = (states[post] ?: PostState()).let { if (it.sub == null) it.copy(sub = event.sub) else it }
        states[post] =
            when (event.type) {
                START -> state.copy(startedAt = event.at)
                POSITION ->
                    state.copy(
                        positionChunk = event.chunk ?: 0,
                        positionMs = event.ms ?: 0L,
                        startedAt = state.startedAt ?: event.at,
                    )
                FINISH -> state.copy(finishedAt = event.at)
                SKIP -> state.copy(skippedAt = event.at)
                DONE -> state.copy(done = true, doneAt = event.at)
                UNDONE -> state.copy(done = false, undoneAt = event.at, finishedAt = null, skippedAt = null)
                else -> state
            }
    }
}

/**
 * The listening log: one JSON event per line, appended as it happens, so a crash loses at most a line that was
 * being written. Everything the playlist knows about listening can be rebuilt from this file.
 */
class EventLog(
    private val file: File,
    private val compactOverLines: Int = COMPACT_OVER_LINES,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    /** Every event once. Long logs lose their old position reports, which nothing reads again. */
    fun load(): List<LocalEvent> =
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val lines = file.readLines()
            val events =
                lines.asSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line -> runCatching { json.decodeFromString<LocalEvent>(line) }.getOrNull() }
                    .distinctBy { it.id }
                    .toList()
            if (lines.size > compactOverLines) compact(events) else events
        }

    fun append(event: LocalEvent) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(event) + "\n")
        }
    }

    private fun compact(events: List<LocalEvent>): List<LocalEvent> {
        val lastPosition = HashMap<String, String>()
        events.filter { it.type == PostStates.POSITION }.sortedWith(compareBy({ it.at }, { it.id })).forEach { event ->
            event.post?.let { lastPosition[it] = event.id }
        }
        val kept = events.filter { it.type != PostStates.POSITION || lastPosition[it.post] == it.id }
        val temp = File(file.path + ".tmp")
        temp.writeText(kept.joinToString("") { json.encodeToString(it) + "\n" })
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return kept
    }

    private companion object {
        const val COMPACT_OVER_LINES = 5_000
    }
}
