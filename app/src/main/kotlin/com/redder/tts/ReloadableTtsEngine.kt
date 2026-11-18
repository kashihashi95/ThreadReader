package com.redder.tts

import com.redder.audio.WavUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [TtsEngine] whose models can be freed while the playback pipeline keeps a reference to it:
 * [unload] drops the real engine and the next call builds a new one through [factory]. The real
 * engine is only touched under [mutex], so unloading waits for a running inference to finish and
 * never closes a model under it.
 */
class ReloadableTtsEngine(
    private val factory: () -> TtsEngine,
) : TtsEngine {
    private val mutex = Mutex()

    @Volatile
    private var inner: TtsEngine? = null

    @Volatile
    private var lastSampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE

    @Volatile
    private var released = false

    /** True while the real engine, and so possibly its models, is in memory. */
    val isLoaded: Boolean get() = inner != null

    override val sampleRate: Int get() = inner?.sampleRate ?: lastSampleRate

    private suspend fun <T> withEngine(block: suspend (TtsEngine) -> T): T =
        mutex.withLock {
            check(!released) { "TtsEngine has been released" }
            block(inner ?: factory().also { inner = it })
        }

    override suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray = withEngine { it.synthesizeToPcm(text, voiceId) }

    override suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
    ): ByteArray = withEngine { it.synthesizeToWav(text, voiceId) }

    override suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
        maxQualitySteps: Int?,
    ): ByteArray = withEngine { it.synthesizeToWav(text, voiceId, maxQualitySteps) }

    override suspend fun warmUp() {
        if (!released) withEngine { it.warmUp() }
    }

    /** Frees the models, after any running inference; the next synthesis loads them again. */
    suspend fun unload() {
        mutex.withLock { dropInner() }
    }

    /** Frees the models for good. Never blocks: during an inference the release happens right after it. */
    override fun release() {
        released = true
        if (mutex.tryLock()) {
            try {
                dropInner()
            } finally {
                mutex.unlock()
            }
        } else {
            CoroutineScope(Dispatchers.Default).launch { mutex.withLock { dropInner() } }
        }
    }

    private fun dropInner() {
        val engine = inner ?: return
        inner = null
        lastSampleRate = runCatching { engine.sampleRate }.getOrDefault(lastSampleRate)
        engine.release()
    }
}
