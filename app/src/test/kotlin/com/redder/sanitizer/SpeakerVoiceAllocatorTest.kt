package com.redder.sanitizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpeakerVoiceAllocatorTest {
    private lateinit var allocator: SpeakerVoiceAllocator

    @BeforeEach
    fun setUp() {
        allocator = SpeakerVoiceAllocator()
    }

    @Test
    fun `op author strictly receives op anchor voice in post and comment contexts`() {
        val opPostVoice = allocator.allocateVoice(author = "alice", parentAuthor = null, isOp = true)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, opPostVoice)

        val opReplyVoice = allocator.allocateVoice(author = "alice", parentAuthor = "bob", isOp = true)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, opReplyVoice)

        val opSelfReplyVoice = allocator.allocateVoice(author = "alice", parentAuthor = "alice", isOp = true)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, opSelfReplyVoice)
    }

    @Test
    fun `direct parent and child never share the same voice across 50 plus author combinations`() {
        for (i in 0..60) {
            val parentAuthor = "parent_user_$i"
            val childAuthor = "child_user_$i"

            val parentVoice = allocator.allocateVoice(author = parentAuthor, parentAuthor = null, isOp = false)
            val childVoice = allocator.allocateVoice(author = childAuthor, parentAuthor = parentAuthor, isOp = false)

            assertNotEquals(
                parentVoice,
                childVoice,
                "Parent and child must have distinct voices for pair ($parentAuthor, $childAuthor)",
            )
        }
    }

    @Test
    fun `direct parent and child never share voice when child is direct reply to op`() {
        val opVoice = allocator.allocateVoice(author = "original_poster", parentAuthor = null, isOp = true)
        assertEquals(SpeakerVoiceAllocator.DEFAULT_OP_VOICE, opVoice)

        for (i in 0..20) {
            val commenter = "commenter_$i"
            val commenterVoice = allocator.allocateVoice(author = commenter, parentAuthor = "original_poster", isOp = false)
            assertNotEquals(opVoice, commenterVoice)
        }
    }

    @Test
    fun `direct parent and child never share voice even when author replies to themselves`() {
        val author = "self_replying_user"
        val parentVoice = allocator.allocateVoice(author = author, parentAuthor = null, isOp = false)
        val replyVoice = allocator.allocateVoice(author = author, parentAuthor = author, isOp = false)

        assertNotEquals(parentVoice, replyVoice)
    }

    @Test
    fun `deterministic allocation produces identical voice for same author in same context`() {
        val voice1 = allocator.allocateVoice(author = "deterministic_user", parentAuthor = "parent_x", isOp = false)
        val voice2 = allocator.allocateVoice(author = "deterministic_user", parentAuthor = "parent_x", isOp = false)
        assertEquals(voice1, voice2)

        // After reset, reallocating in identical context produces identical voice
        allocator.reset()
        val voiceAfterReset = allocator.allocateVoice(author = "deterministic_user", parentAuthor = "parent_x", isOp = false)
        assertEquals(voice1, voiceAfterReset)
    }

    @Test
    fun `pathological author hashcode of Int MIN_VALUE does not throw exception`() {
        val minValString = findIntMinValueString()
        assertEquals(Int.MIN_VALUE, minValString.hashCode())

        // Must safely allocate without IndexOutOfBoundsException or ArithmeticException
        val voice = allocator.allocateVoice(author = minValString, parentAuthor = null, isOp = false)
        assertNotNull(voice)
        assertTrue(allocator.availableVoiceIds.contains(voice))

        // When replying to another author
        val replyVoice = allocator.allocateVoice(author = minValString, parentAuthor = "parent_user", isOp = false)
        assertNotNull(replyVoice)

        // When another author replies to minVal author
        val childVoice = allocator.allocateVoice(author = "child_user", parentAuthor = minValString, isOp = false)
        assertNotNull(childVoice)
        assertNotEquals(replyVoice, childVoice)
    }

    @Test
    fun `custom voice ids and op anchor voice constructor parameters are respected`() {
        val customVoices = listOf("custom_op", "custom_c1", "custom_c2")
        val customAllocator = SpeakerVoiceAllocator(availableVoiceIds = customVoices, opAnchorVoice = "custom_op")

        val opVoice = customAllocator.allocateVoice("alice", null, isOp = true)
        assertEquals("custom_op", opVoice)

        val child1Voice = customAllocator.allocateVoice("bob", "alice", isOp = false)
        assertTrue(child1Voice == "custom_c1" || child1Voice == "custom_c2")
        assertNotEquals("custom_op", child1Voice)

        val child2Voice = customAllocator.allocateVoice("charlie", "bob", isOp = false)
        assertNotEquals(child1Voice, child2Voice)
    }

    @Test
    fun `allocator handles empty voice pool safely with op fallback`() {
        val emptyAllocator = SpeakerVoiceAllocator(availableVoiceIds = emptyList(), opAnchorVoice = "fallback_op")
        val voice = emptyAllocator.allocateVoice("user_a", null, isOp = false)
        assertEquals("fallback_op", voice)
    }

    @Test
    fun `author retains cached voice across conversation unless parent collision occurs`() {
        // Author Bob first comments under parent A
        val parentA = allocator.allocateVoice("parent_a", null, isOp = false)
        val bobVoice1 = allocator.allocateVoice("bob", "parent_a", isOp = false)
        assertNotEquals(parentA, bobVoice1)

        // Bob comments under parent B whose voice is different from Bob's
        // Let's create parent B such that parent B != bobVoice1
        val parentB = allocator.allocateVoice("parent_b", null, isOp = false)
        if (parentB != bobVoice1) {
            val bobVoice2 = allocator.allocateVoice("bob", "parent_b", isOp = false)
            assertEquals(bobVoice1, bobVoice2, "Bob should retain their consistent voice across turns")
        }
    }

    private fun findIntMinValueString(): String {
        for (c0 in 2320..2330) {
            for (c1 in 0..100) {
                val h1 = c0 * 31 + c1
                for (c2 in 0..100) {
                    val h2 = h1 * 31 + c2
                    for (c3 in 0..100) {
                        val h3 = h2 * 31 + c3
                        val diff = Int.MIN_VALUE - (h3 * 31)
                        if (diff in 0..65535) {
                            val s = "${c0.toChar()}${c1.toChar()}${c2.toChar()}${c3.toChar()}${diff.toChar()}"
                            if (s.hashCode() == Int.MIN_VALUE) {
                                return s
                            }
                        }
                    }
                }
            }
        }
        error("Could not find Int.MIN_VALUE string")
    }
}
