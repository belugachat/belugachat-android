package com.belugachat.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Messages API over raw HTTP (shared by every provider so custom base URLs work). */
class AnthropicClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = Http.client,
) : LlmClient {
    private val root = baseUrl.trimEnd('/').removeSuffix("/v1")
    private val official = root.contains("api.anthropic.com")

    private fun Request.Builder.auth(): Request.Builder = apply {
        if (apiKey.isNotEmpty()) header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
    }

    override suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/v1/models?limit=1000").auth().get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderException(errorMessage(response.code, body), response.code)
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RemoteModel(id, obj["display_name"]?.jsonPrimitive?.contentOrNull ?: id)
            }
        }
    }

    override fun stream(request: LlmRequest): Flow<StreamEvent> = flow {
        val payload = buildPayload(request, official)
        val builder = Request.Builder()
            .url("$root/v1/messages")
            .auth()
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(builder.build())
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ProviderException(errorMessage(response.code, body), response.code)
            }
            val translator = AnthropicStreamTranslator()
            readSse(response.body!!.source()) { sse ->
                for (event in translator.translate(sse)) emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Models older than the 4.6 generation take `budget_tokens`; everything newer uses adaptive thinking. */
        fun isLegacyThinking(model: String): Boolean {
            val m = model.lowercase()
            if (m.startsWith("claude-3") || m.startsWith("claude-2") || m.startsWith("claude-instant")) return true
            val legacy = listOf(
                "claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-5", "claude-opus-4-1",
                "claude-sonnet-4-0", "claude-opus-4-0", "claude-sonnet-4-2", "claude-opus-4-2",
            )
            if (legacy.any { m.startsWith(it) }) return true
            return Regex("^claude-(sonnet|opus)-4-\\d{8}").containsMatchIn(m)
        }

        fun maxTokens(model: String): Int {
            val m = model.lowercase()
            return when {
                m.startsWith("claude-3-7") -> 32000
                m.startsWith("claude-3-5") -> 8192
                m.startsWith("claude-3") || m.startsWith("claude-2") -> 4096
                else -> 32000
            }
        }

        fun buildPayload(request: LlmRequest, official: Boolean = true): JsonObject = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            if (request.system.isNotBlank()) put("system", request.system)
            var maxTokens = maxTokens(request.model)
            val legacy = isLegacyThinking(request.model)
            if (legacy) {
                val budget = when (request.reasoning) {
                    "low" -> 2048
                    "medium" -> 4096
                    "high" -> 8192
                    "max" -> 16384
                    else -> null
                }
                if (budget != null) {
                    put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) })
                    maxTokens = maxOf(maxTokens, budget + 4096)
                }
            } else {
                put("thinking", buildJsonObject { put("type", "adaptive"); put("display", "summarized") })
                if (request.reasoning in setOf("low", "medium", "high", "max")) {
                    put("output_config", buildJsonObject { put("effort", request.reasoning) })
                }
            }
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                for (message in mergeRoles(request.messages)) {
                    val blocks = message.content.mapNotNull { contentBlock(it) }
                    if (blocks.isEmpty()) continue
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", JsonArray(blocks))
                    })
                }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in request.tools) add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.inputSchema)
                        if (official) put("eager_input_streaming", true)
                    })
                })
            }
        }

        /** Tool results travel in user turns; consecutive same-role messages are merged. */
        private fun mergeRoles(messages: List<LlmMessage>): List<LlmMessage> {
            val merged = mutableListOf<LlmMessage>()
            for (message in messages) {
                val role = if (message.role == "tool") "user" else message.role
                val last = merged.lastOrNull()
                if (last != null && last.role == role) {
                    merged[merged.lastIndex] = last.copy(content = last.content + message.content)
                } else {
                    merged += LlmMessage(role, message.content)
                }
            }
            return merged
        }

        private fun contentBlock(content: Content): JsonObject? = when (content) {
            is Content.Text -> if (content.text.isBlank()) null else buildJsonObject {
                put("type", "text"); put("text", content.text)
            }
            is Content.Image -> buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64"); put("media_type", content.mime); put("data", content.base64)
                })
            }
            is Content.Pdf -> buildJsonObject {
                put("type", "document")
                put("title", content.name)
                put("source", buildJsonObject {
                    put("type", "base64"); put("media_type", "application/pdf"); put("data", content.base64)
                })
            }
            is Content.Thinking -> when {
                content.redacted != null -> buildJsonObject { put("type", "redacted_thinking"); put("data", content.redacted) }
                content.signature != null -> buildJsonObject {
                    put("type", "thinking"); put("thinking", content.text); put("signature", content.signature)
                }
                else -> null
            }
            is Content.ToolUse -> buildJsonObject {
                put("type", "tool_use"); put("id", content.id); put("name", content.name); put("input", content.input)
            }
            is Content.ToolResult -> buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", content.toolUseId)
                put("content", content.content.ifEmpty { "(empty)" })
                if (content.isError) put("is_error", true)
            }
        }
    }
}

/** Turns Anthropic SSE events into [StreamEvent]s. */
class AnthropicStreamTranslator {
    private data class ToolBlock(val id: String, val name: String, val json: StringBuilder = StringBuilder())

    private val tools = mutableMapOf<Int, ToolBlock>()
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var stopReason: String? = null

    fun translate(sse: SseEvent): List<StreamEvent> {
        val payload = runCatching { Json.parseToJsonElement(sse.data).jsonObject }.getOrNull() ?: return emptyList()
        val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: sse.event
        val index = payload["index"]?.jsonPrimitive?.int ?: 0
        return when (type) {
            "message_start" -> {
                val usage = payload["message"]?.jsonObject?.get("usage")?.jsonObject
                inputTokens = (usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0) +
                    (usage?.get("cache_read_input_tokens")?.jsonPrimitive?.longOrNull ?: 0) +
                    (usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.longOrNull ?: 0)
                listOf(StreamEvent.Usage(inputTokens, 0))
            }
            "content_block_start" -> {
                val block = payload["content_block"]?.jsonObject ?: return emptyList()
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "tool_use" -> {
                        tools[index] = ToolBlock(
                            block["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            block["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                        emptyList()
                    }
                    "redacted_thinking" -> listOf(
                        StreamEvent.RedactedThinking(block["data"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                    )
                    "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { listOf(StreamEvent.TextDelta(it)) } ?: emptyList()
                    else -> emptyList()
                }
            }
            "content_block_delta" -> {
                val delta = payload["delta"]?.jsonObject ?: return emptyList()
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> listOf(StreamEvent.TextDelta(delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    "thinking_delta" -> listOf(StreamEvent.ThinkingDelta(delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    "signature_delta" -> listOf(StreamEvent.ThinkingSignature(delta["signature"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                    "input_json_delta" -> {
                        tools[index]?.json?.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            "content_block_stop" -> {
                val tool = tools.remove(index) ?: return emptyList()
                listOf(StreamEvent.ToolCall(tool.id, tool.name, tool.json.toString().ifBlank { "{}" }))
            }
            "message_delta" -> {
                stopReason = payload["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stopReason
                outputTokens = payload["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.long ?: outputTokens
                listOf(StreamEvent.Usage(inputTokens, outputTokens))
            }
            "message_stop" -> listOf(StreamEvent.Finished(stopReason))
            "error" -> {
                val error = payload["error"]?.jsonObject
                throw ProviderException(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "The provider returned an error.")
            }
            else -> emptyList()
        }
    }
}
