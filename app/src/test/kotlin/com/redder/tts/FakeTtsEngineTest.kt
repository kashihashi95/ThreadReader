package com.redder.tts

import com.redder.audio.WavUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTtsEngineTest {
    private suspend inline fun <reified T : Throwable> assertSuspendThrows(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) {
                return t
            }
            throw t
        }
        throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but nothing was thrown")
    }

    @Test
    fun `verifies default initial state and constants`() {
        val engine = FakeTtsEngine()

        assertEquals(FakeTtsEngine.DEFAULT_DELAY_MS, engine.delayMs)
        assertEquals(FakeTtsEngine.DEFAULT_SAMPLE_RATE, engine.sampleRate)
        assertEquals(FakeTtsEngine.DEFAULT_SAMPLES_PER_WORD, engine.samplesPerWord)
        assertEquals(100L, engine.delayMs)
        assertEquals(22050, engine.sampleRate)
        assertEquals(2205, engine.samplesPerWord)

        assertEquals(0, engine.synthesizedCount)
        assertNull(engine.lastSynthesizedText)
        assertNull(engine.lastVoiceId)
        assertFalse(engine.isReleased)
        assertNull(engine.failureToThrow)
    }

    @Test
    fun `synthesizes single word pcm with correct byte size`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm = engine.synthesizeToPcm("hello", "voice-1")

            val expectedWordCount = 1
            val expectedSamples = expectedWordCount * engine.samplesPerWord
            val expectedBytes = expectedSamples * 2
            assertEquals(expectedBytes, pcm.size)
            assertEquals(4410, pcm.size)
        }

    @Test
    fun `synthesizes multi word pcm with proportional byte size`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm = engine.synthesizeToPcm("The quick brown fox jumps", "voice-1")

            val expectedWordCount = 5
            val expectedSamples = expectedWordCount * engine.samplesPerWord
            val expectedBytes = expectedSamples * 2
            assertEquals(expectedBytes, pcm.size)
            assertEquals(22050, pcm.size)
        }

    @Test
    fun `generates valid little endian 16 bit samples within expected amplitude range`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm = engine.synthesizeToPcm("Sample test sentence", "voice-1")

            val sampleCount = pcm.size / 2
            var nonZeroCount = 0

            for (i in 0 until sampleCount) {
                val offset = i * 2
                val low = pcm[offset].toInt() and 0xFF
                val high = pcm[offset + 1].toInt() and 0xFF
                val sample = ((high shl 8) or low).toShort()

                assertTrue(
                    sample in -16000..16000,
                    "Sample $sample at index $i is outside expected amplitude range [-16000, 16000]",
                )
                if (sample != 0.toShort()) {
                    nonZeroCount++
                }
            }

            assertTrue(nonZeroCount > 0, "Synthesized PCM should not be silent (all zeroes)")
        }

    @Test
    fun `synthesizeToPcm produces deterministic identical output for identical input`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm1 = engine.synthesizeToPcm("Deterministic text", "voice-a")
            val pcm2 = engine.synthesizeToPcm("Deterministic text", "voice-a")

            assertArrayEquals(pcm1, pcm2)
        }

    @Test
    fun `tracks call count and state updates accurately across multiple calls`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)

            assertEquals(0, engine.synthesizedCount)
            assertNull(engine.lastSynthesizedText)
            assertNull(engine.lastVoiceId)

            val pcm1 = engine.synthesizeToPcm("First utterance", "speaker-1")
            assertNotNull(pcm1)
            assertEquals(1, engine.synthesizedCount)
            assertEquals("First utterance", engine.lastSynthesizedText)
            assertEquals("speaker-1", engine.lastVoiceId)

            val pcm2 = engine.synthesizeToPcm("Second utterance", "speaker-2")
            assertNotNull(pcm2)
            assertEquals(2, engine.synthesizedCount)
            assertEquals("Second utterance", engine.lastSynthesizedText)
            assertEquals("speaker-2", engine.lastVoiceId)

            val pcm3 = engine.synthesizeToPcm("Third utterance", "speaker-3")
            assertNotNull(pcm3)
            assertEquals(3, engine.synthesizedCount)
            assertEquals("Third utterance", engine.lastSynthesizedText)
            assertEquals("speaker-3", engine.lastVoiceId)
        }

    @Test
    fun `virtual time advances by configured delayMs during direct call`() =
        runTest {
            val delayDuration = 350L
            val engine = FakeTtsEngine(delayMs = delayDuration)

            val startVirtualTime = testScheduler.currentTime
            engine.synthesizeToPcm("Virtual time test", "voice-1")
            val elapsedVirtualTime = testScheduler.currentTime - startVirtualTime

            assertEquals(delayDuration, elapsedVirtualTime)
            assertEquals(1, engine.synthesizedCount)
        }

    @Test
    fun `advancing virtual time completes synthesis precisely at delay threshold`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 250L)
            var isFinished = false

            val job =
                launch {
                    engine.synthesizeToPcm("Threshold test", "voice-1")
                    isFinished = true
                }

            // Before delay completes
            advanceTimeBy(249L)
            runCurrent()
            assertFalse(isFinished)
            assertEquals(0, engine.synthesizedCount)

            // At exact delay completion
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(isFinished)
            assertEquals(1, engine.synthesizedCount)

            job.join()
        }

    @Test
    fun `cancelling coroutine job cancels synthesis immediately during delay without updating state`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 500L)

            val job =
                launch {
                    engine.synthesizeToPcm("Will be cancelled", "voice-c")
                }

            advanceTimeBy(250L)
            runCurrent()
            assertEquals(0, engine.synthesizedCount)

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(0, engine.synthesizedCount)
            assertNull(engine.lastSynthesizedText)
            assertNull(engine.lastVoiceId)
        }

    @Test
    fun `failureToThrow throws injected exception and leaves state counters unchanged`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 50L)
            val expectedException = IOException("Simulated neural inference model failure")
            engine.failureToThrow = expectedException

            val thrown =
                assertSuspendThrows<IOException> {
                    engine.synthesizeToPcm("Fail this", "voice-err")
                }

            assertEquals(expectedException.message, thrown.message)
            assertEquals(0, engine.synthesizedCount)
            assertNull(engine.lastSynthesizedText)
            assertNull(engine.lastVoiceId)
        }

    @Test
    fun `engine recovers cleanly when failureToThrow is cleared`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            engine.failureToThrow = RuntimeException("Transient failure")

            val thrown =
                assertSuspendThrows<RuntimeException> {
                    engine.synthesizeToPcm("Transient", "voice-1")
                }
            assertEquals("Transient failure", thrown.message)
            assertEquals(0, engine.synthesizedCount)

            // Clear failure
            engine.failureToThrow = null
            val pcm = engine.synthesizeToPcm("Recovered", "voice-1")

            assertNotNull(pcm)
            assertEquals(1, engine.synthesizedCount)
            assertEquals("Recovered", engine.lastSynthesizedText)
            assertEquals("voice-1", engine.lastVoiceId)
        }

    @Test
    fun `release marks engine as released`() {
        val engine = FakeTtsEngine()
        assertFalse(engine.isReleased)

        engine.release()
        assertTrue(engine.isReleased)

        // Multiple release calls are idempotent
        engine.release()
        assertTrue(engine.isReleased)
    }

    @Test
    fun `synthesizing after release throws IllegalStateException and preserves counters`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            engine.release()

            val thrown =
                assertSuspendThrows<IllegalStateException> {
                    engine.synthesizeToPcm("Should fail", "voice-1")
                }

            assertEquals("TtsEngine has been released", thrown.message)
            assertEquals(0, engine.synthesizedCount)
            assertNull(engine.lastSynthesizedText)
            assertNull(engine.lastVoiceId)
        }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\n", "\r\n", "  \t\n  "])
    fun `blank text fallback generates single word pcm audio`(blankInput: String) =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm = engine.synthesizeToPcm(blankInput, "voice-blank")

            val expectedBytes = 1 * engine.samplesPerWord * 2
            assertEquals(expectedBytes, pcm.size)
            assertEquals(1, engine.synthesizedCount)
            assertEquals(blankInput, engine.lastSynthesizedText)
        }

    @Test
    fun `irregular and excessive whitespace correctly splits word count`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val pcm = engine.synthesizeToPcm("   alpha    beta \t gamma \n delta   ", "voice-1")

            val expectedWordCount = 4
            val expectedBytes = expectedWordCount * engine.samplesPerWord * 2
            assertEquals(expectedBytes, pcm.size)
        }

    @Test
    fun `custom constructor parameters configure synthesis dimensions accurately`() =
        runTest {
            val customSampleRate = 16000
            val customSamplesPerWord = 1600
            val customDelay = 0L
            val engine =
                FakeTtsEngine(
                    delayMs = customDelay,
                    sampleRate = customSampleRate,
                    samplesPerWord = customSamplesPerWord,
                )

            val pcm = engine.synthesizeToPcm("hello world", "voice-custom")
            val expectedWords = 2
            val expectedBytes = expectedWords * customSamplesPerWord * 2
            assertEquals(expectedBytes, pcm.size)
            assertEquals(6400, pcm.size)
        }

    @Test
    fun `zero samples per word produces valid empty byte array`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L, samplesPerWord = 0)
            val pcm = engine.synthesizeToPcm("Word count irrelevant when samplesPerWord is zero", "v1")

            assertEquals(0, pcm.size)
            assertEquals(1, engine.synthesizedCount)
        }

    @Test
    fun `constructor rejects invalid parameters with IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            FakeTtsEngine(delayMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeTtsEngine(sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeTtsEngine(sampleRate = -22050)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FakeTtsEngine(samplesPerWord = -1)
        }
    }

    @Test
    fun `synthesized pcm integrates seamlessly with WavUtil`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L)
            val text = "Testing audio pipeline and wav packaging"
            val pcm = engine.synthesizeToPcm(text, "voice-wav")

            // 6 words -> 6 * 2205 samples = 13230 samples -> 26460 bytes
            val expectedWords = 6
            val expectedSamples = expectedWords * engine.samplesPerWord
            assertEquals(expectedSamples * 2, pcm.size)

            // Duration check: 13230 / 22050 = 0.6 seconds
            val duration = WavUtil.calculateDurationSeconds(pcm.size, sampleRate = engine.sampleRate)
            assertEquals(0.6, duration, 0.001)

            // Clipping check: simulated amplitude is 16000 < threshold 32000
            assertFalse(WavUtil.detectClipping(pcm))

            // Peak amplitude check
            val peakAmp = WavUtil.calculatePeakAmplitude(pcm)
            assertTrue(peakAmp in 1..16000, "Peak amplitude $peakAmp should be within 1..16000")

            // WAV header packaging
            val wavBytes = WavUtil.pcmToWav(pcm, sampleRate = engine.sampleRate)
            assertEquals(WavUtil.HEADER_SIZE + pcm.size, wavBytes.size)
        }
}
