package com.redder.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.TimeUnit

class RedditUrlResolverTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testClient: OkHttpClient
    private lateinit var resolver: RedditUrlResolver

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

        resolver = RedditUrlResolver(client = testClient, ioDispatcher = Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `resolves standard Reddit thread URL and appends json extension`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title/",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `resolves standard thread URL without trailing slash`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `strips tracking query parameters while appending json extension`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title/" +
                        "?utm_source=share&utm_medium=web2x&utm_campaign=daily&utm_term=test&utm_content=post" +
                        "&ref=share_btn&ref_source=android&share_id=sh123&si=si456&sh=sh789" +
                        "&rdt_cid=rdt1&fbclid=fb2&gclid=gc3&msclkid=ms4&mc_eid=mc5&igshid=ig6&_ga=ga7" +
                        "&_hsenc=hs8&_hsmi=hs9&dclid=dc10",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `preserves non-tracking query parameters when resolving URL`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/AskReddit/comments/12345/sample_question/" +
                        "?sort=top&limit=50&context=3&depth=2&utm_source=share&ref=share_btn",
                )

            assertEquals(
                "https://www.reddit.com/r/AskReddit/comments/12345/sample_question.json" +
                    "?sort=top&limit=50&context=3&depth=2",
                canonicalUrl,
            )
        }

    @Test
    fun `strips URL fragment from canonical output`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title/#comment-section-123",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `strips URL fragment while preserving non-tracking query parameters`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title/" +
                        "?sort=new&utm_source=share#comment-789",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json?sort=new",
                canonicalUrl,
            )
        }

    @Test
    fun `follows redd-it shortlink redirect to canonical permalink`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .setHeader(
                        "Location",
                        "https://www.reddit.com/r/technology/comments/xyz123/canonical_post/",
                    ),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://redd.it/xyz123")

            assertEquals(
                "https://www.reddit.com/r/technology/comments/xyz123/canonical_post.json",
                canonicalUrl,
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `follows reddit-com-s shortlink redirect to canonical permalink`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader(
                        "Location",
                        "https://www.reddit.com/r/science/comments/abc789/major_discovery/",
                    ),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/AbCdEf123")

            assertEquals(
                "https://www.reddit.com/r/science/comments/abc789/major_discovery.json",
                canonicalUrl,
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `resolves relative redirect Location header`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/r/technology/comments/xyz123/canonical_title/"),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/s/Rel12345")

            assertEquals(
                "https://www.reddit.com/r/technology/comments/xyz123/canonical_title.json",
                canonicalUrl,
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `resolves relative redirect Location preserving query parameters`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .setHeader(
                        "Location",
                        "/r/android/comments/999/update/?sort=top&utm_source=redirect",
                    ),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/s/999")

            assertEquals(
                "https://www.reddit.com/r/android/comments/999/update.json?sort=top",
                canonicalUrl,
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `allows exactly 5 redirect hops and resolves canonical URL`() =
        runTest {
            for (i in 1..4) {
                mockWebServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/s/step$i"),
                )
            }
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/r/test/comments/step5/"),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/s/step0")

            assertEquals(
                "https://www.reddit.com/r/test/comments/step5.json",
                canonicalUrl,
            )
            assertEquals(5, mockWebServer.requestCount)
        }

    @Test
    fun `throws IllegalStateException when redirect hops exceed limit of 5`() =
        runTest {
            for (i in 1..6) {
                mockWebServer.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/s/hop$i"),
                )
            }

            val exception =
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/s/start")
                    }
                }

            assertEquals("Too many redirects: exceeded limit of 5 hops", exception.message)
            assertEquals(6, mockWebServer.requestCount)
        }

    @Test
    fun `throws IllegalStateException on cyclical redirect loop exceeding 5 hops`() =
        runTest {
            for (i in 1..6) {
                val nextTarget = if (i % 2 == 1) "/r/test/loop_b/" else "/r/test/loop_a/"
                mockWebServer.enqueue(
                    MockResponse()
                        .setResponseCode(301)
                        .setHeader("Location", nextTarget),
                )
            }

            val exception =
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/r/test/loop_a/")
                    }
                }

            assertEquals("Too many redirects: exceeded limit of 5 hops", exception.message)
        }

    @Test
    fun `throws SecurityException when redirect targets localhost intranet`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://127.0.0.1/admin/secrets"),
            )

            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/abc")
                    }
                }

            assertTrue(
                exception.message!!.contains("Redirect to unpermitted target: http://127.0.0.1/admin/secrets"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws SecurityException when redirect targets AWS cloud metadata IP`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://169.254.169.254/latest/meta-data/"),
            )

            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/abc")
                    }
                }

            assertTrue(
                exception.message!!.contains("Redirect to unpermitted target: https://169.254.169.254/latest/meta-data/"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws SecurityException when redirect targets malicious phishing domain`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil-reddit.com/phish/"),
            )

            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/abc")
                    }
                }

            assertTrue(
                exception.message!!.contains("Redirect to unpermitted target: https://evil-reddit.com/phish/"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws SecurityException when redirect targets non-https scheme`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://www.reddit.com/r/technology/comments/123/title/"),
            )

            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/s/abc")
                    }
                }

            assertTrue(
                exception.message!!.contains("Redirect to unpermitted target: http://www.reddit.com/r/technology/comments/123/title/"),
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws SecurityException when redirect contains userinfo injection`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://attacker@www.reddit.com/r/test/comments/123/"),
            )

            assertThrows(SecurityException::class.java) {
                kotlinx.coroutines.runBlocking {
                    resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/abc")
                }
            }
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException when redirect response is missing Location header`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(302))

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://redd.it/abc")
                    }
                }

            assertTrue(exception.message!!.contains("HTTP 302 redirect missing Location header"))
        }

    @Test
    fun `throws IOException when redirect response has unresolvable Location header`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://:80"),
            )

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://redd.it/abc")
                    }
                }

            assertTrue(exception.message!!.contains("Unable to resolve redirect location 'http://:80'"))
        }

    @Test
    fun `throws IOException on HTTP 404 response for non-thread URL`() =
        runTest {
            // The original request plus one retry per community mirror all return 404.
            repeat(1 + RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setResponseCode(404))
            }

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/r/technology/")
                    }
                }

            // The last mirror's failure is rethrown.
            assertTrue(
                exception.message!!.contains(
                    "HTTP 404 received while resolving URL: https://${RedditApiClient.COMMUNITY_MIRRORS.last()}/r/technology/",
                ),
                exception.message,
            )
            assertEquals(1 + RedditApiClient.COMMUNITY_MIRRORS.size, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException on HTTP 500 server error response for non-thread URL`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://redd.it/50000")
                    }
                }

            assertTrue(exception.message!!.contains("HTTP 500 received while resolving URL: https://redd.it/50000"))
        }

    @ParameterizedTest
    @ValueSource(ints = [404, 500, 503])
    fun `resolves comments url without requesting it even when server would return HTTP error`(code: Int) =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(code))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/99999/blocked/",
                )

            assertEquals("https://www.reddit.com/r/technology/comments/99999/blocked.json", canonicalUrl)
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException when connection is severed unexpectedly`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    resolver.resolveToCanonicalJsonUrl("https://redd.it/abc123")
                }
            }
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException when request times out`() =
        runTest {
            val timeoutClient =
                testClient.newBuilder()
                    .readTimeout(50, TimeUnit.MILLISECONDS)
                    .build()
            val timeoutResolver = RedditUrlResolver(client = timeoutClient, ioDispatcher = Dispatchers.IO)

            // The original request plus one retry per community mirror all time out.
            repeat(1 + RedditApiClient.COMMUNITY_MIRRORS.size) {
                mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            }

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    timeoutResolver.resolveToCanonicalJsonUrl("https://www.reddit.com/r/technology/s/abc123")
                }
            }
            assertEquals(1 + RedditApiClient.COMMUNITY_MIRRORS.size, mockWebServer.requestCount)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "   ",
            "\t\n",
        ],
    )
    fun `throws IllegalArgumentException when initial URL is blank`(blankUrl: String) =
        runTest {
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl(blankUrl)
                    }
                }

            assertEquals("URL cannot be blank", exception.message)
            assertEquals(0, mockWebServer.requestCount)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not a url",
            "http://reddit.com/r/technology",
            "ftp://reddit.com/r/technology",
            "file:///etc/passwd",
            "https://evil-reddit.com/r/technology",
            "https://127.0.0.1/r/technology",
            "https://localhost/r/technology",
            "https://169.254.169.254/latest/meta-data/",
            "https://user:pass@reddit.com/r/technology",
            "https://reddit.com@evil.com",
            "https://rеddit.com",
        ],
    )
    fun `throws SecurityException when initial URL is unpermitted or malicious`(invalidUrl: String) =
        runTest {
            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl(invalidUrl)
                    }
                }

            assertTrue(
                exception.message!!.startsWith("URL is not a permitted Reddit URL:"),
                "Expected rejection for $invalidUrl, got: ${exception.message}",
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `normalizes URL already ending with json without duplication`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `normalizes URL ending with json and trailing slash`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title.json/",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `normalizes URL with multiple trailing slashes`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/12345/sample_title///",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `normalizes root domain URL to slash dot json`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/")

            assertEquals("https://www.reddit.com/.json", canonicalUrl)
        }

    @Test
    fun `normalizes subreddit listing URL to dot json`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://www.reddit.com/r/technology/")

            assertEquals("https://www.reddit.com/r/technology.json", canonicalUrl)
        }

    @Test
    fun `preserves path segment containing json substring`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/json_format/comments/123/learn_json/",
                )

            assertEquals(
                "https://www.reddit.com/r/json_format/comments/123/learn_json.json",
                canonicalUrl,
            )
        }

    @Test
    fun `strips tracking parameters with uppercase casing`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/technology/comments/123/title/?UTM_SOURCE=share&SORT=top",
                )

            assertEquals(
                "https://www.reddit.com/r/technology/comments/123/title.json?SORT=top",
                canonicalUrl,
            )
        }

    @Test
    fun `default constructor instantiates cleanly`() {
        val defaultResolver = RedditUrlResolver()
        assertNotNull(defaultResolver)
    }

    @Test
    fun `requests include Reddit user agent header`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://www.reddit.com/r/technology/comments/12345/sample_title/"),
            )

            resolver.resolveToCanonicalJsonUrl("https://redd.it/12345")

            val recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recordedRequest)
            assertEquals("android:com.redder:v1.0.0", recordedRequest!!.getHeader("User-Agent"))
        }

    @Test
    fun `custom user agent header is sent when configured`() =
        runTest {
            val customResolver =
                RedditUrlResolver(
                    client = testClient,
                    ioDispatcher = Dispatchers.IO,
                    userAgent = "custom:user:agent",
                )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://www.reddit.com/r/technology/comments/12345/sample_title/"),
            )

            customResolver.resolveToCanonicalJsonUrl("https://redd.it/12345")

            val recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(recordedRequest)
            assertEquals("custom:user:agent", recordedRequest!!.getHeader("User-Agent"))
        }

    @Test
    fun `resolves comments url without requesting it when server would return HTTP 429`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(429))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/",
                )

            assertEquals(
                "https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `resolves comments url without requesting it when server would return HTTP 403`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(403))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3/",
                )

            assertEquals(
                "https://www.reddit.com/r/androiddev/comments/xyz123/kotlin_media3.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `throws IOException when shortlink without comments path returns HTTP 429`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(429))

            val exception =
                assertThrows(IOException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://redd.it/xyz123")
                    }
                }

            assertTrue(exception.message!!.contains("HTTP 429 received while resolving URL"))
        }

    @Test
    fun `stops at comments redirect without requesting comments url that would return HTTP 429`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .setHeader("Location", "https://www.reddit.com/r/androiddev/comments/xyz123/target_post/"),
            )
            mockWebServer.enqueue(MockResponse().setResponseCode(429))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://redd.it/xyz123")

            assertEquals(
                "https://www.reddit.com/r/androiddev/comments/xyz123/target_post.json",
                canonicalUrl,
            )
            assertEquals(1, mockWebServer.requestCount)
        }

    @Test
    fun `resolves community mirror URL to canonical json url preserving mirror host`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://safereddit.com/r/technology/comments/12345/sample_title/",
                )

            assertEquals(
                "https://safereddit.com/r/technology/comments/12345/sample_title.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `resolves other permitted mirror hosts including redlib and catsarch`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://reddit.rtrace.io/r/science/comments/456/title/",
                )

            assertEquals(
                "https://reddit.rtrace.io/r/science/comments/456/title.json",
                canonicalUrl,
            )
        }

    @Test
    fun `resolves mirror comments url without requesting it when server would return HTTP 429`() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(429))

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://safereddit.com/r/androiddev/comments/xyz/post/",
                )

            assertEquals(
                "https://safereddit.com/r/androiddev/comments/xyz/post.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `comments URL resolves with zero network requests`() =
        runTest {
            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl(
                    "https://www.reddit.com/r/BestofRedditorUpdates/comments/abc123/some_update/",
                )

            assertEquals(
                "https://www.reddit.com/r/BestofRedditorUpdates/comments/abc123/some_update.json",
                canonicalUrl,
            )
            assertEquals(0, mockWebServer.requestCount)
        }

    @Test
    fun `falls back to first mirror when reddit share link request times out`() =
        runTest {
            val timeoutClient =
                testClient.newBuilder()
                    .readTimeout(200, TimeUnit.MILLISECONDS)
                    .build()
            val timeoutResolver = RedditUrlResolver(client = timeoutClient, ioDispatcher = Dispatchers.IO)

            mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://red.artemislena.eu/r/x/comments/id/slug/"),
            )

            val canonicalUrl =
                timeoutResolver.resolveToCanonicalJsonUrl("https://www.reddit.com/r/BestofRedditorUpdates/s/UyxzNkRPd5")

            assertEquals("https://red.artemislena.eu/r/x/comments/id/slug.json", canonicalUrl)
            assertEquals(2, mockWebServer.requestCount)
            val first = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("www.reddit.com", first!!.getHeader("Host"))
            assertEquals("/r/BestofRedditorUpdates/s/UyxzNkRPd5", first.path)
            val second = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals(RedditApiClient.COMMUNITY_MIRRORS.first(), second!!.getHeader("Host"))
            assertEquals("/r/BestofRedditorUpdates/s/UyxzNkRPd5", second.path)
        }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503])
    fun `falls back to first mirror when reddit share link returns HTTP 5xx`(code: Int) =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(code))
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://red.artemislena.eu/r/x/comments/id/slug/"),
            )

            val canonicalUrl =
                resolver.resolveToCanonicalJsonUrl("https://reddit.com/r/BestofRedditorUpdates/s/UyxzNkRPd5")

            assertEquals("https://red.artemislena.eu/r/x/comments/id/slug.json", canonicalUrl)
            assertEquals(2, mockWebServer.requestCount)
            mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            val second = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
            assertEquals(RedditApiClient.COMMUNITY_MIRRORS.first(), second!!.getHeader("Host"))
        }

    @Test
    fun `throws SecurityException when comments redirect targets unpermitted host`() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.example.com/r/x/comments/id/slug/"),
            )

            val exception =
                assertThrows(SecurityException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        resolver.resolveToCanonicalJsonUrl("https://reddit.com/s/abc")
                    }
                }

            assertTrue(
                exception.message!!.contains(
                    "Redirect to unpermitted target: https://evil.example.com/r/x/comments/id/slug/",
                ),
            )
            // SecurityException is not retried on mirrors.
            assertEquals(1, mockWebServer.requestCount)
        }
}
