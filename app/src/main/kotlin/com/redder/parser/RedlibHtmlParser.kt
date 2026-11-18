package com.redder.parser

import com.redder.model.RedditComment
import com.redder.model.RedditPost
import com.redder.model.RedditThread
import java.util.ArrayDeque

/**
 * Pure Kotlin parser for Redlib / Libreddit HTML thread payloads and JSON fallback.
 *
 * Extracts structured post data, author metadata, markdown body text, and recursive
 * comment trees without requiring Android platform dependencies.
 */
class RedlibHtmlParser(
    private val polymorphicJsonParser: PolymorphicRedditParser = PolymorphicRedditParser(),
) {
    /**
     * Parses a Redlib thread HTML document or raw JSON payload into a domain [RedditThread].
     *
     * @param htmlOrJson The raw HTML or JSON payload to parse.
     * @return The structured [RedditThread] containing the post and nested comments.
     * @throws IllegalArgumentException If [htmlOrJson] is blank or cannot be parsed.
     */
    fun parseThread(htmlOrJson: String): RedditThread {
        require(htmlOrJson.isNotBlank()) { "Payload must not be blank" }
        val trimmed = htmlOrJson.trim()

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return try {
                polymorphicJsonParser.parseThread(trimmed)
            } catch (e: Exception) {
                if (trimmed.startsWith("{")) {
                    polymorphicJsonParser.parseThread("[$trimmed]")
                } else {
                    throw e
                }
            }
        }

        val doc = parseHtml(trimmed)
        return extractThread(doc)
    }

    private fun extractThread(doc: HtmlNode): RedditThread {
        val titleNode =
            doc.findFirst { it.tagName == "h1" && it.hasClass("post_title") }
                ?: doc.findFirst { it.hasClass("post_title") }
                ?: doc.findFirst { it.tagName == "title" }

        val rawTitle = titleNode?.extractAllText() ?: ""
        val cleanTitle =
            rawTitle
                .removeSuffix(" - Redlib")
                .removeSuffix(" - Libreddit")
                .trim()

        require(cleanTitle.isNotBlank()) { "No post title found in HTML payload" }
        require(!isBotChallenge(cleanTitle)) {
            "Bot challenge or security check detected in HTML title: $cleanTitle"
        }

        val postContainer = doc.findFirst { it.hasClass("post") || it.hasClass("post_container") }
        requireNotNull(postContainer) {
            "Invalid thread HTML: missing post container ($cleanTitle)"
        }

        val postAuthorNode =
            postContainer.findFirst { it.hasClass("post_author") }
                ?: doc.findFirst { it.hasClass("post_author") }

        val postAuthor =
            if (postAuthorNode != null) {
                postAuthorNode.extractAllText().removePrefix("u/").trim()
            } else {
                val metaNode =
                    postContainer.findFirst { it.hasClass("post_meta") }
                        ?: doc.findFirst { it.hasClass("post_meta") }
                if (metaNode != null) {
                    val metaText = metaNode.extractAllText()
                    val match = Regex("""(?:by\s+u/|by\s+|u/)([A-Za-z0-9_-]+)""").find(metaText)
                    match?.groupValues?.get(1) ?: "[unknown]"
                } else {
                    "[unknown]"
                }
            }

        val bodyNode =
            postContainer.findFirst { it.hasClass("post_body") || it.hasClass("md") }
                ?: doc.findFirst { it.hasClass("post_body") }
        val selftext = bodyNode?.extractAllText()?.trim() ?: ""

        val scoreNode =
            postContainer.findFirst { it.hasClass("post_score") || it.hasClass("score") }
                ?: doc.findFirst { it.hasClass("post_score") }
        val postScore = parseScore(scoreNode?.extractAllText())

        val postId = postContainer.id?.ifBlank { null } ?: "post_redlib"

        val commentsContainer =
            doc.findFirst { it.hasClass("comments") || it.id == "comments" } ?: doc
        val topLevelCommentNodes = findTopLevelComments(commentsContainer)
        val comments =
            topLevelCommentNodes.mapIndexed { index, node ->
                parseCommentNode(node, depth = 0, fallbackIndex = "$index")
            }

        val totalComments = countAllComments(comments)

        val post =
            RedditPost(
                id = postId,
                author = postAuthor,
                title = cleanTitle,
                selftext = selftext,
                score = postScore,
                numComments = totalComments,
            )

        return RedditThread(
            post = post,
            comments = comments,
        )
    }

    private fun parseCommentNode(
        commentNode: HtmlNode,
        depth: Int,
        fallbackIndex: String,
    ): RedditComment {
        val commentId = commentNode.id?.ifBlank { null } ?: "comment_$fallbackIndex"

        val author =
            commentNode.findFirst { it.hasClass("comment_author") }
                ?.extractAllText()
                ?.removePrefix("u/")
                ?.trim()
                ?.ifEmpty { "[unknown]" }
                ?: "[unknown]"

        val scoreText = commentNode.findFirst { it.hasClass("comment_score") }?.extractAllText()
        val score = parseScore(scoreText)

        val bodyNode =
            commentNode.findFirst { it.hasClass("comment_body") }
                ?: commentNode.findFirst { it.hasClass("md") }
        val body = bodyNode?.extractAllText()?.trim() ?: ""

        val repliesContainer = commentNode.findFirst { it.hasClass("replies") }
        val replyNodes =
            if (repliesContainer != null) {
                findDirectComments(repliesContainer)
            } else {
                emptyList()
            }

        val replies =
            replyNodes.mapIndexed { index, childNode ->
                parseCommentNode(childNode, depth = depth + 1, fallbackIndex = "${fallbackIndex}_$index")
            }

        return RedditComment(
            id = commentId,
            author = author,
            body = body,
            score = score,
            depth = depth,
            replies = replies,
        )
    }

    private fun findTopLevelComments(root: HtmlNode): List<HtmlNode> {
        val list = mutableListOf<HtmlNode>()

        fun traverse(node: HtmlNode) {
            if (node.hasClass("replies")) {
                return
            }
            for (child in node.children) {
                if (child.hasClass("comment")) {
                    list.add(child)
                } else {
                    traverse(child)
                }
            }
        }
        traverse(root)
        return list
    }

    private fun findDirectComments(container: HtmlNode): List<HtmlNode> {
        val list = mutableListOf<HtmlNode>()

        fun traverse(node: HtmlNode) {
            for (child in node.children) {
                if (child.hasClass("comment")) {
                    list.add(child)
                } else {
                    traverse(child)
                }
            }
        }
        traverse(container)
        return list
    }

    private fun parseScore(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val match = Regex("""([+-]?\d+)""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun countAllComments(comments: List<RedditComment>): Int {
        var count = 0
        for (comment in comments) {
            count += 1 + countAllComments(comment.replies)
        }
        return count
    }

    private fun parseHtml(html: String): HtmlNode {
        val root = HtmlNode("root")
        val stack = ArrayDeque<HtmlNode>()
        stack.addLast(root)

        var index = 0
        val length = html.length

        while (index < length) {
            val nextLt = html.indexOf('<', index)
            if (nextLt == -1) {
                val text = html.substring(index).trim()
                if (text.isNotEmpty()) {
                    stack.last().children.add(HtmlNode("#text", textContent = unescapeHtml(text)))
                }
                break
            }

            if (nextLt > index) {
                val rawText = html.substring(index, nextLt)
                val text = unescapeHtml(rawText)
                if (text.isNotBlank()) {
                    stack.last().children.add(HtmlNode("#text", textContent = text))
                }
            }

            if (html.startsWith("<!--", nextLt)) {
                val endComment = html.indexOf("-->", nextLt + 4)
                index = if (endComment != -1) endComment + 3 else length
                continue
            }

            if (html.startsWith("<!", nextLt)) {
                val endDirective = html.indexOf('>', nextLt + 2)
                index = if (endDirective != -1) endDirective + 1 else length
                continue
            }

            val nextGt = html.indexOf('>', nextLt)
            if (nextGt == -1) {
                break
            }

            val tagContent = html.substring(nextLt + 1, nextGt).trim()
            index = nextGt + 1

            if (tagContent.isEmpty()) {
                continue
            }

            if (tagContent.startsWith("/")) {
                val closingTag =
                    tagContent.substring(1).trim().split(Regex("\\s+"))[0].lowercase()
                while (stack.size > 1 && stack.last().tagName != closingTag) {
                    stack.removeLast()
                }
                if (stack.size > 1 && stack.last().tagName == closingTag) {
                    stack.removeLast()
                }
                continue
            }

            val isSelfClosing = tagContent.endsWith("/")
            val cleanTagContent = if (isSelfClosing) tagContent.dropLast(1).trim() else tagContent
            val parts = parseTagParts(cleanTagContent)
            val tagName = parts.first.lowercase()
            val attributes = parts.second

            val node = HtmlNode(tagName, attributes)
            stack.last().children.add(node)

            if (tagName == "script" || tagName == "style") {
                val endTag = "</$tagName>"
                val endScript = html.indexOf(endTag, index, ignoreCase = true)
                index = if (endScript != -1) endScript + endTag.length else length
                continue
            }

            if (!isSelfClosing && tagName !in VOID_TAGS) {
                stack.addLast(node)
            }
        }

        return root
    }

    private fun parseTagParts(content: String): Pair<String, Map<String, String>> {
        var i = 0
        val len = content.length
        while (i < len && !content[i].isWhitespace()) {
            i++
        }
        val tagName = content.substring(0, i)
        val attrs = mutableMapOf<String, String>()

        while (i < len) {
            while (i < len && content[i].isWhitespace()) i++
            if (i >= len) break

            val keyStart = i
            while (i < len && content[i] != '=' && !content[i].isWhitespace() && content[i] != '/') {
                i++
            }
            val key = content.substring(keyStart, i).lowercase()

            while (i < len && content[i].isWhitespace()) i++

            if (i < len && content[i] == '=') {
                i++
                while (i < len && content[i].isWhitespace()) i++
                if (i < len) {
                    val quote = content[i]
                    if (quote == '"' || quote == '\'') {
                        i++
                        val valStart = i
                        while (i < len && content[i] != quote) {
                            i++
                        }
                        val value = content.substring(valStart, i)
                        if (i < len && content[i] == quote) i++
                        attrs[key] = unescapeHtml(value)
                    } else {
                        val valStart = i
                        while (i < len && !content[i].isWhitespace() && content[i] != '>') {
                            i++
                        }
                        attrs[key] = unescapeHtml(content.substring(valStart, i))
                    }
                }
            } else if (key.isNotEmpty()) {
                attrs[key] = key
            }
        }

        return Pair(tagName, attrs)
    }

    companion object {
        val BOT_CHALLENGE_PATTERNS: List<String> =
            listOf(
                "making sure you're not a bot",
                "making sure you are not a bot",
                "blocked by network security",
                "attention required",
                "just a moment...",
                "just a moment",
                "security check",
                "access denied",
                "whoa there, patient bear",
                "cloudflare ray id",
            )

        fun isBotChallenge(text: String): Boolean {
            val lower = text.lowercase()
            return BOT_CHALLENGE_PATTERNS.any { lower.contains(it) }
        }

        private val VOID_TAGS =
            setOf(
                "area", "base", "br", "col", "embed", "hr", "img", "input",
                "link", "meta", "param", "source", "track", "wbr",
            )

        /**
         * Unescapes HTML entities including standard named entities, decimal, and hex entities.
         */
        fun unescapeHtml(text: String): String {
            if (!text.contains('&')) return text
            var result =
                text
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&#x27;", "'", ignoreCase = true)
                    .replace("&nbsp;", " ")
                    .replace("&mdash;", "—")
                    .replace("&ndash;", "–")
                    .replace("&hellip;", "…")

            result =
                Regex("""&#([0-9]{1,7});""").replace(result) { match ->
                    val code = match.groupValues[1].toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) {
                        String(Character.toChars(code))
                    } else {
                        match.value
                    }
                }

            result =
                Regex("""&#[xX]([0-9a-fA-F]{1,6});""").replace(result) { match ->
                    val code = match.groupValues[1].toIntOrNull(16)
                    if (code != null && Character.isValidCodePoint(code)) {
                        String(Character.toChars(code))
                    } else {
                        match.value
                    }
                }

            return result
        }
    }
}

internal class HtmlNode(
    val tagName: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: MutableList<HtmlNode> = mutableListOf(),
    var textContent: String = "",
) {
    val id: String? get() = attributes["id"]
    val className: String get() = attributes["class"] ?: ""

    fun hasClass(targetClass: String): Boolean {
        val classes = className.split(Regex("\\s+"))
        return classes.any { it.equals(targetClass, ignoreCase = true) }
    }

    fun findFirst(predicate: (HtmlNode) -> Boolean): HtmlNode? {
        if (predicate(this)) return this
        for (child in children) {
            val found = child.findFirst(predicate)
            if (found != null) return found
        }
        return null
    }

    fun extractAllText(): String {
        val sb = StringBuilder()

        fun collect(node: HtmlNode) {
            if (node.tagName == "br") {
                sb.append("\n")
                return
            }
            if (node.textContent.isNotEmpty()) {
                sb.append(node.textContent)
            }
            for (child in node.children) {
                collect(child)
            }
            if (node.tagName in BLOCK_TAGS && sb.isNotEmpty() && !sb.endsWith("\n")) {
                sb.append("\n")
            }
        }
        collect(this)
        return RedlibHtmlParser.unescapeHtml(sb.toString().trim())
    }

    companion object {
        private val BLOCK_TAGS =
            setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr")
    }
}
