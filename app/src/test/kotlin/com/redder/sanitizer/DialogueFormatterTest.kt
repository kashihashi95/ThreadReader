package com.redder.sanitizer

import com.redder.model.RedditPost
import com.redder.model.RedditThread
import com.redder.parser.DialogueNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DialogueFormatterTest {
    @Test
    fun `no word is lost between the sanitizer and the chunks`() {
        val sanitizer = TextSanitizer()
        val sentence = "Well-known teachers (say, 8th grade) don\u2019t always read \u201Cfast\u201D: they re-read 30 pages\u2014slowly. "
        val runOn = List(120) { "word$it" }.joinToString(" ")
        val raw = sentence.repeat(30) + runOn + " end"
        val thread = RedditThread(post = RedditPost(id = "p", title = "A title", selftext = raw, author = "op"), comments = emptyList())
        val nodes = listOf(DialogueNode(id = "c", author = "someone", body = raw, score = 1, depth = 0, parentAuthor = "op", isOp = false))

        val chunks = DialogueFormatter(textSanitizer = sanitizer).format(thread, nodes, includeUsernames = false)

        val words = { text: String -> text.split(Regex("""\s+""")).filter { it.isNotEmpty() } }
        val expected = words("A title. ${sanitizer.sanitize(raw)} ${sanitizer.sanitize(raw)}")
        assertEquals(expected, chunks.flatMap { words(it.spokenText) })
    }

    private lateinit var formatter: DialogueFormatter
    private lateinit var sanitizer: TextSanitizer
    private lateinit var voiceAllocator: SpeakerVoiceAllocator

    @BeforeEach
    fun setUp() {
        sanitizer = TextSanitizer()
        voiceAllocator = SpeakerVoiceAllocator()
        formatter =
            DialogueFormatter(
                textSanitizer = sanitizer,
                voiceAllocator = voiceAllocator,
                maxChunkLength = 400,
            )
    }

    @ParameterizedTest
    @CsvSource(
        "cool_coder_42, cool coder",
        "coder-101, coder",
        "u/dev_pro, dev pro",
        "/u/alpha_beta, alpha beta",
        "john_doe_99, john doe",
        "user123, user",
        "12345, 12345",
        "u/42, 42",
        "u/, anonymous",
        "'', anonymous",
    )
    fun `normalizes technical reddit usernames into spoken speech`(
        input: String,
        expected: String,
    ) {
        val actual = formatter.normalizeUsername(input)
        assertEquals(expected, actual)
    }

    @Test
    fun `formats root post title as its own chunk followed by the selftext`() {
        val post =
            RedditPost(
                id = "p1",
                author = "cool_author_99",
                title = "Android 16 Audio Architecture",
                selftext = "Here is an in-depth breakdown of the new low latency audio pipeline.",
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val chunks = formatter.format(thread = thread, prunedNodes = emptyList())

        assertEquals(2, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkIndex)
            assertEquals("p1", chunk.nodeId)
            assertEquals("cool_author_99", chunk.author)
            assertTrue(chunk.isOp)
            assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, chunk.voiceId)
        }
        assertEquals("Post by cool author: Android 16 Audio Architecture.", chunks[0].spokenText)
        assertEquals("Here is an in-depth breakdown of the new low latency audio pipeline.", chunks[1].spokenText)
    }

    @Test
    fun `formats root post when title already ends in terminal punctuation without double punctuation`() {
        val post =
            RedditPost(
                id = "p2",
                author = "tech_writer",
                title = "Is ONNX runtime fast enough?",
                selftext = "Benchmarks show 0.35x real time factor on mobile devices.",
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val chunks = formatter.format(thread = thread, prunedNodes = emptyList())

        assertEquals(2, chunks.size)
        assertEquals("Post by tech writer: Is ONNX runtime fast enough?", chunks[0].spokenText)
        assertEquals("Benchmarks show 0.35x real time factor on mobile devices.", chunks[1].spokenText)
    }

    @Test
    fun `formats root post without selftext cleanly`() {
        val post =
            RedditPost(
                id = "p3",
                author = "u/news_bot_1",
                title = "Breaking news announcement.",
                selftext = "",
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val chunks = formatter.format(thread = thread, prunedNodes = emptyList())

        assertEquals(1, chunks.size)
        assertEquals(
            "Post by news bot: Breaking news announcement.",
            chunks.first().spokenText,
        )
    }

    @Test
    fun `formats top-level comment and nested replies with correct conversational prefixes`() {
        val post =
            RedditPost(
                id = "p1",
                author = "op_user",
                title = "Discussion topic",
                selftext = "Post body.",
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val nodes =
            listOf(
                DialogueNode(
                    id = "c1",
                    author = "commenter_one_10",
                    body = "This is a top-level perspective.",
                    score = 15,
                    depth = 0,
                    parentAuthor = "op_user",
                    isOp = false,
                ),
                DialogueNode(
                    id = "c2",
                    author = "reply_expert",
                    body = "I agree with your analysis.",
                    score = 8,
                    depth = 1,
                    parentAuthor = "commenter_one_10",
                    isOp = false,
                ),
                DialogueNode(
                    id = "c3",
                    author = "op_user",
                    body = "Thank you all for the feedback!",
                    score = 20,
                    depth = 2,
                    parentAuthor = "reply_expert",
                    isOp = true,
                ),
            )

        val chunks = formatter.format(thread = thread, prunedNodes = nodes)

        assertEquals(5, chunks.size)

        // Root post: title and body are separate utterances by the same speaker
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals("Post by op user: Discussion topic.", chunks[0].spokenText)
        assertEquals(1, chunks[1].chunkIndex)
        assertEquals("Post body.", chunks[1].spokenText)
        assertTrue(chunks[0].isOp && chunks[1].isOp)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, chunks[0].voiceId)
        assertEquals(chunks[0].voiceId, chunks[1].voiceId)

        // Top level comment (depth 0)
        assertEquals(2, chunks[2].chunkIndex)
        assertEquals("commenter one writes: This is a top-level perspective.", chunks[2].spokenText)
        assertFalse(chunks[2].isOp)
        assertNotEquals(chunks[0].voiceId, chunks[2].voiceId)

        // Nested reply (depth 1)
        assertEquals(3, chunks[3].chunkIndex)
        assertEquals("reply expert replies to commenter one: I agree with your analysis.", chunks[3].spokenText)
        assertFalse(chunks[3].isOp)
        assertNotEquals(chunks[2].voiceId, chunks[3].voiceId)

        // OP reply (depth 2)
        assertEquals(4, chunks[4].chunkIndex)
        assertEquals("op user replies to reply expert: Thank you all for the feedback!", chunks[4].spokenText)
        assertTrue(chunks[4].isOp)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, chunks[4].voiceId)
    }

    @Test
    fun `formats nested reply without parent author using generic reply prefix`() {
        val post = RedditPost(id = "p1", author = "author1", title = "Title", selftext = "")
        val thread = RedditThread(post = post, comments = emptyList())

        val nodes =
            listOf(
                DialogueNode(
                    id = "c1",
                    author = "anonymous_reply",
                    body = "A reply without parent attribution.",
                    score = 5,
                    depth = 1,
                    parentAuthor = null,
                    isOp = false,
                ),
            )

        val chunks = formatter.format(thread = thread, prunedNodes = nodes)
        assertEquals(2, chunks.size)
        assertEquals("anonymous reply replies: A reply without parent attribution.", chunks[1].spokenText)
    }

    @Test
    fun `skips comments whose sanitized body is completely blank or deleted`() {
        val post = RedditPost(id = "p1", author = "author1", title = "Title", selftext = "")
        val thread = RedditThread(post = post, comments = emptyList())

        val nodes =
            listOf(
                DialogueNode(
                    id = "c_deleted",
                    author = "deleted_user",
                    body = "   ```\n```  ",
                    score = 1,
                    depth = 0,
                    parentAuthor = null,
                    isOp = false,
                ),
                DialogueNode(
                    id = "c_valid",
                    author = "real_user",
                    body = "Valid spoken comment.",
                    score = 10,
                    depth = 0,
                    parentAuthor = null,
                    isOp = false,
                ),
            )

        val chunks = formatter.format(thread = thread, prunedNodes = nodes)

        // 1 for post + 1 for valid comment (deleted comment skipped)
        assertEquals(2, chunks.size)
        assertEquals("c_valid", chunks[1].nodeId)
        assertEquals("real user writes: Valid spoken comment.", chunks[1].spokenText)
    }

    @Test
    fun `splitUtterance returns single chunk when text is under max length`() {
        val text = "This is a concise sentence well within the length budget."
        val chunks = formatter.splitUtterance(text, maxChars = 400)
        assertEquals(listOf(text), chunks)
    }

    @Test
    fun `splitUtterance splits long text at natural sentence boundaries`() {
        val sentence1 = "The first observation demonstrates that on-device TTS delivers predictable low latency. "
        val sentence2 = "The second observation shows that streaming audio via ExoPlayer avoids memory buildup. "
        val sentence3 = "Finally, chunking sentences allows the player to start speaking almost immediately."
        val combined = sentence1 + sentence2 + sentence3

        // Use maxChars = 110 so that sentence 1 (89 chars) fits, but sentence 1 + sentence 2 (177 chars) exceeds
        val chunks = formatter.splitUtterance(combined, maxChars = 110)

        assertTrue(chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 110, "Chunk '$chunk' exceeded maxChars of 110")
        }
        assertEquals(sentence1.trim(), chunks[0])
        assertEquals(sentence2.trim(), chunks[1])
        assertEquals(sentence3.trim(), chunks[2])
    }

    @Test
    fun `splitUtterance safely splits oversized sentence at clause boundaries`() {
        val longClauseSentence =
            "In this comprehensive audio pipeline analysis, we first evaluated phoneme generation speed, " +
                "then we analyzed ONNX tensor runtime inference latency, " +
                "and lastly we benchmarked OpenSL ES audio playback track under-runs."

        val chunks = formatter.splitUtterance(longClauseSentence, maxChars = 110)

        for (chunk in chunks) {
            assertTrue(chunk.length <= 110, "Chunk '$chunk' exceeded maxChars of 110")
        }
        assertTrue(chunks.size >= 2)
        // Verify reconstructed content preserves all information
        val joined = chunks.joinToString(" ")
        assertTrue(joined.contains("comprehensive audio pipeline"))
        assertTrue(joined.contains("ONNX tensor runtime"))
        assertTrue(joined.contains("OpenSL ES audio"))
    }

    @Test
    fun `splitUtterance handles degenerate text without spaces or punctuation by hard splitting`() {
        val longUnbrokenToken = "A".repeat(250)
        val chunks = formatter.splitUtterance(longUnbrokenToken, maxChars = 80)

        assertEquals(4, chunks.size)
        assertEquals(80, chunks[0].length)
        assertEquals(80, chunks[1].length)
        assertEquals(80, chunks[2].length)
        assertEquals(10, chunks[3].length)
    }

    @Test
    fun `long comment carries spoken prefix only on chunk 0 while continuation chunks contain body only`() {
        val customFormatter =
            DialogueFormatter(
                textSanitizer = sanitizer,
                voiceAllocator = voiceAllocator,
                maxChunkLength = 120,
            )

        val post = RedditPost(id = "p1", author = "op", title = "Post", selftext = "")
        val thread = RedditThread(post = post, comments = emptyList())

        val commentBody =
            "First sentence explains the overall architectural concept. " +
                "Second sentence provides specific implementation details. " +
                "Third sentence summarizes all practical engineering takeaways."

        val node =
            DialogueNode(
                id = "c_long",
                author = "author_pro",
                body = commentBody,
                score = 50,
                depth = 0,
                parentAuthor = "op",
                isOp = false,
            )

        val chunks = customFormatter.format(thread = thread, prunedNodes = listOf(node))

        // Index 0 is post. Chunks 1..N belong to comment c_long.
        val commentChunks = chunks.filter { it.nodeId == "c_long" }
        assertTrue(commentChunks.size >= 2, "Expected multiple chunks for long comment, got ${commentChunks.size}")

        // Chunk 0 carries prefix
        assertTrue(commentChunks[0].spokenText.startsWith("author pro writes: First sentence explains"))

        // Subsequent chunks DO NOT carry prefix
        for (i in 1 until commentChunks.size) {
            val chunkText = commentChunks[i].spokenText
            assertFalse(
                chunkText.contains("writes:"),
                "Continuation chunk $i must not repeat the prefix: '$chunkText'",
            )
            assertTrue(
                chunkText.length <= 120,
                "Continuation chunk $i exceeded maxChunkLength of 120: ${chunkText.length}",
            )
        }

        // Verify sequential monotonic chunkIndex across the whole thread
        for (i in chunks.indices) {
            assertEquals(i, chunks[i].chunkIndex)
        }
    }

    @Test
    fun `formats thread without usernames when includeUsernames is false`() {
        val post =
            RedditPost(
                id = "p1",
                author = "cool_author_99",
                title = "Post Title",
                selftext = "Post body content.",
            )
        val thread = RedditThread(post = post, comments = emptyList())
        val node1 =
            DialogueNode(
                id = "c1",
                author = "commenter_one",
                body = "This is a top-level reply.",
                score = 10,
                depth = 0,
                parentAuthor = "cool_author_99",
                isOp = false,
            )
        val node2 =
            DialogueNode(
                id = "c2",
                author = "commenter_two",
                body = "This is a nested reply.",
                score = 5,
                depth = 1,
                parentAuthor = "commenter_one",
                isOp = false,
            )

        val chunks =
            formatter.format(
                thread = thread,
                prunedNodes = listOf(node1, node2),
                includeUsernames = false,
            )

        assertEquals(4, chunks.size)
        // Root post chunks have no "Post by..." prefix
        assertEquals("Post Title.", chunks[0].spokenText)
        assertEquals("Post body content.", chunks[1].spokenText)
        assertFalse(chunks[0].spokenText.contains("Post by"))
        assertFalse(chunks[0].spokenText.contains("cool author"))

        // Top level comment has no "writes:" prefix
        assertEquals("This is a top-level reply.", chunks[2].spokenText)
        assertFalse(chunks[2].spokenText.contains("writes:"))
        assertFalse(chunks[2].spokenText.contains("commenter one"))

        // Nested comment has no "replies to" prefix
        assertEquals("This is a nested reply.", chunks[3].spokenText)
        assertFalse(chunks[3].spokenText.contains("replies"))
        assertFalse(chunks[3].spokenText.contains("commenter two"))
    }

    @Test
    fun `long post body opens with sentence-sized pieces then returns to normal chunking`() {
        val sentences = (1..20).map { "This is sentence number $it of a fairly long post body." }
        val post =
            RedditPost(
                id = "p_long",
                author = "long_author",
                title = "A long story",
                selftext = sentences.joinToString(" "),
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val chunks = formatter.format(thread = thread, prunedNodes = emptyList())

        assertEquals("Post by long author: A long story.", chunks[0].spokenText)
        val openingBody = chunks.subList(1, DialogueFormatter.DEFAULT_FAST_START_PIECE_COUNT)
        assertEquals(3, openingBody.size)
        openingBody.forEach { assertTrue(it.spokenText.length <= DialogueFormatter.DEFAULT_FAST_START_MAX_CHARS) }
        assertTrue(chunks.drop(DialogueFormatter.DEFAULT_FAST_START_PIECE_COUNT).any { it.spokenText.length > 160 })
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkIndex)
            assertEquals("p_long", chunk.nodeId)
            assertEquals("long_author", chunk.author)
            assertTrue(chunk.isOp)
            assertTrue(chunk.spokenText.length <= 400)
        }
        assertEquals(
            sentences.joinToString(" "),
            chunks.drop(1).joinToString(" ") { it.spokenText },
        )
    }

    @Test
    fun `opening pieces carry over to the first comment when the post has no body`() {
        val post = RedditPost(id = "p_link", author = "op", title = "Link post", selftext = "")
        val longBody = (1..12).map { "Comment sentence $it is here." }.joinToString(" ")
        val node =
            DialogueNode(
                id = "c_long",
                author = "first_commenter",
                body = longBody,
                score = 10,
                depth = 0,
                parentAuthor = null,
                isOp = false,
            )
        val thread = RedditThread(post = post, comments = emptyList())

        val chunks = formatter.format(thread = thread, prunedNodes = listOf(node))

        assertEquals("Post by op: Link post", chunks[0].spokenText)
        val commentChunks = chunks.drop(1)
        assertTrue(commentChunks.first().spokenText.startsWith("first commenter writes: "))
        assertTrue(commentChunks.take(3).all { it.spokenText.length <= 160 })
        assertTrue(commentChunks.all { it.author == "first_commenter" && it.nodeId == "c_long" })
        assertEquals(1, commentChunks.map { it.voiceId }.distinct().size)
    }

    @Test
    fun `opening split can be disabled`() {
        val noFastStart = DialogueFormatter(maxChunkLength = 400, fastStartPieceCount = 0)
        val body = (1..10).map { "Sentence $it of the body." }.joinToString(" ")
        val post = RedditPost(id = "p", author = "a", title = "Title", selftext = body)

        val chunks = noFastStart.format(RedditThread(post = post, comments = emptyList()), emptyList())

        assertEquals(listOf("Post by a: Title.", body), chunks.map { it.spokenText })
    }

    @Test
    fun `splitUtterance treats closing quotes and ellipses after terminal punctuation as sentence ends`() {
        val s1 = "She told me \"we are done here.\""
        val s2 = "I waited for a reply…"
        val s3 = "Then he said he would call back later."
        val chunks = formatter.splitUtterance("$s1 $s2 $s3", maxChars = 70)

        assertEquals(listOf("$s1 $s2", s3), chunks)
    }

    @Test
    fun `splitUtterance prefers a clause boundary over a mid-sentence word break`() {
        val sentence = "When the update finally landed on my phone last week—after months of waiting around for it to ship"
        val chunks = formatter.splitUtterance(sentence, maxChars = 70)

        assertTrue(chunks[0].endsWith("week—"), chunks[0])
        assertEquals(sentence, chunks.joinToString(" ").replace("— ", "—"))
    }

    @Test
    fun `splitUtterance never splits mid-sentence when every sentence fits`() {
        val sentences = (1..30).map { "Sentence $it has a handful of words, some commas; and an ending." }
        val chunks = formatter.splitUtterance(sentences.joinToString(" "), maxChars = 400)

        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 400)
            assertTrue(chunk.endsWith("ending."), "Chunk does not end at a sentence: '$chunk'")
        }
        assertEquals(sentences.joinToString(" "), chunks.joinToString(" "))
    }

    @Test
    fun `opening pieces keep a long sentence whole instead of cutting it at a comma`() {
        val longSentence =
            "My landlord, who had ignored every single message for three weeks straight, finally showed up at " +
                "the door on a Sunday morning, holding a clipboard and a very strange smile."
        val body = "$longSentence Short one. Another short one."
        val post = RedditPost(id = "p", author = "a", title = "Title", selftext = body)

        val chunks = formatter.format(RedditThread(post = post, comments = emptyList()), emptyList())

        assertTrue(longSentence.length > DialogueFormatter.DEFAULT_FAST_START_MAX_CHARS)
        assertEquals(listOf("Post by a: Title.", longSentence, "Short one. Another short one."), chunks.map { it.spokenText })
    }
}
