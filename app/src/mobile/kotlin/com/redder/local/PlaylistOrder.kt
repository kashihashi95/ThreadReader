package com.redder.local

/**
 * The playlist's order, ADR-008's rule worked out on the phone and held to `server/testdata/order_golden.json`.
 * Pass [crosspostKey] to leave crossposts out.
 */
object PlaylistOrder {
    /** Goes round [subs] in order, taking each one's next post by rank, leaving out [skip]. Each post once. */
    fun roundRobin(
        subs: List<String>,
        lists: Map<String, List<String>>,
        skip: Set<String> = emptySet(),
        crosspostKey: (String) -> String? = { null },
    ): List<String> {
        val seen = skip.toHashSet()
        val seenKeys = skip.mapNotNullTo(HashSet(), crosspostKey)
        val cursors = subs.associateWithTo(HashMap()) { 0 }
        val order = ArrayList<String>()
        var moved = true
        while (moved) {
            moved = false
            for (sub in subs) {
                val posts = lists[sub].orEmpty()
                var at = cursors.getValue(sub)
                while (at < posts.size && (posts[at] in seen || crosspostKey(posts[at]) in seenKeys)) at++
                if (at < posts.size) {
                    val post = posts[at]
                    seen += post
                    crosspostKey(post)?.let(seenKeys::add)
                    order += post
                    at++
                    moved = true
                }
                cursors[sub] = at
            }
        }
        return order
    }

    /**
     * The order after a refresh. In a session nothing already there moves, even removed rows, and new posts go at
     * the end. Otherwise it is built fresh: [pinned] posts, then the round robin of the newest hot pages.
     */
    fun nextOrder(
        old: List<String>,
        subs: List<String>,
        lists: Map<String, List<String>>,
        removed: Set<String>,
        pinned: List<String>,
        sessionActive: Boolean,
        crosspostKey: (String) -> String? = { null },
    ): List<String> {
        if (sessionActive) return old + roundRobin(subs, lists, old.toSet() + removed, crosspostKey)
        val head = pinned.filter { it !in removed }
        return head + roundRobin(subs, lists, head.toSet() + removed, crosspostKey)
    }

    /**
     * What two posts share when one is a crosspost of the other: the words of the title. The mirrors' listing
     * pages do not mark crossposts, so this is all there is. A title too short to be distinctive has no key.
     */
    fun crosspostKeyOf(title: String): String? {
        val words = title.lowercase().replace(NOT_WORD, " ").trim()
        return words.takeIf { it.length >= MIN_KEY_CHARS }
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private const val MIN_KEY_CHARS = 24
}
