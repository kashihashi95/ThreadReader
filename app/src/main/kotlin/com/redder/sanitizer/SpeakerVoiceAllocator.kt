package com.redder.sanitizer

/**
 * Deterministically allocates TTS voice identifiers to Reddit conversation participants.
 *
 * Enforces speaker consistency, distinct voices between direct parent and child comments,
 * dedicated OP anchor voice anchoring the original poster, and mathematical safety against
 * integer overflow edge cases such as [Int.MIN_VALUE].
 *
 * @property availableVoiceIds List of available voice identifiers for synthesis.
 * @property opAnchorVoice Dedicated voice identifier strictly assigned to the thread OP.
 */
class SpeakerVoiceAllocator(
    val availableVoiceIds: List<String> = DEFAULT_VOICE_IDS,
    val opAnchorVoice: String = DEFAULT_OP_VOICE,
) {
    private val assignedVoices = mutableMapOf<String, String>()

    private val conversationalPool: List<String> =
        availableVoiceIds.filter { it != opAnchorVoice }.ifEmpty {
            availableVoiceIds.ifEmpty { listOf(opAnchorVoice) }
        }

    /**
     * Allocates a deterministic voice ID for [author], given their conversation context.
     *
     * Invariants guaranteed:
     * 1. If [isOp] is true, strictly returns [opAnchorVoice].
     * 2. Direct parent and child comments never share the same voice ID (`childVoice != parentVoice`),
     *    provided at least two distinct voices exist in the pool.
     * 3. Allocation is deterministic based on [author] username.
     * 4. Safe against [Int.MIN_VALUE] hash codes without [IndexOutOfBoundsException] or [ArithmeticException].
     *
     * @param author Username of the comment or post author.
     * @param parentAuthor Username of the immediate parent comment/post author, or null if root.
     * @param isOp Whether this author is the original poster of the thread.
     * @return Voice identifier string suitable for audio synthesis.
     */
    fun allocateVoice(
        author: String,
        parentAuthor: String?,
        isOp: Boolean,
    ): String {
        if (isOp) {
            assignedVoices[author] = opAnchorVoice
            return opAnchorVoice
        }

        val parentVoice =
            when {
                parentAuthor == null -> null
                else -> assignedVoices[parentAuthor] ?: computeDefaultVoice(parentAuthor, conversationalPool)
            }

        val existingVoice = assignedVoices[author]
        if (existingVoice != null && (parentVoice == null || existingVoice != parentVoice)) {
            return existingVoice
        }

        val candidates =
            if (parentVoice != null) {
                conversationalPool.filter { it != parentVoice }.ifEmpty { conversationalPool }
            } else {
                conversationalPool
            }

        val safeHash = author.hashCode().toLong() and 0xFFFFFFFFL
        val index = (safeHash % candidates.size).toInt()
        val chosenVoice = candidates[index]

        if (existingVoice == null) {
            assignedVoices[author] = chosenVoice
        }

        return chosenVoice
    }

    /**
     * Clears cached speaker assignments, resetting allocation state for a new thread.
     */
    fun reset() {
        assignedVoices.clear()
    }

    private fun computeDefaultVoice(
        authorName: String,
        pool: List<String>,
    ): String {
        if (pool.isEmpty()) return opAnchorVoice
        val safeHash = authorName.hashCode().toLong() and 0xFFFFFFFFL
        val index = (safeHash % pool.size).toInt()
        return pool[index]
    }

    companion object {
        const val DEFAULT_OP_VOICE: String = "voice_op_anchor"

        val DEFAULT_VOICE_IDS: List<String> =
            listOf(
                "voice_op_anchor",
                "voice_conversational_1",
                "voice_conversational_2",
                "voice_conversational_3",
                "voice_conversational_4",
                "voice_conversational_5",
                "voice_conversational_6",
            )
    }
}
