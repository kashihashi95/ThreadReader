package com.redder.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UrlSecurityValidatorTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://reddit.com",
            "https://reddit.com/",
            "https://reddit.com/r/android",
            "https://reddit.com/r/technology/comments/12345/sample_title/",
            "https://reddit.com/r/AskReddit?sort=top&limit=25",
            "https://reddit.com/r/test#comment-123",
            "https://www.reddit.com",
            "https://www.reddit.com/r/kotlin",
            "https://old.reddit.com",
            "https://old.reddit.com/r/android",
            "https://redd.it",
            "https://redd.it/xyz123",
            "https://v.redd.it",
            "https://v.redd.it/abc12345",
            "https://np.reddit.com",
            "https://np.reddit.com/r/science",
            "https://new.reddit.com",
            "https://new.reddit.com/r/popular",
            "https://sh.reddit.com",
            "https://oauth.reddit.com",
            "https://mod.reddit.com",
            "https://sub.domain.reddit.com",
            "https://nested.sub.reddit.com/comments/1",
            "https://reddit.com:443/",
            "https://reddit.com:443/r/android",
        ],
    )
    fun `isPermittedUrl returns true for valid Reddit URLs`(url: String) {
        assertTrue(UrlSecurityValidator.isPermittedUrl(url), "Expected valid: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://REDDIT.COM",
            "https://WWW.REDDIT.COM",
            "https://OLD.REDDIT.COM/r/all",
            "https://V.REDD.IT/1234",
            "https://REDD.IT/abc",
            "https://NP.REDDIT.COM",
            "HTTPS://reddit.com",
            "Https://Www.Reddit.Com",
            "https://Old.Reddit.Com/r/Android",
        ],
    )
    fun `isPermittedUrl handles case-insensitive host and scheme`(url: String) {
        assertTrue(UrlSecurityValidator.isPermittedUrl(url), "Expected valid case variation: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://reddit.com",
            "http://www.reddit.com",
            "http://old.reddit.com",
            "http://redd.it",
            "http://v.redd.it",
            "ftp://reddit.com",
            "file:///etc/passwd",
            "file:///sdcard/test.txt",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ws://reddit.com",
            "wss://reddit.com",
            "content://com.android.providers.media/external/images",
            "custom://reddit.com",
            "//reddit.com",
            "reddit.com",
            "www.reddit.com",
        ],
    )
    fun `isPermittedUrl rejects non-https schemes`(url: String) {
        assertFalse(UrlSecurityValidator.isPermittedUrl(url), "Expected rejected scheme: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://127.0.0.1",
            "http://127.0.0.1",
            "https://127.0.0.1:8080",
            "https://127.0.0.2",
            "https://169.254.169.254",
            "https://169.254.169.254/latest/meta-data/",
            "http://169.254.169.254",
            "https://192.168.1.1",
            "https://192.168.0.100",
            "https://10.0.0.1",
            "https://10.255.255.254",
            "https://172.16.0.1",
            "https://172.31.255.255",
            "https://0.0.0.0",
            "https://localhost",
            "http://localhost",
            "https://localhost:3000",
            "https://sub.localhost",
            "https://[::1]",
            "https://[::]",
            "https://[fe80::1]",
            "https://[fc00::1]",
            "https://[fd12:3456:789a:1::1]",
            "https://8.8.8.8",
        ],
    )
    fun `isPermittedUrl rejects SSRF and intranet targets`(url: String) {
        assertFalse(UrlSecurityValidator.isPermittedUrl(url), "Expected rejected SSRF target: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://fake-reddit.com",
            "https://reddit.com.evil.com",
            "https://evilreddit.com",
            "https://notreddit.com",
            "https://reddit.com-attacker.com",
            "https://evil.redd.it",
            "https://fake.v.redd.it",
            "https://google.com",
            "https://attacker.com/reddit.com",
            "https://redditcom",
            "https://.reddit.com",
            "https://..reddit.com",
            "https://-invalid.reddit.com",
            "https://invalid-.reddit.com",
            "https://invalid..reddit.com",
            "https://rеddit.com", // Cyrillic 'е' homoglyph
        ],
    )
    fun `isPermittedUrl rejects phishing and spoofing domains`(url: String) {
        assertFalse(UrlSecurityValidator.isPermittedUrl(url), "Expected rejected spoofing: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://user:pass@reddit.com",
            "https://user@reddit.com",
            "https://:pass@reddit.com",
            "https://@reddit.com",
            "https://user:pass@www.reddit.com",
            "https://reddit.com@attacker.com",
            "https://reddit.com:pass@attacker.com",
            "https://admin:admin@old.reddit.com",
            "https://user%40evil.com@reddit.com",
            "https://reddit.com%40evil.com",
            "https://reddit.com\\@evil.com",
        ],
    )
    fun `isPermittedUrl rejects userinfo authentication attempts`(url: String) {
        assertFalse(UrlSecurityValidator.isPermittedUrl(url), "Expected rejected userinfo: $url")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "   ",
            "\t\n",
            "not a url",
            "https://",
            "https:// ",
            "https:// reddit.com",
            "https://reddit.com /foo",
        ],
    )
    fun `isPermittedUrl safely returns false for malformed and blank URLs`(url: String) {
        assertFalse(UrlSecurityValidator.isPermittedUrl(url), "Expected rejected malformed: $url")
    }

    @Test
    fun `isPermittedUrl safely handles null`() {
        assertFalse(UrlSecurityValidator.isPermittedUrl(null))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://reddit.com/r/test?q=hello@world",
            "https://reddit.com/#section@1",
            "https://reddit.com?email=test%40reddit.com",
            "https://reddit.com/r/test?q=foo%40bar&tag=baz#section@2",
        ],
    )
    fun `isPermittedUrl allows at sign in query parameters and fragments`(url: String) {
        assertTrue(UrlSecurityValidator.isPermittedUrl(url), "Expected valid query/fragment @: $url")
    }
}
