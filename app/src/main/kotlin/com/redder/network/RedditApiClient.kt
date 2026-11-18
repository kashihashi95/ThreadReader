package com.redder.network

import com.redder.queue.SubredditName
import com.redder.security.UrlSecurityValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thrown when an HTTP response payload exceeds the maximum allowed ceiling of 5 MB.
 *
 * @param message Explanation of payload size violation.
 */
class PayloadTooLargeException(message: String) : IOException(message)

/**
 * HTTP client that fetches Reddit thread pages from public Redlib community mirrors.
 *
 * Enforces URL security validation, custom User-Agent, and a hard 5 MB payload ceiling to prevent
 * OOM DOS attacks. Mirrors in [COMMUNITY_MIRRORS] are tried in order; HTTP errors, network errors,
 * bot challenges, Redlib error pages and pages without a post fail over to the next mirror.
 */
class RedditApiClient(
    private val client: OkHttpClient = createDefaultOkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    /**
     * Fetches the thread page for a Reddit (or mirror) thread URL from the community mirrors.
     *
     * @param url Target Reddit URL. Must be a permitted HTTPS Reddit or mirror URL.
     * @return The response body payload as a string.
     * @throws SecurityException If [url] is unpermitted or invalid according to [UrlSecurityValidator].
     * @throws PayloadTooLargeException If the response payload exceeds 5 MB.
     * @throws IOException If every mirror fails.
     */
    suspend fun fetchThreadJson(url: String): String {
        if (!UrlSecurityValidator.isPermittedUrl(url)) {
            throw SecurityException("URL is not a permitted Reddit URL: $url")
        }

        return withContext(ioDispatcher) {
            fetchThreadFromCommunityMirrors(url)
        }
    }

    /**
     * Fetches one page of a subreddit's hot listing from the community mirrors. Hot is in the path:
     * a mirror's default sort can be something else, and it ignores `?sort=hot`.
     *
     * @param subreddit A bare subreddit name.
     * @param after The next-page token of the previous page, or null for the first page.
     * @throws IllegalArgumentException If [subreddit] is not a valid name.
     * @throws IOException If every mirror fails.
     */
    suspend fun fetchSubredditListing(
        subreddit: String,
        after: String? = null,
    ): String {
        require(SubredditName.isValid(subreddit)) { "Invalid subreddit name" }
        val url =
            HttpUrl.Builder()
                .scheme("https")
                .host(COMMUNITY_MIRRORS.first())
                .addPathSegments("r/$subreddit/hot")
                .apply { if (after != null) addQueryParameter("t", "").addQueryParameter("after", after) }
                .build()
                .toString()
        if (!UrlSecurityValidator.isPermittedUrl(url)) {
            throw SecurityException("URL is not a permitted Reddit URL: $url")
        }
        return withContext(ioDispatcher) { fetchFromCommunityMirrors(url, ::looksLikeListing, "No listing") }
    }

    private fun fetchThreadFromCommunityMirrors(url: String): String = fetchFromCommunityMirrors(url, ::looksLikeThread, "No post")

    private fun fetchFromCommunityMirrors(
        url: String,
        isExpectedPage: (String) -> Boolean,
        missingLabel: String,
    ): String {
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IOException("Invalid URL: $url")

        val targetMirrors =
            if (COMMUNITY_MIRRORS.contains(parsedUrl.host)) {
                listOf(parsedUrl.host) + (COMMUNITY_MIRRORS.filter { it != parsedUrl.host })
            } else {
                COMMUNITY_MIRRORS
            }

        var lastException: Exception? = null

        for (mirrorHost in targetMirrors) {
            val candidateUrl =
                parsedUrl.newBuilder()
                    .host(mirrorHost)
                    .build()
                    .toString()

            val request =
                Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", userAgent)
                    .build()

            try {
                val response = client.newCall(request).execute()
                val (isSuccess, resultOrError) =
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val body = readBodySafely(resp)
                            when {
                                isBotChallengeOrBlocked(body) ->
                                    false to "Bot challenge, block or error page returned from mirror $mirrorHost for URL: $candidateUrl"
                                !isExpectedPage(body) ->
                                    false to "$missingLabel found in response from mirror $mirrorHost for URL: $candidateUrl"
                                else -> true to body
                            }
                        } else {
                            false to "HTTP ${resp.code} error from mirror $mirrorHost for URL: $candidateUrl"
                        }
                    }

                if (isSuccess) {
                    return resultOrError
                } else {
                    lastException = IOException(resultOrError)
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                if (e is PayloadTooLargeException) {
                    throw e
                }
                lastException = e
            }
        }

        throw lastException ?: IOException("All community mirrors failed for URL: $url")
    }

    private fun readBodySafely(response: Response): String {
        val body = response.body ?: return ""
        val contentLength = body.contentLength()
        if (contentLength > MAX_PAYLOAD_BYTES) {
            body.close()
            throw PayloadTooLargeException(
                "Response payload Content-Length ($contentLength bytes) exceeds limit of $MAX_PAYLOAD_BYTES bytes",
            )
        }

        val source = body.source()
        val buffer = Buffer()
        var totalBytesRead = 0L

        while (true) {
            val toRead = minOf(CHUNK_SIZE, MAX_PAYLOAD_BYTES - totalBytesRead + 1L)
            val bytesRead = source.read(buffer, toRead)
            if (bytesRead == -1L) {
                break
            }
            totalBytesRead += bytesRead
            if (totalBytesRead > MAX_PAYLOAD_BYTES) {
                body.close()
                throw PayloadTooLargeException(
                    "Response payload exceeded limit of $MAX_PAYLOAD_BYTES bytes",
                )
            }
        }

        return buffer.readUtf8()
    }

    companion object {
        /**
         * Redlib community mirrors in failover order. Single source of truth for fetching,
         * URL permission checks ([UrlSecurityValidator]) and the dashboard mirror status list.
         */
        val COMMUNITY_MIRRORS: List<String> =
            listOf(
                "red.artemislena.eu",
                "safereddit.com",
                "reddit.rtrace.io",
                "redlib.catsarch.com",
            )

        const val DEFAULT_USER_AGENT = "android:com.redder:v1.0.0"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 8_000L
        const val MAX_PAYLOAD_BYTES = 5L * 1024L * 1024L // 5,242,880 bytes
        private const val CHUNK_SIZE = 8_192L

        /**
         * Creates an [OkHttpClient] pre-configured with RedditApiClient default timeouts.
         */
        fun createDefaultOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

        internal fun isBotChallengeOrBlocked(body: String): Boolean {
            if (body.isEmpty()) return false
            val lower = body.take(4000).lowercase()
            return lower.contains("making sure you're not a bot") ||
                lower.contains("making sure you&#39;re not a bot") ||
                lower.contains("making sure you&#x27;re not a bot") ||
                lower.contains("making sure you are not a bot") ||
                // Anubis proof-of-work challenge markers (not the bare word, which can appear in thread titles)
                lower.contains("anubis_") ||
                lower.contains("/.within.website/") ||
                lower.contains("reddit rate limit exceeded") ||
                lower.contains("failed to parse page json data") ||
                lower.contains("blocked by network security") ||
                lower.contains("attention required! | cloudflare") ||
                lower.contains("just a moment...") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("challenge-platform") ||
                lower.contains("whoa there, patient bear")
        }

        /**
         * True when [body] plausibly contains a thread: a Redlib page with a post title, or a
         * Reddit JSON listing. Empty bodies and HTML pages without a post are rejected.
         */
        internal fun looksLikeThread(body: String): Boolean {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) return true
            return body.contains("post_title")
        }

        /** True when [body] is a Redlib subreddit page, including one with no posts on it. */
        internal fun looksLikeListing(body: String): Boolean =
            body.contains("post_title") || body.contains("id=\"posts\"") || body.contains("id=\"no_posts\"")
    }
}
