package com.redder.parser

import com.redder.model.RedditComment
import com.redder.model.RedditPost
import com.redder.model.RedditThread
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class DialogueTreePrunerTest {
    private lateinit var pruner: DialogueTreePruner
    private lateinit var parser: PolymorphicRedditParser

    @BeforeEach
    fun setUp() {
        pruner = DialogueTreePruner()
        parser = PolymorphicRedditParser()
    }

    private fun loadFixture(name: String): String {
        val candidateRoots =
            listOf(
                File("test-fixtures", name),
                File("../test-fixtures", name),
                File("../../test-fixtures", name),
            )
        val file =
            candidateRoots.firstOrNull { it.exists() }
                ?: error("Fixture $name not found in search paths: ${candidateRoots.map { it.absolutePath }}")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `prunes deep nesting and bots fixture adhering to filtering and depth cap`() {
        val json = loadFixture("reddit_thread_deep_nesting_and_bots.json")
        val thread = parser.parseThread(json)

        val pruned = pruner.prune(thread)

        // Bot, [deleted], and [removed] top-level comments must be excluded
        val authors = pruned.map { it.author }
        assertFalse(authors.contains("AutoModerator"))
        assertFalse(authors.contains("[deleted]"))
        assertFalse(authors.contains("spammer_user"))

        // Maximum depth should be capped at default 3
        val maxDepth = pruned.maxOf { it.depth }
        assertEquals(3, maxDepth)
        assertTrue(pruned.all { it.depth <= 3 })

        // Nodes must be c_deep_0, c_deep_1, c_deep_2, c_deep_3 (c_deep_4 and c_deep_5 pruned)
        assertEquals(4, pruned.size)
        val node0 = pruned[0]
        assertEquals("c_deep_0", node0.id)
        assertEquals("speaker_root", node0.author)
        assertEquals(0, node0.depth)
        assertEquals("curious_reader", node0.parentAuthor)
        assertFalse(node0.isOp)

        val node1 = pruned[1]
        assertEquals("c_deep_1", node1.id)
        assertEquals("speaker_child_1", node1.author)
        assertEquals(1, node1.depth)
        assertEquals("speaker_root", node1.parentAuthor)
        assertFalse(node1.isOp)

        val node2 = pruned[2]
        assertEquals("c_deep_2", node2.id)
        assertEquals("speaker_root", node2.author)
        assertEquals(2, node2.depth)
        assertEquals("speaker_child_1", node2.parentAuthor)
        assertFalse(node2.isOp)

        val node3 = pruned[3]
        assertEquals("c_deep_3", node3.id)
        assertEquals("speaker_child_1", node3.author)
        assertEquals(3, node3.depth)
        assertEquals("speaker_root", node3.parentAuthor)
        assertFalse(node3.isOp)
    }

    @Test
    fun `prunes polymorphic more fixture retaining conversational flow`() {
        val json = loadFixture("reddit_thread_polymorphic_more.json")
        val thread = parser.parseThread(json)

        val pruned = pruner.prune(thread)

        // In polymorphic_more fixture:
        // c_alpha1 (score 210) has reply c_beta1 (score 95)
        // c_alpha2 (score 140) has no replies
        // Pre-order traversal: c_alpha1 -> c_beta1 -> c_alpha2
        assertEquals(3, pruned.size)

        assertEquals("c_alpha1", pruned[0].id)
        assertEquals("perf_engineer", pruned[0].author)
        assertEquals(0, pruned[0].depth)
        assertEquals("android_dev_op", pruned[0].parentAuthor)
        assertFalse(pruned[0].isOp)

        assertEquals("c_beta1", pruned[1].id)
        assertEquals("ai_researcher_99", pruned[1].author)
        assertEquals(1, pruned[1].depth)
        assertEquals("perf_engineer", pruned[1].parentAuthor)
        assertFalse(pruned[1].isOp)

        assertEquals("c_alpha2", pruned[2].id)
        assertEquals("battery_watcher", pruned[2].author)
        assertEquals(0, pruned[2].depth)
        assertEquals("android_dev_op", pruned[2].parentAuthor)
        assertFalse(pruned[2].isOp)
    }

    @Test
    fun `identifies OP comments accurately and case-insensitively`() {
        val post =
            RedditPost(
                id = "p1",
                author = "Author_OP",
                title = "Test Post",
            )
        val thread =
            RedditThread(
                post = post,
                comments =
                    listOf(
                        RedditComment(
                            id = "c1",
                            author = "author_op",
                            body = "Top level comment by OP",
                            score = 10,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(
                                        id = "c2",
                                        author = "regular_user",
                                        body = "Reply to OP",
                                        score = 5,
                                        depth = 1,
                                        replies =
                                            listOf(
                                                RedditComment(
                                                    id = "c3",
                                                    author = "AUTHOR_OP",
                                                    body = "OP responds back",
                                                    score = 8,
                                                    depth = 2,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread)
        assertEquals(3, pruned.size)

        assertTrue(pruned[0].isOp)
        assertEquals("Author_OP", pruned[0].parentAuthor)

        assertFalse(pruned[1].isOp)
        assertEquals("author_op", pruned[1].parentAuthor)

        assertTrue(pruned[2].isOp)
        assertEquals("regular_user", pruned[2].parentAuthor)
    }

    @Test
    fun `caps sibling replies to top 2 by score descending and drops lower siblings`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "root",
                            author = "user0",
                            body = "Root",
                            score = 100,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(id = "r_low", author = "u1", body = "Low", score = 10),
                                    RedditComment(id = "r_top", author = "u2", body = "Top", score = 90),
                                    RedditComment(id = "r_mid", author = "u3", body = "Mid", score = 50),
                                    RedditComment(id = "r_lowest", author = "u4", body = "Lowest", score = 2),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread, maxRepliesPerBranch = 2)

        // Root + top 2 replies = 3 nodes total
        assertEquals(3, pruned.size)
        assertEquals("root", pruned[0].id)
        assertEquals("r_top", pruned[1].id)
        assertEquals("r_mid", pruned[2].id)

        // r_low and r_lowest should be omitted
        val ids = pruned.map { it.id }
        assertFalse(ids.contains("r_low"))
        assertFalse(ids.contains("r_lowest"))
    }

    @Test
    fun `orders negative scores correctly in descending order`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "root",
                            author = "u0",
                            body = "Root",
                            score = 0,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(id = "r_minus_10", author = "u1", body = "m10", score = -10),
                                    RedditComment(id = "r_minus_2", author = "u2", body = "m2", score = -2),
                                    RedditComment(id = "r_minus_50", author = "u3", body = "m50", score = -50),
                                    RedditComment(id = "r_0", author = "u4", body = "zero", score = 0),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread, maxRepliesPerBranch = 2)

        // Order of replies: r_0 (0) > r_minus_2 (-2) > r_minus_10 (-10) > r_minus_50 (-50)
        // Top 2: r_0, r_minus_2
        assertEquals(3, pruned.size)
        assertEquals("root", pruned[0].id)
        assertEquals("r_0", pruned[1].id)
        assertEquals("r_minus_2", pruned[2].id)
    }

    @Test
    fun `orders top-level comments by score descending`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(id = "c_score_10", author = "u1", body = "10", score = 10),
                        RedditComment(id = "c_score_100", author = "u2", body = "100", score = 100),
                        RedditComment(id = "c_score_50", author = "u3", body = "50", score = 50),
                        RedditComment(id = "c_score_minus_5", author = "u4", body = "-5", score = -5),
                    ),
            )

        val pruned = pruner.prune(thread)

        assertEquals(4, pruned.size)
        assertEquals("c_score_100", pruned[0].id)
        assertEquals("c_score_50", pruned[1].id)
        assertEquals("c_score_10", pruned[2].id)
        assertEquals("c_score_minus_5", pruned[3].id)
    }

    @Test
    fun `traverses conversation tree in strict depth-first pre-order`() {
        // Hierarchy:
        // - RootA (score 100)
        //   - ReplyA1 (score 30)
        //     - SubReplyA1_1 (score 10)
        //   - ReplyA2 (score 20)
        // - RootB (score 50)
        //   - ReplyB1 (score 40)
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "RootB",
                            author = "b",
                            body = "B",
                            score = 50,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(id = "ReplyB1", author = "b1", body = "B1", score = 40, depth = 1),
                                ),
                        ),
                        RedditComment(
                            id = "RootA",
                            author = "a",
                            body = "A",
                            score = 100,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(
                                        id = "ReplyA1",
                                        author = "a1",
                                        body = "A1",
                                        score = 30,
                                        depth = 1,
                                        replies =
                                            listOf(
                                                RedditComment(
                                                    id = "SubReplyA1_1",
                                                    author = "a1_1",
                                                    body = "A1_1",
                                                    score = 10,
                                                    depth = 2,
                                                ),
                                            ),
                                    ),
                                    RedditComment(id = "ReplyA2", author = "a2", body = "A2", score = 20, depth = 1),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread)
        val ids = pruned.map { it.id }

        // RootA has higher score than RootB, so RootA branch comes first
        // Pre-order: RootA -> ReplyA1 -> SubReplyA1_1 -> ReplyA2 -> RootB -> ReplyB1
        assertEquals(listOf("RootA", "ReplyA1", "SubReplyA1_1", "ReplyA2", "RootB", "ReplyB1"), ids)
    }

    @Test
    fun `respects custom maxDepth parameter`() {
        val json = loadFixture("reddit_thread_deep_nesting_and_bots.json")
        val thread = parser.parseThread(json)

        // maxDepth = 1: only c_deep_0 and c_deep_1
        val depth1Pruned = pruner.prune(thread, maxDepth = 1)
        assertEquals(2, depth1Pruned.size)
        assertEquals("c_deep_0", depth1Pruned[0].id)
        assertEquals("c_deep_1", depth1Pruned[1].id)

        // maxDepth = 0: only top-level c_deep_0
        val depth0Pruned = pruner.prune(thread, maxDepth = 0)
        assertEquals(1, depth0Pruned.size)
        assertEquals("c_deep_0", depth0Pruned[0].id)

        // maxDepth = 5: all depths 0 through 5
        val depth5Pruned = pruner.prune(thread, maxDepth = 5)
        assertEquals(6, depth5Pruned.size)
        assertEquals("c_deep_5", depth5Pruned[5].id)
        assertEquals(5, depth5Pruned[5].depth)

        // maxDepth = -1: returns empty list
        val negativeDepthPruned = pruner.prune(thread, maxDepth = -1)
        assertTrue(negativeDepthPruned.isEmpty())
    }

    @Test
    fun `respects custom maxRepliesPerBranch parameter`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "root",
                            author = "u0",
                            body = "Root",
                            score = 100,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(id = "r1", author = "u1", body = "R1", score = 30),
                                    RedditComment(id = "r2", author = "u2", body = "R2", score = 20),
                                    RedditComment(id = "r3", author = "u3", body = "R3", score = 10),
                                ),
                        ),
                    ),
            )

        // maxRepliesPerBranch = 1
        val singleReply = pruner.prune(thread, maxRepliesPerBranch = 1)
        assertEquals(2, singleReply.size)
        assertEquals("root", singleReply[0].id)
        assertEquals("r1", singleReply[1].id)

        // maxRepliesPerBranch = 3
        val allReplies = pruner.prune(thread, maxRepliesPerBranch = 3)
        assertEquals(4, allReplies.size)

        // maxRepliesPerBranch = 0
        val zeroReplies = pruner.prune(thread, maxRepliesPerBranch = 0)
        assertEquals(1, zeroReplies.size)
        assertEquals("root", zeroReplies[0].id)

        // maxRepliesPerBranch = -1 (coerced to 0)
        val negativeReplies = pruner.prune(thread, maxRepliesPerBranch = -1)
        assertEquals(1, negativeReplies.size)
        assertEquals("root", negativeReplies[0].id)
    }

    @Test
    fun `returns empty list for thread with 0 comments`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p0", author = "op", title = "Zero Comments"),
                comments = emptyList(),
            )

        val pruned = pruner.prune(thread)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `returns empty list when thread contains only bot and removed comments`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "Only Bots"),
                comments =
                    listOf(
                        RedditComment(id = "c1", author = "AutoModerator", body = "bot", score = 1),
                        RedditComment(id = "c2", author = "automoderator", body = "bot", score = 1),
                        RedditComment(id = "c3", author = "[deleted]", body = "hello", score = 1),
                        RedditComment(id = "c4", author = "[DELETED]", body = "hello", score = 1),
                        RedditComment(id = "c5", author = "[removed]", body = "hello", score = 1),
                        RedditComment(id = "c6", author = "[REMOVED]", body = "hello", score = 1),
                        RedditComment(id = "c7", author = "user", body = "", score = 1),
                        RedditComment(id = "c8", author = "user", body = "   \n\t  ", score = 1),
                        RedditComment(id = "c9", author = "user", body = "[deleted]", score = 1),
                        RedditComment(id = "c10", author = "user", body = "[removed]", score = 1),
                        RedditComment(id = "c11", author = "user", body = "  [DELETED]  ", score = 1),
                        RedditComment(id = "c12", author = "user", body = "  [REMOVED]  ", score = 1),
                    ),
            )

        val pruned = pruner.prune(thread)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `omits entire subtree under excluded comment`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "root",
                            author = "valid_user",
                            body = "Valid root",
                            score = 50,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(
                                        id = "bot_reply",
                                        author = "AutoModerator",
                                        body = "Sticky reply",
                                        score = 1000,
                                        depth = 1,
                                        replies =
                                            listOf(
                                                RedditComment(
                                                    id = "child_under_bot",
                                                    author = "innocent_user",
                                                    body = "Why under bot?",
                                                    score = 500,
                                                    depth = 2,
                                                ),
                                            ),
                                    ),
                                    RedditComment(
                                        id = "deleted_reply",
                                        author = "some_user",
                                        body = "[deleted]",
                                        score = 800,
                                        depth = 1,
                                        replies =
                                            listOf(
                                                RedditComment(
                                                    id = "child_under_deleted",
                                                    author = "another_user",
                                                    body = "Child of deleted",
                                                    score = 400,
                                                    depth = 2,
                                                ),
                                            ),
                                    ),
                                    RedditComment(
                                        id = "valid_reply",
                                        author = "third_user",
                                        body = "Valid branch",
                                        score = 10,
                                        depth = 1,
                                    ),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread)

        assertEquals(2, pruned.size)
        assertEquals("root", pruned[0].id)
        assertEquals("valid_reply", pruned[1].id)

        val ids = pruned.map { it.id }
        assertFalse(ids.contains("bot_reply"))
        assertFalse(ids.contains("child_under_bot"))
        assertFalse(ids.contains("deleted_reply"))
        assertFalse(ids.contains("child_under_deleted"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["[deleted]", "[DELETED]", "[removed]", "[REMOVED]", "AutoModerator", "automoderator"])
    fun `filters out comments with excluded authors`(authorName: String) {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "c_excluded_author",
                            author = "  $authorName  ",
                            body = "Valid body content",
                            score = 10,
                        ),
                    ),
            )

        val pruned = pruner.prune(thread)
        assertTrue(pruned.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "\t\n", "[deleted]", "[DELETED]", "[removed]", "[REMOVED]"])
    fun `filters out comments with excluded bodies`(bodyContent: String) {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "c_excluded_body",
                            author = "valid_author",
                            body = "  $bodyContent  ",
                            score = 10,
                        ),
                    ),
            )

        val pruned = pruner.prune(thread)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `rootPost extension property accesses thread post`() {
        val post = RedditPost(id = "post_ext", author = "op", title = "Title")
        val thread = RedditThread(post = post, comments = emptyList())

        assertEquals(post, thread.rootPost)
    }

    @Test
    fun `handles blank thread post author without marking OP`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "", title = "No Author"),
                comments =
                    listOf(
                        RedditComment(id = "c1", author = "user1", body = "Body", score = 5),
                    ),
            )

        val pruned = pruner.prune(thread)
        assertEquals(1, pruned.size)
        assertFalse(pruned[0].isOp)
        assertEquals("", pruned[0].parentAuthor)
    }

    @Test
    fun `dialogueNode data class methods and serialization`() {
        val node1 =
            DialogueNode(
                id = "d1",
                author = "author1",
                body = "body1",
                score = 42,
                depth = 1,
                parentAuthor = "parent1",
                isOp = true,
            )
        val node2 = node1.copy()
        val node3 = node1.copy(score = 99)

        assertEquals(node1, node2)
        assertNotEquals(node1, node3)
        assertEquals(node1.hashCode(), node2.hashCode())
        assertTrue(node1.toString().contains("d1"))

        // Serialization check
        val json = Json.encodeToString(DialogueNode.serializer(), node1)
        val decoded = Json.decodeFromString(DialogueNode.serializer(), json)
        assertEquals(node1, decoded)
    }

    @Test
    fun `filters comments whose explicit depth exceeds maxDepth`() {
        val thread =
            RedditThread(
                post = RedditPost(id = "p", author = "op", title = "T"),
                comments =
                    listOf(
                        RedditComment(
                            id = "c_explicit_deep",
                            author = "user",
                            body = "Already depth 5",
                            score = 10,
                            depth = 5,
                        ),
                        RedditComment(
                            id = "c_normal_root",
                            author = "user2",
                            body = "Normal root",
                            score = 20,
                            depth = 0,
                            replies =
                                listOf(
                                    RedditComment(
                                        id = "c_reply_deep",
                                        author = "user3",
                                        body = "Reply already depth 5",
                                        score = 15,
                                        depth = 5,
                                    ),
                                ),
                        ),
                    ),
            )

        val pruned = pruner.prune(thread, maxDepth = 3)
        assertEquals(1, pruned.size)
        assertEquals("c_normal_root", pruned[0].id)
    }

    @Test
    fun `dialogueNode inequality across all individual properties`() {
        val base =
            DialogueNode(
                id = "id1",
                author = "a1",
                body = "b1",
                score = 1,
                depth = 0,
                parentAuthor = "p1",
                isOp = false,
            )

        assertNotEquals(base, base.copy(id = "id2"))
        assertNotEquals(base, base.copy(author = "a2"))
        assertNotEquals(base, base.copy(body = "b2"))
        assertNotEquals(base, base.copy(score = 2))
        assertNotEquals(base, base.copy(depth = 1))
        assertNotEquals(base, base.copy(parentAuthor = "p2"))
        assertNotEquals(base, base.copy(isOp = true))
        assertNotEquals(base, null)
        assertNotEquals(base, "some string")
    }
}
