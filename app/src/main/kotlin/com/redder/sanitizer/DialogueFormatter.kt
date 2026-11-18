package com.redder.sanitizer

import com.redder.model.RedditThread
import com.redder.parser.DialogueNode

/**
 * Domain data model representing an atomic, linearized dialogue chunk for neural TTS synthesis.
 *
 * @property chunkIndex Zero-based monotonically increasing sequence index across the thread.
 * @property nodeId ID of the source Reddit post or comment node.
 * @property author Original Reddit author username of this chunk.
 * @property spokenText Sanitized text content formatted for spoken attribution and synthesis.
 * @property voiceId Neural TTS voice identifier allocated for this speaker.
 * @property isOp Flag indicating whether this utterance belongs to the original post author.
 */
data class DialogueChunk(
    val chunkIndex: Int,
    val nodeId: String,
    val author: String,
    val spokenText: String,
    val voiceId: String,
    val isOp: Boolean,
)

/**
 * Formats a pruned Reddit thread conversation tree into linearized [DialogueChunk] units
 * ready for neural text-to-speech synthesis.
 *
 * Handles username humanization, spoken conversational attribution prefixes,
 * natural sentence boundary splitting at character thresholds, and speaker voice allocation.
 *
 * @property textSanitizer Sanitizer for stripping markdown and noise from post and comments.
 * @property voiceAllocator Allocator for distinct speaker voices.
 * @property maxChunkLength Maximum character length per dialogue chunk for TTS synthesis buffer safety.
 * @property fastStartPieceCount Number of opening utterances (title included) kept sentence-sized.
 * @property fastStartMaxChars Maximum character length of each opening utterance.
 */
class DialogueFormatter(
    private val textSanitizer: TextSanitizer = TextSanitizer(),
    private val voiceAllocator: SpeakerVoiceAllocator = SpeakerVoiceAllocator(),
    private val maxChunkLength: Int = DEFAULT_MAX_CHUNK_LENGTH,
    private val fastStartPieceCount: Int = DEFAULT_FAST_START_PIECE_COUNT,
    private val fastStartMaxChars: Int = DEFAULT_FAST_START_MAX_CHARS,
) {
    /**
     * Formats a [RedditThread] and its pruned [DialogueNode] comment tree into sequential [DialogueChunk]s.
     *
     * @param thread The parsed Reddit thread containing the root submission post.
     * @param prunedNodes The linearized and pruned comments from the tree pruner.
     * @return Sequential list of dialogue chunks with monotonically increasing [DialogueChunk.chunkIndex].
     */
    fun format(
        thread: RedditThread,
        prunedNodes: List<DialogueNode>,
        includeUsernames: Boolean = true,
    ): List<DialogueChunk> {
        val result = mutableListOf<DialogueChunk>()
        var globalChunkIndex = 0

        // 1. Format Root Post
        val post = thread.post
        val spokenAuthor = normalizeUsername(post.author)
        val sanitizedTitle = textSanitizer.sanitize(post.title)
        val sanitizedSelftext = textSanitizer.sanitize(post.selftext)

        val rootVoice = voiceAllocator.allocateVoice(post.author, parentAuthor = null, isOp = true)

        // The title is spoken as its own short utterance so playback can start quickly.
        val spokenPrefix = if (includeUsernames) "Post by $spokenAuthor: " else ""
        val rootTexts =
            when {
                sanitizedTitle.isBlank() && sanitizedSelftext.isBlank() ->
                    if (includeUsernames) listOf("Post by $spokenAuthor") else emptyList()
                sanitizedSelftext.isBlank() -> listOf("$spokenPrefix$sanitizedTitle")
                sanitizedTitle.isBlank() -> listOf("$spokenPrefix$sanitizedSelftext")
                else -> {
                    val titleHasTerminalPunctuation =
                        sanitizedTitle.last() == '.' ||
                            sanitizedTitle.last() == '?' ||
                            sanitizedTitle.last() == '!'
                    val titleSentence = if (titleHasTerminalPunctuation) sanitizedTitle else "$sanitizedTitle."
                    listOf("$spokenPrefix$titleSentence", sanitizedSelftext)
                }
            }

        for (rootText in rootTexts) {
            for (chunkText in splitUtterance(rootText, maxChunkLength)) {
                result.add(
                    DialogueChunk(
                        chunkIndex = globalChunkIndex++,
                        nodeId = post.id,
                        author = post.author,
                        spokenText = chunkText,
                        voiceId = rootVoice,
                        isOp = true,
                    ),
                )
            }
        }

        // 2. Format Pruned Comments
        for (node in prunedNodes) {
            val sanitizedBody = textSanitizer.sanitize(node.body)
            if (sanitizedBody.isBlank()) {
                continue
            }

            val commentAuthor = normalizeUsername(node.author)
            val voiceId = voiceAllocator.allocateVoice(node.author, node.parentAuthor, node.isOp)

            val prefix =
                if (!includeUsernames) {
                    ""
                } else if (node.depth == 0) {
                    "$commentAuthor writes: "
                } else {
                    val parentAuthor = node.parentAuthor
                    if (!parentAuthor.isNullOrBlank()) {
                        val spokenParent = normalizeUsername(parentAuthor)
                        "$commentAuthor replies to $spokenParent: "
                    } else {
                        "$commentAuthor replies: "
                    }
                }

            val commentChunks = formatCommentUtterances(prefix, sanitizedBody, maxChunkLength)
            for (chunkText in commentChunks) {
                result.add(
                    DialogueChunk(
                        chunkIndex = globalChunkIndex++,
                        nodeId = node.id,
                        author = node.author,
                        spokenText = chunkText,
                        voiceId = voiceId,
                        isOp = node.isOp,
                    ),
                )
            }
        }

        return splitOpeningChunks(result)
    }

    /**
     * Re-splits the first utterances of the thread into sentence-sized pieces of at most
     * [fastStartMaxChars] until [fastStartPieceCount] pieces have been produced, so the first audio
     * is quick to synthesize. Pieces only break at sentence ends: a single sentence longer than
     * [fastStartMaxChars] stays whole. The rest of the chunk that crossed the limit stays one
     * utterance, and later chunks are untouched. Speaker attribution is copied; chunk indices are
     * renumbered.
     */
    private fun splitOpeningChunks(chunks: List<DialogueChunk>): List<DialogueChunk> {
        if (fastStartPieceCount <= 0) return chunks
        val split = mutableListOf<DialogueChunk>()
        var budget = fastStartPieceCount
        for (chunk in chunks) {
            if (budget <= 0) {
                split.add(chunk)
                continue
            }
            val pieces = splitAtSentences(chunk.spokenText, fastStartMaxChars, maxChunkLength)
            val head = pieces.take(budget)
            val tail = pieces.drop(budget)
            budget -= head.size
            head.forEach { split.add(chunk.copy(spokenText = it)) }
            if (tail.isNotEmpty()) {
                split.add(chunk.copy(spokenText = tail.joinToString(" ")))
            }
        }
        return split.mapIndexed { index, chunk -> chunk.copy(chunkIndex = index) }
    }

    /**
     * Normalizes a technical Reddit username into spoken speech suitable for TTS synthesis.
     *
     * Strips "u/" or "/u/" prefixes, replaces underscores and hyphens with spaces,
     * strips trailing numeric noise (e.g. "coder_42" -> "coder"), and handles edge cases
     * such as purely numeric usernames without returning blank text.
     *
     * @param author The raw Reddit username.
     * @return Humanized spoken username.
     */
    fun normalizeUsername(author: String): String {
        val trimmed = author.trim()
        val withoutPrefix =
            when {
                trimmed.startsWith("u/", ignoreCase = true) -> trimmed.substring(2)
                trimmed.startsWith("/u/", ignoreCase = true) -> trimmed.substring(3)
                else -> trimmed
            }.trim()

        if (withoutPrefix.isEmpty()) {
            return "anonymous"
        }

        // Replace underscores and hyphens with space
        val spaced = withoutPrefix.replace(Regex("""[_\-]+"""), " ").trim()

        // Strip trailing numeric noise: e.g. " 42", " 101", " 99", or trailing digits attached to letters
        val candidate =
            spaced
                .replace(Regex("""(?:\s+\d+)+$"""), "")
                .replace(Regex("""(?<=[a-zA-Z])\d+$"""), "")
                .trim()

        return when {
            candidate.isNotEmpty() -> candidate.replace(Regex("""\s+"""), " ")
            spaced.isNotEmpty() -> spaced.replace(Regex("""\s+"""), " ")
            else -> withoutPrefix
        }
    }

    /**
     * Splits [text] into chunks of at most [maxChars] characters, preserving sentence boundaries
     * where possible, falling back to clause or word boundaries for oversized sentences.
     *
     * @param text The input text to split.
     * @param maxChars The maximum character limit per chunk.
     * @return List of split strings, none exceeding [maxChars] in length.
     */
    fun splitUtterance(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHUNK_LENGTH,
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val result = mutableListOf<String>()
        var remaining = trimmed

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                result.add(remaining.trim())
                break
            }

            val cut = findCutIndex(remaining, maxChars)
            val chunk = remaining.substring(0, cut).trim()
            if (chunk.isNotEmpty()) {
                result.add(chunk)
            }
            remaining = remaining.substring(cut).trim()
        }

        return result
    }

    /**
     * Splits [text] into pieces of at most [softMax] characters that break only at sentence ends.
     * A sentence longer than [softMax] becomes a piece of its own as long as it fits in [hardMax];
     * only a sentence longer than [hardMax] is cut, at a clause or word boundary.
     */
    internal fun splitAtSentences(
        text: String,
        softMax: Int,
        hardMax: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= softMax) {
                result.add(remaining)
                break
            }
            var cut = lastSentenceEnd(remaining, softMax)
            if (cut <= 0) {
                val firstEnd = lastSentenceEnd(remaining, remaining.length, firstOnly = true)
                cut =
                    when {
                        firstEnd in 1..hardMax -> firstEnd
                        remaining.length <= hardMax -> remaining.length
                        else -> findCutIndex(remaining, hardMax)
                    }
            }
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotEmpty()) result.add(piece)
            remaining = remaining.substring(cut).trim()
        }
        return result
    }

    private fun formatCommentUtterances(
        prefix: String,
        body: String,
        maxChars: Int,
    ): List<String> {
        val fullAttempt = "$prefix$body"
        if (fullAttempt.length <= maxChars) {
            return listOf(fullAttempt)
        }

        val availableForBody = maxChars - prefix.length
        if (availableForBody <= 0) {
            return splitUtterance(fullAttempt, maxChars)
        }

        val cut = findCutIndex(body, availableForBody)
        val firstBodyPart = body.substring(0, cut).trim()
        val chunk0 = "$prefix$firstBodyPart"
        val remainingBody = body.substring(cut).trim()

        if (remainingBody.isEmpty()) {
            return listOf(chunk0)
        }

        val subsequentChunks = splitUtterance(remainingBody, maxChars)
        return listOf(chunk0) + subsequentChunks
    }

    private fun findCutIndex(
        text: String,
        maxChars: Int,
    ): Int {
        val limit = minOf(text.length, maxChars)

        // 1. Natural sentence boundaries (. ? ! or an ellipsis, plus any closing quotes or brackets,
        //    followed by whitespace or end of string)
        val sentenceEnd = lastSentenceEnd(text, limit)
        if (sentenceEnd > 0) return sentenceEnd

        // 2. Clause boundaries (; , : followed by whitespace, an em or en dash, or a spaced hyphen)
        for (i in (limit - 1) downTo 0) {
            val char = text[i]
            if (char == ';' || char == ',' || char == ':') {
                val isBoundary = (i + 1 == text.length) || (i + 1 < text.length && text[i + 1].isWhitespace())
                if (isBoundary) {
                    return i + 1
                }
            }
            if ((char == '—' || char == '–') && i > 0) {
                return i + 1
            }
            if (char == '-' && i > 0 && text[i - 1].isWhitespace() && i + 1 < text.length && text[i + 1].isWhitespace()) {
                return i
            }
        }

        // 3. Word boundaries (whitespace)
        for (i in (limit - 1) downTo 1) {
            if (text[i].isWhitespace()) {
                return i
            }
        }

        // 4. Hard break fallback
        return limit
    }

    /**
     * Returns the index just past the last sentence end that lies within the first [limit]
     * characters of [text] (or the first one when [firstOnly]), or -1 when there is none. A sentence
     * end is `.`, `?`, `!` or `…`, optionally followed by closing quotes or brackets, then whitespace
     * or the end of the text.
     */
    private fun lastSentenceEnd(
        text: String,
        limit: Int,
        firstOnly: Boolean = false,
    ): Int {
        val bound = minOf(limit, text.length)
        val indices = if (firstOnly) 0 until bound else (bound - 1) downTo 0
        for (i in indices) {
            val char = text[i]
            if (char != '.' && char != '?' && char != '!' && char != '…') continue
            var end = i + 1
            while (end < text.length && text[end] in SENTENCE_CLOSERS) end++
            if (end <= bound && (end == text.length || text[end].isWhitespace())) return end
        }
        return -1
    }

    companion object {
        /** Closing quotes and brackets that may follow a sentence's terminal punctuation. */
        private const val SENTENCE_CLOSERS: String = "\"')]”’»"

        const val DEFAULT_MAX_CHUNK_LENGTH: Int = 400

        /** The title plus about three sentence-sized pieces of what follows it. */
        const val DEFAULT_FAST_START_PIECE_COUNT: Int = 4
        const val DEFAULT_FAST_START_MAX_CHARS: Int = 160
    }
}
