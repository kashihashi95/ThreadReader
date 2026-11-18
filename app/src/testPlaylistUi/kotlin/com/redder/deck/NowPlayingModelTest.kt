package com.redder.deck

import com.redder.model.DialogueItem
import com.redder.model.SynthesisStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NowPlayingModelTest {
    private fun row(id: String) = PlaylistRow(id, "sub", "title $id", "/r/sub/$id", "new", null, null)

    @Test
    fun `timing matches the vector the server page is held to`() {
        val cases =
            Json.parseToJsonElement(
                File("../server/testdata/caption_timing.json").readText(),
            ).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 9)
        cases.forEach { element ->
            val case = element.jsonObject
            val text = case.getValue("text").jsonPrimitive.content
            val lines = CaptionTiming.split(text)
            assertEquals(case.getValue("lines").jsonArray.map { it.jsonPrimitive.content }, lines, text)
            val starts =
                CaptionTiming.starts(
                    lines,
                    case.getValue("duration_ms").jsonPrimitive.long,
                    case.getValue("pause_ms").jsonPrimitive.long,
                )
            assertEquals(case.getValue("starts").jsonArray.map { it.jsonPrimitive.long }, starts, text)
            case.getValue("probes").jsonArray.forEach { probe ->
                val p = probe.jsonObject
                assertEquals(p.getValue("line").jsonPrimitive.int, CaptionTiming.lineAt(starts, p.getValue("ms").jsonPrimitive.long), text)
            }
        }
    }

    private val items =
        listOf(
            DialogueItem(0, "ann", "One two three. Four five.", pauseMs = 100),
            DialogueItem(1, "ann", "Six seven.", pauseMs = 250),
            DialogueItem(2, "bob", "Eight. Nine."),
        )

    @Test
    fun `lines run across chunks with a label only where the speaker changes`() {
        val script = CaptionScript(items)
        assertEquals(5, script.lines.size)
        assertEquals(listOf("ann", null, null, "bob", null), script.lines.map { it.speaker })
        assertEquals(listOf(0, 0, 1, 2, 2), script.lines.map { it.chunk })
    }

    @Test
    fun `the line comes from the position, forward or back`() {
        val script = CaptionScript(items)
        val second = CaptionTiming.starts(listOf("One two three.", "Four five."), 5000, 100)[1]
        assertEquals(1, script.lineAt(0, second, 5000))
        assertEquals(0, script.lineAt(0, second - 1, 5000))
        assertEquals(1, script.lineAt(0, 4999, 5000))
        assertEquals(3, script.lineAt(2, 0, 5000))
        assertEquals(0, script.lineAt(0, 0, 5000))
        // Rewinding is just another reading.
        assertEquals(0, script.lineAt(0, 10, 5000))
    }

    @Test
    fun `an unknown length shows the chunk's first line and an empty post is safe`() {
        val script = CaptionScript(items)
        assertEquals(3, script.lineAt(2, 4000, null))
        assertEquals(0, CaptionScript(emptyList()).lineAt(5, 0, 100))
    }

    @Test
    fun `a blank chunk still has a line, so indexes stay in step`() {
        val script = CaptionScript(listOf(DialogueItem(0, "a", "  "), DialogueItem(1, "a", "Hi.")))
        assertEquals(2, script.lines.size)
        assertEquals(1, script.lineAt(1, 0, 1000))
    }

    @Test
    fun `a tap goes to the line's start, nudged inside it`() {
        val script = CaptionScript(items)
        val lines = script.lines
        assertEquals(0L, script.startMs(lines[0], 5000))
        val second = script.startMs(lines[1], 5000)
        assertEquals(1, script.lineAt(0, second, 5000))
        assertTrue(script.startMs(lines[1], null) > 0L)
    }

    @Test
    fun `an estimated tap is corrected once the real length is known`() {
        val script = CaptionScript(items)
        val line = script.lines[1]
        assertNull(script.correctionFor(null, 0, 100, 5000))
        assertNull(script.correctionFor(line, 1, 100, 5000))
        assertNull(script.correctionFor(line, 0, 100, null))
        assertEquals(script.startMs(line, 5000), script.correctionFor(line, 0, 100, 5000))
        assertNull(script.correctionFor(line, 0, script.startMs(line, 5000) + 10, 5000))
    }

    @Test
    fun `a hand scroll holds the view for five seconds`() {
        var now = 1_000L
        val gate = FollowGate(clock = { now })
        assertTrue(gate.following())
        gate.userScrolled()
        assertFalse(gate.following())
        now += FollowGate.HOLD_MS - 1
        assertFalse(gate.following())
        now += 1
        assertTrue(gate.following())
        gate.userScrolled()
        gate.resume()
        assertTrue(gate.following())
    }

    @Test
    fun `the deck shows the place and the two after it`() {
        val rows = listOf(row("a"), row("b"), row("c"), row("d"))
        val deck = deckView(rows, "b")
        assertEquals(2, deck.place)
        assertEquals(4, deck.total)
        assertEquals("c", deck.next?.postId)
        assertEquals("d", deck.then?.postId)
        val elsewhere = deckView(rows, "gone")
        assertNull(elsewhere.place)
        assertEquals("a", elsewhere.next?.postId)
        val last = deckView(rows, "d")
        assertNull(last.next)
        assertEquals(0, deckView(emptyList(), null).total)
    }

    @Test
    fun `generating shows only while waiting`() {
        assertEquals(CardNotice.NONE, cardNotice(queueActive = true, hasText = true, isPlaying = true, stage = SynthesisStage.SYNTHESIZING))
        assertEquals(
            CardNotice.GENERATING,
            cardNotice(queueActive = true, hasText = true, isPlaying = false, stage = SynthesisStage.SYNTHESIZING),
        )
        assertEquals(CardNotice.LOADING, cardNotice(queueActive = true, hasText = false, isPlaying = false, stage = SynthesisStage.IDLE))
        assertEquals(CardNotice.NONE, cardNotice(queueActive = false, hasText = false, isPlaying = false, stage = SynthesisStage.IDLE))
    }
}
