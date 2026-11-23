package com.belugachat.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Provider-neutral content block sent to a model. */
sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val mime: String, val base64: String) : Content
    data class Pdf(val name: String, val base64: String) : Content
    data class Thinking(val text: String, val signature: String?, val redacted: String?) : Content
    data class ToolUse(val id: String, val name: String, val input: JsonObject, val thoughtSignature: String? = null) : Content
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : Content
}

data class LlmMessage(val role: String, val content: List<Content>)

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val reasoning: String = "default",
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ThinkingSignature(val signature: String) : StreamEvent
    data class RedactedThinking(val data: String) : StreamEvent
    data class ToolCall(val id: String, val name: String, val arguments: String, val thoughtSignature: String? = null) : StreamEvent
    data class Usage(val inputTokens: Long, val outputTokens: Long) : StreamEvent
    data class Finished(val stopReason: String?) : StreamEvent
}

data class RemoteModel(val id: String, val displayName: String)

class ProviderException(message: String, val status: Int = 0) : Exception(message)

interface LlmClient {
    suspend fun listModels(): List<RemoteModel>
    fun stream(request: LlmRequest): Flow<StreamEvent>
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}

/** Pulls a human-readable message out of a provider error body. */
fun errorMessage(status: Int, body: String): String {
    val parsed = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
        val root = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return@runCatching null
        val error: JsonElement? = root["error"]
        when {
            error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            error != null -> error.jsonPrimitive.contentOrNull
            else -> root["message"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    val hint = when (status) {
        401 -> "The API key was rejected. "
        403 -> "The API key is not allowed to do this. "
        404 -> "Not found (check the base URL or model). "
        429 -> "Rate limited or out of credit. "
        in 500..599 -> "The provider had a server error. "
        else -> ""
    }
    return "$hint${parsed ?: body.take(300).ifBlank { "HTTP $status" }}".trim()
}
