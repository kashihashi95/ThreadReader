package com.redder.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.redder.R
import com.redder.playback.RedderAudioService
import com.redder.security.UrlSecurityValidator

/**
 * Platform abstraction seam for launching playback and overlay services and finishing the activity.
 */
interface ShareActionHandler {
    /**
     * Starts [RedderAudioService] as a foreground service with the validated Reddit URL extra.
     */
    fun startAudioService(redditUrl: String)

    /**
     * Starts the floating overlay service if permitted.
     */
    fun startOverlayService()

    /**
     * Checks whether the application currently has permission to display on-screen overlays.
     */
    fun canDrawOverlays(): Boolean

    /**
     * Tells the user the shared text did not contain a Reddit thread link.
     */
    fun showNotRedditLinkMessage() {}

    /**
     * Finishes the trampoline activity.
     */
    fun finish()
}

/**
 * Default Android platform implementation of [ShareActionHandler].
 */
class DefaultShareActionHandler(
    private val activity: Activity,
) : ShareActionHandler {
    override fun startAudioService(redditUrl: String) {
        val audioIntent =
            Intent(activity, RedderAudioService::class.java).apply {
                putExtra(ShareReceiverActivity.EXTRA_REDDIT_URL, redditUrl)
            }
        ContextCompat.startForegroundService(activity, audioIntent)
    }

    override fun startOverlayService() {
        try {
            val overlayIntent =
                Intent(ShareReceiverActivity.ACTION_START_OVERLAY).apply {
                    component =
                        ComponentName(
                            activity,
                            ShareReceiverActivity.FLOATING_OVERLAY_SERVICE_CLASS,
                        )
                    setPackage(activity.packageName)
                }
            activity.startService(overlayIntent)
        } catch (_: Exception) {
            // Overlay service failure must not crash the transparent trampoline
        }
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(activity)

    override fun showNotRedditLinkMessage() {
        Toast.makeText(activity.applicationContext, R.string.not_reddit_link, Toast.LENGTH_SHORT).show()
    }

    override fun finish() {
        activity.window?.decorView?.postDelayed({
            activity.finish()
        }, 1500L) ?: activity.finish()
    }
}

/**
 * Abstraction seam for reading inbound share intent properties without android.jar dependencies.
 */
interface ShareIntentReader {
    val action: String?
    val type: String?
    val extraText: String?
}

/**
 * Platform Android [Intent] reader.
 */
class AndroidIntentReader(
    private val intent: Intent?,
) : ShareIntentReader {
    override val action: String? get() = intent?.action
    override val type: String? get() = intent?.type
    override val extraText: String?
        get() =
            intent?.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
}

/**
 * In-memory [ShareIntentReader] for unit tests and direct invocations.
 */
data class SimpleIntentReader(
    override val action: String? = Intent.ACTION_SEND,
    override val type: String? = ShareIntentProcessor.MIME_TYPE_TEXT_PLAIN,
    override val extraText: String?,
) : ShareIntentReader

/**
 * Business logic processor for inbound share intents.
 * Extracts and validates Reddit URLs, triggers services according to permissions,
 * and guarantees immediate trampoline completion.
 */
class ShareIntentProcessor(
    private val actionHandler: ShareActionHandler,
    private val urlValidator: (String?) -> Boolean = UrlSecurityValidator::isPermittedUrl,
) {
    /**
     * Processes an inbound [ShareIntentReader].
     */
    fun process(reader: ShareIntentReader): String? =
        process(
            action = reader.action,
            type = reader.type,
            text = reader.extraText,
        )

    /**
     * Processes an Android [Intent].
     */
    fun processIntent(intent: Intent?): String? = process(AndroidIntentReader(intent))

    /**
     * Convenience overload for processing shared text with default ACTION_SEND and text/plain.
     */
    fun process(text: String?): String? =
        process(
            action = Intent.ACTION_SEND,
            type = MIME_TYPE_TEXT_PLAIN,
            text = text,
        )

    /**
     * Primary processor method. Validates intent action, MIME type, and candidate URLs.
     * Guarantees [ShareActionHandler.finish] is invoked in all scenarios.
     */
    fun process(
        action: String?,
        type: String?,
        text: String?,
    ): String? {
        try {
            if (action != Intent.ACTION_SEND) {
                return null
            }

            if (type == null || !type.startsWith(MIME_TYPE_TEXT_PLAIN, ignoreCase = true)) {
                return null
            }

            val validUrl = extractFirstValidUrl(text)
            if (validUrl == null) {
                actionHandler.showNotRedditLinkMessage()
                return null
            }

            actionHandler.startAudioService(validUrl)

            if (actionHandler.canDrawOverlays()) {
                actionHandler.startOverlayService()
            }

            return validUrl
        } finally {
            actionHandler.finish()
        }
    }

    /**
     * Finds and sanitizes the first permitted Reddit URL from the provided [text].
     */
    fun extractFirstValidUrl(text: String?): String? {
        if (text.isNullOrBlank()) {
            return null
        }

        return findUrlCandidates(text).firstOrNull { candidate -> urlValidator(candidate) }
    }

    companion object {
        const val MIME_TYPE_TEXT_PLAIN: String = "text/plain"

        private val URL_REGEX =
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

        private val TRAILING_PUNCTUATION =
            charArrayOf(
                '.', ',', ';', ':', '!', '?',
                ')', ']', '}', '>',
                '\'', '"', '’', '”', '»',
            )

        /**
         * Strips trailing punctuation attached to candidate URLs.
         */
        fun trimTrailingPunctuation(url: String): String = url.trimEnd(*TRAILING_PUNCTUATION)

        /**
         * Returns every http(s) link found in [text], with trailing punctuation stripped, in order.
         */
        fun findUrlCandidates(text: String): List<String> =
            URL_REGEX.findAll(text).map { match -> trimTrailingPunctuation(match.value) }.toList()
    }
}

/**
 * Transparent trampoline activity (Theme.Translucent.NoTitleBar) that handles ACTION_SEND
 * intents with text/plain, starts background audio and HUD services, and finishes immediately.
 */
class ShareReceiverActivity : Activity() {
    internal var actionHandler: ShareActionHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handler = actionHandler ?: DefaultShareActionHandler(this)
        val processor = ShareIntentProcessor(handler)
        processor.processIntent(intent)
    }

    companion object {
        const val EXTRA_REDDIT_URL: String = "com.redder.extra.REDDIT_URL"
        const val ACTION_START_OVERLAY: String = "com.redder.action.START_OVERLAY"
        const val FLOATING_OVERLAY_SERVICE_CLASS: String = "com.redder.ui.FloatingOverlayService"
    }
}
