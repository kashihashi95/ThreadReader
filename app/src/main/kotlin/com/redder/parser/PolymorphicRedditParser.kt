package com.redder.parser

import com.redder.model.RedditListingDto
import com.redder.model.RedditPost
import com.redder.model.RedditThingDto
import com.redder.model.RedditThread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Robust JSON parser for Reddit thread endpoint payloads.
 *
 * Handles Reddit's polymorphic array structure (`[Listing(post), Listing(comments)]`),
 * filtering out pagination artifacts (`kind: "more"`) and decoding deeply nested comment trees.
 */
class PolymorphicRedditParser(
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Parses a raw Reddit thread JSON payload into a [RedditThread].
     *
     * @param jsonString Raw JSON string returned from Reddit's thread endpoint (`.json`).
     * @return Fully parsed [RedditThread] containing the submission post and hierarchical comments.
     * @throws IllegalArgumentException If [jsonString] is blank, not a JSON array, empty, or missing post.
     * @throws kotlinx.serialization.SerializationException On JSON syntax errors.
     */
    fun parseThread(jsonString: String): RedditThread {
        require(jsonString.isNotBlank()) { "JSON string must not be blank" }

        val rootElement = json.parseToJsonElement(jsonString)
        require(rootElement is JsonArray) {
            "Expected JSON array for Reddit thread, but got: ${rootElement::class.simpleName}"
        }
        require(rootElement.isNotEmpty()) { "Reddit thread JSON array must not be empty" }

        val listings = json.decodeFromJsonElement<List<RedditListingDto>>(rootElement)
        require(listings.isNotEmpty()) { "No listings found in Reddit thread JSON" }

        val postListing = listings[0]
        val postThing =
            postListing.data.children
                .filterIsInstance<RedditThingDto.Post>()
                .firstOrNull()
                ?: throw IllegalArgumentException("No post found in listing at index 0")

        val post: RedditPost = postThing.data.toDomain()

        val comments =
            if (listings.size > 1) {
                listings[1].data.children
                    .filterIsInstance<RedditThingDto.Comment>()
                    .map { it.data.toDomain() }
            } else {
                emptyList()
            }

        return RedditThread(
            post = post,
            comments = comments,
        )
    }

    companion object {
        val DEFAULT_JSON: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
                encodeDefaults = true
            }

        /**
         * Convenience static method to parse a thread using default [Json] configuration.
         */
        fun parseThread(jsonString: String): RedditThread = PolymorphicRedditParser(DEFAULT_JSON).parseThread(jsonString)
    }
}
