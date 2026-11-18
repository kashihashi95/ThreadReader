package com.redder.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReloadableTtsEngineTest {
    private class Inner(val gate: CompletableDeferred<Unit>? = null) : TtsEngine {
        var released = 0
        var syntheses = 0

        override suspend fun synthesizeToPcm(
            text: String,
            voiceId: String,
        ): ByteArray {
            gate?.await()
            syntheses++
            return ByteArray(2)
        }

        override fun release() {
            released++
        }
    }

    @Test
    fun `nothing is built until it is used`() {
        var built = 0
        val engine =
            ReloadableTtsEngine {
                built++
                Inner()
            }
        assertFalse(engine.isLoaded)
        assertEquals(0, built)
    }

    @Test
    fun `unload frees the engine and the next synthesis builds a new one`() =
        runTest {
            val inners = mutableListOf<Inner>()
            val engine = ReloadableTtsEngine { Inner().also { inners += it } }

            engine.synthesizeToPcm("a", "v")
            engine.unload()
            assertFalse(engine.isLoaded)
            assertEquals(1, inners[0].released)

            engine.synthesizeToPcm("b", "v")
            assertTrue(engine.isLoaded)
            assertEquals(2, inners.size)
            assertEquals(0, inners[1].released)
        }

    @Test
    fun `unload waits for the running inference`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val inner = Inner(gate)
            val engine = ReloadableTtsEngine { inner }
            val synthesis = launch { engine.synthesizeToPcm("a", "v") }
            runCurrent()
            val unload = launch { engine.unload() }
            runCurrent()
            assertEquals(0, inner.released)
            assertFalse(unload.isCompleted)

            gate.complete(Unit)
            synthesis.join()
            unload.join()
            assertEquals(1, inner.syntheses)
            assertEquals(1, inner.released)
        }

    @Test
    fun `release is final`() =
        runTest {
            val inner = Inner()
            val engine = ReloadableTtsEngine { inner }
            engine.synthesizeToPcm("a", "v")
            engine.release()
            assertEquals(1, inner.released)
            val failure = runCatching { engine.synthesizeToPcm("b", "v") }.exceptionOrNull()
            assertThrows(IllegalStateException::class.java) { throw checkNotNull(failure) }
        }
}
