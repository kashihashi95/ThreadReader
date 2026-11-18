package com.redder.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

/** Shared ONNX Runtime helpers (ADR-004). */
class OrtSupportTest {
    @Test
    fun `unpackFloatArray correctly extracts values from 1D 2D 3D arrays and handles edge types`() {
        // 1D
        val d1 = floatArrayOf(0.1f, 0.2f)
        assertArrayEquals(d1, unpackFloatArray(d1))

        // 2D
        val d2 = arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f))
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), unpackFloatArray(d2))

        // 3D
        val d3 = arrayOf(arrayOf(floatArrayOf(0.5f, 0.6f)))
        assertArrayEquals(floatArrayOf(0.5f, 0.6f), unpackFloatArray(d3))

        // Numbers
        val numbers = arrayOf<Number>(1, 2.5f, 3.0)
        assertArrayEquals(floatArrayOf(1.0f, 2.5f, 3.0f), unpackFloatArray(numbers))

        // Unsupported types and null
        assertEquals(0, unpackFloatArray(null).size)
        assertEquals(0, unpackFloatArray("not an array").size)
        assertEquals(0, unpackFloatArray(42).size)
    }

    @Test
    fun `OrtSessionConfig defaults to two intra-op threads`() {
        assertEquals(2, OrtSessionConfig().intraOpNumThreads)
        assertEquals(OrtSessionConfig.DEFAULT_INTRA_OP_THREADS, OrtSessionConfig().intraOpNumThreads)
    }

    @Test
    fun `OrtSessionConfig builds session options and tensors flatten to floats`() {
        OrtSessionConfig(intraOpNumThreads = 1).createSessionOptions().use { }
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.1f, -0.2f, 0.3f)), longArrayOf(1, 3)).use { tensor ->
            assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.3f), extractAudioFloatsFromTensor(tensor))
        }
    }
}
