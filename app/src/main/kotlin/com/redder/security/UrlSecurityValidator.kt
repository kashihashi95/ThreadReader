package com.redder.security

import com.redder.network.RedditApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Validates untrusted inbound URLs against permitted Reddit domains and blocks
 * SSRF, intranet, scheme, homoglyph, and userinfo injection attacks.
 */
object UrlSecurityValidator {
    /** Permitted mirror hosts, derived from the single mirror list in [RedditApiClient.COMMUNITY_MIRRORS]. */
    val COMMUNITY_MIRROR_HOSTS: Set<String> = RedditApiClient.COMMUNITY_MIRRORS.toSet()

    private val EXACT_ALLOWED_HOSTS =
        setOf(
            "reddit.com",
            "www.reddit.com",
            "old.reddit.com",
            "redd.it",
            "v.redd.it",
        ) + COMMUNITY_MIRROR_HOSTS

    private const val REQUIRED_SCHEME = "https"
    private const val REDDIT_SUBDOMAIN_SUFFIX = ".reddit.com"

    /**
     * Validates whether [urlStr] is a permitted HTTPS Reddit URL.
     *
     * @param urlStr The candidate URL to validate.
     * @return true if the URL is safe and permitted; false otherwise.
     */
    fun isPermittedUrl(urlStr: String?): Boolean {
        if (urlStr.isNullOrBlank()) {
            return false
        }

        if (urlStr.contains('\\')) {
            return false
        }

        if (hasUserInfoInAuthority(urlStr)) {
            return false
        }

        val httpUrl =
            try {
                urlStr.toHttpUrlOrNull()
            } catch (e: Exception) {
                return false
            } ?: return false

        if (httpUrl.scheme != REQUIRED_SCHEME) {
            return false
        }

        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
            return false
        }

        val host = httpUrl.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty() || host.startsWith(".") || host.contains("..")) {
            return false
        }

        if (isPrivateOrInternalAddress(host)) {
            return false
        }

        return isAllowedRedditHost(host)
    }

    private fun hasUserInfoInAuthority(urlStr: String): Boolean {
        val schemeDelimiterIndex = urlStr.indexOf("://")
        if (schemeDelimiterIndex == -1) {
            return false
        }

        val authorityStart = schemeDelimiterIndex + 3
        val authorityEnd =
            urlStr.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { index ->
                if (index == -1) urlStr.length else index
            }

        val authority = urlStr.substring(authorityStart, authorityEnd)
        return authority.contains('@') || authority.contains("%40", ignoreCase = true)
    }

    private fun isAllowedRedditHost(host: String): Boolean {
        if (EXACT_ALLOWED_HOSTS.contains(host)) {
            return true
        }

        if (host.endsWith(REDDIT_SUBDOMAIN_SUFFIX)) {
            val prefix = host.removeSuffix(REDDIT_SUBDOMAIN_SUFFIX)
            if (prefix.isNotEmpty() && !prefix.startsWith(".") && !prefix.endsWith(".")) {
                return isValidDnsLabelSequence(prefix)
            }
        }

        return false
    }

    private fun isValidDnsLabelSequence(prefix: String): Boolean {
        val labels = prefix.split('.')
        return labels.all { label ->
            label.isNotEmpty() &&
                label.all { c -> (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_' } &&
                !label.startsWith('-') &&
                !label.endsWith('-')
        }
    }

    private fun isPrivateOrInternalAddress(host: String): Boolean {
        val cleanHost = host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)

        if (cleanHost == "localhost" || cleanHost.endsWith(".localhost")) {
            return true
        }

        if (cleanHost.contains(':')) {
            return true
        }

        val parts = cleanHost.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() }
            if (octets.all { it != null && it in 0..255 }) {
                return true
            }
        }

        return false
    }
}
