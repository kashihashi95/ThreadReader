package com.redder.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession

/**
 * Functional interface for converting raw input text or phonemes into token IDs.
 */
fun interface TokenMapper {
    /**
     * Maps the given [text] string into an array of token IDs.
     */
    fun mapTextToTokens(text: String): LongArray
}

/**
 * Functional interface resolving local filesystem paths for model checkpoints.
 */
fun interface ModelPathResolver {
    fun resolvePath(voiceId: String): String
}

/**
 * Functional interface resolving in-memory byte arrays for model checkpoints.
 */
fun interface ModelBytesResolver {
    fun resolveBytes(voiceId: String): ByteArray
}

/**
 * Configuration options for creating [OrtSession.SessionOptions].
 *
 * Default configuration sets `intra_op_num_threads = 2` and `OptLevel.ALL_OPT`
 * per mobile TTS deployment requirements.
 */
class OrtSessionConfig(
    val intraOpNumThreads: Int = DEFAULT_INTRA_OP_THREADS,
    val optimizationLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
) {
    fun createSessionOptions(): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(intraOpNumThreads)
        options.setOptimizationLevel(optimizationLevel)
        return options
    }

    companion object {
        const val DEFAULT_INTRA_OP_THREADS: Int = 2
    }
}

/**
 * Helper to extract a flattened [FloatArray] from an [OnnxTensor].
 */
internal fun extractAudioFloatsFromTensor(tensor: OnnxTensor): FloatArray {
    return try {
        val buffer = tensor.floatBuffer.asReadOnlyBuffer()
        val floats = FloatArray(buffer.remaining())
        buffer.get(floats)
        floats
    } catch (e: IllegalStateException) {
        unpackFloatArray(tensor.value)
    } catch (e: UnsupportedOperationException) {
        unpackFloatArray(tensor.value)
    }
}

/**
 * Recursively flattens multi-dimensional float arrays (e.g. [1, 1, N] or [1, N]) into a 1D [FloatArray].
 */
internal fun unpackFloatArray(value: Any?): FloatArray {
    return when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val list = ArrayList<Float>()
            collectFloatSamples(value, list)
            list.toFloatArray()
        }
        else -> FloatArray(0)
    }
}

private fun collectFloatSamples(
    item: Any?,
    destination: ArrayList<Float>,
) {
    when (item) {
        is FloatArray -> {
            for (f in item) {
                destination.add(f)
            }
        }
        is Array<*> -> {
            for (sub in item) {
                collectFloatSamples(sub, destination)
            }
        }
        is Number -> destination.add(item.toFloat())
    }
}
