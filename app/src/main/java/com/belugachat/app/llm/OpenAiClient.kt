package com.belugachat.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI Chat Completions, used by OpenAI and every OpenAI-compatible provider. */
class OpenAiClient(
    baseUrl: String,
    private val apiKey: String,
    private val preset: String,
    private val http: OkHttpClient = Http.client,
) : LlmClient {
    private val root = baseUrl.trimEnd('/')

    private fun Request.Builder.auth(): Request.Builder {
        if (apiKey.isNotEmpty()) header("authorization", "Bearer $apiKey")
        if (preset == "openrouter") {
            header("HTTP-Referer", "https://belugachat.app")
            header("X-Title", "Beluga Chat")
        }
        return this
    }

    override suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$root/models").auth().get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderException(errorMessage(response.code, body), response.code)
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id.removePrefix("models/")
                    RemoteModel(id.removePrefix("models/"), name)
                }
                .filter { model -> EXCLUDED.none { model.id.lowercase().contains(it) } }
                .sortedBy { it.id }
        }
    }

    override fun stream(request: LlmRequest): Flow<StreamEvent> = flow {
        val payload = buildPayload(request, preset)
        val call = http.newCall(
            Request.Builder()
                .url("$root/chat/completions")
                .auth()
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ProviderException(errorMessage(response.code, body), response.code)
            }
            val translator = OpenAiStreamTranslator()
            readSse(response.body!!.source()) { sse ->
                for (event in translator.translate(sse.data)) emit(event)
            }
            for (event in translator.finish()) emit(event)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val GEMINI_FALLBACK_THOUGHT_SIGNATURE = "context_engineering_is_the_way_to_go"
        private val EXCLUDED = listOf(
            "embedding", "tts", "whisper", "dall-e", "moderation", "audio", "realtime", "transcribe",
            "image-", "-image", "imagen", "veo", "aqa", "rerank",
        )

        fun buildPayload(request: LlmRequest, preset: String): JsonObject = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (request.reasoning != "default" && preset != "deepseek") {
                val effort = when {
                    preset == "deploy" && request.reasoning == "medium" -> "high"
                    preset == "deploy" && request.reasoning == "max" -> "max"
                    request.reasoning == "max" -> "high"
                    else -> request.reasoning
                }
                if (preset == "openrouter") {
                    put("reasoning", buildJsonObject { put("effort", effort) })
                } else {
                    put("reasoning_effort", effort)
                }
            }
            put("messages", buildJsonArray {
                if (request.system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", request.system) })
                for (message in request.messages) {
                    when (message.role) {
                        "tool" -> message.content.filterIsInstance<Content.ToolResult>().forEach { result ->
                            add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", result.toolUseId)
                                put("content", result.content.ifEmpty { "(empty)" })
                            })
                        }
                        "assistant" -> {
                            val text = message.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            val thinking = message.content.filterIsInstance<Content.Thinking>().joinToString("") { it.text }
                            val calls = message.content.filterIsInstance<Content.ToolUse>()
                            if (text.isBlank() && calls.isEmpty()) continue
                            add(buildJsonObject {
                                put("role", "assistant")
                                if (text.isBlank()) put("content", JsonNull) else put("content", text)
                                if (preset == "deepseek" && calls.isNotEmpty() && thinking.isNotEmpty()) {
                                    put("reasoning_content", thinking)
                                }
                                if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                                    for (call in calls) add(buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.input.toString())
                                        })
                                        val sig = call.thoughtSignature?.takeIf { it.isNotEmpty() }
                                            ?: if (preset == "gemini" || request.model.contains("gemini", ignoreCase = true)) {
                                                GEMINI_FALLBACK_THOUGHT_SIGNATURE
                                            } else {
                                                null
                                            }
                                        if (sig != null) {
                                            put("extra_content", buildJsonObject {
                                                put("google", buildJsonObject {
                                                    put("thought_signature", sig)
                                                })
                                            })
                                        }
                                    })
                                })
                            })
                        }
                        else -> {
                            val parts = message.content.filter { it is Content.Text || it is Content.Image || it is Content.Pdf }
                            if (parts.isEmpty()) continue
                            add(buildJsonObject {
                                put("role", "user")
                                if (parts.all { it is Content.Text }) {
                                    put("content", parts.joinToString("\n\n") { (it as Content.Text).text })
                                } else {
                                    put("content", buildJsonArray {
                                        for (part in parts) add(userPart(part))
                                    })
                                }
                            })
                        }
                    }
                }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in request.tools) add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                })
            }
        }

        private fun userPart(part: Content): JsonObject = when (part) {
            is Content.Image -> buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:${part.mime};base64,${part.base64}") })
            }
            is Content.Pdf -> buildJsonObject {
                put("type", "file")
                put("file", buildJsonObject {
                    put("filename", part.name)
                    put("file_data", "data:application/pdf;base64,${part.base64}")
                })
            }
            is Content.Text -> buildJsonObject { put("type", "text"); put("text", part.text) }
            else -> error("unsupported user part")
        }
    }
}

/** Turns Chat Completions chunks into [StreamEvent]s. Tool calls are emitted once complete. */
class OpenAiStreamTranslator {
    private data class PendingCall(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
        var thoughtSignature: String? = null,
    )

    private val calls = sortedMapOf<Int, PendingCall>()
    private var finishReason: String? = null
    private var finished = false

    fun translate(data: String): List<StreamEvent> {
        if (data.trim() == "[DONE]") return finish()
        val chunk = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        chunk["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw ProviderException(message)
        }
        val events = mutableListOf<StreamEvent>()
        val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = choice?.get("delta") as? JsonObject
        if (delta != null) {
            val reasoning = (delta["reasoning_content"] ?: delta["reasoning"])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (!reasoning.isNullOrEmpty()) events += StreamEvent.ThinkingDelta(reasoning)
            val content = delta["content"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (!content.isNullOrEmpty()) events += StreamEvent.TextDelta(content)
            (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
                val obj = item.jsonObject
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: calls.size
                val call = calls.getOrPut(index) { PendingCall() }
                obj["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) call.id = it }
                val function = obj["function"] as? JsonObject
                function?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) call.name = it }
                function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { call.args.append(it) }
                val extra = (obj["extra_content"] as? JsonObject)?.get("google") as? JsonObject
                val sig = (extra?.get("thought_signature") as? JsonPrimitive)?.contentOrNull
                    ?: (obj["thought_signature"] as? JsonPrimitive)?.contentOrNull
                if (!sig.isNullOrEmpty()) call.thoughtSignature = sig
            }
        }
        choice?.get("finish_reason")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let { finishReason = it }
        (chunk["usage"] as? JsonObject)?.let { usage ->
            events += StreamEvent.Usage(
                usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
        return events
    }

    fun finish(): List<StreamEvent> {
        if (finished) return emptyList()
        finished = true
        val events = calls.values.mapIndexed { i, call ->
            StreamEvent.ToolCall(call.id.ifEmpty { "call_$i" }, call.name, call.args.toString().ifBlank { "{}" }, call.thoughtSignature)
        }
        calls.clear()
        val reason = if (events.isNotEmpty()) "tool_use" else finishReason
        return events + StreamEvent.Finished(reason)
    }
}
