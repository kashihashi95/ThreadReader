package com.redder.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Catalog of preset voices and style conditioning tensors for Supertonic 3 ONNX TTS.
 *
 * Provides 10 preset neural voice profiles (5 female, 5 male) with deterministic
 * style embeddings for the Text-to-Latent (TTL) and Duration Predictor (DP) stages.
 */
object SupertonicVoiceCatalog {
    const val DEFAULT_OP_VOICE: String = "M1"

    val FEMALE_VOICE_IDS: List<String> = listOf("F1", "F2", "F3", "F4", "F5")
    val MALE_VOICE_IDS: List<String> = listOf("M1", "M2", "M3", "M4", "M5")

    val ALL_VOICE_IDS: List<String> =
        listOf("M1", "F1", "F2", "F3", "F4", "F5", "M2", "M3", "M4", "M5")

    private val ttlCache = ConcurrentHashMap<String, Array<Array<FloatArray>>>()
    private val dpCache = ConcurrentHashMap<String, Array<Array<FloatArray>>>()

    /**
     * Loads preset voice style conditioning tensors from JSON files in [dir].
     * Expects files named `<voiceId>.json` containing `style_ttl` and `style_dp` tensors.
     */
    fun loadVoiceStylesFromDir(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        for (voiceId in ALL_VOICE_IDS) {
            val file = File(dir, "$voiceId.json")
            if (file.exists() && file.isFile) {
                try {
                    val text = file.readText()
                    val jsonElement = Json.parseToJsonElement(text)
                    val rootObj =
                        try {
                            jsonElement.jsonObject
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                    val ttlArray = extract3DFloatArray(rootObj["style_ttl"])
                    val dpArray = extract3DFloatArray(rootObj["style_dp"])
                    if (ttlArray != null) {
                        ttlCache[voiceId.uppercase()] = ttlArray
                    }
                    if (dpArray != null) {
                        dpCache[voiceId.uppercase()] = dpArray
                    }
                } catch (_: Throwable) {
                    // Fall back to synthetic styles if parsing fails
                }
            }
        }
    }

    private fun extract3DFloatArray(element: JsonElement?): Array<Array<FloatArray>>? {
        if (element == null) return null
        val arrayElement =
            if (element is JsonObject && element.containsKey("data")) {
                element["data"]
            } else {
                element
            }
        if (arrayElement !is JsonArray || arrayElement.isEmpty()) return null
        val first = arrayElement[0]
        return if (first is JsonArray) {
            if (first.isEmpty()) return null
            val firstInner = first[0]
            if (firstInner is JsonArray) {
                // Already 3D: [1, N, M]
                Array(arrayElement.size) { i ->
                    val slice2d = arrayElement[i].jsonArray
                    Array(slice2d.size) { j ->
                        val slice1d = slice2d[j].jsonArray
                        FloatArray(slice1d.size) { k ->
                            slice1d[k].jsonPrimitive.float
                        }
                    }
                }
            } else {
                // 2D: [N, M], wrap into 3D [1, N, M]
                val rows = arrayElement.size
                val arr2d =
                    Array(rows) { i ->
                        val row = arrayElement[i].jsonArray
                        FloatArray(row.size) { j ->
                            row[j].jsonPrimitive.float
                        }
                    }
                arrayOf(arr2d)
            }
        } else {
            null
        }
    }

    val VOICE_ALIASES: Map<String, String> =
        mapOf(
            "voice_op_anchor" to "M1",
            "voice_conversational_1" to "F1",
            "voice_conversational_2" to "M2",
            "voice_conversational_3" to "F2",
            "voice_conversational_4" to "M3",
            "voice_conversational_5" to "F3",
            "voice_conversational_6" to "M4",
            "voice_0" to "M1",
            "voice_1" to "F1",
            "voice_2" to "M2",
            "voice_3" to "F2",
            "voice_4" to "M3",
            "voice_5" to "F3",
        )

    /**
     * Deterministically maps any given [voiceId] (including conversational aliases and platform IDs)
     * to a valid Supertonic voice ID in [ALL_VOICE_IDS].
     */
    fun resolveVoiceId(voiceId: String): String {
        val trimmed = voiceId.trim()
        if (trimmed.isEmpty()) return DEFAULT_OP_VOICE

        val direct = ALL_VOICE_IDS.firstOrNull { it.equals(trimmed, ignoreCase = true) }
        if (direct != null) return direct

        val alias = VOICE_ALIASES[trimmed.lowercase()]
        if (alias != null) return alias

        if (trimmed.startsWith("F", ignoreCase = true) || trimmed.startsWith("female", ignoreCase = true)) {
            val hash = trimmed.hashCode().toLong() and 0xFFFFFFFFL
            return FEMALE_VOICE_IDS[(hash % FEMALE_VOICE_IDS.size).toInt()]
        }
        if (trimmed.startsWith("M", ignoreCase = true) || trimmed.startsWith("male", ignoreCase = true)) {
            val hash = trimmed.hashCode().toLong() and 0xFFFFFFFFL
            return MALE_VOICE_IDS[(hash % MALE_VOICE_IDS.size).toInt()]
        }

        val hash = trimmed.hashCode().toLong() and 0xFFFFFFFFL
        return ALL_VOICE_IDS[(hash % ALL_VOICE_IDS.size).toInt()]
    }

    /**
     * Checks if the specified [voiceId] is recognized in the catalog or defined as an alias.
     */
    fun hasVoice(voiceId: String): Boolean {
        if (voiceId.isBlank()) return false
        val lower = voiceId.trim().lowercase()
        return ALL_VOICE_IDS.any { it.equals(lower, ignoreCase = true) } || VOICE_ALIASES.containsKey(lower)
    }

    /**
     * Checks if the specified [voiceId] represents a female voice.
     */
    fun isFemaleVoice(voiceId: String): Boolean = resolveVoiceId(voiceId).startsWith("F", ignoreCase = true)

    /**
     * Checks if the specified [voiceId] represents a male voice.
     */
    fun isMaleVoice(voiceId: String): Boolean = resolveVoiceId(voiceId).startsWith("M", ignoreCase = true)

    /**
     * Resolves the Text-to-Latent (TTL) style tensor for [voiceId].
     * Prefers loaded pre-trained style vectors over synthetic generated vectors.
     * Returns a 3D float array with shape [1, 50, 256] containing non-zero floats.
     */
    fun getStyleTtl(voiceId: String): Array<Array<FloatArray>> {
        val targetKey = resolveVoiceId(voiceId)
        val loaded = ttlCache[targetKey]
        if (loaded != null) return loaded

        val fallbackLoaded = ttlCache[DEFAULT_OP_VOICE] ?: ttlCache.values.firstOrNull()
        if (fallbackLoaded != null) return fallbackLoaded

        return ttlCache.computeIfAbsent(targetKey) { id ->
            generateStyleTtl(id)
        }
    }

    /**
     * Resolves the Duration Predictor (DP) style tensor for [voiceId].
     * Prefers loaded pre-trained style vectors over synthetic generated vectors.
     * Returns a 3D float array with shape [1, 8, 16] containing non-zero floats.
     */
    fun getStyleDp(voiceId: String): Array<Array<FloatArray>> {
        val targetKey = resolveVoiceId(voiceId)
        val loaded = dpCache[targetKey]
        if (loaded != null) return loaded

        val fallbackLoaded = dpCache[DEFAULT_OP_VOICE] ?: dpCache.values.firstOrNull()
        if (fallbackLoaded != null) return fallbackLoaded

        return dpCache.computeIfAbsent(targetKey) { id ->
            generateStyleDp(id)
        }
    }

    private fun generateStyleTtl(voiceId: String): Array<Array<FloatArray>> {
        val seed = (voiceId.hashCode() and 0xFFFF).toFloat()
        return Array(1) {
            Array(50) { step ->
                FloatArray(256) { dim ->
                    val raw = sin(seed + (step + 1) * 0.17f + (dim + 1) * 0.031f) * 0.25f
                    if (raw == 0.0f) 0.05f else raw
                }
            }
        }
    }

    private fun generateStyleDp(voiceId: String): Array<Array<FloatArray>> {
        val seed = (voiceId.hashCode() and 0xFFFF).toFloat()
        return Array(1) {
            Array(8) { step ->
                FloatArray(16) { dim ->
                    val raw = cos(seed + (step + 1) * 0.23f + (dim + 1) * 0.071f) * 0.25f
                    if (raw == 0.0f) 0.05f else raw
                }
            }
        }
    }
}
