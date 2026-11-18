package com.redder.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.redder.audio.SilenceTrimmer
import com.redder.audio.WavUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Abstraction for an active Supertonic 3 neural TTS inference session.
 */
interface SupertonicSession : AutoCloseable {
    /**
     * Like [runInference], but [isCancelled] is checked between stages, so a stop does not have to
     * wait for a long chunk. Cancelling throws [kotlinx.coroutines.CancellationException].
     */
    fun runCancellableInference(
        textIds: LongArray,
        textMask: Array<Array<FloatArray>>,
        styleTtl: Array<Array<FloatArray>>,
        styleDp: Array<Array<FloatArray>>,
        totalSteps: Int,
        speed: Float,
        isCancelled: () -> Boolean,
    ): FloatArray = runInference(textIds, textMask, styleTtl, styleDp, totalSteps, speed)

    fun runInference(
        textIds: LongArray,
        textMask: Array<Array<FloatArray>>,
        styleTtl: Array<Array<FloatArray>>,
        styleDp: Array<Array<FloatArray>>,
        totalSteps: Int = 8,
        speed: Float = 1.05f,
    ): FloatArray
}

/**
 * Real ONNX Runtime session execution implementation of [SupertonicSession].
 */
class OrtSupertonicSession(
    val session: OrtSession,
    private val env: OrtEnvironment,
) : SupertonicSession {
    override fun runInference(
        textIds: LongArray,
        textMask: Array<Array<FloatArray>>,
        styleTtl: Array<Array<FloatArray>>,
        styleDp: Array<Array<FloatArray>>,
        totalSteps: Int,
        speed: Float,
    ): FloatArray {
        val inputNames = session.inputNames
        val tensorsToClose = ArrayList<OnnxTensor>()
        val feed = HashMap<String, OnnxTensor>()
        try {
            val feedAll = inputNames.isEmpty()

            // textIds tensor: [1, seqLen]
            val textIdsKey =
                when {
                    "text_ids" in inputNames -> "text_ids"
                    "textIds" in inputNames -> "textIds"
                    "input_ids" in inputNames -> "input_ids"
                    "tokens" in inputNames -> "tokens"
                    feedAll -> "text_ids"
                    else -> null
                }
            if (textIdsKey != null) {
                val t = OnnxTensor.createTensor(env, arrayOf(textIds))
                tensorsToClose.add(t)
                feed[textIdsKey] = t
            }

            // textMask tensor: [1, 1, seqLen]
            val textMaskKey =
                when {
                    "text_mask" in inputNames -> "text_mask"
                    "textMask" in inputNames -> "textMask"
                    "mask" in inputNames -> "mask"
                    feedAll -> "text_mask"
                    else -> null
                }
            if (textMaskKey != null) {
                val t = OnnxTensor.createTensor(env, textMask)
                tensorsToClose.add(t)
                feed[textMaskKey] = t
            }

            // styleTtl tensor: [1, 50, 256]
            val styleTtlKey =
                when {
                    "style_ttl" in inputNames -> "style_ttl"
                    "styleTtl" in inputNames -> "styleTtl"
                    feedAll -> "style_ttl"
                    else -> null
                }
            if (styleTtlKey != null) {
                val t = OnnxTensor.createTensor(env, styleTtl)
                tensorsToClose.add(t)
                feed[styleTtlKey] = t
            }

            // styleDp tensor: [1, 8, 16]
            val styleDpKey =
                when {
                    "style_dp" in inputNames -> "style_dp"
                    "styleDp" in inputNames -> "styleDp"
                    feedAll -> "style_dp"
                    else -> null
                }
            if (styleDpKey != null) {
                val t = OnnxTensor.createTensor(env, styleDp)
                tensorsToClose.add(t)
                feed[styleDpKey] = t
            }

            // totalSteps tensor: [1]
            val totalStepsKey =
                when {
                    "total_steps" in inputNames -> "total_steps"
                    "totalSteps" in inputNames -> "totalSteps"
                    "steps" in inputNames -> "steps"
                    feedAll -> "total_steps"
                    else -> null
                }
            if (totalStepsKey != null) {
                val t = OnnxTensor.createTensor(env, longArrayOf(totalSteps.toLong()))
                tensorsToClose.add(t)
                feed[totalStepsKey] = t
            }

            // speed tensor: [1]
            val speedKey =
                when {
                    "speed" in inputNames -> "speed"
                    feedAll -> "speed"
                    else -> null
                }
            if (speedKey != null) {
                val t = OnnxTensor.createTensor(env, floatArrayOf(speed))
                tensorsToClose.add(t)
                feed[speedKey] = t
            }

            session.run(feed).use { result ->
                val outputTensor =
                    findAudioTensor(result)
                        ?: throw IllegalStateException("Model inference produced no output audio tensor")
                return extractAudioFloatsFromTensor(outputTensor)
            }
        } finally {
            for (tensor in tensorsToClose) {
                tensor.close()
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun findAudioTensor(result: OrtSession.Result): OnnxTensor? {
        val audioOpt = result.get("audio")
        if (audioOpt.isPresent && audioOpt.get() is OnnxTensor) {
            return audioOpt.get() as OnnxTensor
        }
        val outputOpt = result.get("output")
        if (outputOpt.isPresent && outputOpt.get() is OnnxTensor) {
            return outputOpt.get() as OnnxTensor
        }
        val wavOpt = result.get("wav")
        if (wavOpt.isPresent && wavOpt.get() is OnnxTensor) {
            return wavOpt.get() as OnnxTensor
        }
        if (result.size() > 0 && result.get(0) is OnnxTensor) {
            return result.get(0) as OnnxTensor
        }
        return null
    }
}

private fun directFloatBuffer(data: FloatArray): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    fb.put(data)
    fb.position(0)
    return fb
}

private fun logTts(message: String) {
    try {
        android.util.Log.i("SupertonicTTS", message)
    } catch (_: Throwable) {
        // Fallback for JVM unit test environments without Android framework logging
    }
}

/**
 * Latent geometry for one utterance, computed as upstream `sample_noisy_latent` does: the latent
 * length comes from the float sample count, the mask from the truncated integer sample count.
 */
internal class SupertonicLatentShape(duration: Float, sampleRate: Int) {
    val wavLength: Int = (duration * sampleRate).toInt()
    val latentLength: Int = maxOf(1, ((duration * sampleRate + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt())
    val validLength: Int = minOf(latentLength, (wavLength + CHUNK_SIZE - 1) / CHUNK_SIZE)

    fun mask(): FloatArray = FloatArray(latentLength) { if (it < validLength) 1.0f else 0.0f }

    companion object {
        const val BASE_CHUNK_SIZE: Int = 512
        const val CHUNK_COMPRESS_FACTOR: Int = 6
        const val CHUNK_SIZE: Int = BASE_CHUNK_SIZE * CHUNK_COMPRESS_FACTOR
        const val LATENT_DIM: Int = 24 * CHUNK_COMPRESS_FACTOR
    }
}

/**
 * Real ONNX Runtime 4-stage neural pipeline execution implementation of [SupertonicSession].
 * Executes duration predictor, text encoder, vector estimator flow-matching loop, and vocoder.
 *
 * @param noiseRandom Source of the initial latent noise. It is shared across calls so every
 *   utterance starts from fresh noise, as upstream's global NumPy generator does.
 */
class Ort4StageSupertonicSession(
    private val dpSession: OrtSession,
    private val textEncSession: OrtSession,
    private val vectorEstSession: OrtSession,
    private val vocoderSession: OrtSession,
    private val env: OrtEnvironment,
    private val noiseRandom: java.util.Random = java.util.Random(),
) : SupertonicSession {
    override fun runInference(
        textIds: LongArray,
        textMask: Array<Array<FloatArray>>,
        styleTtl: Array<Array<FloatArray>>,
        styleDp: Array<Array<FloatArray>>,
        totalSteps: Int,
        speed: Float,
    ): FloatArray = runCancellableInference(textIds, textMask, styleTtl, styleDp, totalSteps, speed) { false }

    override fun runCancellableInference(
        textIds: LongArray,
        textMask: Array<Array<FloatArray>>,
        styleTtl: Array<Array<FloatArray>>,
        styleDp: Array<Array<FloatArray>>,
        totalSteps: Int,
        speed: Float,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val seqLen = textIds.size
        if (seqLen == 0) return FloatArray(0)

        logTts("Starting 4-stage neural pipeline: seqLen=$seqLen, steps=$totalSteps, speed=$speed")
        val duration = runDurationPredictor(textIds, styleDp, textMask, speed)
        logTts("Duration predictor finished: duration=${duration}s")
        val textEmbFloats = runTextEncoder(textIds, styleTtl, textMask)
        logTts("Text encoder finished: textEmb.size=${textEmbFloats.size}")
        val shape = SupertonicLatentShape(duration, SupertonicOnnxTtsEngine.DEFAULT_SAMPLE_RATE)
        val denoisedLatentFloats =
            runVectorEstimator(
                shape = shape,
                seqLen = seqLen,
                textEmbFloats = textEmbFloats,
                styleTtl = styleTtl,
                totalSteps = totalSteps,
                isCancelled = isCancelled,
            )
        if (isCancelled()) throw CancellationException("synthesis cancelled")
        logTts("Vector estimator loop finished: latent.size=${denoisedLatentFloats.size}")
        val audioFloats =
            runVocoder(
                denoisedLatentFloats = denoisedLatentFloats,
                shape = shape,
            )
        logTts("Vocoder finished: audioFloats.size=${audioFloats.size}")
        return audioFloats
    }

    private fun runDurationPredictor(
        textIds: LongArray,
        styleDp: Array<Array<FloatArray>>,
        textMask: Array<Array<FloatArray>>,
        speed: Float,
    ): Float {
        val tensorsToClose = ArrayList<OnnxTensor>()
        try {
            val textIdsTensor = OnnxTensor.createTensor(env, arrayOf(textIds))
            tensorsToClose.add(textIdsTensor)
            val styleDpTensor = OnnxTensor.createTensor(env, styleDp)
            tensorsToClose.add(styleDpTensor)
            val textMaskTensor = OnnxTensor.createTensor(env, textMask)
            tensorsToClose.add(textMaskTensor)

            val feed =
                mapOf(
                    "text_ids" to textIdsTensor,
                    "style_dp" to styleDpTensor,
                    "text_mask" to textMaskTensor,
                )
            dpSession.run(feed).use { result ->
                val outputTensor = result.get(0) as OnnxTensor
                val durFloats = extractAudioFloatsFromTensor(outputTensor)
                val rawDuration = if (durFloats.isNotEmpty()) durFloats[0] else 1.0f
                val safeSpeed = if (speed <= 0f) 1.0f else speed
                return maxOf(0.1f, rawDuration / safeSpeed)
            }
        } finally {
            for (t in tensorsToClose) t.close()
        }
    }

    private fun runTextEncoder(
        textIds: LongArray,
        styleTtl: Array<Array<FloatArray>>,
        textMask: Array<Array<FloatArray>>,
    ): FloatArray {
        val tensorsToClose = ArrayList<OnnxTensor>()
        try {
            val textIdsTensor = OnnxTensor.createTensor(env, arrayOf(textIds))
            tensorsToClose.add(textIdsTensor)
            val styleTtlTensor = OnnxTensor.createTensor(env, styleTtl)
            tensorsToClose.add(styleTtlTensor)
            val textMaskTensor = OnnxTensor.createTensor(env, textMask)
            tensorsToClose.add(textMaskTensor)

            val feed =
                mapOf(
                    "text_ids" to textIdsTensor,
                    "style_ttl" to styleTtlTensor,
                    "text_mask" to textMaskTensor,
                )
            textEncSession.run(feed).use { result ->
                val outputTensor = result.get(0) as OnnxTensor
                return extractAudioFloatsFromTensor(outputTensor)
            }
        } finally {
            for (t in tensorsToClose) t.close()
        }
    }

    private fun runVectorEstimator(
        shape: SupertonicLatentShape,
        seqLen: Int,
        textEmbFloats: FloatArray,
        styleTtl: Array<Array<FloatArray>>,
        totalSteps: Int,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val steps = maxOf(1, totalSteps)
        val latentLen = shape.latentLength
        val latentDim = SupertonicLatentShape.LATENT_DIM

        var noisyLatentFloats =
            synchronized(noiseRandom) {
                FloatArray(latentDim * latentLen) { noiseRandom.nextGaussian().toFloat() }
            }
        // noise * latent_mask, as upstream does before the flow-matching loop
        val latentMaskFloats = shape.mask()
        for (i in noisyLatentFloats.indices) {
            noisyLatentFloats[i] *= latentMaskFloats[i % latentLen]
        }
        val textMaskFloats = FloatArray(seqLen) { 1.0f }

        for (step in 0 until steps) {
            if (isCancelled()) throw CancellationException("synthesis cancelled")
            val stepTensors = ArrayList<OnnxTensor>()
            try {
                val noisyTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(noisyLatentFloats),
                        longArrayOf(1L, latentDim.toLong(), latentLen.toLong()),
                    )
                stepTensors.add(noisyTensor)

                val textEmbTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(textEmbFloats),
                        longArrayOf(1L, 256L, seqLen.toLong()),
                    )
                stepTensors.add(textEmbTensor)

                val styleTtlTensor = OnnxTensor.createTensor(env, styleTtl)
                stepTensors.add(styleTtlTensor)

                val latentMaskTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(latentMaskFloats),
                        longArrayOf(1L, 1L, latentLen.toLong()),
                    )
                stepTensors.add(latentMaskTensor)

                val textMaskTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(textMaskFloats),
                        longArrayOf(1L, 1L, seqLen.toLong()),
                    )
                stepTensors.add(textMaskTensor)

                val currentStepTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(floatArrayOf(step.toFloat())),
                        longArrayOf(1L),
                    )
                stepTensors.add(currentStepTensor)

                val totalStepTensor =
                    OnnxTensor.createTensor(
                        env,
                        directFloatBuffer(floatArrayOf(steps.toFloat())),
                        longArrayOf(1L),
                    )
                stepTensors.add(totalStepTensor)

                val feed =
                    mapOf(
                        "noisy_latent" to noisyTensor,
                        "text_emb" to textEmbTensor,
                        "style_ttl" to styleTtlTensor,
                        "latent_mask" to latentMaskTensor,
                        "text_mask" to textMaskTensor,
                        "current_step" to currentStepTensor,
                        "total_step" to totalStepTensor,
                    )

                vectorEstSession.run(feed).use { result ->
                    val denoisedTensor = result.get(0) as OnnxTensor
                    noisyLatentFloats = extractAudioFloatsFromTensor(denoisedTensor)
                }
            } finally {
                for (t in stepTensors) t.close()
            }
        }

        return noisyLatentFloats
    }

    private fun runVocoder(
        denoisedLatentFloats: FloatArray,
        shape: SupertonicLatentShape,
    ): FloatArray {
        val wavLen = shape.wavLength
        val latentLen = shape.latentLength
        val latentDim = SupertonicLatentShape.LATENT_DIM

        val tensorsToClose = ArrayList<OnnxTensor>()
        try {
            val latentTensor =
                OnnxTensor.createTensor(
                    env,
                    directFloatBuffer(denoisedLatentFloats),
                    longArrayOf(1L, latentDim.toLong(), latentLen.toLong()),
                )
            tensorsToClose.add(latentTensor)

            val feed = mapOf("latent" to latentTensor)
            vocoderSession.run(feed).use { result ->
                val wavTensor = result.get(0) as OnnxTensor
                val fullWav = extractAudioFloatsFromTensor(wavTensor)
                val finalLength = minOf(wavLen, fullWav.size)
                return if (finalLength > 0 && finalLength <= fullWav.size) {
                    fullWav.copyOfRange(0, finalLength)
                } else {
                    fullWav
                }
            }
        } finally {
            for (t in tensorsToClose) t.close()
        }
    }

    override fun close() {
        dpSession.close()
        textEncSession.close()
        vectorEstSession.close()
        vocoderSession.close()
    }
}

/**
 * Provider interface for resolving and lifecycle-managing [SupertonicSession] instances.
 */
interface SupertonicSessionProvider : AutoCloseable {
    fun getSession(voiceId: String): SupertonicSession
}

/**
 * [SupertonicSessionProvider] wrapping a single pre-existing [SupertonicSession].
 */
class SingleSupertonicSessionProvider(
    private val session: SupertonicSession,
) : SupertonicSessionProvider {
    @Volatile
    private var isClosed = false

    override fun getSession(voiceId: String): SupertonicSession {
        if (isClosed) {
            throw IllegalStateException("SupertonicSessionProvider has been closed")
        }
        require(voiceId.isNotBlank()) { "voiceId cannot be blank" }
        return session
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        session.close()
    }
}

/**
 * [SupertonicSessionProvider] managing ONNX Runtime sessions created from disk paths.
 */
class OrtSupertonicSessionProvider(
    private val modelPathResolver: ModelPathResolver? = null,
    private val modelBytesResolver: ModelBytesResolver? = null,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
    private val sessionConfig: OrtSessionConfig = OrtSessionConfig(),
    private val ownsEnvironment: Boolean = false,
) : SupertonicSessionProvider {
    constructor(
        modelPathResolver: ModelPathResolver,
        environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
        sessionConfig: OrtSessionConfig = OrtSessionConfig(),
    ) : this(
        modelPathResolver = modelPathResolver,
        modelBytesResolver = null,
        environment = environment,
        sessionConfig = sessionConfig,
        ownsEnvironment = false,
    )

    constructor(
        modelBytesResolver: ModelBytesResolver,
        environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
        sessionConfig: OrtSessionConfig = OrtSessionConfig(),
    ) : this(
        modelPathResolver = null,
        modelBytesResolver = modelBytesResolver,
        environment = environment,
        sessionConfig = sessionConfig,
        ownsEnvironment = false,
    )

    private val activeSessions = ConcurrentHashMap<String, SupertonicSession>()

    @Volatile
    private var isClosed = false

    override fun getSession(voiceId: String): SupertonicSession {
        if (isClosed) {
            throw IllegalStateException("SupertonicSessionProvider has been closed")
        }
        require(voiceId.isNotBlank()) { "voiceId cannot be blank" }

        return if (modelPathResolver != null) {
            val modelPath = modelPathResolver.resolvePath(voiceId)
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                throw FileNotFoundException("Supertonic voice model not found: $modelPath")
            }
            activeSessions.computeIfAbsent("path:$modelPath") { _ ->
                val sessionOptions = sessionConfig.createSessionOptions()
                try {
                    val dpFile =
                        if (modelFile.isDirectory) {
                            File(modelFile, SupertonicAssetManager.DURATION_PREDICTOR_FILENAME)
                        } else {
                            File(modelFile.parentFile ?: modelFile, SupertonicAssetManager.DURATION_PREDICTOR_FILENAME)
                        }
                    if (dpFile.exists()) {
                        val baseDir = dpFile.parentFile ?: modelFile
                        val textEncFile = File(baseDir, SupertonicAssetManager.TEXT_ENCODER_FILENAME)
                        val vectorEstFile = File(baseDir, SupertonicAssetManager.VECTOR_ESTIMATOR_FILENAME)
                        val vocoderFile = File(baseDir, SupertonicAssetManager.VOCODER_FILENAME)

                        val dpSession = environment.createSession(dpFile.absolutePath, sessionOptions)
                        val textEncSession = environment.createSession(textEncFile.absolutePath, sessionOptions)
                        val vectorEstSession = environment.createSession(vectorEstFile.absolutePath, sessionOptions)
                        val vocoderSession = environment.createSession(vocoderFile.absolutePath, sessionOptions)

                        Ort4StageSupertonicSession(
                            dpSession = dpSession,
                            textEncSession = textEncSession,
                            vectorEstSession = vectorEstSession,
                            vocoderSession = vocoderSession,
                            env = environment,
                        )
                    } else {
                        val ortSession = environment.createSession(modelPath, sessionOptions)
                        OrtSupertonicSession(ortSession, environment)
                    }
                } finally {
                    sessionOptions.close()
                }
            }
        } else if (modelBytesResolver != null) {
            val modelBytes = modelBytesResolver.resolveBytes(voiceId)
            val cacheKey = "bytes:"
            activeSessions.computeIfAbsent(cacheKey) { _ ->
                val sessionOptions = sessionConfig.createSessionOptions()
                try {
                    val ortSession = environment.createSession(modelBytes, sessionOptions)
                    OrtSupertonicSession(ortSession, environment)
                } finally {
                    sessionOptions.close()
                }
            }
        } else {
            throw IllegalStateException("Neither modelPathResolver nor modelBytesResolver was configured")
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        for (session in activeSessions.values) {
            session.close()
        }
        activeSessions.clear()
        if (ownsEnvironment) {
            environment.close()
        }
    }
}

private fun resolveTextProcessor(
    modelPathResolver: ModelPathResolver,
    textProcessor: SupertonicTextProcessor?,
): SupertonicTextProcessor {
    if (textProcessor != null && textProcessor.indexer != null) {
        return textProcessor
    }
    return try {
        val samplePath = modelPathResolver.resolvePath(SupertonicVoiceCatalog.DEFAULT_OP_VOICE)
        val sampleFile = File(samplePath)
        val baseDir = if (sampleFile.isDirectory) sampleFile else (sampleFile.parentFile ?: sampleFile)
        val indexerFile = File(baseDir, SupertonicAssetManager.UNICODE_INDEXER_FILENAME)
        if (indexerFile.exists()) {
            SupertonicTextProcessor.fromFile(indexerFile)
        } else {
            textProcessor ?: SupertonicTextProcessor()
        }
    } catch (_: Throwable) {
        textProcessor ?: SupertonicTextProcessor()
    }
}

/**
 * On-device neural TTS synthesis engine powered by Supertonic 3 ONNX architecture.
 *
 * Implements [TtsEngine] to synthesize 44,100 Hz 16-bit Little-Endian PCM audio waveforms
 * using flow matching diffusion steps, style conditioning vectors, and saturation-guaranteed
 * float-to-PCM conversion.
 *
 * @param sessionProvider Factory and cache for [SupertonicSession] instances.
 * @param textProcessor Text normalizer and token mapper for Supertonic input.
 * @param sampleRate Output sampling rate in Hz (defaults to 44,100 Hz).
 * @param speed Speech synthesis speed factor (defaults to 1.05f).
 * @param totalSteps Number of neural diffusion steps (defaults to 8).
 * @param totalStepsProvider Optional live source for the step count, read on every synthesis call
 *   (overrides [totalSteps]); lets a quality preference change apply to the next utterance.
 */
class SupertonicOnnxTtsEngine(
    private val sessionProvider: SupertonicSessionProvider,
    private val textProcessor: SupertonicTextProcessor = SupertonicTextProcessor(),
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val speed: Float = DEFAULT_SPEED,
    val totalSteps: Int = DEFAULT_TOTAL_STEPS,
    val fallbackEngine: TtsEngine? = null,
    private val totalStepsProvider: (() -> Int)? = null,
) : TtsEngine {
    @Volatile
    var isReleased: Boolean = false
        private set

    constructor(
        session: SupertonicSession,
        textProcessor: SupertonicTextProcessor = SupertonicTextProcessor(),
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        speed: Float = DEFAULT_SPEED,
        totalSteps: Int = DEFAULT_TOTAL_STEPS,
        fallbackEngine: TtsEngine? = null,
    ) : this(
        sessionProvider = SingleSupertonicSessionProvider(session),
        textProcessor = textProcessor,
        sampleRate = sampleRate,
        speed = speed,
        totalSteps = totalSteps,
        fallbackEngine = fallbackEngine,
    )

    constructor(
        modelPathResolver: ModelPathResolver,
        environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
        sessionConfig: OrtSessionConfig = OrtSessionConfig(),
        textProcessor: SupertonicTextProcessor? = null,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        speed: Float = DEFAULT_SPEED,
        totalSteps: Int = DEFAULT_TOTAL_STEPS,
        fallbackEngine: TtsEngine? = null,
        totalStepsProvider: (() -> Int)? = null,
    ) : this(
        sessionProvider =
            OrtSupertonicSessionProvider(
                modelPathResolver = modelPathResolver,
                environment = environment,
                sessionConfig = sessionConfig,
            ),
        textProcessor = resolveTextProcessor(modelPathResolver, textProcessor),
        sampleRate = sampleRate,
        speed = speed,
        totalSteps = totalSteps,
        fallbackEngine = fallbackEngine,
        totalStepsProvider = totalStepsProvider,
    )

    /**
     * Returns the diffusion step count to use for the next synthesis call.
     */
    internal fun currentTotalSteps(): Int = totalStepsProvider?.invoke()?.takeIf { it > 0 } ?: totalSteps

    /**
     * Synthesizes raw 16-bit Little-Endian PCM audio bytes from input [text] using [voiceId].
     *
     * Invariants:
     * - Throws [IllegalStateException] if engine is released.
     * - Throws [IllegalArgumentException] if [voiceId] is blank.
     * - Converts text into tokens and attention mask.
     * - Injects TTL and DP style conditioning vectors from [SupertonicVoiceCatalog].
     * - Accurately converts floating-point output to 16-bit Little-Endian PCM with saturation clamping in [-32768, 32767].
     */
    override suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray {
        if (isReleased) {
            throw IllegalStateException("TtsEngine has been released")
        }
        require(voiceId.isNotBlank()) { "voiceId cannot be blank" }

        return try {
            synthesizeInternal(text, voiceId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logTts("Supertonic synthesis failed: ${t.message}. Delegating to fallback engine.")
            fallbackEngine?.synthesizeToPcm(text, voiceId) ?: throw t
        }
    }

    /**
     * Step count for one call: the configured count, lowered to [maxQualitySteps] when given.
     */
    internal fun effectiveTotalSteps(maxQualitySteps: Int?): Int {
        val configured = currentTotalSteps()
        return if (maxQualitySteps != null && maxQualitySteps > 0) minOf(configured, maxQualitySteps) else configured
    }

    private suspend fun synthesizeInternal(
        text: String,
        voiceId: String,
        maxQualitySteps: Int? = null,
    ): ByteArray {
        val processed = textProcessor.process(text)
        val textIds = processed.tokenIds
        val textMask = processed.textMask
        val styleTtl = SupertonicVoiceCatalog.getStyleTtl(voiceId)
        val styleDp = SupertonicVoiceCatalog.getStyleDp(voiceId)

        val startTime = System.currentTimeMillis()
        val session = sessionProvider.getSession(voiceId)
        val job = currentCoroutineContext()[Job]
        val floatSamples =
            session.runCancellableInference(
                textIds = textIds,
                textMask = textMask,
                styleTtl = styleTtl,
                styleDp = styleDp,
                totalSteps = effectiveTotalSteps(maxQualitySteps),
                speed = speed,
                isCancelled = { job?.isActive == false },
            )
        val elapsed = System.currentTimeMillis() - startTime
        val durationSec = floatSamples.size.toFloat() / sampleRate
        logTts(
            "synthesizeToPcm: voice=$voiceId, tokens=${textIds.size}, " +
                "samples=${floatSamples.size} (${durationSec}s), elapsed=${elapsed}ms",
        )
        return floatToPcm16Le(limitPeak(SilenceTrimmer.trim(floatSamples, sampleRate)))
    }

    /**
     * Synthesizes packaged RIFF WAV audio bytes from input [text] using [voiceId] at [sampleRate] (44,100 Hz).
     */
    override suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
    ): ByteArray = synthesizeToWav(text, voiceId, maxQualitySteps = null)

    /**
     * Synthesizes WAV bytes with the step count capped at [maxQualitySteps] for this call only.
     */
    override suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
        maxQualitySteps: Int?,
    ): ByteArray {
        if (isReleased) {
            throw IllegalStateException("TtsEngine has been released")
        }
        require(voiceId.isNotBlank()) { "voiceId cannot be blank" }

        return try {
            val pcm = synthesizeInternal(text, voiceId, maxQualitySteps)
            WavUtil.pcmToWav(pcm, sampleRate = sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logTts("Supertonic synthesizeToWav failed: ${t.message}. Delegating to fallback engine.")
            fallbackEngine?.synthesizeToWav(text, voiceId) ?: throw t
        }
    }

    /**
     * Creates the ONNX sessions and runs one tiny inference at [WARM_UP_STEPS] steps, discarding the
     * audio, so the first real utterance does not pay the one-time initialization cost. Failures are
     * logged and swallowed; real synthesis still has its own fallback path.
     */
    override suspend fun warmUp() {
        if (isReleased) return
        try {
            synthesizeInternal(WARM_UP_TEXT, SupertonicVoiceCatalog.DEFAULT_OP_VOICE, WARM_UP_STEPS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTts("Supertonic warm-up failed: ${e.message}")
        }
    }

    /**
     * Releases held sessions, environment handles, and resources.
     */
    override fun release() {
        if (isReleased) return
        isReleased = true
        sessionProvider.close()
        fallbackEngine?.release()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 44100
        const val DEFAULT_SPEED: Float = 1.05f
        const val DEFAULT_TOTAL_STEPS: Int = 8

        /** Throwaway utterance used by [warmUp]. */
        const val WARM_UP_TEXT: String = "Hello there."

        /** Diffusion steps used by [warmUp]. */
        const val WARM_UP_STEPS: Int = 4

        /** Peak level an over-range utterance is scaled down to instead of being hard-clipped. */
        const val PEAK_LIMIT: Float = 0.99f

        /**
         * Returns [samples] unchanged when they fit in [-1, 1]; otherwise scales the whole utterance
         * so its peak is [PEAK_LIMIT], which avoids the crackle of hard clipping.
         */
        fun limitPeak(samples: FloatArray): FloatArray {
            var peak = 0.0f
            for (s in samples) {
                if (!s.isNaN() && abs(s) > peak) peak = abs(s)
            }
            if (peak <= 1.0f) return samples
            val gain = PEAK_LIMIT / peak
            return FloatArray(samples.size) { samples[it] * gain }
        }

        /**
         * Converts [-1.0f, 1.0f] float audio samples to 16-bit Little-Endian PCM byte array
         * with hard saturation clamping to [-32768, 32767] and NaN values mapped to 0.
         */
        fun floatToPcm16Le(floatSamples: FloatArray): ByteArray {
            val pcmBytes = ByteArray(floatSamples.size * 2)
            for (i in floatSamples.indices) {
                val sample = floatSamples[i]
                val sampleInt =
                    if (sample.isNaN()) {
                        0
                    } else {
                        (sample * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                    }
                val sampleShort = sampleInt.toShort()
                val offset = i * 2
                pcmBytes[offset] = (sampleShort.toInt() and 0xFF).toByte()
                pcmBytes[offset + 1] = ((sampleShort.toInt() shr 8) and 0xFF).toByte()
            }
            return pcmBytes
        }
    }
}
