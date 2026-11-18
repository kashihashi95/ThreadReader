package com.redder.orchestrator

import com.redder.audio.SilenceTrimmer
import com.redder.audio.WavUtil
import com.redder.sanitizer.DialogueChunk
import com.redder.tts.TtsEngine
import com.redder.tts.TtsUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.logging.Logger

/**
 * Model representing a completed synthesis unit containing raw PCM and packaged WAV audio data.
 *
 * Overrides [equals] and [hashCode] using content-based array comparisons to maintain
 * data class contract without compiler warnings under allWarningsAsErrors.
 *
 * @property index Monotonically increasing chunk index in the dialogue sequence.
 * @property dialogueChunk Original dialogue chunk containing attribution and spoken text.
 * @property pcmData Synthesized raw 16-bit Little-Endian PCM audio bytes.
 * @property wavData Packaged WAV audio bytes with 44-byte RIFF header.
 * @property synthMs Wall-clock time the engine took to synthesize this chunk (0 when unknown); timing
 *   metadata only, so it is left out of [equals] and [hashCode].
 */
data class SynthesizedChunk(
    val index: Int,
    val dialogueChunk: DialogueChunk,
    val pcmData: ByteArray,
    val wavData: ByteArray,
    val synthMs: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SynthesizedChunk

        if (index != other.index) return false
        if (dialogueChunk != other.dialogueChunk) return false
        if (!pcmData.contentEquals(other.pcmData)) return false
        if (!wavData.contentEquals(other.wavData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + dialogueChunk.hashCode()
        result = 31 * result + pcmData.contentHashCode()
        result = 31 * result + wavData.contentHashCode()
        return result
    }
}

/**
 * Backpressure-bounded neural TTS synthesis pipeline orchestrator.
 *
 * Streams linearized [DialogueChunk] utterances through neural TTS synthesis, packages raw PCM into
 * valid RIFF WAV containers, and buffers completed [SynthesizedChunk] units into a bounded Kotlin [Channel].
 *
 * Guarantees that at most [BUFFER_CAPACITY] (2) unconsumed chunks are synthesized ahead of playback,
 * automatically suspending producer inference to maintain a 0% CPU idle duty cycle when consumer playback
 * is paused or buffered.
 *
 * @param ttsEngine The neural text-to-speech synthesis engine.
 * @param scope Coroutine scope in which synthesis producer coroutines are launched.
 * @param chunkTimeoutMs Timeout in milliseconds per chunk synthesis utterance before skipping (default: [DEFAULT_CHUNK_TIMEOUT_MS]).
 * @param maxQualityStepsForIndex Optional per-chunk cap on the engine's quality steps (null = engine setting),
 *   used to synthesize the opening chunks faster so playback starts sooner.
 * @param cachedWav A finished WAV (pauses included) for a chunk that was synthesized earlier, used as is.
 * @param awaitTurn Suspends the producer before each chunk, for the look-ahead bound and while paused.
 * @param onSynthesized Reports each chunk's index and playback length once it exists.
 * @param preparedWav When set, every chunk's finished WAV (pauses included) comes from here instead of
 *   the engine. Throwing [TtsUnavailableException] stops generation; anything else skips the chunk.
 */
class PlaybackOrchestrator(
    private val ttsEngine: TtsEngine,
    private val scope: CoroutineScope,
    val chunkTimeoutMs: Long = DEFAULT_CHUNK_TIMEOUT_MS,
    private val maxQualityStepsForIndex: (Int) -> Int? = { null },
    private val cachedWav: (Int) -> ByteArray? = { null },
    private val awaitTurn: suspend () -> Unit = {},
    private val onSynthesized: (Int, Long) -> Unit = { _, _ -> },
    private val preparedWav: (suspend (Int) -> ByteArray)? = null,
) {
    val audioChannel: Channel<SynthesizedChunk> =
        Channel(
            capacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    private val capacitySemaphore = Semaphore(BUFFER_CAPACITY)
    private var synthesisJob: Job? = null
    private var isReleased: Boolean = false

    /** Set when the engine reported it cannot work; the channel then closes after what was already made. */
    @Volatile
    var unavailable: TtsUnavailableException? = null
        private set
    var currentDialogueList: List<DialogueChunk> = emptyList()
        private set

    /**
     * Starts the asynchronous synthesis pipeline for [dialogueList] starting at [startIndex].
     *
     * Launches a producer coroutine in [scope] that iterates sequentially from [startIndex]
     * to the end of [dialogueList]. Backpressure is maintained via [capacitySemaphore] ensuring
     * no more than [BUFFER_CAPACITY] items are synthesized ahead of consumer consumption.
     *
     * If [startIndex] is invalid (< 0 or >= dialogueList.size) or [dialogueList] is empty,
     * the channel is closed cleanly and the pipeline terminates immediately.
     *
     * @param dialogueList Ordered list of dialogue utterances to synthesize.
     * @param startIndex Zero-based starting index within [dialogueList] (default: 0).
     */
    fun startPipeline(
        dialogueList: List<DialogueChunk>,
        startIndex: Int = 0,
    ) {
        if (isReleased) {
            audioChannel.close()
            return
        }
        currentDialogueList = dialogueList
        synthesisJob?.cancel()
        while (audioChannel.tryReceive().isSuccess) {
            capacitySemaphore.release()
        }
        if (dialogueList.isEmpty() || startIndex < 0 || startIndex >= dialogueList.size) {
            audioChannel.close()
            return
        }
        launchSynthesis(startIndex)
    }

    /**
     * Cancels any active in-flight synthesis, drains stale buffered audio chunks, and resumes synthesis
     * starting strictly from [targetIndex].
     *
     * Releases capacity permits for all drained buffered items and in-flight synthesis so that subsequent
     * synthesis calls do not deadlock or exhaust permits.
     *
     * @param targetIndex Zero-based target index within [currentDialogueList] to jump to.
     */
    fun jumpTo(targetIndex: Int) {
        if (isReleased) {
            return
        }
        synthesisJob?.cancel()
        while (audioChannel.tryReceive().isSuccess) {
            capacitySemaphore.release()
        }
        if (currentDialogueList.isEmpty() || targetIndex < 0 || targetIndex >= currentDialogueList.size) {
            audioChannel.close()
            return
        }
        launchSynthesis(targetIndex)
    }

    private fun launchSynthesis(startIndex: Int) {
        synthesisJob =
            scope.launch {
                try {
                    for (i in startIndex until currentDialogueList.size) {
                        currentCoroutineContext().ensureActive()
                        capacitySemaphore.acquire()
                        var sent = false
                        try {
                            awaitTurn()
                            currentCoroutineContext().ensureActive()
                            val chunk = currentDialogueList[i]
                            val cached = cachedWav(i)
                            if (cached != null) {
                                val cachedPcm = cached.copyOfRange(minOf(WavUtil.HEADER_SIZE, cached.size), cached.size)
                                onSynthesized(i, wavDurationMs(cached))
                                audioChannel.send(SynthesizedChunk(i, chunk, cachedPcm, cached))
                                sent = true
                                continue
                            }
                            if (preparedWav != null) {
                                val wav =
                                    try {
                                        preparedWav.invoke(i)
                                    } catch (e: TtsUnavailableException) {
                                        unavailable = e
                                        audioChannel.close()
                                        return@launch
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        logger.warning("Prepared audio failed on chunk ${chunk.nodeId}: ${e.message}, skipping")
                                        continue
                                    }
                                val pcm = wav.copyOfRange(minOf(WavUtil.HEADER_SIZE, wav.size), wav.size)
                                onSynthesized(i, wavDurationMs(wav))
                                audioChannel.send(SynthesizedChunk(i, chunk, pcm, wav))
                                sent = true
                                continue
                            }
                            val stepsCap = maxQualityStepsForIndex(i)
                            val text = chunk.spokenText
                            val synthStartedAt = System.nanoTime()
                            val rawWav =
                                try {
                                    withTimeout(chunkTimeoutMs) {
                                        if (stepsCap != null) {
                                            ttsEngine.synthesizeToWav(text, chunk.voiceId, stepsCap)
                                        } else {
                                            ttsEngine.synthesizeToWav(text, chunk.voiceId)
                                        }
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    try {
                                        android.util.Log.w("PlaybackOrchestrator", "Synthesis timeout on chunk ${chunk.nodeId}, skipping")
                                    } catch (_: Throwable) {
                                        logger.warning("Synthesis timeout on chunk ${chunk.nodeId}, skipping")
                                    }
                                    continue
                                } catch (e: TtsUnavailableException) {
                                    unavailable = e
                                    audioChannel.close()
                                    return@launch
                                } catch (e: Exception) {
                                    try {
                                        android.util.Log.e(
                                            "PlaybackOrchestrator",
                                            "Synthesis error on chunk ${chunk.nodeId}: ${e.message}, skipping",
                                            e,
                                        )
                                    } catch (_: Throwable) {
                                        logger.warning("Synthesis error on chunk ${chunk.nodeId}: ${e.message}, skipping")
                                    }
                                    continue
                                }
                            val synthMs = (System.nanoTime() - synthStartedAt) / 1_000_000L
                            logSynthesisSpeed(i, rawWav, synthMs)
                            val pauseMs = pauseAfterMs(chunk, currentDialogueList.getOrNull(i + 1))
                            val wav = WavUtil.appendSilence(rawWav, pauseMs)
                            val pcm = if (wav.size > WavUtil.HEADER_SIZE) wav.copyOfRange(WavUtil.HEADER_SIZE, wav.size) else wav
                            val synthesized =
                                SynthesizedChunk(
                                    index = i,
                                    dialogueChunk = chunk,
                                    pcmData = pcm,
                                    wavData = wav,
                                    synthMs = synthMs,
                                )
                            currentCoroutineContext().ensureActive()
                            onSynthesized(i, wavDurationMs(wav))
                            audioChannel.send(synthesized)
                            sent = true
                        } finally {
                            if (!sent) {
                                capacitySemaphore.release()
                            }
                        }
                    }
                    audioChannel.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    audioChannel.close(t)
                }
            }
    }

    /**
     * Receives the next synthesized chunk from [audioChannel], suspending if the channel is currently empty.
     *
     * When a chunk is successfully received, a capacity permit is freed, allowing the producer coroutine
     * to synthesize the next utterance in the sequence.
     *
     * @return The next [SynthesizedChunk] in the sequence, or null if the channel has been closed or cancelled.
     */
    suspend fun nextChunk(): SynthesizedChunk? {
        val chunk = audioChannel.receiveCatching().getOrNull()
        if (chunk != null) {
            capacitySemaphore.release()
        }
        return chunk
    }

    /**
     * Cancels any active in-flight synthesis coroutine and closes [audioChannel].
     *
     * Idempotent; subsequent invocations have no additional side-effects.
     */
    fun release() {
        isReleased = true
        synthesisJob?.cancel()
        audioChannel.close()
    }

    private fun logSynthesisSpeed(
        index: Int,
        wav: ByteArray,
        elapsedMs: Long,
    ) {
        val audioMs = wavDurationMs(wav)
        val rtf = if (audioMs > 0L) elapsedMs.toDouble() / audioMs else 0.0
        val message = "SYNTH chunk=$index audio_ms=$audioMs synth_ms=$elapsedMs rtf=${"%.2f".format(java.util.Locale.ROOT, rtf)}"
        try {
            android.util.Log.i(TIMING_TAG, message)
        } catch (_: Throwable) {
            logger.fine(message)
        }
    }

    companion object {
        const val BUFFER_CAPACITY: Int = 2

        /** Logcat tag shared with the time-to-first-audio log. */
        private const val TIMING_TAG: String = "ThreadReaderTiming"

        /**
         * Playback length of a canonical 44-byte-header PCM WAV, read from its header; 0 for anything
         * else (for example an empty or headerless buffer).
         */
        fun wavDurationMs(wav: ByteArray): Long {
            if (wav.size <= WavUtil.HEADER_SIZE) return 0L
            val header = java.nio.ByteBuffer.wrap(wav, 0, WavUtil.HEADER_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val channels = header.getShort(22).toInt()
            val sampleRate = header.getInt(24)
            val bitsPerSample = header.getShort(34).toInt()
            val frameBytes = channels * (bitsPerSample / 8)
            if (sampleRate <= 0 || frameBytes <= 0) return 0L
            return (wav.size - WavUtil.HEADER_SIZE).toLong() / frameBytes * 1000L / sampleRate
        }

        /**
         * Total silence after a chunk when the same speaker carries on in the next chunk of the same
         * post or comment. The engine already keeps [SilenceTrimmer.TRAILING_GUARD_MS] of tail.
         */
        const val SAME_SPEAKER_PAUSE_MS: Int = 150

        /** Total silence after a chunk when the next chunk is a different speaker, post or comment. */
        const val SPEAKER_CHANGE_PAUSE_MS: Int = 300

        /**
         * Milliseconds of silence to append after [current] so the gap before [next] sounds natural:
         * [SAME_SPEAKER_PAUSE_MS] when the same speaker continues the same post or comment,
         * [SPEAKER_CHANGE_PAUSE_MS] at a speaker or comment boundary, minus the
         * [SilenceTrimmer.TRAILING_GUARD_MS] the engine already left; nothing after the last chunk.
         */
        fun pauseAfterMs(
            current: DialogueChunk,
            next: DialogueChunk?,
        ): Int {
            if (next == null) return 0
            val sameSpeaker = next.nodeId == current.nodeId && next.author == current.author && next.voiceId == current.voiceId
            val total = if (sameSpeaker) SAME_SPEAKER_PAUSE_MS else SPEAKER_CHANGE_PAUSE_MS
            return (total - SilenceTrimmer.TRAILING_GUARD_MS).coerceAtLeast(0)
        }

        const val DEFAULT_CHUNK_TIMEOUT_MS: Long = 25000L
        private val logger: Logger = Logger.getLogger(PlaybackOrchestrator::class.java.name)
    }
}
