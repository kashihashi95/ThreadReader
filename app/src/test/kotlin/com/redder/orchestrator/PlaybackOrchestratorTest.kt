package com.redder.orchestrator

import com.redder.audio.WavUtil
import com.redder.sanitizer.DialogueChunk
import com.redder.tts.FakeTtsEngine
import com.redder.tts.TtsEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorTest {
    @Test
    fun `pipeline respects capacity of 2 and suspends producer`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..5).map { index ->
                    DialogueChunk(
                        chunkIndex = index,
                        nodeId = "node_$index",
                        author = "user_$index",
                        spokenText = "Utterance $index",
                        voiceId = "voice_1",
                        isOp = false,
                    )
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            advanceTimeBy(350L) // Enough time for 3 items if unbounded

            // Exactly 2 items should have been synthesized and buffered
            assertEquals(2, mockEngine.synthesizedCount, "Producer must suspend when channel capacity (2) is reached")

            // Consumer consumes 1 item
            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals(0, first?.index)
            assertEquals("Utterance 0", first?.dialogueChunk?.spokenText)

            advanceTimeBy(150L) // Producer unblocks and synthesizes chunk 2
            assertEquals(3, mockEngine.synthesizedCount, "Consuming 1 item should unblock synthesis for next item")

            // Consume remaining items and verify order
            val second = orchestrator.nextChunk()
            assertNotNull(second)
            assertEquals(1, second?.index)

            advanceTimeBy(100L)
            val third = orchestrator.nextChunk()
            assertNotNull(third)
            assertEquals(2, third?.index)

            orchestrator.release()
        }

    @Test
    fun `testIdleDutyCycle - producer maintains zero CPU idle duty cycle when channel buffer is full`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..5).map { index ->
                    DialogueChunk(
                        chunkIndex = index,
                        nodeId = "node_$index",
                        author = "user_$index",
                        spokenText = "Utterance $index",
                        voiceId = "voice_1",
                        isOp = false,
                    )
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            // Fill channel capacity (2 items * 100ms = 200ms)
            advanceTimeBy(250L)
            assertEquals(2, mockEngine.synthesizedCount)

            // Producer coroutine is now suspended waiting for channel consumption.
            // Verify zero busy polling: advancing time or running current tasks does not spin or advance synthesis.
            val wallTimeBeforeNs = System.nanoTime()

            runCurrent()
            advanceTimeBy(10_000L) // 10 seconds of idle playback
            runCurrent()

            val wallTimeAfterNs = System.nanoTime()

            // Exactly 2 items remain synthesized; no polling loops advanced synthesis
            assertEquals(2, mockEngine.synthesizedCount, "Producer must not synthesize while suspended")

            // Real execution time across 10s of virtual time must be near-instantaneous (under 50ms) confirming 0% CPU spin
            val wallElapsedMs = (wallTimeAfterNs - wallTimeBeforeNs) / 1_000_000L
            assertTrue(wallElapsedMs < 50L, "Producer must not spin CPU while suspended: elapsed ${wallElapsedMs}ms")

            // Consuming an item unblocks synthesis cleanly
            val chunk = orchestrator.nextChunk()
            assertNotNull(chunk)
            advanceTimeBy(150L)
            assertEquals(3, mockEngine.synthesizedCount)

            orchestrator.release()
        }

    @Test
    fun `end of stream closes channel and returns null when all chunks consumed`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                listOf(
                    DialogueChunk(0, "node_0", "user_0", "Chunk 0", "voice_1", false),
                    DialogueChunk(1, "node_1", "user_1", "Chunk 1", "voice_1", false),
                )

            orchestrator.startPipeline(chunks, startIndex = 0)

            advanceTimeBy(25L)
            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals(0, first?.index)

            val second = orchestrator.nextChunk()
            assertNotNull(second)
            assertEquals(1, second?.index)

            // Pipeline stream ended; channel is closed
            val third = orchestrator.nextChunk()
            assertNull(third, "Consuming past end of stream must return null")

            val fourth = orchestrator.nextChunk()
            assertNull(fourth, "Subsequent calls after end of stream must return null")
        }

    @Test
    fun `release lifecycle cancels in flight synthesis and closes channel`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..3).map { index ->
                    DialogueChunk(index, "node_$index", "user_$index", "Chunk $index", "voice_1", false)
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            // Advance partially into first synthesis
            advanceTimeBy(50L)
            orchestrator.release()

            // Advance further time
            advanceTimeBy(500L)

            // Synthesis was cancelled before first item finished
            assertEquals(0, mockEngine.synthesizedCount)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `release is idempotent across multiple calls`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            orchestrator.release()
            orchestrator.release()
            orchestrator.release()

            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `empty dialogue list closes channel cleanly without synthesis`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            orchestrator.startPipeline(emptyList(), startIndex = 0)

            assertEquals(0, mockEngine.synthesizedCount)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `negative startIndex closes channel cleanly without synthesis`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Text", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = -1)

            assertEquals(0, mockEngine.synthesizedCount)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `startIndex out of bounds closes channel cleanly without synthesis`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Text", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = 1)

            assertEquals(0, mockEngine.synthesizedCount)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `pipeline starting at non zero index synthesizes from that offset`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 50L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..4).map { index ->
                    DialogueChunk(index, "node_$index", "user_$index", "Utterance $index", "voice_1", false)
                }

            orchestrator.startPipeline(chunks, startIndex = 2)

            advanceTimeBy(60L)
            val chunk2 = orchestrator.nextChunk()
            assertNotNull(chunk2)
            assertEquals(2, chunk2?.index)
            assertEquals("Utterance 2", chunk2?.dialogueChunk?.spokenText)

            advanceTimeBy(60L)
            val chunk3 = orchestrator.nextChunk()
            assertNotNull(chunk3)
            assertEquals(3, chunk3?.index)

            advanceTimeBy(60L)
            val chunk4 = orchestrator.nextChunk()
            assertNotNull(chunk4)
            assertEquals(4, chunk4?.index)

            val end = orchestrator.nextChunk()
            assertNull(end)
            assertEquals(3, mockEngine.synthesizedCount)
        }

    @Test
    fun `error during synthesis closes channel gracefully and nextChunk returns null`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 50L)
            mockEngine.failureToThrow = IOException("TTS model corrupted")
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Text", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = 0)

            advanceTimeBy(60L)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `startPipeline after release closes channel immediately`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            orchestrator.release()

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Text", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = 0)

            assertEquals(0, mockEngine.synthesizedCount)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `startPipeline called again cancels existing synthesis job`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks1 = (0..3).map { DialogueChunk(it, "n$it", "u$it", "SetA $it", "v1", false) }
            orchestrator.startPipeline(chunks1, startIndex = 0)

            advanceTimeBy(50L) // in-flight synthesizing SetA 0
            // Calling startPipeline again cancels the first job and starts fresh
            val chunks2 = (10..12).map { DialogueChunk(it, "n$it", "u$it", "SetB $it", "v1", false) }
            orchestrator.startPipeline(chunks2, startIndex = 0)

            advanceTimeBy(120L) // SetB 10 completes
            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals("SetB 10", first?.dialogueChunk?.spokenText)
        }

    @Test
    fun `synthesized chunk packages valid RIFF WAV header`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L, samplesPerWord = 100)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Hello world", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = 0)

            advanceTimeBy(20L)
            val chunk = orchestrator.nextChunk()
            assertNotNull(chunk)
            val wavData = chunk!!.wavData
            val pcmData = chunk.pcmData

            // RIFF WAV header size is 44 bytes
            assertEquals(WavUtil.HEADER_SIZE + pcmData.size, wavData.size)
            assertEquals('R'.code.toByte(), wavData[0])
            assertEquals('I'.code.toByte(), wavData[1])
            assertEquals('F'.code.toByte(), wavData[2])
            assertEquals('F'.code.toByte(), wavData[3])
            assertEquals('W'.code.toByte(), wavData[8])
            assertEquals('A'.code.toByte(), wavData[9])
            assertEquals('V'.code.toByte(), wavData[10])
            assertEquals('E'.code.toByte(), wavData[11])
        }

    @Test
    fun `SynthesizedChunk equals and hashCode contract with content comparisons`() {
        val dialogue1 = DialogueChunk(0, "node_0", "user_0", "Hello", "v1", false)
        val dialogue2 = DialogueChunk(1, "node_1", "user_1", "World", "v2", true)

        val pcm1 = byteArrayOf(1, 2, 3)
        val pcm2 = byteArrayOf(1, 2, 3)
        val pcm3 = byteArrayOf(4, 5, 6)

        val wav1 = byteArrayOf(10, 20)
        val wav2 = byteArrayOf(10, 20)
        val wav3 = byteArrayOf(30, 40)

        val chunkA = SynthesizedChunk(0, dialogue1, pcm1, wav1)
        val chunkB = SynthesizedChunk(0, dialogue1, pcm2, wav2)
        val chunkDiffIndex = SynthesizedChunk(1, dialogue1, pcm1, wav1)
        val chunkDiffDialogue = SynthesizedChunk(0, dialogue2, pcm1, wav1)
        val chunkDiffPcm = SynthesizedChunk(0, dialogue1, pcm3, wav1)
        val chunkDiffWav = SynthesizedChunk(0, dialogue1, pcm1, wav3)

        // Reflexive
        assertEquals(chunkA, chunkA)
        // Symmetric and equal by content
        assertEquals(chunkA, chunkB)
        assertEquals(chunkB, chunkA)
        assertEquals(chunkA.hashCode(), chunkB.hashCode())

        // Non-equal cases
        assertNotEquals(chunkA, chunkDiffIndex)
        assertNotEquals(chunkA, chunkDiffDialogue)
        assertNotEquals(chunkA, chunkDiffPcm)
        assertNotEquals(chunkA, chunkDiffWav)
        assertFalse(chunkA.equals(null))
        assertFalse(chunkA.equals("Not a SynthesizedChunk"))
    }

    @Test
    fun `startPipeline with default startIndex parameter defaults to zero`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Chunk 0", "voice_1", false))
            orchestrator.startPipeline(chunks)

            advanceTimeBy(20L)
            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals(0, first?.index)
        }

    @Test
    fun `jumpTo cancels in-flight chunk 1 and immediately drains buffer`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..6).map { index ->
                    DialogueChunk(
                        chunkIndex = index,
                        nodeId = "node_$index",
                        author = "user_$index",
                        spokenText = "Utterance $index",
                        voiceId = "voice_1",
                        isOp = false,
                    )
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            // At 250ms, chunk 0 and chunk 1 are fully synthesized and buffered in audioChannel.
            // Producer is suspended waiting for capacity permit.
            advanceTimeBy(250L)
            assertEquals(2, mockEngine.synthesizedCount)

            // Calling jumpTo(4) cancels active synthesis, drains stale buffered chunks (0 and 1),
            // and re-launches the pipeline starting strictly from chunk 4.
            orchestrator.jumpTo(4)

            // Synthesize chunk 4 (takes 100ms)
            advanceTimeBy(100L)

            // Buffer was drained and next consumed chunk is chunk 4
            val next = orchestrator.nextChunk()
            assertNotNull(next)
            assertEquals(4, next?.index)
            assertEquals("Utterance 4", next?.dialogueChunk?.spokenText)

            // Consume chunk 5 after another 100ms
            advanceTimeBy(100L)
            val next5 = orchestrator.nextChunk()
            assertNotNull(next5)
            assertEquals(5, next5?.index)
            assertEquals("Utterance 5", next5?.dialogueChunk?.spokenText)

            orchestrator.release()
        }

    @Test
    fun `jumpTo cancels active in-flight synthesis and drains buffer`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..6).map { index ->
                    DialogueChunk(
                        chunkIndex = index,
                        nodeId = "node_$index",
                        author = "user_$index",
                        spokenText = "Utterance $index",
                        voiceId = "voice_1",
                        isOp = false,
                    )
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            // At 150ms: chunk 0 is buffered in channel, chunk 1 is in-flight (50ms into 100ms synthesis)
            advanceTimeBy(150L)
            assertEquals(1, mockEngine.synthesizedCount)

            // Call jumpTo(4): chunk 0 in buffer is drained, in-flight chunk 1 is cancelled before finishing
            orchestrator.jumpTo(4)

            // Advance 120ms for chunk 4 to synthesize
            advanceTimeBy(120L)

            // Chunk 1 never completed; synthesized count is 2 (chunk 0 and chunk 4)
            assertEquals(2, mockEngine.synthesizedCount)

            val next = orchestrator.nextChunk()
            assertNotNull(next)
            assertEquals(4, next?.index)
            assertEquals("Utterance 4", next?.dialogueChunk?.spokenText)

            orchestrator.release()
        }

    @Test
    fun `rapid successive calls to jumpTo does not deadlock, leak jobs, or corrupt channel`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 100L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks =
                (0..9).map { index ->
                    DialogueChunk(
                        chunkIndex = index,
                        nodeId = "node_$index",
                        author = "user_$index",
                        spokenText = "Utterance $index",
                        voiceId = "voice_1",
                        isOp = false,
                    )
                }

            orchestrator.startPipeline(chunks, startIndex = 0)

            // Rapid successive jumpTo calls mid-synthesis: 0 -> 2 -> 5
            advanceTimeBy(50L) // chunk 0 in-flight
            orchestrator.jumpTo(2)
            advanceTimeBy(30L) // chunk 2 in-flight
            orchestrator.jumpTo(5)

            // Advance time to allow chunk 5 and chunk 6 to synthesize
            advanceTimeBy(200L)

            // Next chunk must be chunk 5
            val chunk5 = orchestrator.nextChunk()
            assertNotNull(chunk5)
            assertEquals(5, chunk5?.index)
            assertEquals("Utterance 5", chunk5?.dialogueChunk?.spokenText)

            // Subsequent chunk must be chunk 6
            val chunk6 = orchestrator.nextChunk()
            assertNotNull(chunk6)
            assertEquals(6, chunk6?.index)
            assertEquals("Utterance 6", chunk6?.dialogueChunk?.spokenText)

            // Rapid synchronous jumps without time advancement
            orchestrator.jumpTo(1)
            orchestrator.jumpTo(3)
            orchestrator.jumpTo(7)

            advanceTimeBy(100L)
            val chunk7 = orchestrator.nextChunk()
            assertNotNull(chunk7)
            assertEquals(7, chunk7?.index)
            assertEquals("Utterance 7", chunk7?.dialogueChunk?.spokenText)

            orchestrator.release()
        }

    @Test
    fun `watchdog timeout catches timeout, skips stalled chunk, and proceeds to next chunk`() =
        runTest {
            val stallingEngine =
                StallingTtsEngine(
                    stalledText = "Stalled Chunk 1",
                    stallDelayMs = 500L,
                    normalDelayMs = 50L,
                )
            val orchestrator =
                PlaybackOrchestrator(
                    ttsEngine = stallingEngine,
                    scope = this,
                    chunkTimeoutMs = 200L,
                )

            val chunks =
                listOf(
                    DialogueChunk(0, "node_0", "user_0", "Chunk 0", "v1", false),
                    DialogueChunk(1, "node_1", "user_1", "Stalled Chunk 1", "v1", false),
                    DialogueChunk(2, "node_2", "user_2", "Chunk 2", "v1", false),
                )

            orchestrator.startPipeline(chunks, startIndex = 0)

            // Chunk 0 takes 50ms
            advanceTimeBy(60L)
            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals(0, first?.index)
            assertEquals("Chunk 0", first?.dialogueChunk?.spokenText)

            // Chunk 1 starts and stalls at 200ms (timeout triggers), is skipped, and Chunk 2 starts and finishes (50ms)
            advanceTimeBy(260L)
            val second = orchestrator.nextChunk()
            assertNotNull(second)
            assertEquals(2, second?.index)
            assertEquals("Chunk 2", second?.dialogueChunk?.spokenText)

            // Pipeline stream ended cleanly; next is null
            val third = orchestrator.nextChunk()
            assertNull(third)

            // Stalled chunk 1 never completed successfully
            assertEquals(listOf("Chunk 0", "Chunk 2"), stallingEngine.synthesizedChunks)

            orchestrator.release()
        }

    @Test
    fun `watchdog timeout with default 25000ms budget skips pathological chunk`() =
        runTest {
            val stallingEngine =
                StallingTtsEngine(
                    stalledText = "Infinite Chunk",
                    stallDelayMs = 35_000L,
                    normalDelayMs = 50L,
                )
            // Uses default chunkTimeoutMs = DEFAULT_CHUNK_TIMEOUT_MS = 25000L
            val orchestrator = PlaybackOrchestrator(stallingEngine, this)

            val chunks =
                listOf(
                    DialogueChunk(0, "node_0", "user_0", "Infinite Chunk", "v1", false),
                    DialogueChunk(1, "node_1", "user_1", "Fast Chunk", "v1", false),
                )

            orchestrator.startPipeline(chunks, startIndex = 0)

            // Advance time past 25000ms timeout + 50ms for chunk 1
            advanceTimeBy(25_100L)

            val first = orchestrator.nextChunk()
            assertNotNull(first)
            assertEquals(1, first?.index)
            assertEquals("Fast Chunk", first?.dialogueChunk?.spokenText)

            orchestrator.release()
        }

    @Test
    fun `jumpTo after release is a no op`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Text", "v1", false))
            orchestrator.startPipeline(chunks, startIndex = 0)
            orchestrator.release()

            orchestrator.jumpTo(0)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `jumpTo with negative index closes channel cleanly`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = (0..3).map { DialogueChunk(it, "n$it", "u$it", "Text $it", "v1", false) }
            orchestrator.startPipeline(chunks, startIndex = 0)

            orchestrator.jumpTo(-1)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `jumpTo with out of bounds index closes channel cleanly`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            val chunks = (0..3).map { DialogueChunk(it, "n$it", "u$it", "Text $it", "v1", false) }
            orchestrator.startPipeline(chunks, startIndex = 0)

            orchestrator.jumpTo(10)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `jumpTo when currentDialogueList is empty closes channel cleanly`() =
        runTest {
            val mockEngine = FakeTtsEngine(delayMs = 10L)
            val orchestrator = PlaybackOrchestrator(mockEngine, this)

            orchestrator.jumpTo(0)
            assertNull(orchestrator.nextChunk())
        }

    @Test
    fun `pipeline preserves WAV container and extracts PCM from synthesizeToWav`() =
        runTest {
            val customPcm = ByteArray(160) { (it + 5).toByte() }
            val customWav = WavUtil.pcmToWav(customPcm, sampleRate = 24000)

            val customEngine =
                object : TtsEngine {
                    var synthesizeToWavCalled = false

                    override suspend fun synthesizeToPcm(
                        text: String,
                        voiceId: String,
                    ): ByteArray = customPcm

                    override suspend fun synthesizeToWav(
                        text: String,
                        voiceId: String,
                    ): ByteArray {
                        synthesizeToWavCalled = true
                        return customWav
                    }

                    override fun release() {}
                }

            val orchestrator = PlaybackOrchestrator(customEngine, this)
            val chunks = listOf(DialogueChunk(0, "node_0", "user_0", "Custom WAV test", "voice_0", false))
            orchestrator.startPipeline(chunks)

            advanceTimeBy(50L)
            val chunk = orchestrator.nextChunk()
            assertNotNull(chunk)
            assertTrue(customEngine.synthesizeToWavCalled)
            assertArrayEquals(customWav, chunk!!.wavData)
            assertArrayEquals(customPcm, chunk.pcmData)
        }

    @Test
    fun `per-index steps cap is passed to the engine only for capped chunks`() =
        runTest {
            val requestedCaps = mutableListOf<Int?>()
            val engine =
                object : TtsEngine {
                    override suspend fun synthesizeToPcm(
                        text: String,
                        voiceId: String,
                    ): ByteArray {
                        requestedCaps.add(null)
                        return ByteArray(10)
                    }

                    override suspend fun synthesizeToWav(
                        text: String,
                        voiceId: String,
                        maxQualitySteps: Int?,
                    ): ByteArray {
                        requestedCaps.add(maxQualitySteps)
                        return WavUtil.pcmToWav(ByteArray(10))
                    }

                    override fun release() {}
                }
            val orchestrator =
                PlaybackOrchestrator(engine, this, maxQualityStepsForIndex = { if (it < 2) 4 else null })
            val chunks =
                (0..3).map { index ->
                    DialogueChunk(index, "n$index", "u$index", "Utterance $index", "voice_1", false)
                }

            orchestrator.startPipeline(chunks)
            repeat(4) { assertNotNull(orchestrator.nextChunk()) }

            assertEquals(listOf<Int?>(4, 4, null, null), requestedCaps)
            orchestrator.release()
        }

    private fun pauseChunk(
        nodeId: String,
        author: String,
        voiceId: String = "voice_1",
    ) = DialogueChunk(0, nodeId, author, "Text.", voiceId, false)

    @Test
    fun `pause after a chunk is short when the same speaker continues and longer at a boundary`() {
        val guard = com.redder.audio.SilenceTrimmer.TRAILING_GUARD_MS
        val a1 = pauseChunk("c1", "alice")
        val a1More = pauseChunk("c1", "alice")
        val a2 = pauseChunk("c2", "alice")
        val b = pauseChunk("c3", "bob", voiceId = "voice_2")

        assertEquals(PlaybackOrchestrator.SAME_SPEAKER_PAUSE_MS - guard, PlaybackOrchestrator.pauseAfterMs(a1, a1More))
        assertEquals(PlaybackOrchestrator.SPEAKER_CHANGE_PAUSE_MS - guard, PlaybackOrchestrator.pauseAfterMs(a1, a2))
        assertEquals(PlaybackOrchestrator.SPEAKER_CHANGE_PAUSE_MS - guard, PlaybackOrchestrator.pauseAfterMs(a2, b))
        assertEquals(0, PlaybackOrchestrator.pauseAfterMs(b, null))
    }

    @Test
    fun `synthesized chunks carry the pause for the next chunk and the last chunk gets none`() =
        runTest {
            val engine = FakeTtsEngine(delayMs = 0L, sampleRate = 22050, samplesPerWord = 100)
            val orchestrator = PlaybackOrchestrator(engine, this)
            val chunks =
                listOf(
                    DialogueChunk(0, "c1", "alice", "One two.", "voice_1", false),
                    DialogueChunk(1, "c1", "alice", "Three four.", "voice_1", false),
                    DialogueChunk(2, "c2", "bob", "Five six.", "voice_2", false),
                )
            orchestrator.startPipeline(chunks)
            val received = (0 until 3).map { orchestrator.nextChunk()!! }
            val speech = 2 * 100 * 2

            fun silenceBytes(ms: Int) = (ms * 22050 / 1000) * 2

            assertEquals(speech + silenceBytes(PlaybackOrchestrator.pauseAfterMs(chunks[0], chunks[1])), received[0].pcmData.size)
            assertEquals(speech + silenceBytes(PlaybackOrchestrator.pauseAfterMs(chunks[1], chunks[2])), received[1].pcmData.size)
            assertEquals(speech, received[2].pcmData.size)
            received.forEach { assertEquals(WavUtil.HEADER_SIZE + it.pcmData.size, it.wavData.size) }
            orchestrator.release()
        }

    @Test
    fun `pauses use each engine's real sample rate`() =
        runTest {
            // Whatever rate an engine outputs, the silence must match the WAV header rate.
            listOf(24000, 22050, 44100).forEach { rate ->
                val engine = FakeTtsEngine(delayMs = 0L, sampleRate = rate, samplesPerWord = 100)
                val orchestrator = PlaybackOrchestrator(engine, this)
                val chunks =
                    listOf(
                        DialogueChunk(0, "c1", "alice", "One two.", "F1", true),
                        DialogueChunk(1, "c2", "bob", "Three four.", "M2", false),
                    )
                orchestrator.startPipeline(chunks)
                val first = orchestrator.nextChunk()!!
                val pauseMs = PlaybackOrchestrator.pauseAfterMs(chunks[0], chunks[1])

                assertEquals(rate, WavUtil.extractSampleRateFromWav(first.wavData))
                assertEquals(2 * 100 * 2 + (pauseMs * rate / 1000) * 2, first.pcmData.size)
                assertEquals((200L * 1000 / rate) + pauseMs, PlaybackOrchestrator.wavDurationMs(first.wavData), "rate $rate")
                orchestrator.release()
            }
        }

    @Test
    fun `wavDurationMs reads the header and ignores non-wav buffers`() {
        assertEquals(1000L, PlaybackOrchestrator.wavDurationMs(WavUtil.pcmToWav(ByteArray(48000), 24000)))
        assertEquals(500L, PlaybackOrchestrator.wavDurationMs(WavUtil.pcmToWav(ByteArray(22050), 22050)))
        assertEquals(0L, PlaybackOrchestrator.wavDurationMs(ByteArray(10)))
        assertEquals(0L, PlaybackOrchestrator.wavDurationMs(WavUtil.pcmToWav(ByteArray(0), 24000)))
    }

    @Test
    fun `chunk timeout is configurable for slow engines`() =
        runTest {
            val engine = StallingTtsEngine(stalledText = "slow", stallDelayMs = 40_000L, normalDelayMs = 0L)
            val orchestrator = PlaybackOrchestrator(engine, this, chunkTimeoutMs = 60_000L)
            orchestrator.startPipeline(listOf(DialogueChunk(0, "c1", "alice", "slow", "F1", false)))

            assertNotNull(orchestrator.nextChunk(), "a 40 s chunk must not hit a 60 s timeout")
            orchestrator.release()
        }
}

private class StallingTtsEngine(
    private val stalledText: String,
    private val stallDelayMs: Long,
    private val normalDelayMs: Long = 50L,
) : TtsEngine {
    val synthesizedChunks = mutableListOf<String>()
    var isReleased: Boolean = false
        private set

    override suspend fun synthesizeToPcm(
        text: String,
        voiceId: String,
    ): ByteArray {
        if (text == stalledText) {
            delay(stallDelayMs)
        } else {
            delay(normalDelayMs)
        }
        synthesizedChunks.add(text)
        return ByteArray(100)
    }

    override fun release() {
        isReleased = true
    }
}
