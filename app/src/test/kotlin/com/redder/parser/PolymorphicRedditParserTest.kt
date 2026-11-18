package com.redder.parser

import com.redder.model.FlexibleLongSerializer
import com.redder.model.RedditComment
import com.redder.model.RedditCommentDataDto
import com.redder.model.RedditPost
import com.redder.model.RedditPostDataDto
import com.redder.model.RedditRepliesSerializer
import com.redder.model.RedditThingDto
import com.redder.model.RedditThingSerializer
import com.redder.model.RedditThread
import com.redder.model.toDto
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class PolymorphicRedditParserTest {
    private lateinit var parser: PolymorphicRedditParser

    @BeforeEach
    fun setUp() {
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
    fun `parses polymorphic more fixture and drops more pagination elements`() {
        val json = loadFixture("reddit_thread_polymorphic_more.json")
        val thread = parser.parseThread(json)

        assertNotNull(thread.post)
        assertEquals("post_xyz123", thread.post.id)
        assertEquals("android_dev_op", thread.post.author)
        assertEquals("Discussion on On-Device Neural TTS on Mobile", thread.post.title)
        assertTrue(thread.post.selftext.contains("80M+ parameter TTS models"))
        assertEquals(452, thread.post.score)
        assertEquals(85, thread.post.numComments)
        assertEquals(1726830000L, thread.post.createdUtc)

        // Top level comments should filter out the root "more" object (c_more_root)
        assertEquals(2, thread.comments.size)

        val commentAlpha1 = thread.comments[0]
        assertEquals("c_alpha1", commentAlpha1.id)
        assertEquals("perf_engineer", commentAlpha1.author)
        assertEquals(210, commentAlpha1.score)
        assertEquals(0, commentAlpha1.depth)
        assertTrue(commentAlpha1.body.contains("Piper is much more practical"))

        // Inside commentAlpha1, "more" object (c_more_sub) must be filtered out, leaving only c_beta1
        assertEquals(1, commentAlpha1.replies.size)
        val commentBeta1 = commentAlpha1.replies[0]
        assertEquals("c_beta1", commentBeta1.id)
        assertEquals("ai_researcher_99", commentBeta1.author)
        assertEquals(95, commentBeta1.score)
        assertEquals(1, commentBeta1.depth)
        assertTrue(commentBeta1.body.contains("Qualcomm QNN"))
        assertTrue(commentBeta1.replies.isEmpty())

        val commentAlpha2 = thread.comments[1]
        assertEquals("c_alpha2", commentAlpha2.id)
        assertEquals("battery_watcher", commentAlpha2.author)
        assertEquals(140, commentAlpha2.score)
        assertEquals(0, commentAlpha2.depth)
        assertTrue(commentAlpha2.body.contains("backpressure"))
        assertTrue(commentAlpha2.replies.isEmpty())
    }

    @Test
    fun `parses deep nesting and bot comments fixture down to depth 5`() {
        val json = loadFixture("reddit_thread_deep_nesting_and_bots.json")
        val thread = parser.parseThread(json)

        assertEquals("post_deep_nesting", thread.post.id)
        assertEquals("curious_reader", thread.post.author)
        assertEquals("A very long debate about Android architecture", thread.post.title)
        assertEquals(120, thread.post.score)
        assertEquals(15, thread.post.numComments)
        assertEquals(1726832000L, thread.post.createdUtc)

        // 4 top-level comments: AutoModerator, [deleted], spammer_user [removed], and c_deep_0
        assertEquals(4, thread.comments.size)

        val botComment = thread.comments[0]
        assertEquals("c_bot_sticky", botComment.id)
        assertEquals("AutoModerator", botComment.author)
        assertEquals(1, botComment.score)
        assertEquals(0, botComment.depth)
        assertTrue(botComment.replies.isEmpty())

        val deletedComment = thread.comments[1]
        assertEquals("c_deleted", deletedComment.id)
        assertEquals("[deleted]", deletedComment.author)
        assertEquals("[deleted]", deletedComment.body)
        assertEquals(0, deletedComment.score)
        assertTrue(deletedComment.replies.isEmpty())

        val removedComment = thread.comments[2]
        assertEquals("c_removed", removedComment.id)
        assertEquals("spammer_user", removedComment.author)
        assertEquals("[removed]", removedComment.body)
        assertEquals(-5, removedComment.score)
        assertTrue(removedComment.replies.isEmpty())

        val level0 = thread.comments[3]
        assertEquals("c_deep_0", level0.id)
        assertEquals(0, level0.depth)
        assertEquals(1, level0.replies.size)

        val level1 = level0.replies[0]
        assertEquals("c_deep_1", level1.id)
        assertEquals(1, level1.depth)
        assertEquals(1, level1.replies.size)

        val level2 = level1.replies[0]
        assertEquals("c_deep_2", level2.id)
        assertEquals(2, level2.depth)
        assertEquals(1, level2.replies.size)

        val level3 = level2.replies[0]
        assertEquals("c_deep_3", level3.id)
        assertEquals(3, level3.depth)
        assertEquals(1, level3.replies.size)

        val level4 = level3.replies[0]
        assertEquals("c_deep_4", level4.id)
        assertEquals(4, level4.depth)
        assertEquals(1, level4.replies.size)

        val level5 = level4.replies[0]
        assertEquals("c_deep_5", level5.id)
        assertEquals(5, level5.depth)
        assertTrue(level5.replies.isEmpty())
    }

    @Test
    fun `parses pathological markdown fixture correctly`() {
        val json = loadFixture("reddit_thread_pathological_markdown.json")
        val thread = parser.parseThread(json)

        assertEquals("post_pathological", thread.post.id)
        assertEquals("formatting_wizard", thread.post.author)
        assertEquals(380, thread.post.score)
        assertEquals(4, thread.post.numComments)
        assertEquals(1726831000L, thread.post.createdUtc)
        assertTrue(thread.post.selftext.contains("| Model | RTF | RAM | Link |"))
        assertTrue(thread.post.selftext.contains(">!Warning: Spoilers ahead"))

        assertEquals(2, thread.comments.size)

        val quoteComment = thread.comments[0]
        assertEquals("c_path1", quoteComment.id)
        assertEquals("quote_enthusiast", quoteComment.author)
        assertTrue(quoteComment.body.contains("> Kokoro is 0.85x"))
        assertTrue(quoteComment.body.contains("val x = 10"))

        val asciiComment = thread.comments[1]
        assertEquals("c_path2", asciiComment.id)
        assertEquals("ascii_artist", asciiComment.author)
        assertTrue(asciiComment.body.contains("¯\\_(ツ)_/¯ Deal with it!"))
        assertTrue(asciiComment.body.contains("***Bold and italic***"))
    }

    @Test
    fun `companion parseThread static helper parses valid JSON`() {
        val json = loadFixture("reddit_thread_polymorphic_more.json")
        val thread = PolymorphicRedditParser.parseThread(json)

        assertEquals("post_xyz123", thread.post.id)
        assertEquals(2, thread.comments.size)
    }

    @Test
    fun `parses single listing thread with no comments listing`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "single_post",
                        "author": "single_author",
                        "title": "Single Post Title",
                        "selftext": "No comments listing included",
                        "score": 10,
                        "num_comments": 0,
                        "created_utc": 1600000000
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)
        assertEquals("single_post", thread.post.id)
        assertTrue(thread.comments.isEmpty())
    }

    @Test
    fun `parses thread with empty comments listing`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "empty_comments_post",
                        "author": "author_1",
                        "title": "Title",
                        "score": 1,
                        "num_comments": 0,
                        "created_utc": 1600000000
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": []
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)
        assertEquals("empty_comments_post", thread.post.id)
        assertTrue(thread.comments.isEmpty())
    }

    @Test
    fun `handles missing optional fields with sensible defaults`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "minimal_post",
                        "author": "author_min",
                        "title": "Minimal Title"
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "id": "minimal_comment",
                        "author": "commenter_min",
                        "body": "Body text"
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)
        assertEquals("minimal_post", thread.post.id)
        assertEquals("", thread.post.selftext)
        assertEquals(0, thread.post.score)
        assertEquals(0, thread.post.numComments)
        assertEquals(0L, thread.post.createdUtc)

        assertEquals(1, thread.comments.size)
        val comment = thread.comments[0]
        assertEquals("minimal_comment", comment.id)
        assertEquals(0, comment.score)
        assertEquals(0, comment.depth)
        assertTrue(comment.replies.isEmpty())
    }

    @Test
    fun `parses created_utc as double floating point and string gracefully`() {
        val jsonDoubleUtc =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "post_double_utc",
                        "author": "author",
                        "title": "Title",
                        "created_utc": 1726830000.5
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val threadDouble = parser.parseThread(jsonDoubleUtc)
        assertEquals(1726830000L, threadDouble.post.createdUtc)

        val jsonStringUtc =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "post_string_utc",
                        "author": "author",
                        "title": "Title",
                        "created_utc": "1726830000"
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val threadString = parser.parseThread(jsonStringUtc)
        assertEquals(1726830000L, threadString.post.createdUtc)
    }

    @Test
    fun `ignores unknown thing kinds in children`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t4_message",
                      "data": { "foo": "bar" }
                    },
                    {
                      "kind": "t3",
                      "data": {
                        "id": "valid_post",
                        "author": "author",
                        "title": "Post after unknown thing"
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "unknown_future_kind",
                      "data": { "test": 123 }
                    },
                    {
                      "kind": "t1",
                      "data": {
                        "id": "c1",
                        "author": "author",
                        "body": "Valid comment",
                        "replies": ""
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)
        assertEquals("valid_post", thread.post.id)
        assertEquals(1, thread.comments.size)
        assertEquals("c1", thread.comments[0].id)
    }

    @Test
    fun `handles empty replies object or null replies`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "p1",
                        "author": "a1",
                        "title": "t1"
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "id": "c_empty_replies_obj",
                        "author": "a",
                        "body": "b",
                        "replies": {}
                      }
                    },
                    {
                      "kind": "t1",
                      "data": {
                        "id": "c_null_replies",
                        "author": "a",
                        "body": "b",
                        "replies": null
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)
        assertEquals(2, thread.comments.size)
        assertTrue(thread.comments[0].replies.isEmpty())
        assertTrue(thread.comments[1].replies.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\n\t"])
    fun `throws IllegalArgumentException on blank input`(blankJson: String) {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(blankJson)
            }
        assertTrue(ex.message!!.contains("must not be blank"))
    }

    @Test
    fun `throws IllegalArgumentException on empty JSON array`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread("[]")
            }
        assertTrue(ex.message!!.contains("must not be empty"))
    }

    @Test
    fun `throws IllegalArgumentException when JSON is not an array`() {
        val jsonObject = """{ "kind": "Listing", "data": { "children": [] } }"""
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(jsonObject)
            }
        assertTrue(ex.message!!.contains("Expected JSON array"))
    }

    @Test
    fun `throws SerializationException on invalid JSON syntax`() {
        assertThrows(SerializationException::class.java) {
            parser.parseThread("{ malformed_json: true ")
        }
    }

    @Test
    fun `throws IllegalArgumentException when listing 0 has no post`() {
        val json =
            """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "more",
                      "data": { "id": "m1" }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(json)
            }
        assertTrue(ex.message!!.contains("No post found in listing at index 0"))
    }

    @Test
    fun `bidirectional serialization of RedditRepliesSerializer and RedditThingSerializer`() {
        val sampleComment =
            RedditComment(
                id = "c_test",
                author = "tester",
                body = "hello test",
                score = 15,
                depth = 0,
                replies =
                    listOf(
                        RedditComment(
                            id = "c_sub",
                            author = "reply_author",
                            body = "reply body",
                            score = 5,
                            depth = 1,
                            replies = emptyList(),
                        ),
                    ),
            )

        // Test toDto extension
        val dto = sampleComment.toDto()
        assertEquals("c_test", dto.id)
        assertEquals(1, dto.replies.size)

        val json = PolymorphicRedditParser.DEFAULT_JSON

        // Test RedditRepliesSerializer serialization when empty -> should encode as empty string ""
        val emptyRepliesJson = json.encodeToString(RedditRepliesSerializer, emptyList())
        assertEquals("\"\"", emptyRepliesJson)

        // Test RedditRepliesSerializer serialization when non-empty -> should encode children
        val repliesJson = json.encodeToString(RedditRepliesSerializer, sampleComment.replies)
        assertTrue(repliesJson.contains("Listing"))
        assertTrue(repliesJson.contains("children"))
        assertTrue(repliesJson.contains("c_sub"))

        // Deserialize back
        val decodedReplies = json.decodeFromString(RedditRepliesSerializer, repliesJson)
        assertEquals(1, decodedReplies.size)
        assertEquals("c_sub", decodedReplies[0].id)

        // Test RedditThingSerializer serialization for each polymorphic variant
        val postThing: RedditThingDto = RedditThingDto.Post(data = RedditPostDataDto(id = "p1", title = "P"))
        val commentThing: RedditThingDto = RedditThingDto.Comment(data = RedditCommentDataDto(id = "c1"))
        val moreThing: RedditThingDto = RedditThingDto.More()
        val unknownThing: RedditThingDto = RedditThingDto.Unknown(kind = "custom_unknown")

        val postJson = json.encodeToString(RedditThingSerializer, postThing)
        val commentJson = json.encodeToString(RedditThingSerializer, commentThing)
        val moreJson = json.encodeToString(RedditThingSerializer, moreThing)
        val unknownJson = json.encodeToString(RedditThingSerializer, unknownThing)

        assertTrue(postJson.contains("\"kind\":\"t3\""))
        assertTrue(commentJson.contains("\"kind\":\"t1\""))
        assertTrue(moreJson.contains("\"kind\":\"more\""))
        assertTrue(unknownJson.contains("\"kind\":\"custom_unknown\""))

        // Deserialize each variant back
        val decodedPost = json.decodeFromString(RedditThingSerializer, postJson)
        assertTrue(decodedPost is RedditThingDto.Post)

        val decodedComment = json.decodeFromString(RedditThingSerializer, commentJson)
        assertTrue(decodedComment is RedditThingDto.Comment)

        val decodedMore = json.decodeFromString(RedditThingSerializer, moreJson)
        assertTrue(decodedMore is RedditThingDto.More)

        val decodedUnknown = json.decodeFromString(RedditThingSerializer, unknownJson)
        assertTrue(decodedUnknown is RedditThingDto.Unknown)

        // Test FlexibleLongSerializer serialization
        val longJson = json.encodeToString(FlexibleLongSerializer, 123456789L)
        assertEquals("123456789", longJson)
        val decodedLong = json.decodeFromString(FlexibleLongSerializer, longJson)
        assertEquals(123456789L, decodedLong)
    }

    @Test
    fun `test domain models copy equals and toString`() {
        val post =
            RedditPost(
                id = "p",
                author = "a",
                title = "t",
                selftext = "s",
                score = 10,
                numComments = 2,
                createdUtc = 100L,
            )
        val comment =
            RedditComment(
                id = "c",
                author = "a2",
                body = "b",
                score = 3,
                depth = 0,
            )
        val thread =
            RedditThread(
                post = post,
                comments = listOf(comment),
            )

        assertEquals("p", post.id)
        assertEquals("c", comment.id)
        assertEquals(1, thread.comments.size)

        val postCopy = post.copy(title = "new title")
        assertEquals("new title", postCopy.title)
        assertEquals("p", postCopy.id)

        val commentCopy = comment.copy(score = 99)
        assertEquals(99, commentCopy.score)

        val threadCopy = thread.copy(comments = emptyList())
        assertTrue(threadCopy.comments.isEmpty())

        assertEquals(post, post.copy())
        assertEquals(comment, comment.copy())
        assertEquals(thread, thread.copy())
        assertFalse(post == postCopy)
        assertTrue(post.hashCode() != 0)
        assertTrue(comment.hashCode() != 0)
        assertTrue(thread.hashCode() != 0)
        assertTrue(thread.toString().contains("RedditThread"))

        val json = PolymorphicRedditParser.DEFAULT_JSON
        val postJson = json.encodeToString(RedditPost.serializer(), post)
        val postDecoded = json.decodeFromString(RedditPost.serializer(), postJson)
        assertEquals(post, postDecoded)

        val threadJson = json.encodeToString(RedditThread.serializer(), thread)
        val threadDecoded = json.decodeFromString(RedditThread.serializer(), threadJson)
        assertEquals(thread, threadDecoded)

        val commentJson = json.encodeToString(RedditComment.serializer(), comment)
        val commentDecoded = json.decodeFromString(RedditComment.serializer(), commentJson)
        assertEquals(comment, commentDecoded)
    }

    @Test
    fun `flexible long serializer returns 0 for non-numeric string`() {
        val json = PolymorphicRedditParser.DEFAULT_JSON
        val decoded = json.decodeFromString(FlexibleLongSerializer, "\"invalid_timestamp\"")
        assertEquals(0L, decoded)
    }
}
