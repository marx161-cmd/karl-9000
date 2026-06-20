package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.Requests
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_TOP_K = 6
private const val DEFAULT_MIN_SCORE = 0.0
private const val MAX_TOP_K = 12
private const val MAX_RESULT_TEXT_CHARS = 1200

object PhoneRagSearchTool : Tool {
    override val schema = ToolSchema(
        name = "search_phone_context",
        description = "Search the user's on-device Phone RAG vector index for local files, transcripts, notes, documents, and saved phone context. Use this when the user asks about indexed local material, prior saved text, transcripts, or whether something exists in the phone context. Returns matching snippets with source paths and scores.",
        parameters = mapOf(
            "query" to ParameterSchema(
                type = "string",
                description = "Natural-language search query for the local phone vector index",
                required = true,
            ),
            "top_k" to ParameterSchema(
                type = "integer",
                description = "Maximum number of matches to return. Defaults to 6; maximum is 12.",
                required = false,
            ),
            "min_score" to ParameterSchema(
                type = "number",
                description = "Minimum similarity score to return. Defaults to 0.0 so borderline local matches are still visible.",
                required = false,
            ),
        ),
    )

    private val requests = Requests()

    override suspend fun execute(args: Map<String, Any>): Any {
        val query = args["query"]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return mapOf("success" to false, "error" to "query is required")

        val topK = args.optionalInt("top_k", DEFAULT_TOP_K).coerceIn(1, MAX_TOP_K)
        val minScore = args.optionalDouble("min_score", DEFAULT_MIN_SCORE).coerceIn(0.0, 1.0)

        return try {
            val response = requests.phoneRagQuery(
                query = query,
                topK = topK,
                minScore = minScore,
            ).getOrThrow()

            mapOf(
                "success" to response.ok,
                "query" to query,
                "top_k" to topK,
                "min_score" to minScore,
                "results" to response.results.map { result ->
                    mapOf(
                        "score" to result.score,
                        "title" to result.metadata.stringValue("title"),
                        "source" to result.metadata.stringValue("source"),
                        "path" to (result.metadata.stringValue("path") ?: result.metadata.stringValue("source")),
                        "chunk" to result.metadata.stringValue("chunk"),
                        "chunks" to result.metadata.stringValue("chunks"),
                        "text" to result.text.trim().limitChars(MAX_RESULT_TEXT_CHARS),
                    )
                },
            )
        } catch (e: Exception) {
            mapOf(
                "success" to false,
                "error" to "Phone RAG query failed: ${e.message ?: e::class.simpleName.orEmpty()}",
                "hint" to "Start Phone RAG on the device, then index files with `phonerag index-dir ...` from Termux.",
            )
        }
    }

    val toolInfo = ToolInfo(
        id = schema.name,
        name = "Search Phone Context",
        description = "Search the local Phone RAG index from the model when the chat top-bar Phone RAG toggle is enabled.",
    )
}

private fun Map<String, Any>.optionalInt(key: String, default: Int): Int =
    (this[key] as? Number)?.toInt()
        ?: this[key]?.toString()?.toIntOrNull()
        ?: default

private fun Map<String, Any>.optionalDouble(key: String, default: Double): Double =
    (this[key] as? Number)?.toDouble()
        ?: this[key]?.toString()?.toDoubleOrNull()
        ?: default

private fun Map<String, JsonElement>.stringValue(key: String): String? = try {
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

private fun String.limitChars(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."
