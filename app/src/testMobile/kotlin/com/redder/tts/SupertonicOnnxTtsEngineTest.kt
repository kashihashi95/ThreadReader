package com.redder.tts

import com.redder.audio.WavUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupertonicOnnxTtsEngineTest {
    private class RecordingSupertonicSession(
        var outputSamples: FloatArray = floatArrayOf(0.1f, -0.2f, 0.5f, -0.5f),
    ) : SupertonicSession {
        var lastTextIds: LongArray? = null
        var lastTextMask: Array<Array<FloatArray>>? = null
        var lastStyleTtl: Array<Array<FloatArray>>? = null
        var lastStyleDp: Array<Array<FloatArray>>? = null
        var lastTotalSteps: Int = -1
        var lastSpeed: Float = -1.0f
        var isClosed: Boolean = false
        var inferenceCount: Int = 0

        override fun runInference(
            textIds: LongArray,
            textMask: Array<Array<FloatArray>>,
            styleTtl: Array<Array<FloatArray>>,
            styleDp: Array<Array<FloatArray>>,
            totalSteps: Int,
            speed: Float,
        ): FloatArray {
            inferenceCount++
            lastTextIds = textIds
            lastTextMask = textMask
            lastStyleTtl = styleTtl
            lastStyleDp = styleDp
            lastTotalSteps = totalSteps
            lastSpeed = speed
            return outputSamples
        }

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun `synthesizeToPcm delegates to SupertonicSession with expected parameters`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession, speed = 1.05f, totalSteps = 8)

            val pcm = engine.synthesizeToPcm("Hello test", "M1")

            assertEquals(1, fakeSession.inferenceCount)
            assertNotNull(fakeSession.lastTextIds)
            assertNotNull(fakeSession.lastTextMask)
            assertNotNull(fakeSession.lastStyleTtl)
            assertNotNull(fakeSession.lastStyleDp)
            assertEquals(8, fakeSession.lastTotalSteps)
            assertEquals(1.05f, fakeSession.lastSpeed, 1e-6f)
            assertEquals(fakeSession.outputSamples.size * 2, pcm.size)
        }

    @Test
    fun `default totalSteps is 8`() {
        val fakeSession = RecordingSupertonicSession()
        val engine = SupertonicOnnxTtsEngine(session = fakeSession)
        assertEquals(8, engine.totalSteps)
        assertEquals(8, SupertonicOnnxTtsEngine.DEFAULT_TOTAL_STEPS)
    }

    @Test
    fun `totalStepsProvider is read on every synthesis call`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            var steps = 4
            val engine =
                SupertonicOnnxTtsEngine(
                    sessionProvider = SingleSupertonicSessionProvider(fakeSession),
                    totalStepsProvider = { steps },
                )

            engine.synthesizeToPcm("First utterance", "M1")
            assertEquals(4, fakeSession.lastTotalSteps)

            steps = 8
            engine.synthesizeToPcm("Second utterance", "M1")
            assertEquals(8, fakeSession.lastTotalSteps)
        }

    @Test
    fun `invalid totalStepsProvider value falls back to totalSteps`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine =
                SupertonicOnnxTtsEngine(
                    sessionProvider = SingleSupertonicSessionProvider(fakeSession),
                    totalSteps = 8,
                    totalStepsProvider = { 0 },
                )

            engine.synthesizeToPcm("Hello", "M1")

            assertEquals(8, fakeSession.lastTotalSteps)
        }

    @Test
    fun `synthesizeToWav generates valid 44100 Hz WAV audio container`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession)

            val wavBytes = engine.synthesizeToWav("Testing audio", "F1")

            assertTrue(wavBytes.size > WavUtil.HEADER_SIZE)
            assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(wavBytes, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(wavBytes, 36, 4, Charsets.US_ASCII))

            val extractedSampleRate = WavUtil.extractSampleRateFromWav(wavBytes)
            assertEquals(44100, extractedSampleRate)
            assertEquals(SupertonicOnnxTtsEngine.DEFAULT_SAMPLE_RATE, extractedSampleRate)
            assertEquals(WavUtil.SUPERTONIC_SAMPLE_RATE, extractedSampleRate)
        }

    @Test
    fun `floatToPcm16Le clamps out-of-range values and handles NaN properly`() {
        val samples =
            floatArrayOf(
                0.0f,
                1.0f,
                -1.0f,
                // should clamp to +32767
                2.5f,
                // should clamp to -32768
                -3.0f,
                // should map to 0
                Float.NaN,
            )

        val pcm = SupertonicOnnxTtsEngine.floatToPcm16Le(samples)
        assertEquals(samples.size * 2, pcm.size)

        // Helper to extract short at index
        fun getShort(index: Int): Short {
            val offset = index * 2
            val low = pcm[offset].toInt() and 0xFF
            val high = pcm[offset + 1].toInt()
            return ((high shl 8) or low).toShort()
        }

        assertEquals(0.toShort(), getShort(0))
        assertEquals(32767.toShort(), getShort(1))
        assertEquals((-32767).toShort(), getShort(2)) // -1.0 * 32767 = -32767
        assertEquals(32767.toShort(), getShort(3)) // clamped
        assertEquals((-32768).toShort(), getShort(4)) // clamped
        assertEquals(0.toShort(), getShort(5)) // NaN -> 0
    }

    @Test
    fun `release is idempotent and causes subsequent synthesis to throw IllegalStateException`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession)

            assertFalse(engine.isReleased)
            assertFalse(fakeSession.isClosed)

            engine.release()
            assertTrue(engine.isReleased)
            assertTrue(fakeSession.isClosed)

            // Idempotent
            engine.release()
            assertTrue(engine.isReleased)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    engine.synthesizeToPcm("text", "M1")
                }
            }
        }

    @Test
    fun `synthesize throws IllegalArgumentException for blank voiceId`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    engine.synthesizeToPcm("text", "   ")
                }
            }
        }

    @Test
    fun `synthesizeToWav with a steps cap lowers the step count for that call only`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession, totalSteps = 8)

            engine.synthesizeToWav("Fast start", "M1", maxQualitySteps = 4)
            assertEquals(4, fakeSession.lastTotalSteps)

            engine.synthesizeToWav("Normal", "M1")
            assertEquals(8, fakeSession.lastTotalSteps)
        }

    @Test
    fun `steps cap never raises the configured step count`() {
        val engine = SupertonicOnnxTtsEngine(session = RecordingSupertonicSession(), totalSteps = 2)
        assertEquals(2, engine.effectiveTotalSteps(4))
        assertEquals(2, engine.effectiveTotalSteps(null))
    }

    @Test
    fun `warmUp runs one tiny inference at fast steps and swallows failures`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession()
            val engine = SupertonicOnnxTtsEngine(session = fakeSession, totalSteps = 8)

            engine.warmUp()

            assertEquals(1, fakeSession.inferenceCount)
            assertEquals(SupertonicOnnxTtsEngine.WARM_UP_STEPS, fakeSession.lastTotalSteps)

            val failing =
                object : SupertonicSession {
                    override fun runInference(
                        textIds: LongArray,
                        textMask: Array<Array<FloatArray>>,
                        styleTtl: Array<Array<FloatArray>>,
                        styleDp: Array<Array<FloatArray>>,
                        totalSteps: Int,
                        speed: Float,
                    ): FloatArray = throw IllegalStateException("boom")

                    override fun close() {}
                }
            SupertonicOnnxTtsEngine(session = failing).warmUp()
        }

    @Test
    fun `over-range output is scaled to the peak limit instead of hard-clipped`() =
        runBlocking {
            val fakeSession = RecordingSupertonicSession(outputSamples = floatArrayOf(0.5f, -2.0f, 1.0f))
            val engine = SupertonicOnnxTtsEngine(session = fakeSession)

            val pcm = engine.synthesizeToPcm("Loud", "M1")
            val samples = (0 until 3).map { ((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xFF)).toShort().toInt() }

            val limit = SupertonicOnnxTtsEngine.PEAK_LIMIT * 32767.0
            assertEquals(-limit, samples[1].toDouble(), 1.0)
            assertEquals(limit / 2, samples[2].toDouble(), 1.0)
            assertEquals(limit / 4, samples[0].toDouble(), 1.0)
        }

    @Test
    fun `limitPeak leaves in-range audio untouched`() {
        val input = floatArrayOf(0.25f, -1.0f, 0.99f)
        assertTrue(SupertonicOnnxTtsEngine.limitPeak(input) === input)
    }

    @Test
    fun `latent shape follows upstream sample_noisy_latent`() {
        // 1.0 s -> 44100 samples -> ceil(44100 / 3072) = 15 latent frames, all valid
        val oneSecond = SupertonicLatentShape(1.0f, 44100)
        assertEquals(44100, oneSecond.wavLength)
        assertEquals(15, oneSecond.latentLength)
        assertTrue(oneSecond.mask().all { it == 1.0f })

        // Exactly 3072 samples fits one frame; the float length decides, not a rounded-up int
        val oneFrame = SupertonicLatentShape(3072f / 44100f, 44100)
        assertEquals(1, oneFrame.latentLength)
        assertEquals(144, SupertonicLatentShape.LATENT_DIM)
    }

    @Test
    fun `cancellation propagates instead of switching to the fallback engine`() {
        val cancelling =
            object : SupertonicSession {
                override fun runInference(
                    textIds: LongArray,
                    textMask: Array<Array<FloatArray>>,
                    styleTtl: Array<Array<FloatArray>>,
                    styleDp: Array<Array<FloatArray>>,
                    totalSteps: Int,
                    speed: Float,
                ): FloatArray = throw kotlinx.coroutines.CancellationException("cancelled")

                override fun close() {}
            }
        val fallback = FakeTtsEngine(delayMs = 0)
        val engine = SupertonicOnnxTtsEngine(session = cancelling, fallbackEngine = fallback)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { engine.synthesizeToWav("Hello", "M1") }
        }
        assertEquals(0, fallback.synthesizedCount)
    }
}
