package com.redder.network

import com.redder.security.UrlSecurityValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.TimeUnit

class RedditApiClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testClient: OkHttpClient
    private lateinit var apiClient: RedditApiClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        testClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val originalHost = original.url.host
                    val rewrittenUrl =
                        original.url.newBuilder()
                            .scheme("http")
                            .host(mockWebServer.hostName)
                            .port(mockWebServer.port)
                            .build()
                    val rewrittenRequest =
                        original.newBuilder()
                            .url(rewrittenUrl)
                            .header("Host", originalHost)
                            .build()
                    chain.proceed(rewrittenRequest)
                }
                .build()

        apiClient =
            RedditApiClient(
                client = testClient,
                ioDispatcher = Dispatchers.IO,
            )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetches thread JSON successfully on HTTP 200`() =
        runTest {
            val jsonResponse = """[{"kind": "Listing", "data": {"children": []}}]"""
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/technology/comments/123/sample.json")

            assertEquals(jsonResponse, result)
            assertEquals(1, mockWebServer.requestCount)

            val recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recordedRequest)
            assertEquals("GET", recordedRequest!!.method)
            assertEquals("android:com.redder:v1.0.0", recordedRequest.getHeader("User-Agent"))
            assertEquals("red.artemislena.eu", recordedRequest.getHeader("Host"))
            assertEquals("/r/technology/comments/123/sample.json", recordedRequest.path)
        }

    @Test
    fun `sends custom User-Agent when configured`() =
        runTest {
            val customAgentClient =
                RedditApiClient(
                    client = testClient,
                    userAgent = "custom:reddit-client:v2.5.0",
                )
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            val result =
                customAgentClient.fetchThreadJson("https://www.reddit.com/r/news/comments/456/title.json")

            assertEquals("{}", result)
            val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("custom:reddit-client:v2.5.0", recorded!!.getHeader("User-Agent"))
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://evil.com/r/technology/comments/123.json",
            "http://www.reddit.com/r/technology/comments/123.json",
            "https://127.0.0.1/r/technology/comments/123.json",
            "https://localhost/r/technology/comments/123.json",
            "https://169.254.169.254/latest/meta-data/",
            "https://attacker@reddit.com/r/tech/comments/1.json",
            "https://evil-reddit.com/post.json",
            "ftp://reddit.com/comments/123.json",
            "not a url",
            "",
            "   ",
        ],
    )
    fun `throws SecurityException on unpermitted or invalid URLs without network access`(unpermittedUrl: String) =
        runTest {
            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        apiClient.fetchThreadJson(unpermittedUrl)
                    }
                }

            assertTrue(
                exception.message!!.startsWith("URL is not a permitted Reddit URL:"),
                "Expected security rejection for $unpermittedUrl",
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `successfully reads payload of exactly 5 MB`() =
        runTest {
            val exactBufferSize = 5 * 1024 * 1024 // 5,242,880 bytes
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            val exactBuffer = Buffer()
            val marker = "<h1 class=\"post_title\">"
            exactBuffer.writeUtf8(marker)
            exactBuffer.write(chunk, 0, chunk.size - marker.length)
            val chunkCount = exactBufferSize / chunk.size
            for (i in 1 until chunkCount) {
                exactBuffer.write(chunk)
            }

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(exactBuffer),
            )

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/test/comments/5mb.json")

            assertEquals(exactBufferSize, result.length)
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws PayloadTooLargeException when Content-Length header exceeds 5 MB without failover`() =
        runTest {
            val oversizedLength = RedditApiClient.MAX_PAYLOAD_BYTES + 1L
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", oversizedLength.toString()),
            )

            val exception =
                assertThrows(PayloadTooLargeException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        apiClient.fetchThreadJson("https://www.reddit.com/r/test/comments/huge_cl.json")
                    }
                }

            assertTrue(
                exception.message!!.contains("exceeds limit of ${RedditApiClient.MAX_PAYLOAD_BYTES} bytes"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws PayloadTooLargeException on chunked stream exceeding 5 MB and aborts early`() =
        runTest {
            val chunk = ByteArray(64 * 1024) { 'B'.code.toByte() }
            val chunkedBuffer = Buffer()
            // 81 chunks * 64 KB = 5,308,416 bytes (> 5,242,880 bytes)
            for (i in 0 until 81) {
                chunkedBuffer.write(chunk)
            }

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody(chunkedBuffer, 64 * 1024),
            )

            val exception =
                assertThrows(PayloadTooLargeException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        apiClient.fetchThreadJson("https://www.reddit.com/r/test/comments/chunked_large.json")
                    }
                }

            assertTrue(
                exception.message!!.contains("exceeded limit of ${RedditApiClient.MAX_PAYLOAD_BYTES} bytes"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `createDefaultOkHttpClient sets 5000ms connect and 8000ms read timeouts`() {
        val defaultOkHttpClient = RedditApiClient.createDefaultOkHttpClient()

        assertEquals(5000, defaultOkHttpClient.connectTimeoutMillis)
        assertEquals(8000, defaultOkHttpClient.readTimeoutMillis)
    }

    @Test
    fun `default constructor instantiates cleanly`() {
        val defaultClient = RedditApiClient()
        assertNotNull(defaultClient)
    }

    @Test
    fun `fetchThreadJson rethrows CancellationException from execute call immediately`() =
        runTest {
            val cancelingClient =
                testClient.newBuilder()
                    .addInterceptor { throw CancellationException("Network call cancelled") }
                    .build()
            val client = RedditApiClient(client = cancelingClient)

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    client.fetchThreadJson("https://www.reddit.com/r/test/comments/cancel_exec.json")
                }
            }
        }

    @Test
    fun `mirror list is the single source of truth for URL permission`() {
        assertEquals(
            listOf("red.artemislena.eu", "safereddit.com", "reddit.rtrace.io", "redlib.catsarch.com"),
            RedditApiClient.COMMUNITY_MIRRORS,
        )
        assertEquals(RedditApiClient.COMMUNITY_MIRRORS.toSet(), UrlSecurityValidator.COMMUNITY_MIRROR_HOSTS)
        RedditApiClient.COMMUNITY_MIRRORS.forEach { host ->
            assertTrue(UrlSecurityValidator.isPermittedUrl("https://$host/r/test/comments/1/t"), host)
        }
        assertFalse(UrlSecurityValidator.isPermittedUrl("https://redlib.privacyredirect.com/r/test/comments/1/t"))
        assertFalse(UrlSecurityValidator.isPermittedUrl("https://redlib.cow.rip/r/test/comments/1/t"))
    }

    @Test
    fun `routes reddit com URL to first community mirror without Authorization header`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/technology/comments/123/sample.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(1, mockWebServer.requestCount)

            val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("GET", recorded!!.method)
            assertEquals("red.artemislena.eu", recorded.getHeader("Host"))
            assertEquals(null, recorded.getHeader("Authorization"))
            assertEquals("/r/technology/comments/123/sample.json", recorded.path)
        }

    @Test
    fun `preserves mirror host when inbound URL is already a community mirror`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://reddit.rtrace.io/r/science/comments/456/title.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(1, mockWebServer.requestCount)

            val recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("reddit.rtrace.io", recorded!!.getHeader("Host"))
        }

    @Test
    fun `fails over from primary mirror to second mirror on HTTP 429`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(2, mockWebServer.requestCount)

            val req1 = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("red.artemislena.eu", req1!!.getHeader("Host"))

            val req2 = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("safereddit.com", req2!!.getHeader("Host"))
        }

    @Test
    fun `fails over through mirrors in order on HTTP 502`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway 1"))
            mockWebServer.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway 2"))
            mockWebServer.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway 3"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(4, mockWebServer.requestCount)

            val hosts = (1..4).map { mockWebServer.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Host") }
            assertEquals(RedditApiClient.COMMUNITY_MIRRORS, hosts)
        }

    @Test
    fun `fails over to next mirror when network IOException occurs on first mirror`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(2, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException when all community mirrors fail`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(429))
            mockWebServer.enqueue(MockResponse().setResponseCode(502))
            mockWebServer.enqueue(MockResponse().setResponseCode(404))
            mockWebServer.enqueue(MockResponse().setResponseCode(500))

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")
                    }
                }

            assertTrue(exception.message!!.contains("error from mirror redlib.catsarch.com"))
            assertEquals(4, mockWebServer.requestCount)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<!DOCTYPE html><html><head><title>Making sure you're not a bot!</title></head></html>",
            "<!doctype html><html><head><title>Making sure you&#39;re not a bot!</title></head></html>",
            "<!doctype html><html><head><title>Making sure you&#x27;re not a bot!</title></head></html>",
            "<html><head><script id=\"anubis_version\" type=\"application/json\">\"1.0\"</script></head></html>",
            "<html><body><img src=\"/.within.website/x/cmd/anubis/static/img/pensive.webp\"></body></html>",
            "<html><body><h1 class=\"post_title\">x</h1><p>Reddit rate limit exceeded</p></body></html>",
            "<html><body><h1 class=\"post_title\">x</h1><p>Failed to parse page JSON data: expected value</p></body></html>",
        ],
    )
    fun `200 challenge or Redlib error page fails over to next mirror`(badPage: String) =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(badPage))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(2, mockWebServer.requestCount)
        }

    @Test
    fun `200 HTML page without a post fails over to next mirror`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Nothing here</body></html>"))
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(THREAD_HTML))

            val result =
                apiClient.fetchThreadJson("https://www.reddit.com/r/news/comments/789/update.json")

            assertEquals(THREAD_HTML, result)
            assertEquals(2, mockWebServer.requestCount)
        }

    @Test
    fun `empty bodies on every mirror throw IOException`() =
        runTest {
            repeat(RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(""))
            }

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        apiClient.fetchThreadJson("https://www.reddit.com/r/test/comments/empty.json")
                    }
                }

            assertTrue(exception.message!!.contains("No post found"))
            assertEquals(RedditApiClient.COMMUNITY_MIRRORS.size, mockWebServer.requestCount)
        }

    @Test
    fun `isBotChallengeOrBlocked detects Anubis entity variants and Redlib errors but not normal threads`() {
        assertTrue(RedditApiClient.isBotChallengeOrBlocked("Making sure you&#39;re not a bot!"))
        assertTrue(RedditApiClient.isBotChallengeOrBlocked("Making sure you&#x27;re not a bot!"))
        assertTrue(RedditApiClient.isBotChallengeOrBlocked("<script id=\"anubis_challenge\"></script>"))
        assertTrue(RedditApiClient.isBotChallengeOrBlocked("Reddit rate limit exceeded"))
        assertTrue(RedditApiClient.isBotChallengeOrBlocked("Failed to parse page JSON data"))
        assertFalse(RedditApiClient.isBotChallengeOrBlocked(THREAD_HTML))
        assertFalse(RedditApiClient.isBotChallengeOrBlocked("<h1 class=\"post_title\">Statue of Anubis found</h1>"))
        assertFalse(RedditApiClient.isBotChallengeOrBlocked(""))
    }

    @Test
    fun `looksLikeThread accepts Redlib posts and JSON listings only`() {
        assertTrue(RedditApiClient.looksLikeThread(THREAD_HTML))
        assertTrue(RedditApiClient.looksLikeThread("""  [{"kind": "Listing"}]"""))
        assertFalse(RedditApiClient.looksLikeThread(""))
        assertFalse(RedditApiClient.looksLikeThread("<html><body>Not found</body></html>"))
    }

    companion object {
        private const val THREAD_HTML =
            """<html><body><div class="post"><h1 class="post_title">Mirror Post</h1></div></body></html>"""
    }

    @Test
    fun `listing asks for hot by name and passes the next page token`() =
        runTest {
            val page = """<div id="posts"></div>"""
            mockWebServer.enqueue(MockResponse().setBody(page))
            mockWebServer.enqueue(MockResponse().setBody(page))

            assertEquals(page, apiClient.fetchSubredditListing("AskReddit"))
            assertEquals("/r/AskReddit/hot", mockWebServer.takeRequest().path)
            apiClient.fetchSubredditListing("AskReddit", "t3_abc")
            assertEquals("/r/AskReddit/hot?t=&after=t3_abc", mockWebServer.takeRequest().path)
        }

    @Test
    fun `listing fails over past bot pages and error pages`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setBody("<html>Making sure you're not a bot</html>"))
            mockWebServer.enqueue(MockResponse().setResponseCode(503))
            mockWebServer.enqueue(MockResponse().setBody("<html>no listing here</html>"))
            mockWebServer.enqueue(MockResponse().setBody("""<h2 class="post_title">x</h2>"""))

            assertEquals("""<h2 class="post_title">x</h2>""", apiClient.fetchSubredditListing("AskReddit"))
            assertEquals(4, mockWebServer.requestCount)
        }

    @Test
    fun `listing throws when every mirror fails and refuses invalid names`() =
        runTest {
            repeat(RedditApiClient.COMMUNITY_MIRRORS.size) { mockWebServer.enqueue(MockResponse().setResponseCode(404)) }

            var failure: Exception? = null
            try {
                apiClient.fetchSubredditListing("AskReddit")
            } catch (e: IOException) {
                failure = e
            }
            assertNotNull(failure)

            var refused: Exception? = null
            try {
                apiClient.fetchSubredditListing("../admin")
            } catch (e: IllegalArgumentException) {
                refused = e
            }
            assertNotNull(refused)
        }
}
