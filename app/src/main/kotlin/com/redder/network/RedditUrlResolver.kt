package com.redder.network

import com.redder.security.UrlSecurityValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Resolves untrusted inbound Reddit URLs (including redd.it and /s/ share short links) to
 * canonical thread URLs. The thread itself is later fetched from community mirrors by
 * [RedditApiClient], which maps the host onto each mirror.
 *
 * Validates URLs against security policies, follows redirects up to 5 hops,
 * strips query tracking parameters, and normalizes paths to end with `.json`.
 */
class RedditUrlResolver(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = RedditApiClient.DEFAULT_USER_AGENT,
) {
    private val noRedirectClient: OkHttpClient =
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /**
     * Resolves [rawUrl] to its canonical `.json` Reddit API endpoint URL.
     *
     * @param rawUrl The unvalidated Reddit URL to resolve.
     * @return The canonical HTTPS Reddit JSON URL.
     * @throws IllegalArgumentException If [rawUrl] is blank.
     * @throws SecurityException If [rawUrl] or any redirect target fails security validation.
     * @throws IllegalStateException If redirect hops exceed 5.
     * @throws IOException If a network failure occurs or an HTTP error status is received.
     */
    suspend fun resolveToCanonicalJsonUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) {
            throw IllegalArgumentException("URL cannot be blank")
        }
        if (!UrlSecurityValidator.isPermittedUrl(rawUrl)) {
            throw SecurityException("URL is not a permitted Reddit URL: $rawUrl")
        }

        return withContext(ioDispatcher) {
            val startUrl = rawUrl.toHttpUrl()
            // Reddit itself often stalls or blocks requests from phones, so if resolving a share link
            // directly fails, ask each mirror to resolve it instead (Redlib answers /s/ links with a 302).
            val candidates =
                listOf(startUrl) +
                    if (startUrl.host == "reddit.com" || startUrl.host.endsWith(".reddit.com")) {
                        RedditApiClient.COMMUNITY_MIRRORS.map { startUrl.newBuilder().host(it).build() }
                    } else {
                        emptyList()
                    }

            var lastException: Exception? = null
            for (candidate in candidates) {
                try {
                    return@withContext buildCanonicalJsonUrl(followRedirects(candidate))
                } catch (e: IOException) {
                    lastException = e
                }
            }
            throw lastException ?: IOException("Unable to resolve URL: $rawUrl")
        }
    }

    private fun followRedirects(startUrl: HttpUrl): HttpUrl {
        var currentHttpUrl = startUrl
        var hops = 0

        while (true) {
            // A thread URL is all we need: the thread itself is fetched from mirrors later, and
            // requesting it from Reddit here can hang until the socket times out.
            if (currentHttpUrl.encodedPath.contains("/comments/")) {
                return currentHttpUrl
            }
            val request =
                Request.Builder()
                    .url(currentHttpUrl)
                    .header("User-Agent", userAgent)
                    .build()

            val response = noRedirectClient.newCall(request).execute()
            val (isRedirect, nextHttpUrl) =
                response.use { resp ->
                    when {
                        resp.isRedirect -> {
                            hops++
                            if (hops > MAX_REDIRECT_HOPS) {
                                throw IllegalStateException("Too many redirects: exceeded limit of 5 hops")
                            }
                            val locationHeader =
                                resp.header("Location")
                                    ?: throw IOException(
                                        "HTTP ${resp.code} redirect missing Location header for URL: $currentHttpUrl",
                                    )
                            val targetHttpUrl =
                                currentHttpUrl.resolve(locationHeader)
                                    ?: throw IOException(
                                        "Unable to resolve redirect location '$locationHeader' from URL: $currentHttpUrl",
                                    )
                            val targetUrlString = targetHttpUrl.toString()
                            if (!UrlSecurityValidator.isPermittedUrl(targetUrlString)) {
                                throw SecurityException("Redirect to unpermitted target: $targetUrlString")
                            }
                            true to targetHttpUrl
                        }
                        resp.isSuccessful -> {
                            false to currentHttpUrl
                        }
                        else -> {
                            throw IOException("HTTP ${resp.code} received while resolving URL: $currentHttpUrl")
                        }
                    }
                }

            if (!isRedirect) {
                return currentHttpUrl
            }
            currentHttpUrl = nextHttpUrl
        }
    }

    private fun buildCanonicalJsonUrl(httpUrl: HttpUrl): String {
        val currentPath = httpUrl.encodedPath
        val trimmed = currentPath.trimEnd('/')
        val withoutJson =
            if (trimmed.endsWith(".json")) {
                trimmed.removeSuffix(".json").trimEnd('/')
            } else {
                trimmed
            }
        val normalizedPath = if (withoutJson.isEmpty()) "/.json" else "$withoutJson.json"

        val builder =
            httpUrl.newBuilder()
                .encodedPath(normalizedPath)
                .fragment(null)

        val trackingParams = httpUrl.queryParameterNames.filter { isTrackingParameter(it) }
        for (param in trackingParams) {
            builder.removeAllQueryParameters(param)
        }

        return builder.build().toString()
    }

    private fun isTrackingParameter(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower == "utm" ||
            lower.startsWith("utm_") ||
            lower == "ref" ||
            lower.startsWith("ref_") ||
            lower == "share_id" ||
            lower == "si" ||
            lower == "sh" ||
            lower == "rdt_cid" ||
            lower == "fbclid" ||
            lower == "gclid" ||
            lower == "msclkid" ||
            lower == "mc_eid" ||
            lower == "igshid" ||
            lower == "_ga" ||
            lower == "_hsenc" ||
            lower == "_hsmi" ||
            lower == "dclid"
    }

    companion object {
        private const val MAX_REDIRECT_HOPS = 5
    }
}
