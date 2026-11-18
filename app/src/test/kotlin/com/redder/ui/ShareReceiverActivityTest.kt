package com.redder.ui

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ShareReceiverActivityTest {
    private lateinit var fakeActionHandler: FakeShareActionHandler
    private lateinit var processor: ShareIntentProcessor

    @BeforeEach
    fun setUp() {
        fakeActionHandler = FakeShareActionHandler()
        processor = ShareIntentProcessor(fakeActionHandler)
    }

    // =========================================================================
    // 1. Clean Reddit URLs
    // =========================================================================

    @Test
    fun `clean reddit com url launches audio service`() {
        val url = "https://reddit.com/r/technology/comments/123/title/"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean www reddit com url launches audio service`() {
        val url = "https://www.reddit.com/r/science/comments/456/sample/"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean old reddit com url launches audio service`() {
        val url = "https://old.reddit.com/r/android/comments/789/update/"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean redd it url launches audio service`() {
        val url = "https://redd.it/xyz123"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean v redd it url launches audio service`() {
        val url = "https://v.redd.it/abc12345"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean reddit subdomain url launches audio service`() {
        val url = "https://np.reddit.com/r/AskReddit/comments/111/questions/"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean reddit url with query params launches audio service`() {
        val url = "https://reddit.com/r/AskReddit?sort=top&limit=25"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `clean reddit url with fragment launches audio service`() {
        val url = "https://reddit.com/r/test#comment-123"
        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 2. Messy Shared Text
    // =========================================================================

    @Test
    fun `extracts reddit url with leading descriptive text`() {
        val text = "Check out this thread: https://reddit.com/r/technology/comments/xyz/test/"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/technology/comments/xyz/test/", result)
        assertEquals(1, fakeActionHandler.startedAudioUrls.size)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `extracts reddit url with trailing text`() {
        val text = "https://reddit.com/r/android is an awesome thread discussion"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/android", result)
        assertEquals(1, fakeActionHandler.startedAudioUrls.size)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `extracts reddit url surrounded by conversational text`() {
        val text = "Hey everyone, see https://old.reddit.com/r/kotlin/comments/abc/tips/ for details!"
        val result = processor.process(text)

        assertEquals("https://old.reddit.com/r/kotlin/comments/abc/tips/", result)
        assertEquals(1, fakeActionHandler.startedAudioUrls.size)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `extracts reddit url from multi-line shared text`() {
        val text = "Breaking News\nhttps://reddit.com/r/news/comments/999/live/\nSent from official app"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/news/comments/999/live/", result)
        assertEquals(1, fakeActionHandler.startedAudioUrls.size)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 3. Trailing Punctuation & Bracket Trimming
    // =========================================================================

    @Test
    fun `trims trailing period from reddit url`() {
        val text = "Take a look at https://reddit.com/r/technology."
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/technology", result)
        assertEquals(listOf("https://reddit.com/r/technology"), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `trims multiple trailing periods ellipsis from reddit url`() {
        val text = "See this https://reddit.com/r/technology..."
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/technology", result)
        assertEquals(listOf("https://reddit.com/r/technology"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing closing parenthesis from parenthesized reddit url`() {
        val text = "Interesting thread (https://reddit.com/r/technology)"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/technology", result)
        assertEquals(listOf("https://reddit.com/r/technology"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing period and closing parenthesis from reddit url`() {
        val text = "Check this out (https://reddit.com/r/technology)."
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/technology", result)
        assertEquals(listOf("https://reddit.com/r/technology"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing closing bracket from markdown link`() {
        val text = "Check out [this link](https://redd.it/xyz123)"
        val result = processor.process(text)

        assertEquals("https://redd.it/xyz123", result)
        assertEquals(listOf("https://redd.it/xyz123"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing comma from comma-separated list`() {
        val text = "Visit https://reddit.com/r/android, and let me know"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/android", result)
        assertEquals(listOf("https://reddit.com/r/android"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing exclamation mark from reddit url`() {
        val text = "Wow look at https://reddit.com/r/funny!"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/funny", result)
        assertEquals(listOf("https://reddit.com/r/funny"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing question mark from reddit url without query string`() {
        val text = "Have you seen https://reddit.com/r/AskReddit?"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/AskReddit", result)
        assertEquals(listOf("https://reddit.com/r/AskReddit"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing semicolon and colon from reddit url`() {
        val text = "Two links: https://reddit.com/r/all; followed by more"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/all", result)
        assertEquals(listOf("https://reddit.com/r/all"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `trims trailing single and double quotation marks from reddit url`() {
        val textSingle = "Quote: 'https://reddit.com/r/science' is great"
        val resultSingle = processor.process(textSingle)
        assertEquals("https://reddit.com/r/science", resultSingle)

        val textDouble = "Quote: \"https://reddit.com/r/science\" is great"
        val resultDouble = processor.process(textDouble)
        assertEquals("https://reddit.com/r/science", resultDouble)
    }

    @Test
    fun `trims trailing unicode curly quotes from reddit url`() {
        val text = "Shared “https://reddit.com/r/science”"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/science", result)
        assertEquals(listOf("https://reddit.com/r/science"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `preserves trailing slash in reddit url path`() {
        val text = "https://reddit.com/r/android/"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/android/", result)
        assertEquals(listOf("https://reddit.com/r/android/"), fakeActionHandler.startedAudioUrls)
    }

    @Test
    fun `utility trimTrailingPunctuation strips various trailing characters`() {
        assertEquals(
            "https://reddit.com",
            ShareIntentProcessor.trimTrailingPunctuation("https://reddit.com...)."),
        )
        assertEquals(
            "https://reddit.com/path",
            ShareIntentProcessor.trimTrailingPunctuation("https://reddit.com/path]!"),
        )
    }

    // =========================================================================
    // 4. Overlay Permission Handling (Graceful Degradation)
    // =========================================================================

    @Test
    fun `when overlay permission is granted both audio service and overlay service are started`() {
        fakeActionHandler.overlayPermissionGranted = true
        val url = "https://reddit.com/r/technology"

        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `when overlay permission is denied only audio service is started and overlay service is skipped`() {
        fakeActionHandler.overlayPermissionGranted = false
        val url = "https://reddit.com/r/technology"

        val result = processor.process(url)

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `overlay service is not started when url is invalid even if overlay permission is granted`() {
        fakeActionHandler.overlayPermissionGranted = true
        val invalidUrl = "https://evil.com/fake"

        val result = processor.process(invalidUrl)

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 5. Non-Reddit / SSRF / Intranet / Insecure URLs Blocked
    // =========================================================================

    @Test
    fun `blocks localhost url and starts zero services`() {
        val result = processor.process("http://localhost:8080/admin")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks https localhost url and starts zero services`() {
        val result = processor.process("https://localhost:8080/admin")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks private intranet 192 168 ip and starts zero services`() {
        val result = processor.process("http://192.168.1.1/router")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks aws cloud metadata ip and starts zero services`() {
        val result = processor.process("http://169.254.169.254/latest/meta-data/")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks non reddit external domain and starts zero services`() {
        val result = processor.process("https://evil.com/phishing")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks insecure http reddit com url and starts zero services`() {
        val result = processor.process("http://reddit.com/r/android")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks insecure http old reddit com url and starts zero services`() {
        val result = processor.process("http://old.reddit.com/r/android")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks insecure http redd it url and starts zero services`() {
        val result = processor.process("http://redd.it/xyz")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blocks userinfo injection url and starts zero services`() {
        val result = processor.process("https://attacker:secret@reddit.com/r/android")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 6. Null / Empty / Blank / Plain Text
    // =========================================================================

    @Test
    fun `null shared text starts zero services`() {
        val result = processor.process(null as String?)

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `empty shared text starts zero services`() {
        val result = processor.process("")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `blank whitespace shared text starts zero services`() {
        val result = processor.process("   \n\t  ")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `plain text without any urls starts zero services`() {
        val result = processor.process("Just a conversation with no links at all")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `non reddit link shows not a reddit link message and finishes`() {
        val result = processor.process("Look at this https://example.com/article")

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(1, fakeActionHandler.notRedditLinkMessageCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `valid reddit link does not show not a reddit link message`() {
        processor.process("https://www.reddit.com/r/technology/comments/123/title/")

        assertEquals(0, fakeActionHandler.notRedditLinkMessageCount)
    }

    @Test
    fun `unsupported mime type does not show not a reddit link message`() {
        processor.process(action = Intent.ACTION_SEND, type = "image/png", text = "https://example.com")

        assertEquals(0, fakeActionHandler.notRedditLinkMessageCount)
    }

    // =========================================================================
    // 7. Intent Action & MIME Type Validation
    // =========================================================================

    @Test
    fun `unsupported intent action starts zero services`() {
        val result =
            processor.process(
                action = Intent.ACTION_VIEW,
                type = ShareIntentProcessor.MIME_TYPE_TEXT_PLAIN,
                text = "https://reddit.com/r/android",
            )

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `null intent action starts zero services`() {
        val result =
            processor.process(
                action = null,
                type = ShareIntentProcessor.MIME_TYPE_TEXT_PLAIN,
                text = "https://reddit.com/r/android",
            )

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `unsupported mime type starts zero services`() {
        val result =
            processor.process(
                action = Intent.ACTION_SEND,
                type = "image/png",
                text = "https://reddit.com/r/android",
            )

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `null mime type starts zero services`() {
        val result =
            processor.process(
                action = Intent.ACTION_SEND,
                type = null,
                text = "https://reddit.com/r/android",
            )

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `supported mime type with charset parameter is accepted`() {
        val url = "https://reddit.com/r/android"
        val result =
            processor.process(
                action = Intent.ACTION_SEND,
                type = "text/plain; charset=utf-8",
                text = url,
            )

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `case insensitive text plain mime type is accepted`() {
        val url = "https://reddit.com/r/android"
        val result =
            processor.process(
                action = Intent.ACTION_SEND,
                type = "TEXT/PLAIN",
                text = url,
            )

        assertEquals(url, result)
        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 8. Immediate finish() Invocation in All Scenarios
    // =========================================================================

    @Test
    fun `finish is invoked immediately when url is valid and overlay is granted`() {
        fakeActionHandler.overlayPermissionGranted = true
        processor.process("https://reddit.com/r/android")

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately when url is valid and overlay is denied`() {
        fakeActionHandler.overlayPermissionGranted = false
        processor.process("https://reddit.com/r/android")

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately when url is invalid`() {
        processor.process("https://evil.com")

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately when text is null`() {
        processor.process(null as String?)

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately when action is unsupported`() {
        processor.process(action = Intent.ACTION_EDIT, type = "text/plain", text = "https://reddit.com")

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately when mime type is unsupported`() {
        processor.process(action = Intent.ACTION_SEND, type = "video/mp4", text = "https://reddit.com")

        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `finish is invoked immediately even when audio service launch throws exception`() {
        fakeActionHandler.onStartAudio = {
            throw IllegalStateException("Simulated platform error during service start")
        }

        assertThrows<IllegalStateException> {
            processor.process("https://reddit.com/r/android")
        }

        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 9. Multiple Candidate URLs
    // =========================================================================

    @Test
    fun `selects first valid reddit url when preceded by invalid external url`() {
        val text = "See https://google.com first, then check https://reddit.com/r/android"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/android", result)
        assertEquals(listOf("https://reddit.com/r/android"), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `selects first valid reddit url when preceded by insecure http reddit url`() {
        val text = "Old link http://reddit.com/r/android and secure link https://reddit.com/r/android"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/android", result)
        assertEquals(listOf("https://reddit.com/r/android"), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `selects first valid reddit url when followed by another valid reddit url`() {
        val text = "First https://reddit.com/r/first and second https://reddit.com/r/second"
        val result = processor.process(text)

        assertEquals("https://reddit.com/r/first", result)
        assertEquals(listOf("https://reddit.com/r/first"), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `starts zero services when multiple candidate urls are all invalid`() {
        val text = "Check https://evil.com and http://localhost:8080 and https://google.com"
        val result = processor.process(text)

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 10. SimpleIntentReader Integration Tests
    // =========================================================================

    @Test
    fun `processes simple intent reader with valid send intent`() {
        val reader =
            SimpleIntentReader(
                action = Intent.ACTION_SEND,
                type = "text/plain",
                extraText = "https://reddit.com/r/kotlin",
            )
        val result = processor.process(reader)

        assertEquals("https://reddit.com/r/kotlin", result)
        assertEquals(listOf("https://reddit.com/r/kotlin"), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.finishCount)
    }

    @Test
    fun `processes simple intent reader with non send action and rejects`() {
        val reader =
            SimpleIntentReader(
                action = "android.intent.action.VIEW",
                type = "text/plain",
                extraText = "https://reddit.com/r/kotlin",
            )
        val result = processor.process(reader)

        assertNull(result)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(1, fakeActionHandler.finishCount)
    }

    // =========================================================================
    // 11. Constants verification
    // =========================================================================

    @Test
    fun `verifies activity constants`() {
        assertEquals("com.redder.extra.REDDIT_URL", ShareReceiverActivity.EXTRA_REDDIT_URL)
        assertEquals("com.redder.action.START_OVERLAY", ShareReceiverActivity.ACTION_START_OVERLAY)
        assertEquals(
            "com.redder.ui.FloatingOverlayService",
            ShareReceiverActivity.FLOATING_OVERLAY_SERVICE_CLASS,
        )
    }
}

/**
 * Fake implementation of [ShareActionHandler] for pure JVM testing.
 */
class FakeShareActionHandler(
    var overlayPermissionGranted: Boolean = true,
    var onStartAudio: ((String) -> Unit)? = null,
    var onStartOverlay: (() -> Unit)? = null,
) : ShareActionHandler {
    val startedAudioUrls = mutableListOf<String>()
    var overlayServiceStartCount = 0
    var finishCount = 0
    var notRedditLinkMessageCount = 0

    override fun showNotRedditLinkMessage() {
        notRedditLinkMessageCount++
    }

    override fun startAudioService(redditUrl: String) {
        onStartAudio?.invoke(redditUrl)
        startedAudioUrls.add(redditUrl)
    }

    override fun startOverlayService() {
        onStartOverlay?.invoke()
        overlayServiceStartCount++
    }

    override fun canDrawOverlays(): Boolean = overlayPermissionGranted

    override fun finish() {
        finishCount++
    }
}
