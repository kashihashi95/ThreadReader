package com.redder.playback

/**
 * Logs time-to-first-audio milestones under [TAG], as milliseconds since the last shared URL arrived,
 * in a grep-friendly form such as `TTFA first_audio_playing=12345ms`.
 */
class TimeToFirstAudioLog(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val logger: (String) -> Unit = { message ->
        try {
            android.util.Log.i(TAG, message)
        } catch (_: Throwable) {
            // JVM unit tests without android.util.Log
        }
    },
) {
    @Volatile
    private var urlReceivedAtMs: Long = -1L

    @Volatile
    private var awaitingFirstAudio: Boolean = false

    /** Starts a new measurement and logs `url_received=0ms`. */
    fun urlReceived() {
        urlReceivedAtMs = clockMs()
        awaitingFirstAudio = true
        mark(URL_RECEIVED)
    }

    /** Logs [event] relative to the last URL; does nothing before any URL arrived. */
    fun mark(event: String) {
        val start = urlReceivedAtMs
        if (start < 0L) return
        logger("TTFA $event=${clockMs() - start}ms")
    }

    /** Logs the warm-up result: its own duration, plus its offset from the URL if one arrived. */
    fun warmUpDone(durationMs: Long) {
        logger("TTFA $WARMUP_DURATION=${durationMs}ms")
        if (urlReceivedAtMs < 0L) {
            logger("TTFA $WARMUP_DONE=before_url")
        } else {
            mark(WARMUP_DONE)
        }
    }

    /** Logs [FIRST_AUDIO_PLAYING] once per URL, the first time the player is ready and playing. */
    fun onPlayerState(
        isReady: Boolean,
        isPlaying: Boolean,
    ) {
        if (awaitingFirstAudio && isReady && isPlaying) {
            awaitingFirstAudio = false
            mark(FIRST_AUDIO_PLAYING)
        }
    }

    companion object {
        const val TAG: String = "ThreadReaderTiming"
        const val URL_RECEIVED: String = "url_received"
        const val THREAD_FETCHED: String = "thread_fetched"
        const val THREAD_PARSED: String = "thread_parsed"
        const val WARMUP_DONE: String = "warmup_done"
        const val WARMUP_DURATION: String = "warmup_duration"
        const val FIRST_CHUNK_SYNTHESIZED: String = "first_chunk_synthesized"
        const val FIRST_AUDIO_PLAYING: String = "first_audio_playing"
    }
}
