package com.redder.parser

import com.redder.model.RedditComment
import com.redder.model.RedditPost
import com.redder.model.RedditThread
import kotlinx.serialization.Serializable

/**
 * Domain representation of a conversational dialogue turn linearized from a Reddit comment tree.
 *
 * @property id Unique identifier of the comment.
 * @property author Username of the comment author.
 * @property body Markdown text content of the comment.
 * @property score Net upvote score of the comment.
 * @property depth Nesting level in the tree (0 for top-level root comments).
 * @property parentAuthor Username of the parent comment author, or root post OP if depth is 0.
 * @property isOp Flag indicating whether this comment was authored by the thread's original poster.
 */
@Serializable
data class DialogueNode(
    val id: String,
    val author: String,
    val body: String,
    val score: Int,
    val depth: Int,
    val parentAuthor: String?,
    val isOp: Boolean,
)

/**
 * Convenience property providing direct access to the root submission post of a [RedditThread].
 */
val RedditThread.rootPost: RedditPost
    get() = post

/**
 * Prunes and linearizes Reddit comment trees for dialogue synthesis and audio playback.
 *
 * Implements deterministic filtering of bots and deleted comments, depth-capping, sibling
 * branch limitation by net score, and pre-order conversational traversal.
 */
class DialogueTreePruner {
    /**
     * Prunes a [RedditThread] comment tree according to depth limits, sibling reply limits,
     * and content filtering rules, linearizing the conversation via depth-first pre-order traversal.
     *
     * @param thread The parsed Reddit thread containing root post and comments.
     * @param maxDepth The maximum nesting depth allowed (comments with depth > maxDepth are discarded).
     * @param maxRepliesPerBranch The maximum number of sibling replies retained per comment branch.
     * @return Linearized list of [DialogueNode] representing the pruned conversational flow.
     */
    fun prune(
        thread: RedditThread,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxRepliesPerBranch: Int = DEFAULT_MAX_REPLIES_PER_BRANCH,
    ): List<DialogueNode> {
        if (maxDepth < 0) {
            return emptyList()
        }

        val effectiveMaxReplies = maxRepliesPerBranch.coerceAtLeast(0)
        val result = mutableListOf<DialogueNode>()
        val opAuthor = thread.post.author

        val topLevelComments =
            thread.comments
                .filterNot { isExcluded(it) }
                .filter { maxOf(0, it.depth) <= maxDepth }
                .sortedByDescending { it.score }

        for (comment in topLevelComments) {
            traverse(
                comment = comment,
                currentDepth = maxOf(0, comment.depth),
                parentAuthor = opAuthor,
                opAuthor = opAuthor,
                maxDepth = maxDepth,
                maxRepliesPerBranch = effectiveMaxReplies,
                destination = result,
            )
        }

        return result
    }

    private fun traverse(
        comment: RedditComment,
        currentDepth: Int,
        parentAuthor: String?,
        opAuthor: String,
        maxDepth: Int,
        maxRepliesPerBranch: Int,
        destination: MutableList<DialogueNode>,
    ) {
        val isOp = opAuthor.isNotBlank() && comment.author.equals(opAuthor, ignoreCase = true)
        val node =
            DialogueNode(
                id = comment.id,
                author = comment.author,
                body = comment.body,
                score = comment.score,
                depth = currentDepth,
                parentAuthor = parentAuthor,
                isOp = isOp,
            )
        destination.add(node)

        val nextDepth = currentDepth + 1
        if (nextDepth > maxDepth || maxRepliesPerBranch == 0) {
            return
        }

        val replies =
            comment.replies
                .filterNot { isExcluded(it) }
                .filter { maxOf(nextDepth, it.depth) <= maxDepth }
                .sortedByDescending { it.score }
                .take(maxRepliesPerBranch)

        for (reply in replies) {
            traverse(
                comment = reply,
                currentDepth = maxOf(nextDepth, reply.depth),
                parentAuthor = comment.author,
                opAuthor = opAuthor,
                maxDepth = maxDepth,
                maxRepliesPerBranch = maxRepliesPerBranch,
                destination = destination,
            )
        }
    }

    private fun isExcluded(comment: RedditComment): Boolean {
        val author = comment.author.trim()
        val body = comment.body.trim()

        if (author.equals("AutoModerator", ignoreCase = true) ||
            author.equals("[deleted]", ignoreCase = true) ||
            author.equals("[removed]", ignoreCase = true)
        ) {
            return true
        }

        if (body.isBlank() ||
            body.equals("[deleted]", ignoreCase = true) ||
            body.equals("[removed]", ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 3
        const val DEFAULT_MAX_REPLIES_PER_BRANCH: Int = 2
    }
}
