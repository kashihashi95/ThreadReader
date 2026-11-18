package com.redder.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedlibHtmlParserTest {
    private val parser = RedlibHtmlParser()

    @Test
    fun `parses standard Redlib HTML thread with post title author body and score`() {
        val html =
            """
            <!DOCTYPE html>
            <html lang="en">
            <head><title>Ignored Head Title</title></head>
            <body>
              <div class="post" id="post_123">
                <h1 class="post_title">Building an Offline Audio Player with Kotlin &amp; Media3</h1>
                <div class="post_meta">
                  Posted by <a class="post_author" href="/u/kotlin_guru">kotlin_guru</a>
                  <span class="post_score">128 points</span>
                </div>
                <div class="post_body">
                  <p>Here is the detailed guide on how to build it.</p>
                  <p>It works completely offline!</p>
                </div>
              </div>
              <div class="comments">
                <div class="comment" id="c_1">
                  <a class="comment_author" href="/u/audio_fan">audio_fan</a>
                  <span class="comment_score">35 points</span>
                  <div class="comment_body">
                    Great tutorial, thanks for sharing!
                  </div>
                </div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals("Building an Offline Audio Player with Kotlin & Media3", thread.post.title)
        assertEquals("kotlin_guru", thread.post.author)
        assertTrue(thread.post.selftext.contains("Here is the detailed guide on how to build it."))
        assertTrue(thread.post.selftext.contains("It works completely offline!"))
        assertEquals(128, thread.post.score)
        assertEquals(1, thread.post.numComments)
        assertEquals(1, thread.comments.size)

        val comment = thread.comments[0]
        assertEquals("c_1", comment.id)
        assertEquals("audio_fan", comment.author)
        assertEquals(35, comment.score)
        assertEquals(0, comment.depth)
        assertEquals("Great tutorial, thanks for sharing!", comment.body)
        assertTrue(comment.replies.isEmpty())
    }

    @Test
    fun `parses nested comments with correct hierarchical depths`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="post">
                <h1 class="post_title">Discussion Thread</h1>
                <a class="post_author">op_user</a>
                <div class="post_body">Body text</div>
              </div>
              <div class="comments">
                <div class="comment" id="c_root1">
                  <a class="comment_author">root_user1</a>
                  <span class="comment_score">10</span>
                  <div class="comment_body">Root comment 1</div>
                  <div class="replies">
                    <div class="comment" id="c_child1">
                      <a class="comment_author">child_user1</a>
                      <span class="comment_score">5</span>
                      <div class="comment_body">Direct reply to root 1</div>
                      <div class="replies">
                        <div class="comment" id="c_grandchild1">
                          <a class="comment_author">grandchild_user1</a>
                          <span class="comment_score">2</span>
                          <div class="comment_body">Grandchild reply</div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                <div class="comment" id="c_root2">
                  <a class="comment_author">root_user2</a>
                  <span class="comment_score">-4</span>
                  <div class="comment_body">Root comment 2</div>
                </div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals(4, thread.post.numComments)
        assertEquals(2, thread.comments.size)

        val root1 = thread.comments[0]
        assertEquals("c_root1", root1.id)
        assertEquals("root_user1", root1.author)
        assertEquals(0, root1.depth)
        assertEquals(10, root1.score)
        assertEquals(1, root1.replies.size)

        val child1 = root1.replies[0]
        assertEquals("c_child1", child1.id)
        assertEquals("child_user1", child1.author)
        assertEquals(1, child1.depth)
        assertEquals(5, child1.score)
        assertEquals(1, child1.replies.size)

        val grandchild1 = child1.replies[0]
        assertEquals("c_grandchild1", grandchild1.id)
        assertEquals("grandchild_user1", grandchild1.author)
        assertEquals(2, grandchild1.depth)
        assertEquals(2, grandchild1.score)
        assertTrue(grandchild1.replies.isEmpty())

        val root2 = thread.comments[1]
        assertEquals("c_root2", root2.id)
        assertEquals("root_user2", root2.author)
        assertEquals(0, root2.depth)
        assertEquals(-4, root2.score)
        assertTrue(root2.replies.isEmpty())
    }

    @Test
    fun `properly unescapes HTML entities in post title body and comment text`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head><title>Ignored</title></head>
            <body>
              <div class="post">
                <h1 class="post_title">Tom &amp; Jerry &#39;Special&#39; &quot;Edition&quot; &lt;Classic&gt;</h1>
                <a class="post_author">author_&#x27;test</a>
                <div class="post_body">
                  Price: $10 &ndash; $20 &mdash; Don&#39;t miss out &hellip;
                  Entity test: &#65;&#66;&#67; and &#x44;&#x45;&#x46;
                </div>
              </div>
              <div class="comments">
                <div class="comment" id="c_test">
                  <a class="comment_author">user_1</a>
                  <span class="comment_score">10</span>
                  <div class="comment_body">
                    It&apos;s &quot;super&quot; &amp; &lt;awesome&gt;!
                  </div>
                </div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals("Tom & Jerry 'Special' \"Edition\" <Classic>", thread.post.title)
        assertEquals("author_'test", thread.post.author)
        assertTrue(thread.post.selftext.contains("Price: $10 – $20 — Don't miss out …"))
        assertTrue(thread.post.selftext.contains("Entity test: ABC and DEF"))

        val comment = thread.comments[0]
        assertEquals("It's \"super\" & <awesome>!", comment.body)
    }

    @Test
    fun `falls back to title tag when h1 post_title is not present`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <head><title>Kotlin Coroutines Guide - Redlib</title></head>
            <body>
              <div class="post">
                <a class="post_author">author1</a>
                <div class="post_body">Guide content</div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals("Kotlin Coroutines Guide", thread.post.title)
        assertEquals("author1", thread.post.author)
        assertEquals("Guide content", thread.post.selftext)
    }

    @Test
    fun `extracts author from post_meta when post_author anchor is not present`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="post">
                <h1 class="post_title">Post Title</h1>
                <div class="post_meta">
                  Submitted 2 hours ago by u/meta_detective to r/android
                </div>
                <div class="post_body">Content</div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals("meta_detective", thread.post.author)
    }

    @Test
    fun `delegates to PolymorphicRedditParser when input starts with array bracket`() {
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
                        "id": "t3_json_post",
                        "author": "json_author",
                        "title": "JSON Thread Title",
                        "selftext": "Self text from json",
                        "score": 42,
                        "num_comments": 1,
                        "created_utc": 1726830000
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
                        "id": "t1_json_comm",
                        "author": "json_commenter",
                        "body": "Comment from json",
                        "score": 10,
                        "depth": 0,
                        "replies": ""
                      }
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        val thread = parser.parseThread(json)

        assertEquals("t3_json_post", thread.post.id)
        assertEquals("json_author", thread.post.author)
        assertEquals("JSON Thread Title", thread.post.title)
        assertEquals(1, thread.comments.size)
        assertEquals("t1_json_comm", thread.comments[0].id)
    }

    @Test
    fun `delegates to PolymorphicRedditParser when input starts with object brace`() {
        val jsonObject =
            """
            {
              "kind": "Listing",
              "data": {
                "children": [
                  {
                    "kind": "t3",
                    "data": {
                      "id": "t3_single",
                      "author": "author_single",
                      "title": "Single Listing Title",
                      "selftext": "Text single",
                      "score": 5
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val thread = parser.parseThread(jsonObject)

        assertEquals("t3_single", thread.post.id)
        assertEquals("Single Listing Title", thread.post.title)
    }

    @Test
    fun `throws IllegalArgumentException when input is blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseThread("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseThread("   \n\t  ")
        }
    }

    @Test
    fun `handles comments without replies or empty comments section gracefully`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="post">
                <h1 class="post_title">Post With Zero Comments</h1>
                <a class="post_author">lonely_poster</a>
                <div class="post_body">No comments yet!</div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals("Post With Zero Comments", thread.post.title)
        assertEquals("lonely_poster", thread.post.author)
        assertEquals(0, thread.post.numComments)
        assertTrue(thread.comments.isEmpty())
    }

    @Test
    fun `handles hidden or negative comment scores correctly`() {
        val html =
            """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="post">
                <h1 class="post_title">Scores Test</h1>
                <a class="post_author">author</a>
              </div>
              <div class="comments">
                <div class="comment" id="c_hidden">
                  <a class="comment_author">user_h</a>
                  <span class="comment_score">[score hidden]</span>
                  <div class="comment_body">Hidden score comment</div>
                </div>
                <div class="comment" id="c_neg">
                  <a class="comment_author">user_n</a>
                  <span class="comment_score">-42 points</span>
                  <div class="comment_body">Downvoted comment</div>
                </div>
              </div>
            </body>
            </html>
            """.trimIndent()

        val thread = parser.parseThread(html)

        assertEquals(2, thread.comments.size)
        assertEquals(0, thread.comments[0].score)
        assertEquals(-42, thread.comments[1].score)
    }

    @Test
    fun `throws IllegalArgumentException when HTML is Reddit bot challenge page`() {
        val botHtml =
            """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Making sure you're not a bot!</title>
            </head>
            <body>
              <div>Please solve this captcha</div>
            </body>
            </html>
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(botHtml)
            }
        assertTrue(exception.message!!.contains("Bot challenge or security check detected"))
    }

    @Test
    fun `throws IllegalArgumentException when HTML is blocked by network security page`() {
        val blockedHtml =
            """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Blocked by network security</title>
            </head>
            <body>
              <p>You've been blocked by network security.</p>
            </body>
            </html>
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(blockedHtml)
            }
        assertTrue(exception.message!!.contains("Bot challenge or security check detected"))
    }

    @Test
    fun `throws IllegalArgumentException when post container is missing`() {
        val missingContainerHtml =
            """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Some Random Page - Redlib</title>
            </head>
            <body>
              <div>No post container here</div>
            </body>
            </html>
            """.trimIndent()

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseThread(missingContainerHtml)
            }
        assertTrue(exception.message!!.contains("missing post container"))
    }
}
