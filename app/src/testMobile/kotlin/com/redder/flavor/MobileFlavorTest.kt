package com.redder.flavor

import com.redder.BuildConfig
import com.redder.local.LocalPlaylist
import com.redder.settings.RedderPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.VoiceEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MobileFlavorTest {
    @Test
    fun `uses the mobile application id and ThreadReader name`() {
        assertEquals("com.redder.mobile", BuildConfig.APPLICATION_ID)
        assertTrue(
            File("src/mobile/res/values/strings.xml").readText().contains("""<string name="app_name">ThreadReader</string>"""),
        )
    }

    @Test
    fun `Supertonic is the one engine, with its voices and its quality setting`() {
        assertEquals(VoiceEngine.SUPERTONIC, CurrentFlavor.fixedVoiceEngine)
        assertEquals("M1", EngineVoices.defaultNarrator(VoiceEngine.SUPERTONIC))
        assertEquals(8, RedderPreferences.DEFAULT_QUALITY_STEPS)
        assertEquals(8, RedderPreferences.QUALITY_STEPS_BEST)
        assertEquals(4, RedderPreferences.QUALITY_STEPS_FAST)
    }

    @Test
    fun `no LLM and no server is on the classpath, and nothing of the other voices`() {
        listOf(
            "com.google.ai.edge.litertlm.Engine",
            "com.redder.llm.LiteRtTextCleanupLlm",
            "com.k2fsa.sherpa.onnx.OfflineTts",
            "com.redder.tts.SherpaOnnxTtsEngine",
            "com.redder.tts.RoutingTtsEngine",
            "com.redder.server.ServerClient",
            "com.redder.server.PlaylistRepository",
            "com.redder.server.RemoteTtsEngine",
            "com.redder.server.ServerConfigStore",
        ).forEach { name -> assertThrows(ClassNotFoundException::class.java, { Class.forName(name) }, name) }
        Class.forName("com.redder.tts.SupertonicOnnxTtsEngine")
    }

    @Test
    fun `the build gives this flavor no LLM, sherpa, Kokoro or Piper, and no background job`() {
        val gradle = File("build.gradle.kts").readText()
        val mobileLines = gradle.lines().filter { it.contains("\"mobileImplementation\"") }
        assertEquals(1, mobileLines.size, mobileLines.toString())
        assertTrue(mobileLines.single().contains("onnxruntime"))
        assertFalse(gradle.contains("work-runtime"))
        val code =
            File("src/mobile").walkTopDown().filter { it.isFile }.plus(File("src/playlistUi").walkTopDown().filter { it.isFile })
                .map { it.readText() }.joinToString("\n")
        listOf("WorkManager", "AlarmManager", "JobScheduler", "startForegroundService(context, Intent").forEach {
            assertFalse(code.contains(it), it)
        }
        assertTrue(
            File("src/playlistUi/kotlin/com/redder/deck/PlaylistCard.kt").readText().contains("repeatOnLifecycle(Lifecycle.State.STARTED)"),
        )
        assertFalse(File("src/mobile/AndroidManifest.xml").readText().contains("cleartext", ignoreCase = true))
        assertFalse(File("src/mobile/AndroidManifest.xml").readText().contains("uses-native-library"))
    }

    @Test
    fun `the default sub list is the owner's twelve`() {
        assertEquals(12, LocalPlaylist.DEFAULT_SUBS.size)
        assertEquals("BestofRedditorUpdates", LocalPlaylist.DEFAULT_SUBS.first())
        assertEquals("offmychest", LocalPlaylist.DEFAULT_SUBS.last())
    }

    @Test
    fun `the service asks the playlist for nothing to play, and reads posts itself`() =
        runBlocking {
            val source = File("src/mobile/kotlin/com/redder/local/LocalPlaylist.kt").readText()
            assertTrue(source.contains("override suspend fun load(post: ListingPost): ServerPost? = null"))
        }
}
