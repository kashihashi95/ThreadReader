package com.redder.sanitizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Reddit acronyms said in full by the rule step, before any model, and the guard that keeps them. */
class RedditAcronymsTest {
    private fun say(text: String) = TextSanitizer.sanitize(text)

    @Test
    fun `OOP in a BORU post is the original poster`() {
        assertEquals(
            "I am NOT original poster, original poster is QuietHarbor_42. Originally posted to r slash confessions.",
            say("I am NOT OOP, OOP is u/QuietHarbor_42. Originally posted to r/confessions."),
        )
        assertEquals(
            "Editor's note: original poster did not leave any comments in this original post",
            say("Editor's note: OOP did not leave any comments in this original post"),
        )
        assertEquals("Finally, a Best of Redditor Updates that's lighthearted.", say("Finally, a BORU that's lighthearted."))
    }

    @Test
    fun `OP is the original poster, and lower case op is left alone`() {
        assertEquals("Original poster said not the asshole. op is fine.", say("OP said NTA. op is fine."))
        assertEquals("I read original poster's update.", say("I read OP's update."))
    }

    @Test
    fun `OOP in a programming comment is left as written`() {
        assertEquals("OOP in Java means classes.", say("OOP in Java means classes."))
    }

    @Test
    fun `so and SO`() {
        assertEquals("I was so tired, so I left.", say("I was so tired, so I left."))
        assertEquals("I was SO angry.", say("I was SO angry."))
        assertEquals("My significant other said no.", say("My SO said no."))
        assertEquals("I love her SO much.", say("I love her SO much."))
        assertEquals("It hit SO HARD.", say("It hit SO HARD."))
    }

    @Test
    fun `ex is never expanded`() {
        assertEquals("My ex said so.", say("My ex said so."))
    }

    @Test
    fun `INFO only as the opening judgement`() {
        assertEquals("More information needed: did you tell him?", say("INFO: did you tell him?"))
        assertEquals("More information needed, do your friends know?", say("INFO do your friends know?"))
        assertEquals("Then the INFO came.", say("Then the INFO came."))
        assertEquals("INFO SESSION TONIGHT", say("INFO SESSION TONIGHT"))
    }

    @Test
    fun `the GC is the group chat unless it is about building`() {
        assertEquals("Then the group chat blew up.", say("Then the GC blew up."))
        assertEquals("Our GC pulled the permit.", say("Our GC pulled the permit."))
    }

    @Test
    fun `family and contact words`() {
        assertEquals(
            "My mother-in-law and dear husband went no contact. I live in NC.",
            say("My MIL and DH went NC. I live in NC."),
        )
        assertEquals("My dear daughter (7) and their DD left.", say("My DD (7) and their DD left."))
        assertEquals("Nintendo DS for my DS.", say("Nintendo DS for my DS."))
    }

    @Test
    fun `ETA only in the ETA colon form`() {
        assertEquals("Edited to add: thanks! ETA 5 minutes.", say("ETA: thanks! ETA 5 minutes."))
    }

    @Test
    fun `urls, user and sub names and code are untouched`() {
        assertEquals("See and r slash AITAH and NTA_bot.", say("See https://x.y/OP and r/AITAH and u/NTA_bot."))
        assertEquals("obj.OP() and OP_x, OP/OOP and #OP", say("obj.OP() and OP_x, OP/OOP and #OP"))
        assertEquals("What does LO stand for?", say("What does LO stand for?"))
    }
}
