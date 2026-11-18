package com.redder.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Clean domain representation of a parsed Reddit thread.
 *
 * @property post The root submission of the thread.
 * @property comments Tree of top-level comments and their replies.
 */
@Serializable
data class RedditThread(
    val post: RedditPost,
    val comments: List<RedditComment> = emptyList(),
)

/**
 * Clean domain representation of a Reddit post (submission).
 *
 * @property id Unique identifier of the post (e.g. "post_xyz123").
 * @property author Username of the post author.
 * @property title Title text of the post.
 * @property selftext Body markdown text of the post, empty for link posts.
 * @property score Net upvote score of the post.
 * @property numComments Total number of comments reported on the thread.
 * @property createdUtc Epoch timestamp (seconds) when the post was created.
 */
@Serializable
data class RedditPost(
    val id: String,
    val author: String,
    val title: String,
    val selftext: String = "",
    val score: Int = 0,
    val numComments: Int = 0,
    val createdUtc: Long = 0L,
)

/**
 * Clean domain representation of a Reddit comment within a discussion tree.
 *
 * @property id Unique identifier of the comment (e.g. "c_alpha1").
 * @property author Username of the comment author.
 * @property body Markdown body content of the comment.
 * @property score Net upvote score of the comment.
 * @property depth Nesting level in the tree (0 for root comments).
 * @property replies Hierarchical list of direct reply comments.
 */
@Serializable
data class RedditComment(
    val id: String,
    val author: String,
    val body: String,
    val score: Int = 0,
    val depth: Int = 0,
    val replies: List<RedditComment> = emptyList(),
)

/**
 * Internal DTO for a Reddit Listing envelope containing children "things".
 */
@Serializable
internal data class RedditListingDto(
    val kind: String = "Listing",
    val data: RedditListingDataDto = RedditListingDataDto(),
)

/**
 * Internal DTO payload for a Reddit Listing containing a list of polymorphic things.
 */
@Serializable
internal data class RedditListingDataDto(
    val children: List<RedditThingDto> = emptyList(),
)

/**
 * Polymorphic envelope representing any Reddit "Thing" (`t1`, `t3`, `more`, etc.).
 */
@Serializable(with = RedditThingSerializer::class)
internal sealed interface RedditThingDto {
    /**
     * Reddit Post submission (`kind: "t3"`).
     */
    @Serializable
    data class Post(
        val kind: String = "t3",
        val data: RedditPostDataDto = RedditPostDataDto(),
    ) : RedditThingDto

    /**
     * Reddit Comment (`kind: "t1"`).
     */
    @Serializable
    data class Comment(
        val kind: String = "t1",
        val data: RedditCommentDataDto = RedditCommentDataDto(),
    ) : RedditThingDto

    /**
     * Reddit pagination placeholder (`kind: "more"`). Safely skipped during domain mapping.
     */
    @Serializable
    data class More(
        val kind: String = "more",
    ) : RedditThingDto

    /**
     * Any unrecognized Reddit thing kind. Safely ignored.
     */
    @Serializable
    data class Unknown(
        val kind: String = "",
        val data: JsonElement? = null,
    ) : RedditThingDto
}

/**
 * Internal DTO for Reddit Post payload.
 */
@Serializable
internal data class RedditPostDataDto(
    val id: String = "",
    val author: String = "",
    val title: String = "",
    val selftext: String = "",
    val score: Int = 0,
    @SerialName("num_comments")
    val numComments: Int = 0,
    @SerialName("created_utc")
    @Serializable(with = FlexibleLongSerializer::class)
    val createdUtc: Long = 0L,
) {
    fun toDomain(): RedditPost =
        RedditPost(
            id = id,
            author = author,
            title = title,
            selftext = selftext,
            score = score,
            numComments = numComments,
            createdUtc = createdUtc,
        )
}

/**
 * Internal DTO for Reddit Comment payload.
 */
@Serializable
internal data class RedditCommentDataDto(
    val id: String = "",
    val author: String = "",
    val body: String = "",
    val score: Int = 0,
    val depth: Int = 0,
    @Serializable(with = RedditRepliesSerializer::class)
    val replies: List<RedditComment> = emptyList(),
) {
    fun toDomain(): RedditComment =
        RedditComment(
            id = id,
            author = author,
            body = body,
            score = score,
            depth = depth,
            replies = replies,
        )
}

/**
 * Converts a domain [RedditComment] back to [RedditCommentDataDto] for bidirectional serialization.
 */
internal fun RedditComment.toDto(): RedditCommentDataDto =
    RedditCommentDataDto(
        id = id,
        author = author,
        body = body,
        score = score,
        depth = depth,
        replies = replies,
    )

/**
 * Custom serializer handling Reddit's polymorphic `replies` invariant.
 *
 * In Reddit API responses, `replies` is either:
 * - A JSON Object (`{ "kind": "Listing", "data": ... }`) when replies exist.
 * - An empty JSON String (`""`) or null when no replies exist.
 */
internal object RedditRepliesSerializer : KSerializer<List<RedditComment>> {
    override val descriptor: SerialDescriptor =
        ListSerializer(RedditComment.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<RedditComment> {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("RedditRepliesSerializer only supports Json decoding")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> {
                val listing =
                    jsonDecoder.json.decodeFromJsonElement(
                        RedditListingDto.serializer(),
                        element,
                    )
                listing.data.children
                    .filterIsInstance<RedditThingDto.Comment>()
                    .map { it.data.toDomain() }
            }
            else -> emptyList()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<RedditComment>,
    ) {
        if (value.isEmpty()) {
            encoder.encodeString("")
        } else {
            val children =
                value.map { comment ->
                    RedditThingDto.Comment(
                        data = comment.toDto(),
                    )
                }
            val listing =
                RedditListingDto(
                    data = RedditListingDataDto(children = children),
                )
            encoder.encodeSerializableValue(RedditListingDto.serializer(), listing)
        }
    }
}

/**
 * Polymorphic serializer for [RedditThingDto] inspecting the `kind` field.
 */
internal object RedditThingSerializer : KSerializer<RedditThingDto> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("RedditThing")

    override fun deserialize(decoder: Decoder): RedditThingDto {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("RedditThingSerializer only supports Json decoding")

        val element = jsonDecoder.decodeJsonElement()
        val kind = (element as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull

        val strategy: DeserializationStrategy<RedditThingDto> =
            when (kind) {
                "t3" -> RedditThingDto.Post.serializer()
                "t1" -> RedditThingDto.Comment.serializer()
                "more" -> RedditThingDto.More.serializer()
                else -> RedditThingDto.Unknown.serializer()
            }

        return jsonDecoder.json.decodeFromJsonElement(strategy, element)
    }

    override fun serialize(
        encoder: Encoder,
        value: RedditThingDto,
    ) {
        when (value) {
            is RedditThingDto.Post ->
                encoder.encodeSerializableValue(RedditThingDto.Post.serializer(), value)
            is RedditThingDto.Comment ->
                encoder.encodeSerializableValue(RedditThingDto.Comment.serializer(), value)
            is RedditThingDto.More ->
                encoder.encodeSerializableValue(RedditThingDto.More.serializer(), value)
            is RedditThingDto.Unknown ->
                encoder.encodeSerializableValue(RedditThingDto.Unknown.serializer(), value)
        }
    }
}

/**
 * Resilient Long serializer that parses Long, Double (e.g. 1726830000.0), or String representations.
 */
internal object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                return element.longOrNull
                    ?: element.doubleOrNull?.toLong()
                    ?: element.content.toLongOrNull()
                    ?: 0L
            }
        }
        return decoder.decodeLong()
    }

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) {
        encoder.encodeLong(value)
    }
}
