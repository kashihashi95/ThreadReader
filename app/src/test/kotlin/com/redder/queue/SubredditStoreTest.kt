package com.redder.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SubredditStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, SubredditStore.FILE_NAME)

    @Test
    fun `adds normalised names and refuses duplicates and invalid names`() {
        val store = SubredditStore(file)

        assertEquals(AddSubredditResult.ADDED, store.add("r/AskReddit"))
        assertEquals(AddSubredditResult.DUPLICATE, store.add("askreddit"))
        assertEquals(AddSubredditResult.INVALID, store.add("not a sub"))
        assertEquals(listOf(SavedSubreddit("AskReddit", selected = true)), store.subreddits())
    }

    @Test
    fun `list and selection survive a restart`() {
        val first = SubredditStore(file)
        first.add("one_sub")
        first.add("two_sub")
        first.setSelected("ONE_SUB", false)

        val restarted = SubredditStore(file)

        assertEquals(listOf(SavedSubreddit("one_sub", false), SavedSubreddit("two_sub", true)), restarted.subreddits())
        assertEquals(listOf("two_sub"), restarted.selectedNames())
    }

    @Test
    fun `select all and none and remove`() {
        val store = SubredditStore(file)
        store.add("one_sub")
        store.add("two_sub")

        store.setAllSelected(false)
        assertTrue(store.selectedNames().isEmpty())
        store.setAllSelected(true)
        assertEquals(listOf("one_sub", "two_sub"), store.selectedNames())
        store.remove("ONE_sub")
        assertEquals(listOf("two_sub"), store.selectedNames())
    }

    @Test
    fun `played history stores id sub and time and survives a restart`() {
        val first = SubredditStore(file, clock = { 1234L })
        first.markPlayed("1xe3r5l", "AskReddit")

        val restarted = SubredditStore(file)

        assertTrue(restarted.isPlayed("1xe3r5l"))
        assertFalse(restarted.isPlayed("other"))
        assertEquals(1, restarted.playedCount("askreddit"))
        assertTrue(file.readText().contains("\"at\":1234"))
    }

    @Test
    fun `played history drops the oldest beyond the cap`() {
        val store = SubredditStore(file, maxPlayed = 3)
        listOf("a1", "a2", "a3", "a4").forEach { store.markPlayed(it, "s") }
        store.markPlayed("a2", "s")
        store.markPlayed("a5", "s")

        assertFalse(store.isPlayed("a1"), "dropped first")
        assertFalse(store.isPlayed("a3"), "a2 was refreshed, so a3 is the next oldest")
        assertEquals(setOf(true), setOf("a2", "a4", "a5").map(store::isPlayed).toSet())
        assertEquals(3, SubredditStore(file, maxPlayed = 3).playedTotal())
    }

    @Test
    fun `forgets played posts per sub and for all subs`() {
        val store = SubredditStore(file)
        store.markPlayed("a1", "One")
        store.markPlayed("b1", "Two")

        store.forgetPlayed("one")
        assertFalse(store.isPlayed("a1"))
        assertTrue(store.isPlayed("b1"))

        store.forgetPlayed()
        assertEquals(0, SubredditStore(file).playedTotal())
    }

    @Test
    fun `removing a sub keeps its played history`() {
        val store = SubredditStore(file)
        store.add("One_sub")
        store.markPlayed("a1", "One_sub")

        store.remove("One_sub")

        assertTrue(store.isPlayed("a1"))
    }

    @Test
    fun `a corrupt file starts empty and is replaced on the next save`() {
        file.writeText("{not json")
        val store = SubredditStore(file)
        assertTrue(store.subreddits().isEmpty())

        store.add("one_sub")

        assertEquals(1, SubredditStore(file).subreddits().size)
    }
}
