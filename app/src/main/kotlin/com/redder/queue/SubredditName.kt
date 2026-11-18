package com.redder.queue

import com.redder.security.UrlSecurityValidator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns what people type or paste for a subreddit into its bare name. */
object SubredditName {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9_]{1,20}$")
    private val PREFIX = Regex("^/?r/", RegexOption.IGNORE_CASE)
    private val HAS_HOST = Regex("""^[A-Za-z0-9.-]+\.[A-Za-z]{2,}/""")

    fun isValid(name: String): Boolean = VALID.matches(name)

    /** Accepts `r/Name`, `/r/Name`, `Name` or a Reddit or mirror link; null when it is none of those. */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val candidate =
            when {
                text.contains("://") -> fromUrl(text)
                HAS_HOST.containsMatchIn(text) -> fromUrl("https://$text")
                else -> text.trimEnd('/').replace(PREFIX, "")
            }
        return candidate?.takeIf(::isValid)
    }

    private fun fromUrl(url: String): String? {
        if (!UrlSecurityValidator.isPermittedUrl(url)) return null
        val segments = url.toHttpUrlOrNull()?.pathSegments ?: return null
        return if (segments.size >= 2 && segments[0].equals("r", ignoreCase = true)) segments[1] else null
    }
}
